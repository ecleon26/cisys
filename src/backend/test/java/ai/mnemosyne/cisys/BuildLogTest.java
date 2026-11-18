/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BuildLogTest {
  @Inject BuildLogs logs;
  @Inject BuildRunner runner;

  static final class Sink implements BuildRunner.LogSink {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    public synchronized void append(byte[] b, int off, int len) {
      bytes.write(b, off, len);
    }

    synchronized byte[] bytes() {
      return bytes.toByteArray();
    }

    String text() {
      return new String(bytes(), StandardCharsets.UTF_8);
    }
  }

  private static BuildRunner.Streamed sh(String script, BuildRunner.LogSink sink, long maxBytes)
      throws Exception {
    return BuildRunner.stream(List.of("sh", "-c", script), 60, sink, maxBytes);
  }

  static Long project() {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = java.util.UUID.randomUUID().toString();
          p.name = "logs-" + n;
          p.repositoryUrl = "https://github.com/octocat/logs-" + n + ".git";
          p.ownerSubject = "owner-id";
          p.persist();
          return p.id;
        });
  }

  static long newBuild() {
    Long project = project();
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build b = new Build();
          b.projectId = project;
          b.number = Build.nextNumber(project);
          b.requestedBy = "owner-id";
          b.status = Build.Status.PASSED;
          b.startedAt = java.time.Instant.now();
          b.persist();
          return b.id;
        });
  }

  private static long largeObjectCount(long oid) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            ((Number)
                    Build.getEntityManager()
                        .createNativeQuery(
                            "select count(*) from pg_largeobject_metadata where oid = cast(?1 as oid)")
                        .setParameter(1, oid)
                        .getSingleResult())
                .longValue());
  }

  @Test
  void streamKeepsLargeOutputComplete() throws Exception {
    Sink sink = new Sink();
    var r = sh("yes cisys | head -c 3000000", sink, Long.MAX_VALUE);
    assertEquals(0, r.code());
    assertFalse(r.truncated());
    assertEquals(3_000_000, r.totalBytes());
    assertNull(r.readerError());
    assertEquals(3_000_000, sink.bytes().length);
    assertTrue(sink.text().startsWith("cisys\ncisys\n"));
  }

  @Test
  void streamPassesBytesThroughUnchanged() throws Exception {
    Sink sink = new Sink();
    sh("printf 'a\\000b\\303\\251c\\377'", sink, Long.MAX_VALUE);
    assertArrayEquals(
        new byte[] {'a', 0, 'b', (byte) 0xC3, (byte) 0xA9, 'c', (byte) 0xFF}, sink.bytes());
  }

  @Test
  void streamAtExactLimitIsNotTruncated() throws Exception {
    Sink exact = new Sink();
    var r = sh("head -c 1000 /dev/zero", exact, 1000);
    assertFalse(r.truncated());
    assertEquals(1000, r.totalBytes());
    assertEquals(1000, exact.bytes().length);
    Sink over = new Sink();
    r = sh("head -c 1001 /dev/zero", over, 1000);
    assertTrue(r.truncated());
    assertEquals(1001, r.totalBytes());
    assertEquals(1000, over.bytes().length);
  }

  @Test
  void stagesShareOneLogBudget() throws Exception {
    Sink sink = new Sink();
    var first = sh("head -c 600 /dev/zero", sink, 1000);
    assertEquals(400, BuildRunner.remainingLogBytes(1000, first));
    var second = sh("head -c 600 /dev/zero", sink, BuildRunner.remainingLogBytes(1000, first));
    assertTrue(second.truncated());
    assertEquals(1000, sink.bytes().length);

    Sink full = new Sink();
    first = sh("head -c 1500 /dev/zero", full, 1000);
    assertEquals(0, BuildRunner.remainingLogBytes(1000, first));
    second = sh("head -c 600 /dev/zero", full, BuildRunner.remainingLogBytes(1000, first));
    assertEquals(0, second.code());
    assertEquals(600, second.totalBytes());
    assertEquals(1000, full.bytes().length);
  }

  @Test
  void streamDrainsPastLimit() throws Exception {
    Sink sink = new Sink();
    long start = System.nanoTime();
    var r = sh("head -c 50000000 /dev/zero", sink, 1000);
    assertEquals(0, r.code());
    assertFalse(r.timeout());
    assertTrue(r.truncated());
    assertEquals(50_000_000, r.totalBytes());
    assertEquals(1000, sink.bytes().length);
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(50));
  }

  @Test
  void streamIncludesStderr() throws Exception {
    Sink sink = new Sink();
    sh("echo out; echo err 1>&2", sink, Long.MAX_VALUE);
    assertTrue(sink.text().contains("out\n"));
    assertTrue(sink.text().contains("err\n"));
  }

  @Test
  void streamDrainsLargeStderr() throws Exception {
    Sink sink = new Sink();
    var r = sh("head -c 5000000 /dev/zero 1>&2", sink, Long.MAX_VALUE);
    assertEquals(0, r.code());
    assertFalse(r.timeout());
    assertEquals(5_000_000, r.totalBytes());
  }

  @Test
  void streamStopsWhenChildHoldsPipe() throws Exception {
    // The background sleep keeps the pipe open after the shell exits.
    Sink sink = new Sink();
    long start = System.nanoTime();
    var r =
        BuildRunner.stream(
            List.of("sh", "-c", "echo hi; sleep 30 &"), 20, sink, Long.MAX_VALUE, 300, 300);
    assertTrue(
        System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10),
        "stream() must not wait for the background child");
    assertEquals(0, r.code());
    assertTrue(sink.text().contains("hi"));
  }

  @Test
  void sinkFailureKeepsDraining() throws Exception {
    BuildRunner.LogSink failing =
        (b, off, len) -> {
          throw new IllegalStateException("storage down");
        };
    var r = sh("head -c 5000000 /dev/zero", failing, Long.MAX_VALUE);
    assertEquals(0, r.code());
    assertFalse(r.timeout());
    assertEquals(5_000_000, r.totalBytes());
    assertInstanceOf(IllegalStateException.class, r.readerError());
  }

  @Test
  void writerStoresLargeLogInOneLargeObject() {
    long id = newBuild();
    byte[] data = "0123456789".repeat(300_000).getBytes(StandardCharsets.UTF_8);
    try (var w = logs.open(id)) {
      w.append(data, 0, data.length);
    }
    Long oid = logs.logOid(id);
    assertNotNull(oid);
    assertEquals(1, largeObjectCount(oid));
    assertEquals("0123456789".repeat(300_000), logs.readAll(id));
  }

  @Test
  void writerKeepsBytesUnchanged() throws Exception {
    long id = newBuild();
    byte[] data = {'a', 0, 'b', (byte) 0xFF};
    try (var w = logs.open(id)) {
      w.append(data, 0, data.length);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    logs.streamTo(id, out);
    assertArrayEquals(data, out.toByteArray());
  }

  @Test
  void buildWithoutLogStreamsNothing() throws Exception {
    long id = newBuild();
    assertNull(logs.logOid(id));
    assertEquals(0, logs.size(id));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    logs.streamTo(id, out);
    assertEquals(0, out.size());
  }

  @Test
  void sizeAndPartialReadsUseStoredBytes() throws Exception {
    long id = newBuild();
    try (var w = logs.open(id)) {
      w.line("0123456789");
    }
    assertEquals(11, logs.size(id));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    logs.streamTo(id, out, 2, 4);
    assertEquals("2345", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void offsetPastTheEndStreamsNothing() throws Exception {
    long id = newBuild();
    try (var w = logs.open(id)) {
      w.line("abc");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    logs.streamTo(id, out, 100, 10);
    assertEquals(0, out.size());
  }

  @Test
  void rangeResolvesOffsetLimitTailAndCap() {
    assertEquals(new BuildLogs.Range(10, 20), BuildLogs.range(100, 10L, 20L, null, 50));
    assertEquals(new BuildLogs.Range(70, 30), BuildLogs.range(100, null, null, 30L, 50));
    assertEquals(new BuildLogs.Range(0, 50), BuildLogs.range(100, null, null, null, 50));
    assertEquals(new BuildLogs.Range(90, 10), BuildLogs.range(100, 90L, null, null, 50));
    assertEquals(new BuildLogs.Range(0, 50), BuildLogs.range(100, null, 200L, null, 50));
    assertEquals(new BuildLogs.Range(0, 0), BuildLogs.range(0, null, null, null, 50));
    assertEquals(new BuildLogs.Range(0, 100), BuildLogs.range(100, null, null, null, -1));
    assertEquals(new BuildLogs.Range(50, 50), BuildLogs.range(100, null, null, 80L, 50));
  }

  @Test
  void rangeRejectsNegativeValues() {
    assertThrows(IllegalArgumentException.class, () -> BuildLogs.range(100, -1L, null, null, -1));
    assertThrows(IllegalArgumentException.class, () -> BuildLogs.range(100, null, -1L, null, -1));
    assertThrows(IllegalArgumentException.class, () -> BuildLogs.range(100, null, null, -1L, -1));
  }

  @Test
  void periodicFlushStoresQuietOutput() throws Exception {
    long id = newBuild();
    logs.flushInterval(200);
    try (var w = logs.open(id)) {
      w.line("x");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (logs.readAll(id).isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertEquals(
          "x\n", logs.readAll(id), "buffered output is stored without a further append or close");
    } finally {
      logs.flushInterval(5000);
    }
  }

  @Test
  void openAppendsToExistingLog() {
    long id = newBuild();
    try (var w = logs.open(id)) {
      w.line("first");
    }
    Long oid = logs.logOid(id);
    try (var w = logs.open(id)) {
      w.line("second");
    }
    assertEquals(oid, logs.logOid(id), "the same large object is reused");
    assertEquals("first\nsecond\n", logs.readAll(id));
  }

  @Test
  void appendAfterCloseIsDropped() {
    long id = newBuild();
    var w = logs.open(id);
    w.line("kept");
    w.close();
    w.line("dropped");
    w.close();
    assertEquals("kept\n", logs.readAll(id));
  }

  @Test
  void deletingBuildRemovesItsLargeObject() {
    long id = newBuild();
    try (var w = logs.open(id)) {
      w.line("gone soon");
    }
    long oid = logs.logOid(id);
    assertEquals(1, largeObjectCount(oid));
    QuarkusTransaction.requiringNew().run(() -> Build.deleteById(id));
    assertEquals(0, largeObjectCount(oid), "lo_manage unlinks the log with its build");
  }

  @Test
  void writerFailureDiscardsBuffer() {
    long id = newBuild();
    var w = logs.open(id);
    logs.failWrites(true);
    try {
      byte[] big = new byte[BuildLogs.FLUSH_BYTES + 10];
      w.append(big, 0, big.length);
      assertNotNull(w.failure());
      assertEquals(0, w.buffered());
      w.append(big, 0, big.length);
      assertEquals(0, w.buffered(), "a failed writer must not grow its buffer");
      assertDoesNotThrow(w::close);
    } finally {
      logs.failWrites(false);
    }
    assertEquals("", logs.readAll(id));
  }

  @Test
  void runFinishesWhenFinalFlushFails() throws Exception {
    // A local URL that cannot be fetched fails the checkout without network or a container runtime.
    Long project = project();
    logs.failWrites(true);
    try {
      Build created =
          runner.submit(
              project,
              "owner-id",
              "file:///nonexistent/cisys-" + project,
              new BuildResource.RunRequest("main", null));
      runner.dispatch();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      Build b;
      do {
        Thread.sleep(100);
        b = QuarkusTransaction.requiringNew().call(() -> Build.findById(created.id));
      } while ((b.status == Build.Status.RUNNING || b.status == Build.Status.QUEUED)
          && System.nanoTime() < deadline);
      assertEquals(Build.Status.FAILED, b.status);
      assertNotNull(b.finishedAt);
      assertTrue(b.logTruncated);
      assertNull(b.exitCode);
      assertEquals("main", b.branch);
      assertNull(b.image);
      while (runner.hasPendingBuilds() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertFalse(runner.hasPendingBuilds(), "the runner is released");
    } finally {
      logs.failWrites(false);
    }
  }
}
