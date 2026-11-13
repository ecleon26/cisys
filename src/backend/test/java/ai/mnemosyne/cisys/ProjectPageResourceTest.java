/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ProjectPageResourceTest {
    @Inject BuildLogs logs;
    @Inject ProjectPageResource pages;

    record Seeded(String name, long buildId) {}

    /** Creates a project with one finished build and returns its name. */
    private String project(boolean priv) {
        return seed(priv).name();
    }

    private Seeded seed(boolean priv) {
        return seed(priv, "hello");
    }

    private Seeded seed(boolean priv, String logText) {
        Seeded seeded = QuarkusTransaction.requiringNew().call(() -> {
            String n = UUID.randomUUID().toString();
            Project p = new Project();
            p.name = "Page-" + n;
            p.repositoryUrl = "https://github.com/octocat/page-" + n + ".git";
            p.ownerSubject = "owner-id";
            p.privateRepository = priv;
            p.persist();
            Build b = new Build();
            b.projectId = p.id;
            b.status=Build.Status.PASSED;
            b.branch = "main";
            b.requestedBy = "owner-id";
            b.credentialRef = "CISYS_GIT_SECRET";
            b.persist();
            return new Seeded(p.name, b.id);
        });
        byte[] log = logText.getBytes(StandardCharsets.UTF_8);
        try (var w = logs.open(seeded.buildId())) { w.append(log, 0, log.length); }
        return seeded;
    }

    private void addBuild(String projectName, String branch) {
        QuarkusTransaction.requiringNew().run(() -> {
            Project p = Project.<Project>find("name = ?1", projectName).firstResult();
            Build b = new Build();
            b.projectId = p.id;
            b.status=Build.Status.PASSED;
            b.branch = branch;
            b.persist();
        });
    }

    @Test
    void anonymousSeesPublicProjectBuilds() {
        String name = project(false);
        given().get("/api/project-pages/" + name.toLowerCase()).then().statusCode(200)
                .body("project.name", equalTo(name), "project.privateRepository", equalTo(false),
                        "builds", hasSize(1), "builds[0].status", equalTo("PASSED"), "builds[0].logTruncated", equalTo(false),
                        "builds[0].pinned", equalTo(false), "builds[0].canPin", equalTo(false),
                        "builds[0]", not(hasKey("output")),
                        "builds[0]", not(hasKey("requestedBy")), "builds[0]", not(hasKey("credentialRef")));
    }

    @Test
    void anonymousCanFilterPublicProjectBuildsByBranch() {
        String name = project(false);
        addBuild(name, "dev");
        given().queryParam("branch", "dev").get("/api/project-pages/" + name.toLowerCase()).then()
                .statusCode(200).body("builds", hasSize(1), "builds[0].branch", equalTo("dev"));
        given().queryParam("branch", "main").get("/api/project-pages/" + name.toLowerCase()).then()
                .statusCode(200).body("builds", hasSize(1), "builds[0].branch", equalTo("main"));
    }

    @Test
    void filteringByUnknownBranchReturnsNoBuilds() {
        String name = project(false);
        given().queryParam("branch", "missing").get("/api/project-pages/" + name.toLowerCase()).then()
                .statusCode(200).body("builds", hasSize(0));
    }

    @Test
    void privateProjectFilterIsStillNotFound() {
        String name = project(true);
        given().queryParam("branch", "main").get("/api/project-pages/" + name.toLowerCase()).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerCanFilterPrivateProjectByBranch() {
        String name = project(true);
        addBuild(name, "dev");
        given().queryParam("branch", "dev").get("/api/project-pages/" + name.toLowerCase()).then()
                .statusCode(200).body("builds", hasSize(1), "builds[0].branch", equalTo("dev"));
        given().queryParam("branch", "main").get("/api/project-pages/" + name.toLowerCase()).then()
                .statusCode(200).body("builds", hasSize(1), "builds[0].branch", equalTo("main"));
    }

    @Test
    @TestSecurity(user = "member", roles = "member")
    @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void otherMemberCannotFilterPrivateProjectByBranch() {
        String name = project(true);
        given().queryParam("branch", "main").get("/api/project-pages/" + name.toLowerCase()).then().statusCode(404);
    }

    @Test
    void anonymousReadsPublicBuildPage() {
        Seeded s = seed(false);
        given().get("/api/project-pages/" + s.name().toLowerCase() + "/builds/" + s.buildId()).then().statusCode(200)
                .body("status", equalTo("PASSED"), "branch", equalTo("main"), "logTruncated", equalTo(false),
                        "$", not(hasKey("requestedBy")), "$", not(hasKey("credentialRef")));
    }

    @Test
    void anonymousCannotReadPrivateBuildPage() {
        Seeded s = seed(true);
        given().get("/api/project-pages/" + s.name().toLowerCase() + "/builds/" + s.buildId()).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerReadsPrivateBuildPage() {
        Seeded s = seed(true);
        given().get("/api/project-pages/" + s.name().toLowerCase() + "/builds/" + s.buildId()).then().statusCode(200)
                .body("status", equalTo("PASSED"), "canPin", equalTo(true), "projectId", notNullValue());
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void otherLeadCannotPinPublicBuild() {
        Seeded s = seed(false);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId()).then().statusCode(200)
                .body("canPin", equalTo(false));
    }

    @Test
    void buildPageOfAnotherProjectIsNotFound() {
        Seeded page = seed(false), other = seed(false);
        given().get("/api/project-pages/" + page.name().toLowerCase() + "/builds/" + other.buildId()).then().statusCode(404);
    }

    @Test
    void unknownBuildPageIsNotFound() {
        given().get("/api/project-pages/" + project(false).toLowerCase() + "/builds/999999").then().statusCode(404);
    }

    @Test
    void anonymousReadsPublicBuildLog() {
        Seeded s = seed(false);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(200)
                .contentType(startsWith("text/plain")).body(equalTo("hello"));
    }

    @Test
    void anonymousLogReadIsCappedAndCanContinue() {
        Seeded s = seed(false, "0123456789");
        ProjectPageResource resource = io.quarkus.arc.ClientProxy.unwrap(pages);
        long original = resource.publicLogMaxBytes;
        resource.publicLogMaxBytes = 5;
        try {
            String url = "/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log";
            given().get(url).then().statusCode(200)
                    .header("X-Log-Size", "10")
                    .header("X-Log-Offset", "0").header("X-Log-Length", "5")
                    .body(equalTo("01234"));
            given().queryParam("offset", 5).get(url).then().statusCode(200)
                    .header("X-Log-Offset", "5").header("X-Log-Length", "5")
                    .body(equalTo("56789"));
            // A tail larger than the cap still returns the end of the log, not a chunk from the middle.
            given().queryParam("tail", 50).get(url).then().statusCode(200)
                    .header("X-Log-Offset", "5").header("X-Log-Length", "5")
                    .body(equalTo("56789"));
        } finally {
            resource.publicLogMaxBytes = original;
        }
    }

    @Test
    void anonymousLogSupportsOffsetLimitAndTail() {
        Seeded s = seed(false, "0123456789");
        String url = "/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log";
        given().queryParam("offset", 2).queryParam("limit", 3).get(url).then().statusCode(200)
                .header("X-Log-Offset", "2").header("X-Log-Length", "3").body(equalTo("234"));
        given().queryParam("tail", 4).get(url).then().statusCode(200)
                .header("X-Log-Offset", "6").header("X-Log-Length", "4").body(equalTo("6789"));
    }

    @Test
    void negativeLogRangeIsRejected() {
        Seeded s = seed(false);
        String url = "/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log";
        given().queryParam("offset", -1).get(url).then().statusCode(400);
        given().queryParam("limit", -1).get(url).then().statusCode(400);
        given().queryParam("tail", -1).get(url).then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void signedInLogReadIsNotCapped() {
        Seeded s = seed(true, "0123456789");
        ProjectPageResource resource = io.quarkus.arc.ClientProxy.unwrap(pages);
        long original = resource.publicLogMaxBytes;
        resource.publicLogMaxBytes = 5;
        try {
            given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(200)
                    .header("X-Log-Size", "10").body(equalTo("0123456789"));
        } finally {
            resource.publicLogMaxBytes = original;
        }
    }

    @Test
    void anonymousCannotReadPrivateBuildLog() {
        Seeded s = seed(true);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerReadsPrivateBuildLog() {
        Seeded s = seed(true);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(200)
                .body(equalTo("hello"));
    }

    @Test
    void logOfAnotherProjectsBuildIsNotFound() {
        Seeded page = seed(false), other = seed(false);
        given().get("/api/project-pages/" + page.name() + "/builds/" + other.buildId() + "/log").then().statusCode(404);
    }

    @Test
    void anonymousCannotSeePrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(404);
    }

    @Test
    void unknownProjectIsNotFound() {
        given().get("/api/project-pages/does-not-exist-" + UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "member", roles = "member")
    @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void otherMemberCannotSeePrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerSeesPrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(200).body("builds", hasSize(1));
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminSeesPrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(200).body("builds[0].canPin", equalTo(true));
    }

    private void setStatus(long buildId, Build.Status status) {
        QuarkusTransaction.requiringNew().run(() -> Build.update("status = ?1 where id = ?2", status, buildId));
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerMayCancelOnlyActiveBuilds() {
        Seeded s = seed(false);
        String path = "/api/project-pages/" + s.name() + "/builds/" + s.buildId();
        given().get(path).then().statusCode(200).body("canCancel", equalTo(false));
        setStatus(s.buildId(), Build.Status.QUEUED);
        try {
            given().get(path).then().statusCode(200).body("canCancel", equalTo(true));
            given().get("/api/project-pages/" + s.name()).then().statusCode(200).body("builds[0].canCancel", equalTo(true));
        } finally {
            setStatus(s.buildId(), Build.Status.CANCELLED);
        }
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void otherLeadCannotCancel() {
        Seeded s = seed(false);
        setStatus(s.buildId(), Build.Status.RUNNING);
        try {
            given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId()).then().statusCode(200)
                    .body("canCancel", equalTo(false));
        } finally {
            setStatus(s.buildId(), Build.Status.FAILED);
        }
    }
}
