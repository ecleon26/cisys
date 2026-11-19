/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BuildResourceTest {
  @org.junit.jupiter.api.AfterEach
  void releaseSyntheticRunningBuilds() {
    QuarkusTransaction.requiringNew()
        .run(() ->
            Build.update(
                "status = ?1 where status = ?2 and repositoryUrl is null",
                Build.Status.FAILED,
                Build.Status.RUNNING));
  }

  @Inject BuildRunner runner;
  @Inject BuildLogs logs;

  private Long project(boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = java.util.UUID.randomUUID().toString();
          p.name = "example-" + n;
          p.repositoryUrl = "https://github.com/octocat/Hello-World-" + n + ".git";
          p.ownerSubject = "owner-id";
          p.privateRepository = priv;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = p.ownerSubject;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = java.time.Instant.now();
          m.persist();
          return p.id;
        });
  }

  private static void allow(String image) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          if (AllowedImage.findById(image) == null) {
            AllowedImage i = new AllowedImage();
            i.name = image;
            i.persist();
          }
        });
  }

  private Long build(Long project, String status, String... logParts) {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = project;
              b.number = Build.nextNumber(project);
              b.requestedBy = "owner-id";
              b.status = Build.Status.valueOf(status);
              b.branch = "main";
              b.image = "alpine:3.23";
              b.exitCode = "PASSED".equals(status) ? 0 : null;
              b.startedAt = java.time.Instant.now();
              b.persist();
              return b.id;
            });
    if (logParts.length > 0) {
      try (var w = logs.open(id)) {
        for (String part : logParts) {
          byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          w.append(bytes, 0, bytes.length);
        }
      }
    }
    return id;
  }

  static final String REQUEST = "{\"branch\":\"main\"}";

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void privateLogsAreHidden() {
    Long id = project(true);
    given().get("/api/projects/" + id + "/builds").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberCanViewPublicButCannotTrigger() {
    Long id = project(false);
    given().get("/api/projects/" + id + "/builds").then().statusCode(200);
    given()
        .contentType("application/json")
        .body(REQUEST)
        .post("/api/projects/" + id + "/builds")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void ownerCanQueueWhileRunningAndGetsClearCapacityError() {
    Long id = project(true);
    allow("alpine:3.23");
    build(id, "RUNNING");
    BuildRunner target = io.quarkus.arc.ClientProxy.unwrap(runner);
    int limit = target.queueLimit;
    target.queueLimit = 1;
    String path = "/api/projects/" + id + "/builds";
    try {
      given()
          .contentType("application/json")
          .body(REQUEST)
          .post(path)
          .then()
          .statusCode(200)
          .body("status", equalTo("QUEUED"), "startedAt", nullValue());
      given()
          .contentType("application/json")
          .body(REQUEST)
          .post(path)
          .then()
          .statusCode(429)
          .body(containsString("queue is full"));
    } finally {
      target.queueLimit = limit;
      QuarkusTransaction.requiringNew()
          .run(() -> Build.delete("projectId = ?1 and status = ?2", id, Build.Status.QUEUED));
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void rejectInvalidBranch() {
    Long id = project(true);
    given()
        .contentType("application/json")
        .body(REQUEST.replace("main", "branch.lock"))
        .post("/api/projects/" + id + "/builds")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void leadCannotUseGlobalCredentials() {
    Long id = project(true);
    allow("alpine:3.23");
    given()
        .contentType("application/json")
        .body(REQUEST.replace("}", ",\"credentialRef\":\"CISYS_GIT_SECRET\"}"))
        .post("/api/projects/" + id + "/builds")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCannotUseServerConfigAsCredential() {
    Long id = project(true);
    allow("alpine:3.23");
    given()
        .contentType("application/json")
        .body(REQUEST.replace("}", ",\"credentialRef\":\"CISYS_GIT_ALLOWED_HOSTS\"}"))
        .post("/api/projects/" + id + "/builds")
        .then()
        .statusCode(400)
        .body("error", containsString("configures the server"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCannotUseCredentialForPublicProject() {
    Long id = project(false);
    allow("alpine:3.23");
    given()
        .contentType("application/json")
        .body(REQUEST.replace("}", ",\"credentialRef\":\"CISYS_GIT_SECRET\"}"))
        .post("/api/projects/" + id + "/builds")
        .then()
        .statusCode(400)
        .body("error", equalTo("Only private projects can use a credential"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void buildUsesProjectCredential() throws InterruptedException {
    Long projectId = project(true);
    allow("alpine:3.23");
    // Unset reference, so the checkout fails before contacting the remote.
    String credentialRef =
        "CISYS_GIT_TEST_"
            + java.util
                .UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .toUpperCase(java.util.Locale.ROOT);
    QuarkusTransaction.requiringNew()
        .run(() -> Project.<Project>findById(projectId).credentialRef = credentialRef);
    try {
      int id =
          given()
              .contentType("application/json")
              .body(REQUEST)
              .post("/api/projects/" + projectId + "/builds")
              .then()
              .statusCode(200)
              .body("$", not(hasKey("credentialRef")))
              .extract()
              .path("id");
      assertEquals(
          credentialRef,
          QuarkusTransaction.requiringNew()
              .call(() -> Build.<Build>findById((long) id).credentialRef));
    } finally {
      runner.dispatch();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
      while (runner.hasPendingBuilds() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertFalse(runner.hasPendingBuilds(), "Build did not finish");
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void rejectHostResolvingToABlockedAddress() {
    Long id = project(false);
    allow("alpine:3.23");
    TestHostResolver.set("github.com", "127.0.0.1");
    try {
      given()
          .contentType("application/json")
          .body(REQUEST)
          .post("/api/projects/" + id + "/builds")
          .then()
          .statusCode(400);
    } finally {
      TestHostResolver.reset();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void savesBuildRequestDetailsAndReturnsCredentialReferenceToAdmin() throws InterruptedException {
    Long projectId = project(true);
    allow("alpine:3.23");
    String path = "/api/projects/" + projectId + "/builds";
    // Use an unset reference so the checkout fails before contacting the remote.
    String credentialRef =
        "CISYS_GIT_TEST_"
            + java.util
                .UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .toUpperCase(java.util.Locale.ROOT);
    String request = REQUEST.replace("}", ",\"credentialRef\":\"" + credentialRef + "\"}");
    try {
      int id =
          given()
              .contentType("application/json")
              .body(request)
              .post(path)
              .then()
              .statusCode(200)
              .body(
                  "branch",
                  equalTo("main"),
                  "image",
                  nullValue(),
                  "$",
                  not(hasKey("command")),
                  "credentialRef",
                  equalTo(credentialRef))
              .extract()
              .path("id");
      runner.dispatch();
      given().get(path).then().statusCode(200).body("[0].credentialRef", equalTo(credentialRef));
      given()
          .get(path + "/" + id)
          .then()
          .statusCode(200)
          .body("branch", equalTo("main"), "credentialRef", equalTo(credentialRef));
    } finally {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
      while (runner.hasPendingBuilds() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertFalse(runner.hasPendingBuilds(), "Build did not finish");
    }
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void buildHistoryReturnsTheStoredPullRequestIdentity() {
    Long projectId = project(false);
    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = projectId;
              b.number = Build.nextNumber(projectId);
              b.status = Build.Status.PASSED;
              b.branch = "feature";
              b.trigger = Build.Trigger.PULL_REQUEST;
              b.pullRequest = 7;
              b.pullRequestProvider = "GITLAB";
              b.revision = "a".repeat(40);
              b.persist();
              return b.id;
            });
    String path = "/api/projects/" + projectId + "/builds";
    given()
        .get(path)
        .then()
        .statusCode(200)
        .body(
            "[0].trigger",
            equalTo("PULL_REQUEST"),
            "[0].pullRequest",
            equalTo(7),
            "[0].pullRequestProvider",
            equalTo("GITLAB"));
    given()
        .get(path + "/" + buildId)
        .then()
        .statusCode(200)
        .body(
            "trigger",
            equalTo("PULL_REQUEST"),
            "pullRequest",
            equalTo(7),
            "pullRequestProvider",
            equalTo("GITLAB"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void buildHistoryHidesCredentialReferenceAndSupportsLegacyBuilds() {
    Long projectId = project(false);
    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build legacy = new Build();
              legacy.projectId = projectId;
              legacy.number = Build.nextNumber(projectId);
              legacy.status = Build.Status.PASSED;
              legacy.persist();
              Build build = new Build();
              build.projectId = projectId;
              build.number = Build.nextNumber(projectId);
              build.status = Build.Status.PASSED;
              build.branch = "main";
              build.image = "alpine:3.23";
              build.credentialRef = "CISYS_GIT_SECRET";
              build.workflow = "name: main\n";
              build.persist();
              return build.id;
            });
    String path = "/api/projects/" + projectId + "/builds";
    given()
        .get(path)
        .then()
        .statusCode(200)
        .body(
            "[0].branch",
            equalTo("main"),
            "[0].image",
            equalTo("alpine:3.23"),
            "[0]",
            not(hasKey("command")),
            "[0]",
            not(hasKey("credentialRef")),
            "[0]",
            not(hasKey("workflow")),
            "[1].status",
            equalTo("PASSED"),
            "[1].branch",
            nullValue(),
            "[1].image",
            nullValue());
    given()
        .get(path + "/" + buildId)
        .then()
        .statusCode(200)
        .body(
            "branch",
            equalTo("main"),
            "$",
            not(hasKey("credentialRef")),
            "workflow",
            equalTo("name: main\n"));
  }

  @Test
  void processOutputIsBounded() throws Exception {
    var r = BuildRunner.command(List.of("sh", "-c", "head -c 500000 /dev/zero"), 10, 1024);
    assertEquals(0, r.code());
    assertTrue(r.output().endsWith("[output truncated]\n"));
    assertTrue(r.output().length() < 1100);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void listOmitsOutput() {
    Long id = project(false);
    build(id, "PASSED", "secret-log-text");
    given()
        .get("/api/projects/" + id + "/builds")
        .then()
        .statusCode(200)
        .body(not(containsString("secret-log-text")))
        .body(not(containsString("\"output\"")))
        .body("[0].number", equalTo(1))
        .body("[0].branch", equalTo("main"))
        .body("[0].image", equalTo("alpine:3.23"))
        .body("[0].exitCode", equalTo(0))
        .body("[0].logTruncated", equalTo(false));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void logStreamsAppendedParts() {
    Long id = project(false);
    Long b = build(id, "PASSED", "one\n", "two\n", "é\n");
    given()
        .get("/api/projects/" + id + "/builds/" + b + "/log")
        .then()
        .statusCode(200)
        .contentType(startsWith("text/plain"))
        .body(equalTo("one\ntwo\né\n"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void logSupportsOffsetLimitAndTail() {
    Long id = project(false);
    Long b = build(id, "PASSED", "0123456789");
    String path = "/api/projects/" + id + "/builds/" + b + "/log";
    given()
        .queryParam("offset", 2)
        .queryParam("limit", 3)
        .get(path)
        .then()
        .statusCode(200)
        .header("X-Log-Size", "10")
        .header("X-Log-Offset", "2")
        .header("X-Log-Length", "3")
        .body(equalTo("234"));
    given()
        .queryParam("tail", 4)
        .get(path)
        .then()
        .statusCode(200)
        .header("X-Log-Offset", "6")
        .body(equalTo("6789"));
    given().queryParam("offset", -1).get(path).then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void privateLogIsHidden() {
    Long id = project(true);
    Long b = build(id, "PASSED", "hidden");
    given().get("/api/projects/" + id + "/builds/" + b + "/log").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void logOfBuildInOtherProjectIs404() {
    Long id = project(false), other = project(false);
    Long b = build(other, "PASSED", "elsewhere");
    given().get("/api/projects/" + id + "/builds/" + b + "/log").then().statusCode(404);
  }

  @Test
  void recoverKeepsPartialLog() {
    Long id = project(false);
    Long b = build(id, "RUNNING", "partial\n");
    runner.recover(null);
    Build after = QuarkusTransaction.requiringNew().call(() -> Build.findById(b));
    assertEquals(Build.Status.FAILED, after.status);
    assertNotNull(after.finishedAt);
    assertEquals("partial\nServer restarted during build\n", logs.readAll(b));
  }

  private static boolean pinned(Long build) {
    return QuarkusTransaction.requiringNew().call(() -> Build.<Build>findById(build).pinned);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void ownerPinsAndUnpins() {
    Long id = project(false);
    Long b = build(id, "PASSED");
    String pin = "/api/projects/" + id + "/builds/" + b + "/pin";
    given()
        .contentType("application/json")
        .put(pin)
        .then()
        .statusCode(200)
        .body("pinned", equalTo(true));
    assertTrue(pinned(b));
    given().get("/api/projects/" + id + "/builds").then().body("[0].pinned", equalTo(true));
    given()
        .contentType("application/json")
        .put(pin)
        .then()
        .statusCode(200)
        .body("pinned", equalTo(true));
    given()
        .contentType("application/json")
        .delete(pin)
        .then()
        .statusCode(200)
        .body("pinned", equalTo(false));
    assertFalse(pinned(b));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminPinsPrivateProjectOfOthers() {
    Long id = project(true);
    Long b = build(id, "FAILED");
    given()
        .contentType("application/json")
        .put("/api/projects/" + id + "/builds/" + b + "/pin")
        .then()
        .statusCode(200)
        .body("pinned", equalTo(true));
    assertTrue(pinned(b));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void otherLeadCannotPin() {
    Long id = project(false);
    Long b = build(id, "PASSED");
    given()
        .contentType("application/json")
        .put("/api/projects/" + id + "/builds/" + b + "/pin")
        .then()
        .statusCode(403);
    assertFalse(pinned(b));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberCannotPinOrSeePrivate() {
    Long pub = project(false), priv = project(true);
    Long b = build(pub, "PASSED"), hidden = build(priv, "PASSED");
    given()
        .contentType("application/json")
        .put("/api/projects/" + pub + "/builds/" + b + "/pin")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .put("/api/projects/" + priv + "/builds/" + hidden + "/pin")
        .then()
        .statusCode(404);
    assertFalse(pinned(b));
    assertFalse(pinned(hidden));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void pinOfBuildInOtherProjectIs404() {
    Long id = project(false), other = project(false);
    Long b = build(other, "PASSED");
    given()
        .contentType("application/json")
        .put("/api/projects/" + id + "/builds/" + b + "/pin")
        .then()
        .statusCode(404);
    assertFalse(pinned(b));
  }

  private void requesterVisible(boolean visible) {
    Long id = project(false);
    Long b = build(id, "PASSED");
    String path = "/api/projects/" + id + "/builds";
    if (visible) {
      given().get(path).then().statusCode(200).body("[0].requestedBy", equalTo("owner-id"));
      given().get(path + "/" + b).then().statusCode(200).body("requestedBy", equalTo("owner-id"));
    } else {
      given().get(path).then().statusCode(200).body("[0]", not(hasKey("requestedBy")));
      given().get(path + "/" + b).then().statusCode(200).body("$", not(hasKey("requestedBy")));
    }
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberDoesNotSeeRequester() {
    requesterVisible(false);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void leadDoesNotSeeRequester() {
    requesterVisible(false);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminSeesRequester() {
    requesterVisible(true);
  }

  private static Build stored(Long build) {
    return QuarkusTransaction.requiringNew().call(() -> Build.findById(build));
  }

  private static void requestedBy(Long build, String subject) {
    QuarkusTransaction.requiringNew()
        .run(() -> Build.update("requestedBy = ?1 where id = ?2", subject, build));
  }

  private static void membership(Long projectId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = role;
          m.createdAt = java.time.Instant.now();
          m.persist();
        });
  }

  private static String cancel(Long project, Long build) {
    return "/api/projects/" + project + "/builds/" + build + "/cancel";
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void ownerCancelsQueuedAndRunningBuilds() {
    Long id = project(false);
    Long queued = build(id, "QUEUED"), running = build(id, "RUNNING"), done = build(id, "PASSED");
    given()
        .contentType("application/json")
        .post(cancel(id, queued))
        .then()
        .statusCode(200)
        .body("status", equalTo("CANCELLED"), "finishedAt", notNullValue());
    given()
        .contentType("application/json")
        .post(cancel(id, running))
        .then()
        .statusCode(200)
        .body("status", equalTo("RUNNING"));
    assertTrue(stored(running).cancelRequested);
    given()
        .contentType("application/json")
        .post(cancel(id, done))
        .then()
        .statusCode(409)
        .body("error", equalTo("The build has already finished."));
    assertEquals(Build.Status.PASSED, stored(done).status);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCancelsPrivateBuildOfOthers() {
    Long id = project(true);
    Long b = build(id, "RUNNING");
    given().contentType("application/json").post(cancel(id, b)).then().statusCode(200);
    assertTrue(stored(b).cancelRequested);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "requester-id"))
  void requesterCancelsOnlyOwnBuild() {
    Long id = project(false);
    Long own = build(id, "RUNNING"), others = build(id, "RUNNING");
    requestedBy(own, "requester-id");
    given().contentType("application/json").post(cancel(id, own)).then().statusCode(200);
    assertTrue(stored(own).cancelRequested);
    given().contentType("application/json").post(cancel(id, others)).then().statusCode(403);
    assertFalse(stored(others).cancelRequested);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberCannotCancel() {
    Long id = project(false);
    Long b = build(id, "RUNNING");
    given().contentType("application/json").post(cancel(id, b)).then().statusCode(403);
    assertFalse(stored(b).cancelRequested);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead2-id"))
  void projectLeadWithGlobalMemberCanCancelAnotherUsersBuild() {
    Long id = project(false);
    Long b = build(id, "RUNNING");
    membership(id, "lead2-id", ProjectMember.TeamRole.LEAD);
    given().contentType("application/json").post(cancel(id, b)).then().statusCode(200);
    assertTrue(stored(b).cancelRequested);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead2-id"))
  void projectLeadWithGlobalMemberCanPinAndUnpin() {
    Long id = project(false);
    Long b = build(id, "PASSED");
    membership(id, "lead2-id", ProjectMember.TeamRole.LEAD);
    String link = "/api/projects/" + id + "/builds/" + b + "/pin";
    given()
        .contentType("application/json")
        .put(link)
        .then()
        .statusCode(200)
        .body("pinned", org.hamcrest.Matchers.equalTo(true));
    given()
        .contentType("application/json")
        .delete(link)
        .then()
        .statusCode(200)
        .body("pinned", org.hamcrest.Matchers.equalTo(false));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void globalLeadWithOnlyMembershipAsMemberCannotCancelAnotherUsersBuild() {
    Long id = project(false);
    Long b = build(id, "RUNNING");
    membership(id, "other-id", ProjectMember.TeamRole.MEMBER);
    given().contentType("application/json").post(cancel(id, b)).then().statusCode(403);
    assertFalse(stored(b).cancelRequested);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberCannotCancelOwnBuild() {
    // A global member cannot cancel a build they started, even as project member.
    Long id = project(false);
    Long b = build(id, "RUNNING");
    membership(id, "other-id", ProjectMember.TeamRole.MEMBER);
    requestedBy(b, "other-id");
    given().contentType("application/json").post(cancel(id, b)).then().statusCode(403);
    assertFalse(stored(b).cancelRequested);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void cancelOfBuildInOtherProjectIs404() {
    Long id = project(false), other = project(false);
    Long b = build(other, "RUNNING");
    given().contentType("application/json").post(cancel(id, b)).then().statusCode(404);
    assertFalse(stored(b).cancelRequested);
  }

  @Test
  void recoverCancelsRequestedBuild() {
    Long id = project(false);
    Long b = build(id, "RUNNING", "partial\n");
    QuarkusTransaction.requiringNew()
        .run(() -> Build.update("cancelRequested = true where id = ?1", b));
    runner.recover(null);
    assertEquals(Build.Status.CANCELLED, stored(b).status);
    assertEquals("partial\nBuild cancelled\n", logs.readAll(b));
  }

  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  /** A finished build of the project's current repository at {@link #COMMIT}. */
  private Long finished(Long project, String status) {
    Long b = build(project, status);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build row = Build.findById(b);
          row.revision = COMMIT;
          row.repositoryUrl = Project.<Project>findById(project).repositoryUrl;
          row.finishedAt = java.time.Instant.now();
        });
    return b;
  }

  private static String rerun(Long project, Long build) {
    return "/api/projects/" + project + "/builds/" + build + "/rerun";
  }

  private static io.restassured.response.ValidatableResponse postRerun(Long project, Long build) {
    return given().contentType("application/json").post(rerun(project, build)).then();
  }

  private static void deleteQueued(Long project) {
    QuarkusTransaction.requiringNew()
        .run(() -> Build.delete("projectId = ?1 and status = ?2", project, Build.Status.QUEUED));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberRerunsFinishedBuildAtSameCommitWithCurrentCredential() {
    Long id = project(true);
    membership(id, "other-id", ProjectMember.TeamRole.MEMBER);
    Long b = finished(id, "FAILED");
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.update("credentialRef = 'CISYS_GIT_OLD' where id = ?1", b);
          Project.<Project>findById(id).credentialRef = "CISYS_GIT_CURRENT";
        });
    try {
      int queued =
          given()
              .contentType("application/json")
              .post(rerun(id, b))
              .then()
              .statusCode(200)
              .body(
                  "status", equalTo("QUEUED"),
                  "branch", equalTo("main"),
                  "revision", equalTo(COMMIT),
                  "trigger", equalTo("MANUAL"),
                  "$", not(hasKey("credentialRef")))
              .extract()
              .path("id");
      Build row = stored((long) queued);
      assertEquals("CISYS_GIT_CURRENT", row.credentialRef);
      assertEquals("other-id", row.requestedBy);
      assertTrue(row.number > stored(b).number);
    } finally {
      deleteQueued(id);
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void rerunOfPublicProjectSendsNoCredential() {
    Long id = project(false);
    Long b = finished(id, "PASSED");
    QuarkusTransaction.requiringNew()
        .run(() -> Build.update("credentialRef = 'CISYS_GIT_OLD' where id = ?1", b));
    try {
      int queued =
          given()
              .contentType("application/json")
              .post(rerun(id, b))
              .then()
              .statusCode(200)
              .extract()
              .path("id");
      assertNull(stored((long) queued).credentialRef);
    } finally {
      deleteQueued(id);
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void rerunRejectsBuildsThatCannotBeRepeated() {
    Long id = project(false);
    Long running = finished(id, "RUNNING");
    Long pullRequest = finished(id, "PASSED");
    Long noCommit = build(id, "FAILED");
    Long moved = finished(id, "FAILED");
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build pr = Build.findById(pullRequest);
          pr.trigger = Build.Trigger.PULL_REQUEST;
          pr.pullRequest = 7;
          pr.pullRequestProvider = "GITHUB";
          Build.update("repositoryUrl = 'https://github.com/old/repository' where id = ?1", moved);
        });
    try {
      postRerun(id, running).statusCode(409).body(containsString("not finished"));
      postRerun(id, pullRequest).statusCode(409).body(containsString("Pull request"));
      postRerun(id, noCommit).statusCode(409).body(containsString("no commit"));
      postRerun(id, moved).statusCode(409).body(containsString("repository has changed"));
      assertEquals(
          0L,
          QuarkusTransaction.requiringNew()
              .call(() -> Build.count("projectId = ?1 and status = ?2", id, Build.Status.QUEUED)));
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> Build.update("status = ?1 where id = ?2", Build.Status.FAILED, running));
    }
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void rerunNeedsProjectMembership() {
    Long id = project(false);
    Long b = finished(id, "FAILED");
    postRerun(id, b).statusCode(403);
    Long hidden = project(true);
    postRerun(hidden, finished(hidden, "FAILED")).statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void rerunOfBuildInOtherProjectIs404() {
    Long id = project(false), other = project(false);
    postRerun(id, finished(other, "FAILED")).statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void buildPageOffersRerunToMembersOnly() {
    Long id = project(false);
    Long b = finished(id, "FAILED");
    String page =
        "/api/project-pages/"
            + QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(id).name)
            + "/builds/"
            + b;
    given().get(page).then().statusCode(200).body("canRerun", equalTo(false));
    membership(id, "other-id", ProjectMember.TeamRole.MEMBER);
    given().get(page).then().statusCode(200).body("canRerun", equalTo(true));
  }

  @Test
  void anonymousVisitorsAreNotOfferedRerun() {
    Long id = project(false);
    Long b = finished(id, "FAILED");
    String name = QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(id).name);
    given()
        .get("/api/project-pages/" + name + "/builds/" + b)
        .then()
        .statusCode(200)
        .body("canRerun", equalTo(false));
    postRerun(id, b).statusCode(401);
  }
}
