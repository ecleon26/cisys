/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactArchiveTest {
  /** Writes ustar entries; enough to build the archives a hostile step could. */
  static final class Tar {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Tar entry(String name, char type, byte[] data) {
      byte[] h = new byte[512];
      byte[] n = name.getBytes(StandardCharsets.UTF_8);
      System.arraycopy(n, 0, h, 0, Math.min(n.length, 100));
      put(h, 100, "0000644");
      put(h, 108, "0000000");
      put(h, 116, "0000000");
      put(h, 124, String.format("%011o", data.length));
      put(h, 136, "00000000000");
      h[156] = (byte) type;
      put(h, 257, "ustar");
      put(h, 263, "00");
      Arrays.fill(h, 148, 156, (byte) ' ');
      long sum = 0;
      for (byte b : h) {
        sum += b & 0xff;
      }
      put(h, 148, String.format("%06o", sum));
      h[154] = 0;
      out.writeBytes(h);
      out.writeBytes(data);
      out.writeBytes(new byte[(512 - data.length % 512) % 512]);
      return this;
    }

    Tar file(String name, String content) {
      return entry(name, '0', content.getBytes(StandardCharsets.UTF_8));
    }

    byte[] bytes() {
      out.writeBytes(new byte[1024]);
      return out.toByteArray();
    }

    private static void put(byte[] h, int off, String s) {
      byte[] b = s.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(b, 0, h, off, b.length);
    }
  }

  record Read(Map<String, String> files, List<String> log, ArtifactArchive.Result result) {}

  static Read read(byte[] archive, long maxBytes, int maxFiles) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    List<String> log = new ArrayList<>();
    ArtifactArchive.Result r =
        ArtifactArchive.read(
            new ByteArrayInputStream(archive),
            maxBytes,
            maxFiles,
            path -> true,
            (path, size, data) ->
                files.put(path, new String(data.readAllBytes(), StandardCharsets.UTF_8)),
            log::add);
    return new Read(files, log, r);
  }

  @Test
  void readsRegularFilesAndSkipsDirectories() throws Exception {
    Read r =
        read(
            new Tar()
                .entry("./target/", '5', new byte[0])
                .file("./target/a.txt", "a")
                .file("target/b.txt", "b".repeat(600))
                .bytes(),
            1 << 20,
            10);
    assertEquals(Map.of("target/a.txt", "a", "target/b.txt", "b".repeat(600)), r.files());
    assertEquals(new ArtifactArchive.Result(2, 601, false), r.result());
    assertTrue(r.log().isEmpty(), r.log().toString());
  }

  @Test
  void skipsLinksDevicesAndBadNames() throws Exception {
    Read r =
        read(
            new Tar()
                .entry("link", '2', new byte[0])
                .entry("hard", '1', new byte[0])
                .entry("dev", '3', new byte[0])
                .entry("fifo", '6', new byte[0])
                .file("/etc/passwd", "x")
                .file("../outside", "x")
                .file("a/../../b", "x")
                .file("a\\b", "x")
                .file("ok", "1")
                .file("ok", "2")
                .bytes(),
            1 << 20,
            10);
    assertEquals(Map.of("ok", "1"), r.files());
    assertEquals(
        List.of(
            "==> Artifact skipped: link (symbolic link)",
            "==> Artifact skipped: hard (hard link)",
            "==> Artifact skipped: dev (device)",
            "==> Artifact skipped: fifo (FIFO)",
            "==> Artifact skipped: /etc/passwd (invalid name)",
            "==> Artifact skipped: ../outside (invalid name)",
            "==> Artifact skipped: a/../../b (invalid name)",
            "==> Artifact skipped: a\\b (invalid name)",
            "==> Artifact skipped: ok (listed twice)"),
        r.log());
    assertFalse(r.result().truncated());
  }

  @Test
  void readsGnuLongNamesAndPaxPaths() throws Exception {
    String longName = "d/" + "x".repeat(150);
    String pax = "path=p/" + "y".repeat(120) + "\n";
    String record = (pax.length() + 4) + " " + pax;
    Read r =
        read(
            new Tar()
                .entry("././@LongLink", 'L', (longName + "\0").getBytes(StandardCharsets.UTF_8))
                .file("ignored", "long")
                .entry("pax", 'x', record.getBytes(StandardCharsets.UTF_8))
                .file("ignored", "pax")
                .bytes(),
            1 << 20,
            10);
    assertEquals(Map.of(longName, "long", "p/" + "y".repeat(120), "pax"), r.files());
  }

  @Test
  void stopsAtTheSizeLimit() throws Exception {
    Read r = read(new Tar().file("a", "1234").file("b", "5678").bytes(), 6, 10);
    assertEquals(Map.of("a", "1234"), r.files());
    assertTrue(r.result().truncated());
    assertEquals(List.of("==> Artifacts truncated at 0 MiB"), r.log());
  }

  @Test
  void stopsAtTheFileLimit() throws Exception {
    Read r = read(new Tar().file("a", "1").file("b", "2").file("c", "3").bytes(), 100, 2);
    assertEquals(2, r.files().size());
    assertEquals(new ArtifactArchive.Result(2, 2, true), r.result());
    assertEquals(List.of("==> Artifacts truncated at 2 files"), r.log());
  }

  @Test
  void keepsWhatWasReadFromADamagedArchive() throws Exception {
    byte[] archive = new Tar().file("a", "1").file("b", "x".repeat(2000)).bytes();
    Read cut = read(Arrays.copyOf(archive, 512 * 3), 1 << 20, 10);
    assertEquals(Map.of("a", "1"), cut.files());
    assertTrue(cut.result().truncated());
    assertEquals(
        List.of("==> Artifacts damaged: the archive ends early or is malformed"), cut.log());

    byte[] bad = new Tar().file("a", "1").bytes();
    bad[0] = 'b';
    Read checksum = read(bad, 1 << 20, 10);
    assertTrue(checksum.files().isEmpty());
    assertEquals(
        List.of("==> Artifacts damaged: an archive header has a wrong checksum"), checksum.log());
  }

  @Test
  void anEmptyArchiveHasNoFiles() throws Exception {
    Read r = read(new byte[0], 100, 10);
    assertEquals(new ArtifactArchive.Result(0, 0, false), r.result());
  }

  @Test
  void normalizes() {
    assertEquals("a/b", ArtifactArchive.normalize("./a/b/"));
    assertEquals("a/b", ArtifactArchive.normalize("././a/b"));
    assertNull(ArtifactArchive.normalize(""));
    assertNull(ArtifactArchive.normalize("./"));
    assertNull(ArtifactArchive.normalize("a/./b"));
    assertNull(ArtifactArchive.normalize("a//b"));
    assertNull(ArtifactArchive.normalize("a\nb"));
    assertNull(ArtifactArchive.normalize("x".repeat(1001)));
  }

  @Test
  void patternsMatchTheListedPathAndBelowIt() {
    var dir = BuildArtifacts.pattern("target/surefire-reports");
    assertTrue(dir.matcher("target/surefire-reports").matches());
    assertTrue(dir.matcher("target/surefire-reports/TEST-a.xml").matches());
    assertFalse(dir.matcher("target/surefire-reports-old/a").matches());
    var glob = BuildArtifacts.pattern("target/*.jar");
    assertTrue(glob.matcher("target/app.jar").matches());
    assertTrue(glob.matcher("target/my lib.jar").matches());
    assertFalse(glob.matcher("target/.hidden.jar").matches());
    assertFalse(glob.matcher("target/sub/app.jar").matches());
    assertFalse(glob.matcher("target/app.jar.sha1").matches());
    assertTrue(BuildArtifacts.pattern("*").matcher("anything/below").matches());
  }
}
