/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The page of a single project, addressed by its name (for example /cisys/ in the UI).
 * Public projects are readable by anyone, including visitors who are not signed in.
 * Private projects are only readable by their owner and admins; everyone else gets a 404,
 * so the existence of a private project is not revealed.
 */
@Path("/api/project-pages/{name}")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@PublicRateLimited
public class ProjectPageResource {
    @Inject ProjectAccess access;
    @Inject BuildLogs logs;
    @Inject SecurityIdentity identity;
    /** Anonymous callers may read at most this many bytes of one log in one response. */
    @ConfigProperty(name = "cisys.public.log-max-bytes", defaultValue = "2097152") long publicLogMaxBytes;

    public record ProjectInfo(Long id, String name, String repositoryUrl, boolean privateRepository) {}
    /** Leaves out who requested the build and any credential reference, as the page may be anonymous. */
    public record PageBuild(Long id, Long projectId, Build.Status status, String revision, String branch, String image,
                            Instant startedAt, Instant finishedAt, String imageDigest, Integer exitCode,
                            boolean logTruncated, boolean pinned, boolean canPin, boolean canCancel,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String workflow) {}
    public record ProjectPage(ProjectInfo project, List<PageBuild> builds) {}

    @GET
    public ProjectPage page(@PathParam("name") String name, @QueryParam("branch") String branch) {
        Project p = access.visible(name);
        String branchFilter = branch == null ? "" : branch.trim();
        List<Build> rows = branchFilter.isEmpty()
                ? Build.<Build>find("projectId = ?1 order by id desc", p.id).page(0, 50).list()
                : Build.<Build>find("projectId = ?1 and branch = ?2 order by id desc", p.id, branchFilter).page(0, 50).list();
        // Whoever may see a private project may also pin its builds.
        boolean canPin = access.canSeePrivate(p);
        List<PageBuild> builds = rows.stream().map(b -> pageBuild(b, canPin, access.canCancel(p, b), false)).toList();
        return new ProjectPage(new ProjectInfo(p.id, p.name, p.repositoryUrl, p.privateRepository), builds);
    }

    /** One build of a project, for the dedicated build page. Visibility matches the project page and the log. */
    @GET
    @Path("/builds/{buildId}")
    public PageBuild build(@PathParam("name") String name, @PathParam("buildId") Long buildId) {
        Project p = access.visible(name);
        Build b = Build.findById(buildId);
        if (b == null || !p.id.equals(b.projectId)) throw new NotFoundException();
        return pageBuild(b, access.canSeePrivate(p), access.canCancel(p, b), true);
    }

    /** The workflow file is sent for a single build only, as it can be up to 64 KiB. */
    private static PageBuild pageBuild(Build b, boolean canPin, boolean canCancel, boolean withWorkflow) {
        return new PageBuild(b.id, b.projectId, b.status, b.revision, b.branch, b.image, b.startedAt,
                b.finishedAt, b.imageDigest, b.exitCode, b.logTruncated, b.pinned, canPin, canCancel,
                withWorkflow ? b.workflow : null);
    }

    /**
     * Streams a public build log. Anonymous callers are capped at {@code cisys.public.log-max-bytes}
     * per response; use {@code offset}, {@code limit} or {@code tail} to page through a larger log.
     * The headers expose the total size and the returned range so a client can continue.
     */
    @GET
    @Path("/builds/{buildId}/log")
    @Produces("text/plain; charset=UTF-8")
    public Response log(@PathParam("name") String name, @PathParam("buildId") Long buildId,
                        @QueryParam("offset") Long offset, @QueryParam("limit") Long limit,
                        @QueryParam("tail") Long tail) {
        Project p = access.visible(name);
        Build b = Build.findById(buildId);
        if (b == null || !p.id.equals(b.projectId)) throw new NotFoundException();
        long size = logs.size(buildId);
        // The size and range are a snapshot; a running build may append while this response streams.
        // Signed-in visitors, including the owner on a private project, still get the whole log.
        long maxBytes = identity.isAnonymous() ? publicLogMaxBytes : -1;
        BuildLogs.Range range;
        try {
            range = BuildLogs.range(size, offset, limit, tail, maxBytes);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        return LogResponses.stream(logs, buildId, size, range);
    }
}
