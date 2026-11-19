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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BuildCommentResourceTest {
  private record Seeded(Long project, String name, Long build) {}

  private static Seeded seed(boolean priv, String member, ProjectMember.TeamRole role) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = "comments-" + n;
          p.repositoryUrl = "https://github.com/octocat/Hello-World-" + n + ".git";
          p.ownerSubject = "owner-id";
          p.privateRepository = priv;
          p.persist();
          if (member != null) {
            ProjectMember m = new ProjectMember();
            m.projectId = p.id;
            m.subject = member;
            m.role = role;
            m.createdAt = Instant.now();
            m.persist();
          }
          Build b = new Build();
          b.projectId = p.id;
          b.number = Build.nextNumber(p.id);
          b.status = Build.Status.PASSED;
          b.branch = "main";
          b.persist();
          return new Seeded(p.id, p.name, b.id);
        });
  }

  private static Long comment(Long build, String author, String body) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          BuildComment c = new BuildComment();
          c.buildId = build;
          c.authorSubject = author;
          c.body = body;
          c.createdAt = Instant.now();
          c.persist();
          return c.id;
        });
  }

  private static BuildComment stored(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> BuildComment.findById(id));
  }

  private static String path(Seeded s) {
    return "/api/projects/" + s.project() + "/builds/" + s.build() + "/comments";
  }

  private static String page(Seeded s) {
    return "/api/project-pages/" + s.name() + "/builds/" + s.build();
  }

  private static String body(String text) {
    return "{\"body\":\"" + text + "\"}";
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberComments() {
    TestUserDirectory.add("member-id", "maria", "member");
    Seeded s = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .contentType("application/json")
        .body(body("  Looks flaky  "))
        .post(path(s))
        .then()
        .statusCode(201)
        .body("author", equalTo("maria"))
        .body("body", equalTo("Looks flaky"))
        .body("mine", equalTo(true))
        .body("updatedAt", nullValue());
    given()
        .get(page(s) + "/comments")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].author", equalTo("maria"));
    given().get(page(s)).then().statusCode(200).body("canComment", equalTo(true));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void nonMemberCannotComment() {
    Seeded s = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    given().contentType("application/json").body(body("hi")).post(path(s)).then().statusCode(403);
    given().get(page(s)).then().statusCode(200).body("canComment", equalTo(false));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminComments() {
    Seeded s = seed(true, null, null);
    given().contentType("application/json").body(body("hi")).post(path(s)).then().statusCode(201);
    given().get(page(s)).then().statusCode(200).body("canComment", equalTo(true));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void blankOrTooLongCommentIs400() {
    Seeded s = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .contentType("application/json")
        .body(body("   "))
        .post(path(s))
        .then()
        .statusCode(400)
        .body("error", notNullValue());
    given()
        .contentType("application/json")
        .body(body("x".repeat(BuildComment.MAX_BODY + 1)))
        .post(path(s))
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void authorEditsOwnComment() {
    Seeded s = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    Long c = comment(s.build(), "member-id", "first");
    given()
        .contentType("application/json")
        .body(body("second"))
        .put(path(s) + "/" + c)
        .then()
        .statusCode(200)
        .body("body", equalTo("second"))
        .body("updatedAt", notNullValue());
    assertEquals("second", stored(c).body);
    assertNotNull(stored(c).updatedAt);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void othersCannotEdit() {
    Seeded s = seed(false, "lead-id", ProjectMember.TeamRole.LEAD);
    Long c = comment(s.build(), "member-id", "first");
    given()
        .contentType("application/json")
        .body(body("changed"))
        .put(path(s) + "/" + c)
        .then()
        .statusCode(403);
    assertEquals("first", stored(c).body);
    given().get(page(s) + "/comments").then().statusCode(200).body("[0].mine", equalTo(false));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void commentOfAnotherBuildIs404() {
    Seeded s = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    Seeded other = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    Long c = comment(other.build(), "member-id", "first");
    given()
        .contentType("application/json")
        .body(body("changed"))
        .put(path(s) + "/" + c)
        .then()
        .statusCode(404);
    String wrongProject = "/api/projects/" + s.project() + "/builds/" + other.build() + "/comments";
    given()
        .contentType("application/json")
        .body(body("hi"))
        .post(wrongProject)
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void privateProjectIs404() {
    Seeded s = seed(true, "member-id", ProjectMember.TeamRole.MEMBER);
    Long c = comment(s.build(), "member-id", "first");
    given().get(page(s) + "/comments").then().statusCode(404);
    given().contentType("application/json").body(body("hi")).post(path(s)).then().statusCode(404);
    given()
        .contentType("application/json")
        .body(body("changed"))
        .put(path(s) + "/" + c)
        .then()
        .statusCode(404);
  }

  @Test
  void anonymousReadsPublicComments() {
    Seeded s = seed(false, "member-id", ProjectMember.TeamRole.MEMBER);
    comment(s.build(), "member-id", "first");
    given()
        .get(page(s) + "/comments")
        .then()
        .statusCode(200)
        .body("[0].body", equalTo("first"))
        .body("[0].mine", equalTo(false));
    given().get(page(s)).then().statusCode(200).body("canComment", equalTo(false));
    given().contentType("application/json").body(body("hi")).post(path(s)).then().statusCode(401);
  }

  @Test
  void deletingTheBuildDeletesItsComments() {
    Seeded s = seed(false, null, null);
    Long c = comment(s.build(), "member-id", "first");
    QuarkusTransaction.requiringNew()
        .run(() ->
            Build.getEntityManager()
                .createNativeQuery("delete from build where id = ?1")
                .setParameter(1, s.build())
                .executeUpdate());
    assertNull(stored(c));
  }
}
