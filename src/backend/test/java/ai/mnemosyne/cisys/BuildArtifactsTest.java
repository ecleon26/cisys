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
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest
class BuildArtifactsTest {
  @Inject BuildRunner runner;
  @Inject BuildArtifacts artifacts;
  @Inject EntityManager em;

  @TempDir Path dir;

  static final Workflow WORKFLOW =
      Workflow.parse(
          """
          name: main
          container: alpine:3.23
          jobs:
            - job: Test
              steps:
                - name: Test
                  run: make test
          artifacts:
            - name: Reports
              path: target/reports
            - name: Jars
              path: target/*.jar
          """);

  record Seeded(String name, long projectId, long buildId) {}

  private Seeded seed(boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          String n = UUID.randomUUID().toString();
          Project p = new Project();
          p.name = "Artifacts-" + n;
          p.repositoryUrl = "https://github.com/octocat/artifacts-" + n + ".git";
          p.ownerSubject = "owner-id";
          p.privateRepository = priv;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = p.ownerSubject;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = java.time.Instant.now();
          m.persist();
          Build b = new Build();
          b.projectId = p.id;
          b.number = Build.nextNumber(p.id);
          b.status = Build.Status.FAILED;
          b.branch = "main";
          b.requestedBy = "owner-id";
          b.persist();
          return new Seeded(p.name, p.id, b.id);
        });
  }

  private List<String> collect(Seeded s) throws Exception {
    Files.write(
        dir.resolve(Workflow.ARTIFACT_ARCHIVE),
        new ArtifactArchiveTest.Tar()
            .file("./target/reports/TEST-a.xml", "<testsuite/>")
            .file("./target/app.jar", "jar")
            .file("./unlisted.txt", "x")
            .entry("./target/link.jar", '2', new byte[0])
            .bytes());
    List<String> log = new ArrayList<>();
    runner.collectArtifacts(s.buildId(), WORKFLOW, dir, log::add);
    return log;
  }

  private String base(Seeded s) {
    return "/api/project-pages/" + s.name().toLowerCase() + "/builds/" + s.buildId() + "/artifacts";
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void storesTheListedFilesAndServesThemAsDownloads() throws Exception {
    Seeded s = seed(true);

    assertEquals(
        List.of(
            "==> Artifact skipped: unlisted.txt (not listed)",
            "==> Artifact skipped: target/link.jar (symbolic link)",
            "==> Artifacts: 2 files, 0.0 MiB"),
        collect(s));

    given()
        .get(base(s))
        .then()
        .statusCode(200)
        .body("truncated", equalTo(false))
        .body("files", hasSize(2))
        .body("files[0].name", equalTo("Jars"))
        .body("files[0].path", equalTo("target/app.jar"))
        .body("files[0].size", equalTo(3))
        .body("files[0].sha256", equalTo(sha256("jar")))
        .body("files[1].name", equalTo("Reports"))
        .body("files[1].path", equalTo("target/reports/TEST-a.xml"));

    long id = given().get(base(s)).then().extract().jsonPath().getLong("files[0].id");
    given()
        .get(base(s) + "/" + id)
        .then()
        .statusCode(200)
        .header("Content-Disposition", containsString("attachment; filename=\"app.jar\""))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox")
        .body(equalTo("jar"));

    byte[] zip = given().get(base(s) + ".zip").then().statusCode(200).extract().asByteArray();
    List<String> entries = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        entries.add(e.getName() + "=" + new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    assertEquals(List.of("target/app.jar=jar", "target/reports/TEST-a.xml=<testsuite/>"), entries);
  }

  @Test
  void anonymousVisitorsCannotDownloadEvenFromPublicProjects() throws Exception {
    Seeded s = seed(false);
    collect(s);
    given().get(base(s)).then().statusCode(401);
    given().get(base(s) + ".zip").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void otherUsersCannotSeeAPrivateProjectsArtifacts() throws Exception {
    Seeded s = seed(true);
    collect(s);
    given().get(base(s)).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void limitsMarkTheBuildTruncated() throws Exception {
    Seeded s = seed(true);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings settings = Settings.findById(1L);
          if (settings == null) {
            settings = new Settings();
            settings.persist();
          }
          settings.maxArtifactFiles = 1;
        });
    try {
      List<String> log = collect(s);
      assertTrue(log.contains("==> Artifacts truncated at 1 files"), log.toString());
      given().get(base(s)).then().body("truncated", equalTo(true)).body("files", hasSize(1));
    } finally {
      QuarkusTransaction.requiringNew()
          .run(() -> Settings.<Settings>findById(1L).maxArtifactFiles = null);
    }
  }

  @Test
  void aBuildWithoutAnArchiveStoresNothing() {
    Seeded s = seed(false);
    List<String> log = new ArrayList<>();
    runner.collectArtifacts(s.buildId(), WORKFLOW, dir, log::add);
    assertTrue(log.isEmpty(), log.toString());
    assertTrue(artifacts.list(s.buildId()).isEmpty());
  }

  @Test
  void aLinkedArchiveIsNotRead() throws Exception {
    Seeded s = seed(false);
    Path outside = Files.write(dir.resolveSibling(UUID.randomUUID() + ".tar"), new byte[0]);
    try {
      Files.createSymbolicLink(dir.resolve(Workflow.ARTIFACT_ARCHIVE), outside);
      List<String> log = new ArrayList<>();
      runner.collectArtifacts(s.buildId(), WORKFLOW, dir, log::add);
      assertEquals(List.of("==> Artifacts skipped: artifacts.tar is not a regular file"), log);
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void deletingTheBuildUnlinksTheContent() throws Exception {
    Seeded s = seed(false);
    collect(s);
    long before = largeObjects();
    QuarkusTransaction.requiringNew().run(() -> Project.deleteById(s.projectId()));
    assertTrue(artifacts.list(s.buildId()).isEmpty());
    assertEquals(before - 2, largeObjects());
  }

  private long largeObjects() {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            ((Number)
                    em.createNativeQuery("select count(*) from pg_largeobject_metadata")
                        .getSingleResult())
                .longValue());
  }

  private static String sha256(String s) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
  }
}
