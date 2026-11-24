/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reads the tar archive a build's collect script wrote. The steps can write that file themselves,
 * so it is untrusted: only regular files with relative names are passed on, within the size and
 * file limits. Links, devices and other entries are skipped and logged. Understands ustar names,
 * GNU long names and PAX {@code path} and {@code size} records.
 */
final class ArtifactArchive {
  private ArtifactArchive() {}

  static final int BLOCK = 512;

  /** The longest GNU long name or PAX header read. */
  static final int MAX_HEADER_DATA = 64 * 1024;

  /** Receives each accepted file; {@code data} holds exactly {@code size} bytes. */
  interface Sink {
    void file(String path, long size, InputStream data) throws IOException;
  }

  /** What was read. {@code truncated} when a limit was reached or the archive was damaged. */
  record Result(int files, long bytes, boolean truncated) {}

  /** {@code wanted} decides which valid names are kept; the others are skipped as not listed. */
  static Result read(
      InputStream in,
      long maxBytes,
      int maxFiles,
      Predicate<String> wanted,
      Sink sink,
      Consumer<String> log)
      throws IOException {
    Set<String> seen = new HashSet<>();
    int files = 0;
    long bytes = 0;
    String longName = null;
    Long paxSize = null;
    byte[] header = new byte[BLOCK];
    try {
      while (true) {
        if (!readBlock(in, header)) {
          return new Result(files, bytes, false);
        }
        if (isZero(header)) {
          return new Result(files, bytes, false);
        }
        if (!checksumMatches(header)) {
          log.accept("==> Artifacts damaged: an archive header has a wrong checksum");
          return new Result(files, bytes, true);
        }
        char type = (char) header[156];
        long size = octal(header, 124, 12);
        if (type != 'L' && type != 'x' && paxSize != null) {
          size = paxSize;
        }
        if (size < 0) {
          log.accept("==> Artifacts damaged: an archive entry has an invalid size");
          return new Result(files, bytes, true);
        }
        if (type == 'L' || type == 'x') {
          if (size > MAX_HEADER_DATA) {
            log.accept("==> Artifacts damaged: an archive header is too large");
            return new Result(files, bytes, true);
          }
          byte[] data = readData(in, (int) size);
          if (type == 'L') {
            longName = cString(data, 0, data.length);
          } else {
            String[] pax = pax(data);
            if (pax[0] != null) {
              longName = pax[0];
            }
            if (pax[1] != null) {
              paxSize = Long.parseLong(pax[1]);
            }
          }
          continue;
        }
        String raw = longName != null ? longName : name(header);
        longName = null;
        paxSize = null;
        if (type == 'g' || type == 'K') {
          skip(in, size + padding(size));
          continue;
        }
        String path = normalize(raw);
        if (type == '5') {
          skip(in, size + padding(size));
          continue;
        }
        String reason = kind(type);
        if (reason == null && path == null) {
          reason = "invalid name";
        }
        if (reason == null && !seen.add(path)) {
          reason = "listed twice";
        }
        if (reason == null && !wanted.test(path)) {
          reason = "not listed";
        }
        if (reason != null) {
          log.accept("==> Artifact skipped: " + shown(path, raw) + " (" + reason + ")");
          skip(in, size + padding(size));
          continue;
        }
        if (files >= maxFiles) {
          log.accept("==> Artifacts truncated at " + maxFiles + " files");
          return new Result(files, bytes, true);
        }
        if (bytes + size > maxBytes) {
          log.accept("==> Artifacts truncated at " + mib(maxBytes) + " MiB");
          return new Result(files, bytes, true);
        }
        Bounded data = new Bounded(in, size);
        sink.file(path, size, data);
        data.drain();
        skip(in, padding(size));
        files++;
        bytes += size;
      }
    } catch (EOFException | NumberFormatException e) {
      log.accept("==> Artifacts damaged: the archive ends early or is malformed");
      return new Result(files, bytes, true);
    }
  }

  /** Why an entry type is skipped, or null for a regular file. */
  private static String kind(char type) {
    return switch (type) {
      case '0', '\0', '7' -> null;
      case '1' -> "hard link";
      case '2' -> "symbolic link";
      case '3', '4' -> "device";
      case '6' -> "FIFO";
      case 'S' -> "sparse file";
      default -> "unsupported entry";
    };
  }

