/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WebhookDeliveryTest {
  @Inject BuildRunner runner;
  @Inject WebhookDispatcher dispatcher;

  /** Earlier tests leave pending rows; they must not fill this test's batch. */
  @BeforeEach
  void reset() {
    TestWebhookTransport.reset();
    QuarkusTransaction.requiringNew()
        .run(() ->
            WebhookDelivery.update(
                "status = ?1, nextAttemptAt = null where status = ?2",
                WebhookDelivery.Status.FAILED,
                WebhookDelivery.Status.PENDING));
  }

  @AfterEach
  void cleanup() {
    TestWebhookTransport.reset();
    TestHostResolver.reset();
  }

  private static Long project() {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = "deliver-" + n;
          p.repositoryUrl = "https://github.com/octocat/deliver-" + n + ".git";
          p.ownerSubject = "owner";
          p.persist();
          return p.id;
        });
  }

  private static ProjectWebhook hook(
      Long projectId, String url, boolean finished, boolean failed, boolean fixed) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          ProjectWebhook h = new ProjectWebhook();
          h.projectId = projectId;
          h.url = url;
          h.secret = WebhookService.newSecret();
          h.onFinished = finished;
          h.onFailed = failed;
          h.onFixed = fixed;
          h.createdAt = Instant.now();
          h.persist();
          return h;
        });
  }

  private static Long running(Long projectId, String branch, Integer pullRequest) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.status = Build.Status.RUNNING;
          b.branch = branch;
          b.requestedAt = Instant.now();
          b.startedAt = Instant.now();
          if (pullRequest != null) {
            b.trigger = Build.Trigger.PULL_REQUEST;
            b.pullRequest = pullRequest;
          }
          b.persist();
          return b.id;
        });
  }

  private Long finish(Long projectId, String branch, Build.Status status) {
    return finish(projectId, branch, null, status);
  }

  private Long finish(Long projectId, String branch, Integer pullRequest, Build.Status status) {
    Long id = running(projectId, branch, pullRequest);
    runner.complete(
        new BuildRunner.Completion(
            id, status, "rev", status == Build.Status.PASSED ? 0 : 1, null, false, Instant.now()));
    return id;
  }

  private static List<WebhookDelivery> deliveries(Long buildId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> WebhookDelivery.<WebhookDelivery>list("buildId = ?1 order by id", buildId));
  }

  private static WebhookDelivery only(Long buildId) {
    List<WebhookDelivery> list = deliveries(buildId);
    assertEquals(1, list.size(), "deliveries of build " + buildId);
    return list.get(0);
  }

  private static WebhookDelivery reload(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> WebhookDelivery.findById(id));
  }

  @Test
  void failedThenPassedIsFixedAndLaterPassesAreQuiet() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", false, true, true);
    Long failed = finish(p, "main", Build.Status.FAILED);
    assertEquals(WebhookDelivery.Event.FAILED, only(failed).event);
    Long fixed = finish(p, "main", Build.Status.PASSED);
    assertEquals(WebhookDelivery.Event.FIXED, only(fixed).event);
    Long passed = finish(p, "main", Build.Status.PASSED);
    assertTrue(deliveries(passed).isEmpty());
  }

  @Test
  void finishedReceivesEveryResult() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", true, false, false);
    assertEquals(WebhookDelivery.Event.PASSED, only(finish(p, "main", Build.Status.PASSED)).event);
    assertEquals(WebhookDelivery.Event.FAILED, only(finish(p, "main", Build.Status.FAILED)).event);
  }

  @Test
  void fixedIsJudgedPerBranchAndPullRequest() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", false, false, true);
    finish(p, "main", Build.Status.FAILED);
    assertTrue(deliveries(finish(p, "feature", Build.Status.PASSED)).isEmpty());
    assertTrue(deliveries(finish(p, "main", 7, Build.Status.PASSED)).isEmpty());
    assertEquals(WebhookDelivery.Event.FIXED, only(finish(p, "main", Build.Status.PASSED)).event);
  }

  @Test
  void cancelledBuildsSendNothing() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", true, true, true);
    Long id = running(p, "main", null);
    assertTrue(runner.cancel(id));
    runner.complete(
        new BuildRunner.Completion(id, Build.Status.PASSED, "rev", 0, null, false, Instant.now()));
    assertTrue(deliveries(id).isEmpty());
  }

  @Test
  void eachWebhookGetsItsOwnDelivery() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", false, true, false);
    hook(p, "https://hooks.example.com/b", true, false, false);
    hook(p, "https://hooks.example.com/c", false, false, true);
    assertEquals(2, deliveries(finish(p, "main", Build.Status.FAILED)).size());
  }

  @Test
  void deliverySignsThePayload() {
    Long p = project();
    ProjectWebhook h = hook(p, "https://hooks.example.com/a?x=1", false, true, false);
    Long build = finish(p, "main", Build.Status.FAILED);
    WebhookDelivery d = only(build);
    dispatcher.dispatch(Instant.now());

    List<TestWebhookTransport.Call> calls = TestWebhookTransport.forDelivery(d.id);
    assertEquals(1, calls.size());
    TestWebhookTransport.Call call = calls.get(0);
    assertEquals("hooks.example.com", call.target().host());
    assertEquals("build.failed", call.headers().get(WebhookService.EVENT_HEADER));
    long timestamp = Long.parseLong(call.headers().get(WebhookService.TIMESTAMP_HEADER));
    assertEquals(
        WebhookService.sign(h.secret, timestamp, call.body().getBytes(StandardCharsets.UTF_8)),
        call.headers().get(WebhookService.SIGNATURE_HEADER));
    assertTrue(call.body().contains("\"event\":\"build.failed\""), call.body());
    assertTrue(call.body().contains("\"delivery\":" + d.id), call.body());
    assertTrue(call.body().contains("\"status\":\"FAILED\""), call.body());
    assertTrue(call.body().contains("\"reason\":null"), call.body());

    WebhookDelivery after = reload(d.id);
    assertEquals(WebhookDelivery.Status.DELIVERED, after.status);
    assertEquals(1, after.attempts);
    assertEquals(200, after.responseStatus);
    assertNull(after.nextAttemptAt);
  }

  @Test
  void serverErrorsAreRetriedWithBackoffUntilTheLimit() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", false, true, false);
    WebhookDelivery d = only(finish(p, "main", Build.Status.FAILED));
    TestWebhookTransport.status = 503;

    Instant now = Instant.now();
    dispatcher.dispatch(now);
    WebhookDelivery first = reload(d.id);
    assertEquals(WebhookDelivery.Status.PENDING, first.status);
    assertEquals(1, first.attempts);
    assertEquals(503, first.responseStatus);
    assertEquals("HTTP 503", first.error);
    assertTrue(first.nextAttemptAt.isAfter(now.plus(Duration.ofSeconds(25))));

    dispatcher.dispatch(now);
    assertEquals(1, TestWebhookTransport.forDelivery(d.id).size(), "not due yet");

    Instant later = now.plus(Duration.ofDays(1));
    for (int i = 1; i < WebhookDispatcher.MAX_ATTEMPTS; i++) {
      dispatcher.dispatch(later);
    }
    WebhookDelivery last = reload(d.id);
    assertEquals(WebhookDelivery.Status.FAILED, last.status);
    assertEquals(WebhookDispatcher.MAX_ATTEMPTS, last.attempts);
    assertNull(last.nextAttemptAt);
    dispatcher.dispatch(later);
    assertEquals(WebhookDispatcher.MAX_ATTEMPTS, TestWebhookTransport.forDelivery(d.id).size());
  }

  @Test
  void redirectsAreNotFollowedOrRetried() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", true, false, false);
    WebhookDelivery d = only(finish(p, "main", Build.Status.PASSED));
    TestWebhookTransport.status = 302;
    dispatcher.dispatch(Instant.now());
    WebhookDelivery after = reload(d.id);
    assertEquals(WebhookDelivery.Status.FAILED, after.status);
    assertEquals(1, after.attempts);
  }

  @Test
  void aBuildFailedByARestartSaysSo() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", false, true, false);
    Long id = running(p, "main", null);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = Build.findById(id);
          b.status = Build.Status.FAILED;
          b.finishedAt = Instant.now();
          WebhookService.enqueue(id, WebhookService.SERVER_RESTART);
        });
    assertTrue(only(id).payload.contains("\"reason\":\"server_restart\""), only(id).payload);
  }

  @Test
  void slowSendsDoNotOutliveTheLease() {
    int rounds =
        (WebhookDispatcher.BATCH + WebhookDispatcher.PARALLEL - 1) / WebhookDispatcher.PARALLEL;
    assertTrue(
        WebhookDispatcher.LEASE.toMillis() > 2L * rounds * SocketWebhookTransport.DEADLINE_MILLIS);
  }

  @Test
  void clientErrorsGiveUpButRateLimitsRetry() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", true, false, false);
    WebhookDelivery gone = only(finish(p, "main", Build.Status.PASSED));
    TestWebhookTransport.status = 404;
    dispatcher.dispatch(Instant.now());
    assertEquals(WebhookDelivery.Status.FAILED, reload(gone.id).status);

    WebhookDelivery limited = only(finish(p, "main", Build.Status.PASSED));
    TestWebhookTransport.status = 429;
    dispatcher.dispatch(Instant.now());
    assertEquals(WebhookDelivery.Status.PENDING, reload(limited.id).status);
  }

  @Test
  void networkErrorsAreRecordedAndRetried() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", true, false, false);
    WebhookDelivery d = only(finish(p, "main", Build.Status.PASSED));
    TestWebhookTransport.failure = new IOException("Connection refused");
    dispatcher.dispatch(Instant.now());
    WebhookDelivery after = reload(d.id);
    assertEquals(WebhookDelivery.Status.PENDING, after.status);
    assertNull(after.responseStatus);
    assertEquals("IOException: Connection refused", after.error);
  }

  @Test
  void aHostThatNowResolvesPrivatelyIsNotContacted() {
    Long p = project();
    hook(p, "https://rebind.example.com/a", true, false, false);
    WebhookDelivery d = only(finish(p, "main", Build.Status.PASSED));
    TestHostResolver.set("rebind.example.com", "169.254.169.254");
    dispatcher.dispatch(Instant.now());
    assertTrue(TestWebhookTransport.forDelivery(d.id).isEmpty());
    WebhookDelivery after = reload(d.id);
    assertEquals(WebhookUrlValidator.BLOCKED, after.error);
    assertEquals(1, after.attempts);
  }

  @Test
  void deletingTheWebhookOrBuildRemovesItsDeliveries() {
    Long p = project();
    ProjectWebhook h = hook(p, "https://hooks.example.com/a", true, false, false);
    Long first = finish(p, "main", Build.Status.PASSED);
    Long second = finish(p, "main", Build.Status.PASSED);
    WebhookDelivery d = only(second);
    QuarkusTransaction.requiringNew().run(() -> Build.deleteById(first));
    assertTrue(deliveries(first).isEmpty());
    QuarkusTransaction.requiringNew().run(() -> ProjectWebhook.deleteById(h.id));
    assertNull(reload(d.id));
  }

  @Test
  void purgeRemovesOldFinishedDeliveriesOnly() {
    Long p = project();
    hook(p, "https://hooks.example.com/a", true, false, false);
    WebhookDelivery old = only(finish(p, "main", Build.Status.PASSED));
    WebhookDelivery pending = only(finish(p, "main", Build.Status.PASSED));
    Instant past = Instant.now().minus(WebhookDispatcher.KEEP).minus(Duration.ofDays(1));
    QuarkusTransaction.requiringNew()
        .run(() -> {
          WebhookDelivery.update(
              "status = ?1, createdAt = ?2 where id = ?3",
              WebhookDelivery.Status.DELIVERED,
              past,
              old.id);
          WebhookDelivery.update("createdAt = ?1 where id = ?2", past, pending.id);
        });
    dispatcher.purge(Instant.now());
    assertNull(reload(old.id));
    assertNotNull(reload(pending.id));
  }

  @Test
  void backoffGrowsFourfold() {
    assertEquals(Duration.ofSeconds(30), WebhookDispatcher.backoff(1));
    assertEquals(Duration.ofMinutes(2), WebhookDispatcher.backoff(2));
    assertEquals(Duration.ofMinutes(8), WebhookDispatcher.backoff(3));
    assertEquals(Duration.ofMinutes(32), WebhookDispatcher.backoff(4));
  }
}
