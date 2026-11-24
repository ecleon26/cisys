/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;

/**
 * A build's artifacts: one row per file in {@code buildartifact}, its content in a large object.
 * Deleting the build deletes the rows, and their lo_manage trigger the content.
 */
@ApplicationScoped
public class BuildArtifacts {
  static final int CHUNK = 1 << 20;

  /** Storing up to the largest allowed total can take longer than the default timeout. */
  static final int STORE_TIMEOUT_SECONDS = 1800;

  @Inject DataSource dataSource;

  /** One stored file. */
  public record Info(Long id, String name, String path, long size, String sha256) {}

  /**
   * Reads the archive and stores its files in one transaction, each under the name of the workflow
   * entry it came from. Files no entry lists are skipped.
   */
  ArtifactArchive.Result store(
      long buildId,
      List<Workflow.Artifact> listed,
      InputStream archive,
      long maxBytes,
      int maxFiles,
      Consumer<String> log) {
    List<Pattern> patterns = listed.stream().map(a -> pattern(a.path())).toList();
    return QuarkusTransaction.requiringNew()
        .timeout(STORE_TIMEOUT_SECONDS)
        .call(() -> {
          try (Connection c = dataSource.getConnection()) {
            LargeObjectManager lom = largeObjects(c);
            Instant now = Instant.now();
            ArtifactArchive.Result r =
                ArtifactArchive.read(
                    archive,
                    maxBytes,
                    maxFiles,
                    path -> nameOf(listed, patterns, path) != null,
                    (path, size, data) -> {
                      try {
                        insert(c, lom, buildId, nameOf(listed, patterns, path), path, data, now);
                      } catch (SQLException e) {
                        throw new IOException(e);
                      }
                    },
                    log);
            try (PreparedStatement s =
                c.prepareStatement("update build set artifactstruncated = ? where id = ?")) {
              s.setBoolean(1, r.truncated());
              s.setLong(2, buildId);
              s.executeUpdate();
            }
            return r;
          }
        });
  }

  /** The name of the first entry whose path matches, or null. */
  private static String nameOf(
      List<Workflow.Artifact> listed, List<Pattern> patterns, String path) {
    for (int i = 0; i < patterns.size(); i++) {
      if (patterns.get(i).matcher(path).matches()) {
        return listed.get(i).name();
      }
    }
    return null;
  }

  /**
   * A file at {@code path} or below it, with {@code *} matching within one segment as the shell
   * does, but not a leading dot.
   */
  static Pattern pattern(String path) {
    StringBuilder p = new StringBuilder();
    String[] segments = path.split("/");
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        p.append('/');
      }
      String segment = segments[i];
      if (segment.startsWith("*")) {
        p.append("(?!\\.)");
      }
      for (String part : segment.split("\\*", -1)) {
        p.append(Pattern.quote(part)).append("[^/]*");
      }
      p.setLength(p.length() - "[^/]*".length());
    }
    return Pattern.compile(p + "(/.*)?");
  }

  private static void insert(
      Connection c,
      LargeObjectManager lom,
      long buildId,
      String name,
      String path,
      InputStream data,
      Instant now)
      throws SQLException, IOException {
    long oid = lom.createLO(LargeObjectManager.READWRITE);
    MessageDigest sha;
    try {
      sha = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    long size = 0;
    LargeObject lo = lom.open(oid, LargeObjectManager.WRITE);
    try {
      byte[] buffer = new byte[CHUNK];
      for (int n; (n = data.read(buffer)) > 0; ) {
        lo.write(buffer, 0, n);
        sha.update(buffer, 0, n);
        size += n;
      }
    } catch (IOException | SQLException e) {
      // A damaged archive keeps the files before it; this one has no row to unlink it later.
      lo.close();
      lom.delete(oid);
      throw e;
    }
    lo.close();
    try (PreparedStatement s =
        c.prepareStatement(
            "insert into buildartifact (buildid, name, path, size, sha256, dataoid, createdat)"
                + " values (?, ?, ?, ?, ?, cast(? as oid), ?)")) {
      s.setLong(1, buildId);
      s.setString(2, name);
      s.setString(3, path);
      s.setLong(4, size);
      s.setString(5, HexFormat.of().formatHex(sha.digest()));
      s.setLong(6, oid);
      s.setTimestamp(7, Timestamp.from(now));
      s.executeUpdate();
    }
  }

  /** The build's files, by path. */
  public List<Info> list(long buildId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection();
              PreparedStatement s =
                  c.prepareStatement(
                      "select id, name, path, size, sha256 from buildartifact"
                          + " where buildid = ? order by path")) {
            s.setLong(1, buildId);
            List<Info> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) {
                out.add(
                    new Info(
                        r.getLong(1),
                        r.getString(2),
                        r.getString(3),
                        r.getLong(4),
                        r.getString(5)));
              }
            }
            return out;
          }
        });
  }

  /** Whether the build had more artifacts than its limits, or a damaged archive. */
  public boolean truncated(long buildId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection();
              PreparedStatement s =
                  c.prepareStatement("select artifactstruncated from build where id = ?")) {
            s.setLong(1, buildId);
            try (ResultSet r = s.executeQuery()) {
              return r.next() && r.getBoolean(1);
            }
          }
        });
  }

  /** One file of the build, or null. */
  public Info find(long buildId, long artifactId) {
    return list(buildId).stream().filter(i -> i.id() == artifactId).findFirst().orElse(null);
  }

  /** Streams one file's content. */
  public void streamTo(long artifactId, OutputStream out) throws IOException {
    Long oid = oid(artifactId);
    if (oid == null) {
      return;
    }
    for (long at = 0; ; ) {
      byte[] part = read(oid, at);
      if (part.length == 0) {
        return;
      }
      out.write(part);
      at += part.length;
    }
  }

  /** Streams every file of the build as a ZIP archive. */
  public void zipTo(long buildId, OutputStream out) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Info i : list(buildId)) {
        zip.putNextEntry(new ZipEntry(i.path()));
        streamTo(i.id(), zip);
        zip.closeEntry();
      }
    }
  }

  private Long oid(long artifactId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection();
              PreparedStatement s =
                  c.prepareStatement("select dataoid from buildartifact where id = ?")) {
            s.setLong(1, artifactId);
            try (ResultSet r = s.executeQuery()) {
              return r.next() ? r.getLong(1) : null;
            }
          }
        });
  }

  private byte[] read(long oid, long offset) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection()) {
            LargeObject lo = largeObjects(c).open(oid, LargeObjectManager.READ);
            try {
              lo.seek64(offset, LargeObject.SEEK_SET);
              return lo.read(CHUNK);
            } finally {
              lo.close();
            }
          }
        });
  }

  private static LargeObjectManager largeObjects(Connection c) throws SQLException {
    return c.unwrap(PGConnection.class).getLargeObjectAPI();
  }
}
