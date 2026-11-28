/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A project's webhooks, for its LEADs and admins. URLs are shown without their path and query,
 * which often hold a token, and a secret is returned only when it is created or rotated.
 */
@Path("/api/projects/{projectId}/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class WebhookResource {
  static final int DELIVERY_LIMIT = 50;

  @Inject ProjectAccess access;
  @Inject AuditService audit;
  @Inject WebhookUrlValidator urls;

  /** A webhook to create or change. A blank URL on change keeps the current one. */
  public record WebhookInput(String url, boolean onFinished, boolean onFailed, boolean onFixed) {}

  /** One attempt history entry. */
  public record DeliveryView(
      Long id,
      Long webhookId,
      Long buildId,
      Long buildNumber,
      String event,
      WebhookDelivery.Status status,
      int attempts,
      Integer responseStatus,
      String error,
      Instant createdAt,
      Instant lastAttemptAt,
      Instant nextAttemptAt) {}

  /** A webhook; {@code secret} is set only in the create and rotate responses. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WebhookView(
      Long id,
      String url,
      boolean onFinished,
      boolean onFailed,
      boolean onFixed,
      Instant createdAt,
      DeliveryView lastDelivery,
      String secret) {}

  /** The project's webhooks with their latest delivery. */
  @GET
  @Transactional
  public List<WebhookView> list(@PathParam("projectId") Long projectId) {
    access.editable(projectId);
    return ProjectWebhook.<ProjectWebhook>list("projectId = ?1 order by id", projectId).stream()
        .map(h -> view(h, null))
        .toList();
  }

  /** The latest deliveries of all the project's webhooks, newest first. */
  @GET
  @Path("/deliveries")
  @Transactional
  public List<DeliveryView> deliveries(@PathParam("projectId") Long projectId) {
    access.editable(projectId);
    List<Object[]> rows =
        WebhookDelivery.getEntityManager()
            .createQuery(
                "select d, b.number from WebhookDelivery d, ProjectWebhook h, Build b"
                    + " where d.webhookId = h.id and d.buildId = b.id and h.projectId = ?1"
                    + " order by d.id desc",
                Object[].class)
            .setParameter(1, projectId)
            .setMaxResults(DELIVERY_LIMIT)
            .getResultList();
    List<DeliveryView> result = new ArrayList<>();
    for (Object[] row : rows) {
      result.add(delivery((WebhookDelivery) row[0], (Long) row[1]));
    }
    return result;
  }

  /** Adds a webhook and returns its secret once. */
  @POST
  @Transactional
  @Audited(
      action = AuditAction.PROJECT_WEBHOOK_CREATE,
      target = AuditTargetType.WEBHOOK,
      summary = "Add project webhook")
  public Response create(@PathParam("projectId") Long projectId, WebhookInput input) {
    Project p = access.editable(projectId);
    if (input == null) {
      throw badRequest(WebhookUrlValidator.SHAPE);
    }
    requireEvent(input);
    // Serializes creates per project so the limit holds.
    Project.findById(projectId, LockModeType.PESSIMISTIC_WRITE);
    if (ProjectWebhook.count("projectId", projectId) >= ProjectWebhook.MAX_PER_PROJECT) {
      throw badRequest(
          "A project can have at most " + ProjectWebhook.MAX_PER_PROJECT + " webhooks");
    }
    ProjectWebhook h = new ProjectWebhook();
    h.projectId = projectId;
    h.url = checked(input.url());
    h.secret = WebhookService.newSecret();
    h.onFinished = input.onFinished();
    h.onFailed = input.onFailed();
    h.onFixed = input.onFixed();
    h.createdAt = Instant.now();
    h.persist();
    audit.success(
        AuditAction.PROJECT_WEBHOOK_CREATE,
        AuditTargetType.WEBHOOK,
        h.id,
        WebhookUrlValidator.display(h.url),
        p,
        "Added a project webhook",
        details(h));
    return Response.status(201).entity(view(h, h.secret)).build();
  }

  /** Changes the events, and the URL when one is given. */
  @PUT
  @Path("/{id}")
  @Transactional
  @Audited(
      action = AuditAction.PROJECT_WEBHOOK_UPDATE,
      target = AuditTargetType.WEBHOOK,
      summary = "Change project webhook")
  public WebhookView update(
      @PathParam("projectId") Long projectId, @PathParam("id") Long id, WebhookInput input) {
    Project p = access.editable(projectId);
    ProjectWebhook h = find(projectId, id);
    if (input == null) {
      throw badRequest(WebhookUrlValidator.SHAPE);
    }
    requireEvent(input);
    List<String> changed = new ArrayList<>();
    if (input.url() != null && !input.url().isBlank()) {
      String url = checked(input.url());
      if (!url.equals(h.url)) {
        h.url = url;
        changed.add("url");
      }
    }
    if (h.onFinished != input.onFinished()
        || h.onFailed != input.onFailed()
        || h.onFixed != input.onFixed()) {
      h.onFinished = input.onFinished();
      h.onFailed = input.onFailed();
      h.onFixed = input.onFixed();
      changed.add("events");
    }
    if (!changed.isEmpty()) {
      Map<String, Object> details = details(h);
      details.put("changed", changed);
      audit.success(
          AuditAction.PROJECT_WEBHOOK_UPDATE,
          AuditTargetType.WEBHOOK,
          h.id,
          WebhookUrlValidator.display(h.url),
          p,
          "Changed a project webhook",
          details);
    }
    return view(h, null);
  }

  /** Replaces the secret and returns the new one once. */
  @POST
  @Path("/{id}/secret")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  @Audited(
      action = AuditAction.PROJECT_WEBHOOK_SECRET_ROTATE,
      target = AuditTargetType.WEBHOOK,
      summary = "Rotate project webhook secret")
  public WebhookView rotate(@PathParam("projectId") Long projectId, @PathParam("id") Long id) {
    Project p = access.editable(projectId);
    ProjectWebhook h = find(projectId, id);
    h.secret = WebhookService.newSecret();
    audit.success(
        AuditAction.PROJECT_WEBHOOK_SECRET_ROTATE,
        AuditTargetType.WEBHOOK,
        h.id,
        WebhookUrlValidator.display(h.url),
        p,
        "Rotated a project webhook secret",
        AuditDetails.of("host", host(h.url)));
    return view(h, h.secret);
  }

  /** Removes a webhook and its delivery history. */
  @DELETE
  @Path("/{id}")
  @Transactional
  @Audited(
      action = AuditAction.PROJECT_WEBHOOK_DELETE,
      target = AuditTargetType.WEBHOOK,
      summary = "Delete project webhook")
  public Response delete(@PathParam("projectId") Long projectId, @PathParam("id") Long id) {
    Project p = access.editable(projectId);
    ProjectWebhook h = find(projectId, id);
    String name = WebhookUrlValidator.display(h.url);
    Map<String, Object> details = details(h);
    h.delete();
    audit.success(
        AuditAction.PROJECT_WEBHOOK_DELETE,
        AuditTargetType.WEBHOOK,
        id,
        name,
        p,
        "Deleted a project webhook",
        details);
    return Response.noContent().build();
  }

  private String checked(String url) {
    try {
      urls.validate(url);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    return url.trim();
  }

  private static void requireEvent(WebhookInput input) {
    if (!input.onFinished() && !input.onFailed() && !input.onFixed()) {
      throw badRequest("Choose at least one event");
    }
  }

  private static ProjectWebhook find(Long projectId, Long id) {
    ProjectWebhook h = ProjectWebhook.findById(id);
    if (h == null || !Objects.equals(h.projectId, projectId)) {
      throw new NotFoundException();
    }
    return h;
  }

  private static WebhookView view(ProjectWebhook h, String secret) {
    WebhookDelivery last =
        WebhookDelivery.<WebhookDelivery>find("webhookId = ?1 order by id desc", h.id)
            .firstResult();
    DeliveryView lastView = null;
    if (last != null) {
      Build b = Build.findById(last.buildId);
      lastView = delivery(last, b == null ? null : b.number);
    }
    return new WebhookView(
        h.id,
        WebhookUrlValidator.display(h.url),
        h.onFinished,
        h.onFailed,
        h.onFixed,
        h.createdAt,
        lastView,
        secret);
  }

  private static DeliveryView delivery(WebhookDelivery d, Long buildNumber) {
    return new DeliveryView(
        d.id,
        d.webhookId,
        d.buildId,
        buildNumber,
        d.event.code(),
        d.status,
        d.attempts,
        d.responseStatus,
        d.error,
        d.createdAt,
        d.lastAttemptAt,
        d.nextAttemptAt);
  }

  /** The host and events only: the rest of the URL may hold a token. */
  private static Map<String, Object> details(ProjectWebhook h) {
    return AuditDetails.of(
        "host", host(h.url),
        "onFinished", h.onFinished,
        "onFailed", h.onFailed,
        "onFixed", h.onFixed);
  }

  private static String host(String url) {
    return RepositoryUrls.host(url);
  }

  private static BadRequestException badRequest(String message) {
    return new BadRequestException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
