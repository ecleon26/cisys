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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProjectResourceTest {
  @Inject RepositoryMirror mirrors;

  @Test
  void anonymousCannotListProjects() {
    given().when().get("/api/projects").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void memberCannotCreateProject() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"demo\",\"repositoryUrl\":\"https://github.com/repo.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCreatesPrivateProjectWithSubjectOwner() {
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"demo\",\"repositoryUrl\":\"https://github.com/repo.git\",\"privateRepository\":true}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201)
        .body(
            "owned",
            equalTo(true),
            "privateRepository",
            equalTo(true),
            "$",
            not(hasKey("ownerSubject")));
    assertEquals(
        "lead-id",
        QuarkusTransaction.requiringNew()
            .call(() -> Project.<Project>find("name", "demo").firstResult().ownerSubject));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void memberListHidesOwnerSubject() {
    Long id = project("lead-id", false);
    given()
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body(
            inList(id) + ".owned",
            equalTo(false),
            inList(id),
            not(hasKey("ownerSubject")),
            "ownerSubject",
            everyItem(nullValue()));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadListMarksOwnProjects() {
    Long own = project("lead-id", true), other = project("other-id", false);
    given()
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body(
            inList(own) + ".owned",
            equalTo(true),
            inList(other) + ".owned",
            equalTo(false),
            "ownerSubject",
            everyItem(nullValue()));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminSeesOwnerSubject() {
    Long id = project("lead-id", true);
    given()
        .when()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body(
            inList(id) + ".ownerSubject",
            equalTo("lead-id"),
            inList(id) + ".owned",
            equalTo(false));
    given()
        .contentType("application/json")
        .body("{\"name\":\"admin-owned\",\"repositoryUrl\":\"https://github.com/admin-owned.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201)
        .body("ownerSubject", equalTo("admin-id"), "owned", equalTo(true));
  }

  private static String inList(Long id) {
    return "find { it.id == " + id + " }";
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void rejectsDuplicateNameOrRepositoryUrl() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"unique-one\",\"repositoryUrl\":\"https://github.com/unique-one.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201);
    given()
        .contentType("application/json")
        .body("{\"name\":\" Unique-ONE \",\"repositoryUrl\":\"https://github.com/other.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(409)
        .body("error", containsString("name"));
    given()
        .contentType("application/json")
        .body("{\"name\":\"unique-two\",\"repositoryUrl\":\"https://github.com/unique-one.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(409)
        .body("error", containsString("repository URL"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void databaseUniqueConstraintMapsToConflict() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project.getEntityManager()
              .createNativeQuery(
                  "CREATE FUNCTION test_project_insert_race() RETURNS trigger "
                      + "LANGUAGE plpgsql AS $$ BEGIN "
                      + "IF pg_trigger_depth() = 1 THEN "
                      + "INSERT INTO project (id, name, ownersubject, privaterepository, repositoryurl) "
                      + "VALUES (nextval('project_seq'), NEW.name || '-race', NEW.ownersubject, "
                      + "NEW.privaterepository, NEW.repositoryurl); END IF; RETURN NEW; END $$")
              .executeUpdate();
          Project.getEntityManager()
              .createNativeQuery(
                  "CREATE TRIGGER test_project_insert_race_trigger BEFORE INSERT ON project "
                      + "FOR EACH ROW EXECUTE FUNCTION test_project_insert_race()")
              .executeUpdate();
        });
    try {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\"constraint-race\",\"repositoryUrl\":\"https://github.com/constraint-race\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(409)
          .body("error", containsString("name or repository URL"));
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Project.getEntityManager()
                .createNativeQuery("DROP TRIGGER test_project_insert_race_trigger ON project")
                .executeUpdate();
            Project.getEntityManager()
                .createNativeQuery("DROP FUNCTION test_project_insert_race()")
                .executeUpdate();
          });
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void databaseNameUniqueIndexMapsToConflict() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project.getEntityManager()
              .createNativeQuery(
                  "CREATE FUNCTION test_project_name_insert_race() RETURNS trigger "
                      + "LANGUAGE plpgsql AS $$ BEGIN "
                      + "IF pg_trigger_depth() = 1 THEN "
                      + "INSERT INTO project (id, name, ownersubject, privaterepository, repositoryurl) "
                      + "VALUES (nextval('project_seq'), upper(NEW.name), NEW.ownersubject, "
                      + "NEW.privaterepository, NEW.repositoryurl || '-race'); END IF; RETURN NEW; END $$")
              .executeUpdate();
          Project.getEntityManager()
              .createNativeQuery(
                  "CREATE TRIGGER test_project_name_insert_race_trigger BEFORE INSERT ON project "
                      + "FOR EACH ROW EXECUTE FUNCTION test_project_name_insert_race()")
              .executeUpdate();
        });
    try {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\"constraint-name-race\",\"repositoryUrl\":\"https://github.com/constraint-name-race\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(409)
          .body("error", containsString("name or repository URL"));
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Project.getEntityManager()
                .createNativeQuery("DROP TRIGGER test_project_name_insert_race_trigger ON project")
                .executeUpdate();
            Project.getEntityManager()
                .createNativeQuery("DROP FUNCTION test_project_name_insert_race()")
                .executeUpdate();
          });
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void rejectsInvalidNames() {
    for (String name :
        new String[] {
          "org/repo",
          ".",
          "..",
          ".hidden",
          "-x",
          "_x",
          "a b",
          "a?b",
          "a#b",
          "a%20b",
          "a\\nb",
          "a\\u200Bb",
          "c\\u0456sys",
          "\\u00fcn"
        }) {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\""
                  + name
                  + "\",\"repositoryUrl\":\"https://github.com/invalid-name.git\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(400)
          .body("error", equalTo(ProjectResource.NAME_RULE));
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void rejectsTheManualName() {
    for (String name : new String[] {"manual", "Manual", "MANUAL"}) {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\"" + name + "\",\"repositoryUrl\":\"https://github.com/org/manual.git\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(400)
          .body("error", equalTo(ProjectResource.RESERVED_RULE));
    }
    Long id = project("lead-id", false);
    given()
        .contentType("application/json")
        .body("{\"name\":\"manual\",\"repositoryUrl\":\"https://github.com/org/manual.git\"}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(400)
        .body("error", equalTo(ProjectResource.RESERVED_RULE));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void projectNamedManualBeforeTheReservationCanStillBeEdited() {
    Long id = project("lead-id", false);
    String url = "https://github.com/org/legacy-manual-" + java.util.UUID.randomUUID() + ".git";
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(id);
          p.name = "Manual";
          p.repositoryUrl = url;
        });
    try {
      given()
          .contentType("application/json")
          .body("{\"name\":\"manual\",\"repositoryUrl\":\"" + url + "\"}")
          .when()
          .put("/api/projects/" + id)
          .then()
          .statusCode(200);
    } finally {
      QuarkusTransaction.requiringNew().run(() -> Project.findById(id).delete());
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void acceptsSlugNames() {
    for (String name : new String[] {"slug", "My.Project_2-x", "9lives", "a".repeat(100)}) {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\""
                  + name
                  + "\",\"repositoryUrl\":\"https://github.com/slug-"
                  + name
                  + ".git\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(201);
    }
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/api/project-pages/My.Project_2-x")
        .then()
        .statusCode(200)
        .body("project.name", equalTo("My.Project_2-x"));
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + "a".repeat(101)
                + "\",\"repositoryUrl\":\"https://github.com/too-long.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void rejectsHostThatIsNotAllowed() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"not-allowed\",\"repositoryUrl\":\"https://example.com/repo.git\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(400)
        .body(containsString("allowed host"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void rejectsHostThatResolvesToABlockedAddress() {
    TestHostResolver.set("gitlab.com", "10.0.0.1");
    try {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\"blocked-address\",\"repositoryUrl\":\"https://gitlab.com/group/repo.git\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(400)
          .body(containsString("blocked address"));
    } finally {
      TestHostResolver.reset();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCreatesPrivateProjectWithCredential() {
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"cred-project\",\"repositoryUrl\":\"https://github.com/org/cred.git\",\"privateRepository\":true,\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201)
        .body("credentialRef", equalTo("CISYS_GIT_TEST"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotSetACredential() {
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"lead-cred\",\"repositoryUrl\":\"https://github.com/org/lead-cred.git\",\"privateRepository\":true,\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void credentialNeedsAPrivateProjectAndAnEnvironmentVariableName() {
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"public-cred\",\"repositoryUrl\":\"https://github.com/org/public-cred.git\",\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(400)
        .body("error", containsString("Only private projects"));
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"bad-cred\",\"repositoryUrl\":\"https://github.com/org/bad-cred.git\",\"privateRepository\":true,\"credentialRef\":\"TOKEN\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(400)
        .body("error", equalTo("Invalid credential reference"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void configurationVariablesAreNotCredentials() {
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"config-cred\",\"repositoryUrl\":\"https://github.com/org/config-cred.git\",\"privateRepository\":true,\"credentialRef\":\"CISYS_GIT_ALLOWED_PORTS\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(400)
        .body("error", containsString("configures the server"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminChangesAndClearsTheCredential() {
    int id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\"change-cred\",\"repositoryUrl\":\"https://github.com/org/change-cred.git\",\"privateRepository\":true}")
            .when()
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    given()
        .contentType("application/json")
        .body("{\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(200)
        .body("credentialRef", equalTo("CISYS_GIT_TEST"));
    given()
        .contentType("application/json")
        .body("{\"credentialRef\":null}")
        .when()
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(200)
        .body("credentialRef", nullValue());
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotChangeACredential() {
    int id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\"lead-change\",\"repositoryUrl\":\"https://github.com/org/lead-change.git\",\"privateRepository\":true}")
            .when()
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    given()
        .contentType("application/json")
        .body("{\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadKeepsTheCredentialWhenEditingOwnProjectWithoutChangingTheRepository() {
    Long id = project("lead-id", true);
    String url = "https://github.com/org/lead-keeps-" + java.util.UUID.randomUUID() + ".git";
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(id);
          p.repositoryUrl = url;
          p.credentialRef = "CISYS_GIT_TEST";
        });
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"lead-keeps\",\"repositoryUrl\":\""
                + url
                + "\",\"privateRepository\":true}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        // The reference is for admins, so a lead's GET-then-PUT round trip has nothing to reject.
        .body("credentialRef", nullValue());
    QuarkusTransaction.requiringNew()
        .run(() -> assertEquals("CISYS_GIT_TEST", Project.<Project>findById(id).credentialRef));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadChangingTheRepositoryDropsTheCredential() {
    Long id = project("lead-id", true);
    String url = "https://github.com/org/lead-moves-" + java.util.UUID.randomUUID() + ".git";
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(id);
          p.credentialRef = "CISYS_GIT_TEST";
        });
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"lead-moves\",\"repositoryUrl\":\""
                + url
                + "\",\"privateRepository\":true}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("credentialRef", nullValue());
    QuarkusTransaction.requiringNew()
        .run(() -> assertNull(Project.<Project>findById(id).credentialRef));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void changingTheRepositoryUrlResetsThePollingCursors() {
    Long id = project("lead-id", false);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectBranch row = new ProjectBranch();
          row.projectId = id;
          row.branch = "main";
          row.lastSeenRevision = "a".repeat(40);
          row.persist();
        });
    assertNotNull(QuarkusTransaction.requiringNew().call(() -> ProjectBranch.of(id, "main")));

    String name = "moved-" + java.util.UUID.randomUUID();
    String url = "https://github.com/moved/" + java.util.UUID.randomUUID() + ".git";
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\""
                + url
                + "\",\"privateRepository\":false}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(200);

    assertNull(
        QuarkusTransaction.requiringNew().call(() -> ProjectBranch.of(id, "main")),
        "a new repository URL must make the poller seed again");
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void ownerUpdatesOwnProject() {
    Long id = project("lead-id", false);
    String name = "updated-" + java.util.UUID.randomUUID();
    String updatedUrl = "https://github.com/updated/" + java.util.UUID.randomUUID() + ".git";
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\""
                + updatedUrl
                + "\",\"privateRepository\":true}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body(
            "name",
            equalTo(name),
            "repositoryUrl",
            equalTo(RepositoryUrls.normalize(updatedUrl)),
            "privateRepository",
            equalTo(true));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotUpdateOthersProjects() {
    Long open = project("other-id", false), hidden = project("other-id", true);
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"hijacked\",\"repositoryUrl\":\"https://github.com/other/repository.git\",\"privateRepository\":false}")
        .when()
        .put("/api/projects/" + open)
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"hijacked\",\"repositoryUrl\":\"https://github.com/other/repository.git\",\"privateRepository\":false}")
        .when()
        .put("/api/projects/" + hidden)
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminSetsAndClearsCredentialThroughUpdate() {
    Long id = project("other-id", true);
    String name = "cred-update-" + java.util.UUID.randomUUID();
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\"https://github.com/org/cred-update.git\",\"privateRepository\":true,\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("credentialRef", equalTo("CISYS_GIT_TEST"));
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\"https://github.com/org/cred-update.git\",\"privateRepository\":true,\"credentialRef\":null}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("credentialRef", nullValue());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void cannotSetCredentialOnPublicProjectThroughUpdate() {
    Long id = project("other-id", false);
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"public-update\",\"repositoryUrl\":\"https://github.com/org/public-update-"
                + java.util.UUID.randomUUID()
                + ".git\",\"privateRepository\":false,\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(400)
        .body("error", containsString("Only private projects"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotSetCredentialThroughUpdate() {
    Long id = project("lead-id", true);
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"lead-update-cred\",\"repositoryUrl\":\"https://github.com/org/lead-update-cred.git\",\"privateRepository\":true,\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .when()
        .put("/api/projects/" + id)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void updateDoesNotRetargetQueuedOrRunningBuilds() {
    Long id = project("lead-id", false);
    Long queued = build(id, "QUEUED");
    Long running = build(id, "RUNNING");
    String originalUrl = "https://github.com/original/" + java.util.UUID.randomUUID() + ".git";
    String updatedUrl = "https://github.com/updated/" + java.util.UUID.randomUUID() + ".git";
    String updatedName = "updated-" + java.util.UUID.randomUUID();
    try {
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Project p = Project.findById(id);
            p.repositoryUrl = originalUrl;
            Build.<Build>findById(queued).repositoryUrl = originalUrl;
            Build.<Build>findById(running).repositoryUrl = originalUrl;
          });
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\""
                  + updatedName
                  + "\",\"repositoryUrl\":\""
                  + updatedUrl
                  + "\",\"privateRepository\":true}")
          .when()
          .put("/api/projects/" + id)
          .then()
          .statusCode(200)
          .body(
              "name",
              equalTo(updatedName),
              "repositoryUrl",
              equalTo(RepositoryUrls.normalize(updatedUrl)));
      QuarkusTransaction.requiringNew()
          .run(() -> {
            assertEquals(
                RepositoryUrls.normalize(updatedUrl), Project.<Project>findById(id).repositoryUrl);
            assertEquals(originalUrl, Build.<Build>findById(queued).repositoryUrl);
            assertEquals(originalUrl, Build.<Build>findById(running).repositoryUrl);
          });
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Build.deleteById(queued);
            Build.deleteById(running);
            Project.deleteById(id);
          });
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void duplicateUpdateLeavesProjectUnchanged() {
    Long originalId = project("lead-id", false);
    Long duplicateId = project("lead-id", false);
    Project before = QuarkusTransaction.requiringNew().call(() -> Project.findById(originalId));
    String duplicateName =
        QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(duplicateId).name);
    try {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\""
                  + duplicateName
                  + "\",\"repositoryUrl\":\"https://github.com/unique/repository.git\",\"privateRepository\":true}")
          .when()
          .put("/api/projects/" + originalId)
          .then()
          .statusCode(409)
          .body("error", containsString("name"));
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Project unchanged = Project.findById(originalId);
            assertEquals(before.name, unchanged.name);
            assertEquals(before.repositoryUrl, unchanged.repositoryUrl);
            assertFalse(unchanged.privateRepository);
          });
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Project.deleteById(originalId);
            Project.deleteById(duplicateId);
          });
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void normalizesRepositoryUrlAndRejectsVariants() {
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\"url-variants\",\"repositoryUrl\":\"HTTPS://GITHUB.COM/org/url-variants.git/\"}")
        .when()
        .post("/api/projects")
        .then()
        .statusCode(201)
        .body("repositoryUrl", equalTo("https://github.com/org/url-variants"));
    for (String url :
        new String[] {
          "https://github.com/org/url-variants/",
          "https://github.com/org/url-variants.git",
          "HTTPS://github.com/org/url-variants",
          "https://GITHUB.COM/org/url-variants"
        }) {
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\"url-variant-" + url.hashCode() + "\",\"repositoryUrl\":\"" + url + "\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(409)
          .body("error", containsString("repository URL"));
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void rejectsUrlsWithoutRepository() {
    for (String url :
        new String[] {
          "https://example.com/",
          "https://example.com",
          "https://example.com/.git",
          "https://github.com/repo.git?x=1",
          "https://example.com/repo#top"
        }) {
      given()
          .contentType("application/json")
          .body("{\"name\":\"suffix\",\"repositoryUrl\":\"" + url + "\"}")
          .when()
          .post("/api/projects")
          .then()
          .statusCode(400)
          .body("error", containsString("repository's URL"));
    }
  }

  @Test
  @TestSecurity(user = "outsider", roles = "unrelated")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "outsider-id")})
  void unrelatedRoleCannotReadProjects() {
    given().when().get("/api/projects").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void deletingAProjectWaitsBrieflyForThePollerLock() throws Exception {
    Long id = project("lead-id", false);
    java.nio.file.Path dir = mirrors.path(id);
    java.nio.file.Files.createDirectories(dir);
    var lock = mirrors.lock(id);
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    Thread holder =
        new Thread(() -> {
          lock.lock();
          held.countDown();
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lock.unlock();
          }
        });
    holder.start();
    assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));

    given().when().delete("/api/projects/" + id).then().statusCode(204);
    holder.join();
    assertFalse(java.nio.file.Files.exists(dir), "the mirror is removed once the lock is free");
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void deletingAProjectDoesNotHoldTheQueueWhileWaitingForThePoller() throws Exception {
    Long id = project("lead-id", false);
    java.nio.file.Path dir = mirrors.path(id);
    java.nio.file.Files.createDirectories(dir);
    var lock = mirrors.lock(id);
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    // Like the poller: holds the mirror lock, then needs the queue lock to submit a build.
    Thread poller =
        new Thread(() -> {
          lock.lock();
          held.countDown();
          try {
            Thread.sleep(500);
            QuarkusTransaction.requiringNew().run(BuildRunner::lockQueue);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            lock.unlock();
          }
        });
    poller.start();
    assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));

    long start = System.nanoTime();
    given().when().delete("/api/projects/" + id).then().statusCode(204);
    long millis = (System.nanoTime() - start) / 1_000_000;
    poller.join();
    assertTrue(millis < 4000, "delete waited " + millis + " ms on the poller");
    assertFalse(java.nio.file.Files.exists(dir), "the mirror is removed once the lock is free");
  }

  private static Long project(String owner, boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = java.util.UUID.randomUUID().toString();
          p.name = "delete-" + n;
          p.repositoryUrl = "https://example.com/delete-" + n;
          p.ownerSubject = owner;
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

  private static Long build(Long project, String status) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build b = new Build();
          b.projectId = project;
          b.number = Build.nextNumber(project);
          b.requestedBy = "lead-id";
          b.status = Build.Status.valueOf(status);
          b.branch = "main";
          b.persist();
          return b.id;
        });
  }

  private static boolean exists(Long project) {
    return QuarkusTransaction.requiringNew().call(() -> Project.findById(project) != null);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminDeletesProjectAndItsBuilds() {
    Long id = project("lead-id", false);
    Long b = build(id, "PASSED");
    given().when().delete("/api/projects/" + id).then().statusCode(204);
    assertFalse(exists(id));
    assertNull(QuarkusTransaction.requiringNew().call(() -> Build.findById(b)));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotDeleteOthersProjects() {
    Long open = project("other-id", false), hidden = project("other-id", true);
    given().when().delete("/api/projects/" + open).then().statusCode(403);
    given().when().delete("/api/projects/" + hidden).then().statusCode(404);
    assertTrue(exists(open));
    assertTrue(exists(hidden));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void memberCannotDeleteProject() {
    Long id = project("member-id", false);
    given().when().delete("/api/projects/" + id).then().statusCode(403);
    assertTrue(exists(id));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void queuedBuildPreventsProjectDeletion() {
    Long id = project("other-id", true), queued = build(id, "QUEUED");
    try {
      given()
          .delete("/api/projects/" + id)
          .then()
          .statusCode(409)
          .body("error", containsString("queued"));
      assertTrue(exists(id));
    } finally {
      QuarkusTransaction.requiringNew().run(() -> Build.deleteById(queued));
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminDeletesAnyProjectButNotWhileBuilding() {
    Long id = project("other-id", true);
    Long running = build(id, "RUNNING");
    given()
        .when()
        .delete("/api/projects/" + id)
        .then()
        .statusCode(409)
        .body("error", containsString("running"));
    assertTrue(exists(id));
    QuarkusTransaction.requiringNew()
        .run(() -> Build.<Build>findById(running).status = Build.Status.PASSED);
    given().when().delete("/api/projects/" + id).then().statusCode(204);
    assertFalse(exists(id));
    given().when().delete("/api/projects/" + id).then().statusCode(404);
  }
}