  /**
   * A relative name without {@code ..}, {@code .} or empty segments, backslashes or control
   * characters, or null. A leading {@code ./} and a trailing {@code /} are removed.
   */
  static String normalize(String name) {
    if (name == null) {
      return null;
    }
    String n = name;
    while (n.startsWith("./")) {
      n = n.substring(2);
    }
    while (n.endsWith("/")) {
      n = n.substring(0, n.length() - 1);
    }
    if (n.isEmpty() || n.length() > Workflow.MAX_ARTIFACT_PATH || n.startsWith("/")) {
      return null;
    }
    for (int i = 0; i < n.length(); i++) {
      char c = n.charAt(i);
      if (c < 0x20 || c == 0x7f || c == '\\') {
        return null;
      }
    }
    for (String segment : n.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return null;
      }
    }
    return n;
  }

  private static String shown(String path, String raw) {
    String s = path != null ? path : raw == null ? "" : raw;
    s = s.replaceAll("\\p{Cntrl}", "?");
    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }

  private static long mib(long bytes) {
    return bytes / (1024 * 1024);
  }

  private static String name(byte[] h) {
    String name = cString(h, 0, 100);
    boolean ustar = new String(h, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
    String prefix = ustar ? cString(h, 345, 155) : "";
    return prefix.isEmpty() ? name : prefix + "/" + name;
  }

  private static String cString(byte[] b, int off, int len) {
    int end = off;
    while (end < off + len && b[end] != 0) {
      end++;
    }
    return new String(b, off, end - off, StandardCharsets.UTF_8);
  }

  /** An octal field, or -1 when it is not one (such as GNU base-256 sizes). */
  static long octal(byte[] b, int off, int len) {
    long v = 0;
    boolean digits = false;
    for (int i = off; i < off + len; i++) {
      byte c = b[i];
      if (c == 0 || c == ' ') {
        if (digits) {
          break;
        }
        continue;
      }
      if (c < '0' || c > '7' || v > (Long.MAX_VALUE >> 3)) {
        return -1;
      }
      v = (v << 3) + (c - '0');
      digits = true;
    }
    return v;
  }

  private static boolean checksumMatches(byte[] h) {
    long expected = octal(h, 148, 8);
    long unsigned = 0, signed = 0;
    for (int i = 0; i < BLOCK; i++) {
      int b = i >= 148 && i < 156 ? ' ' : h[i];
      unsigned += b & 0xff;
      signed += b;
    }
    return expected == unsigned || expected == signed;
  }

  /** The {@code path} and {@code size} of PAX records such as {@code "30 path=a/b\n"}. */
  private static String[] pax(byte[] data) {
    String[] out = new String[2];
    int i = 0;
    while (i < data.length) {
      int space = i;
      while (space < data.length && data[space] != ' ') {
        space++;
      }
      if (space >= data.length) {
        throw new NumberFormatException("PAX record");
      }
      int length = Integer.parseInt(new String(data, i, space - i, StandardCharsets.US_ASCII));
      // The length counts its own digits, the space and the closing newline.
      if (length < space - i + 2 || i + length > data.length) {
        throw new NumberFormatException("PAX record");
      }
      String record = new String(data, space + 1, i + length - space - 2, StandardCharsets.UTF_8);
      int eq = record.indexOf('=');
      if (eq > 0) {
        String key = record.substring(0, eq), value = record.substring(eq + 1);
        if (key.equals("path")) {
          // A NUL inside a PAX path is not a valid name.
          out[0] = value.indexOf('\0') >= 0 ? "\0" : value;
        } else if (key.equals("size")) {
          out[1] = value;
        }
      }
      i += length;
    }
    return out;
  }

  private static boolean isZero(byte[] b) {
    for (byte x : b) {
      if (x != 0) {
        return false;
      }
    }
    return true;
  }

  /** False at a clean end of the stream; a partial block is an early end. */
  private static boolean readBlock(InputStream in, byte[] block) throws IOException {
    int n = in.readNBytes(block, 0, BLOCK);
    if (n == 0) {
      return false;
    }
    if (n < BLOCK) {
      throw new EOFException();
    }
    return true;
  }

  private static byte[] readData(InputStream in, int size) throws IOException {
    byte[] data = in.readNBytes(size);
    if (data.length < size) {
      throw new EOFException();
    }
    skip(in, padding(size));
    return data;
  }

  private static long padding(long size) {
    return (BLOCK - size % BLOCK) % BLOCK;
  }

  private static void skip(InputStream in, long n) throws IOException {
    in.skipNBytes(n);
  }

  /** At most {@code size} bytes of the archive, the data of one entry. */
  private static final class Bounded extends InputStream {
    private final InputStream in;
    private long left;

    Bounded(InputStream in, long size) {
      this.in = in;
      this.left = size;
    }

    @Override
    public int read() throws IOException {
      if (left == 0) {
        return -1;
      }
      int b = in.read();
      if (b < 0) {
        throw new EOFException();
      }
      left--;
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left == 0) {
        return -1;
      }
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n < 0) {
        throw new EOFException();
      }
      left -= n;
      return n;
    }

    /** Reads what the sink left, so the next header starts where it should. */
    void drain() throws IOException {
      in.skipNBytes(left);
      left = 0;
    }

    @Override
    public void close() {}
  }
}
