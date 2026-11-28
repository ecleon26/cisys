/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a finished build into webhook deliveries, and signs them. Static, so runners built without
 * CDI, as in tests, queue deliveries too.
 */
public final class WebhookService {
  static final String SIGNATURE_HEADER = "X-Cisys-Signature";
  static final String TIMESTAMP_HEADER = "X-Cisys-Timestamp";
  static final String EVENT_HEADER = "X-Cisys-Event";
  static final String DELIVERY_HEADER = "X-Cisys-Delivery";
  static final String SERVER_RESTART = "server_restart";

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final JsonMapper JSON = new JsonMapper();

  private WebhookService() {}

  static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /**
   * {@code sha256=} and the hex HMAC-SHA256 of {@code timestamp + "." + body}, so a receiver can
   * reject old payloads.
   */
  static String sign(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Queues a delivery for every webhook of the build's project that wants its event. Runs in the
   * transaction that stores the result, so the deliveries exist exactly when the result does.
   * Cancelled and unfinished builds send nothing.
   */
  static void enqueue(Long buildId) {
    enqueue(buildId, null);
  }

  /**
   * As {@link #enqueue(Long)}, with a {@code reason} in the payload, such as {@value
   * #SERVER_RESTART} for a build failed because the server restarted while it ran.
   */
  static void enqueue(Long buildId, String reason) {
    Build build = Build.findById(buildId);
    if (build == null
        || build.projectId == null
        || (build.status != Build.Status.PASSED && build.status != Build.Status.FAILED)) {
      return;
    }
    List<ProjectWebhook> hooks = ProjectWebhook.list("projectId", build.projectId);
    if (hooks.isEmpty()) {
      return;
    }
    WebhookDelivery.Event event = event(build);
    Project project = Project.findById(build.projectId);
    Instant now = Instant.now();
    for (ProjectWebhook hook : hooks) {
      if (!hook.wants(event)) {
        continue;
      }
      WebhookDelivery d = new WebhookDelivery();
      d.webhookId = hook.id;
      d.buildId = build.id;
      d.event = event;
      d.payload = "{}";
      d.status = WebhookDelivery.Status.PENDING;
      d.attempts = 0;
      d.nextAttemptAt = now;
      d.createdAt = now;
      d.persist();
      d.payload = payload(d, project, build, reason);
    }
  }

  /** FIXED when the previous finished build of the same branch or pull request failed. */
  static WebhookDelivery.Event event(Build build) {
    if (build.status == Build.Status.FAILED) {
      return WebhookDelivery.Event.FAILED;
    }
    List<Object> params =
        new ArrayList<>(
            List.of(build.projectId, build.id, List.of(Build.Status.PASSED, Build.Status.FAILED)));
    String scope;
    if (build.pullRequest != null) {
      scope = "pullRequest = ?4";
      params.add(build.pullRequest);
    } else if (build.branch != null) {
      scope = "pullRequest is null and branch = ?4";
      params.add(build.branch);
    } else {
      scope = "pullRequest is null and branch is null";
    }
    Build previous =
        Build.<Build>find(
                "projectId = ?1 and id <> ?2 and status in ?3 and "
                    + scope
                    + " order by finishedAt desc nulls last, id desc",
                params.toArray())
            .firstResult();
    return previous != null && previous.status == Build.Status.FAILED
        ? WebhookDelivery.Event.FIXED
        : WebhookDelivery.Event.PASSED;
  }

  private static String payload(WebhookDelivery d, Project project, Build build, String reason) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("id", build.id);
    b.put("number", build.number);
    b.put("status", build.status.name());
    b.put("trigger", build.trigger.name());
    b.put("branch", build.branch);
    b.put("revision", build.revision);
    b.put("pullRequest", build.pullRequest);
    b.put("exitCode", build.exitCode);
    b.put("startedAt", text(build.startedAt));
    b.put("finishedAt", text(build.finishedAt));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("event", d.event.code());
    body.put("delivery", d.id);
    body.put("reason", reason);
    body.put("project", Map.of("id", project.id, "name", project.name));
    body.put("build", b);
    return JSON.writeValueAsString(body);
  }

  private static String text(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
