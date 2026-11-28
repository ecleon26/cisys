/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TeamTestProfile.class)
class WebhookResourceTest {
  private static final String SLACK = "https://hooks.example.com/services/T000/B000/XXXXSECRET";

  @AfterEach
  void reset() {
    TestHostResolver.reset();
  }

  static Long project(String lead, boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = "hooks-" + n;
          p.repositoryUrl = "https://github.com/octocat/hooks-" + n + ".git";
          p.ownerSubject = lead;
          p.privateRepository = priv;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = lead;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = Instant.now();
          m.persist();
          return p.id;
        });
  }

  private static void member(Long projectId, String subject) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = ProjectMember.TeamRole.MEMBER;
          m.createdAt = Instant.now();
          m.persist();
        });
  }

  private static String input(String url, boolean finished, boolean failed, boolean fixed) {
    return String.format(
        "{\"url\":%s,\"onFinished\":%s,\"onFailed\":%s,\"onFixed\":%s}",
        url == null ? "null" : "\"" + url + "\"", finished, failed, fixed);
  }

  private static Long create(Long projectId, String url) {
    return given()
        .contentType(ContentType.JSON)
        .body(input(url, false, true, true))
        .post("/api/projects/" + projectId + "/webhooks")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  private static ProjectWebhook stored(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> ProjectWebhook.findById(id));
  }

  private static AuditEvent audit(String action, Long webhookId) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            AuditEvent.<AuditEvent>find(
                    "action = ?1 and targetId = ?2 order by id desc",
                    action,
                    String.valueOf(webhookId))
                .firstResult());
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void createReturnsTheSecretOnceAndMasksTheUrl() {
    Long projectId = project("hook-lead", false);
    String secret =
        given()
            .contentType(ContentType.JSON)
            .body(input(SLACK, false, true, true))
            .post("/api/projects/" + projectId + "/webhooks")
            .then()
            .statusCode(201)
            .body("url", equalTo("https://hooks.example.com/…"))
            .body("onFailed", equalTo(true))
            .body("onFinished", equalTo(false))
            .body("secret", matchesPattern("[0-9a-f]{64}"))
            .extract()
            .jsonPath()
            .getString("secret");
    given()
        .get("/api/projects/" + projectId + "/webhooks")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].url", equalTo("https://hooks.example.com/…"))
        .body("[0].secret", nullValue());
    Long id = given().get("/api/projects/" + projectId + "/webhooks").jsonPath().getLong("[0].id");
    assertEquals(secret, stored(id).secret);
    assertEquals(SLACK, stored(id).url);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void auditKeepsOnlyTheHost() {
    Long projectId = project("hook-lead", false);
    Long id = create(projectId, SLACK);
    AuditEvent event = audit("project.webhook.create", id);
    assertEquals(AuditTargetType.WEBHOOK, event.targetType);
    assertEquals("hooks.example.com", event.details.get("host"));
    assertFalse(event.targetName.contains("SECRET"), event.targetName);
    assertFalse(event.details.toString().contains("services"), event.details.toString());
    assertFalse(event.details.toString().contains(stored(id).secret));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void rejectsUnsafeUrls() {
    Long projectId = project("hook-lead", false);
    TestHostResolver.set("intranet.example.com", "10.0.0.8");
    TestHostResolver.set("127.0.0.1", "127.0.0.1");
    for (String[] c :
        new String[][] {
          {"http://hooks.example.com/x", WebhookUrlValidator.SHAPE},
          {"https://user:pw@hooks.example.com/x", WebhookUrlValidator.SHAPE},
          {"https://hooks.example.com:22/x", WebhookUrlValidator.PORT},
          {"https://intranet.example.com/x", WebhookUrlValidator.BLOCKED},
          {"https://127.0.0.1/x", WebhookUrlValidator.BLOCKED},
        }) {
      given()
          .contentType(ContentType.JSON)
          .body(input(c[0], false, true, false))
          .post("/api/projects/" + projectId + "/webhooks")
          .then()
          .statusCode(400)
          .body("error", equalTo(c[1]));
    }
    given()
        .get("/api/projects/" + projectId + "/webhooks")
        .then()
        .statusCode(200)
        .body("$", hasSize(0));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void requiresAnEvent() {
    Long projectId = project("hook-lead", false);
    given()
        .contentType(ContentType.JSON)
        .body(input(SLACK, false, false, false))
        .post("/api/projects/" + projectId + "/webhooks")
        .then()
        .statusCode(400)
        .body("error", equalTo("Choose at least one event"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void limitsWebhooksPerProject() {
    Long projectId = project("hook-lead", false);
    for (int i = 0; i < ProjectWebhook.MAX_PER_PROJECT; i++) {
      create(projectId, "https://hooks.example.com/" + i);
    }
    given()
        .contentType(ContentType.JSON)
        .body(input(SLACK, false, true, false))
        .post("/api/projects/" + projectId + "/webhooks")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void updateKeepsTheUrlWhenNoneIsGiven() {
    Long projectId = project("hook-lead", false);
    Long id = create(projectId, SLACK);
    given()
        .contentType(ContentType.JSON)
        .body(input(null, true, false, false))
        .put("/api/projects/" + projectId + "/webhooks/" + id)
        .then()
        .statusCode(200)
        .body("onFinished", equalTo(true))
        .body("onFailed", equalTo(false))
        .body("secret", nullValue());
    ProjectWebhook h = stored(id);
    assertEquals(SLACK, h.url);
    assertTrue(h.onFinished);
    assertFalse(h.onFailed);

    given()
        .contentType(ContentType.JSON)
        .body(input("https://other.example.com/hook", true, false, false))
        .put("/api/projects/" + projectId + "/webhooks/" + id)
        .then()
        .statusCode(200)
        .body("url", equalTo("https://other.example.com/…"));
    assertEquals("https://other.example.com/hook", stored(id).url);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void rotateReplacesTheSecret() {
    Long projectId = project("hook-lead", false);
    Long id = create(projectId, SLACK);
    String before = stored(id).secret;
    String after =
        given()
            .post("/api/projects/" + projectId + "/webhooks/" + id + "/secret")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("secret");
    assertNotEquals(before, after);
    assertEquals(after, stored(id).secret);
    assertEquals(
        "hooks.example.com", audit("project.webhook.secret.rotate", id).details.get("host"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void deleteRemovesTheWebhook() {
    Long projectId = project("hook-lead", false);
    Long id = create(projectId, SLACK);
    given().delete("/api/projects/" + projectId + "/webhooks/" + id).then().statusCode(204);
    assertNull(stored(id));
    assertEquals("hooks.example.com", audit("project.webhook.delete", id).details.get("host"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-lead"))
  void anotherProjectsWebhookIsNotFound() {
    Long mine = project("hook-lead", false);
    Long theirs = project("hook-lead", false);
    Long id = create(theirs, SLACK);
    given().delete("/api/projects/" + mine + "/webhooks/" + id).then().statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(input(null, true, true, true))
        .put("/api/projects/" + mine + "/webhooks/" + id)
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-member"))
  void membersCannotManageWebhooks() {
    Long projectId = project("hook-lead", false);
    member(projectId, "hook-member");
    given().get("/api/projects/" + projectId + "/webhooks").then().statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .body(input(SLACK, false, true, true))
        .post("/api/projects/" + projectId + "/webhooks")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "hook-outsider"))
  void privateProjectsAreHiddenFromOutsiders() {
    Long projectId = project("hook-lead", true);
    given().get("/api/projects/" + projectId + "/webhooks").then().statusCode(404);
  }
}
