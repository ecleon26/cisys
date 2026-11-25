/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BadgeResourceTest {
  private String project(boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          String n = UUID.randomUUID().toString();
          Project p = new Project();
          p.name = "Badge-" + n;
          p.repositoryUrl = "https://github.com/octocat/badge-" + n + ".git";
          p.ownerSubject = "owner-id";
          p.privateRepository = priv;
          p.persist();
          return p.name;
        });
  }

  private void build(String projectName, String branch, Build.Status status, Integer pr) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.<Project>find("name = ?1", projectName).firstResult();
          Build b = new Build();
          b.projectId = p.id;
          b.number = Build.nextNumber(p.id);
          b.status = status;
          b.branch = branch;
          if (pr != null) {
            b.trigger = Build.Trigger.PULL_REQUEST;
            b.pullRequest = pr;
          }
          b.persist();
        });
  }

  private String badge(String path) {
    return given()
        .get("/api/badge/" + path)
        .then()
        .statusCode(200)
        .contentType(startsWith("image/svg+xml"))
        .header("Cache-Control", containsString("no-cache"))
        .extract()
        .asString();
  }

  @Test
  void showsTheNewestBuildOfTheBranch() {
    String name = project(false);
    build(name, "main", Build.Status.FAILED, null);
    build(name, "main", Build.Status.PASSED, null);
    build(name, "dev", Build.Status.FAILED, null);

    assertThat(badge(name + "/main.svg"), containsString(">passing<"));
    assertThat(badge(name + "/dev.svg"), containsString(">failing<"));
  }

  @Test
  void matchesTheProjectNameCaseInsensitively() {
    String name = project(false);
    build(name, "main", Build.Status.PASSED, null);
    assertThat(badge(name.toLowerCase() + "/main.svg"), containsString(">passing<"));
  }

  @Test
  void queuedAndRunningBuildsShowRunning() {
    String name = project(false);
    try {
      build(name, "main", Build.Status.RUNNING, null);
      assertThat(badge(name + "/main.svg"), containsString(">running<"));
      build(name, "main", Build.Status.QUEUED, null);
      assertThat(badge(name + "/main.svg"), containsString(">running<"));
    } finally {
      // Active rows take runner slots and would stall build tests that run later.
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Project p = Project.<Project>find("name = ?1", name).firstResult();
            Build.update(
                "status = ?1 where projectId = ?2 and status in ?3",
                Build.Status.CANCELLED,
                p.id,
                List.of(Build.Status.RUNNING, Build.Status.QUEUED));
          });
    }
  }

  @Test
  void acceptsBranchesWithSlashes() {
    String name = project(false);
    build(name, "feature/badges", Build.Status.PASSED, null);
    assertThat(badge(name + "/feature/badges.svg"), containsString(">passing<"));
  }

  @Test
  void ignoresPullRequestBuilds() {
    String name = project(false);
    build(name, "main", Build.Status.PASSED, null);
    build(name, "main", Build.Status.FAILED, 7);
    assertThat(badge(name + "/main.svg"), containsString(">passing<"));
  }

  @Test
  void branchWithoutBuildsShowsNoBuilds() {
    String name = project(false);
    assertThat(badge(name + "/main.svg"), containsString(">no builds<"));
  }

  @Test
  void privateAndMissingProjectsLookTheSame() {
    String priv = project(true);
    build(priv, "main", Build.Status.PASSED, null);
    String privateBadge = badge(priv + "/main.svg");
    String missingBadge = badge("no-such-project-" + UUID.randomUUID() + "/main.svg");

    assertThat(privateBadge, containsString(">not found<"));
    assertThat(privateBadge, not(containsString("passing")));
    assertThat(privateBadge, equalTo(missingBadge));
  }

  @Test
  @TestSecurity(user = "owner", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void privateProjectIsNotFoundEvenForItsOwner() {
    String priv = project(true);
    build(priv, "main", Build.Status.PASSED, null);
    assertThat(badge(priv + "/main.svg"), containsString(">not found<"));
  }
}
