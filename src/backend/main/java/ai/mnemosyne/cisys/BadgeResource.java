/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.util.Locale;

/**
 * Build status badges for READMEs. Private and missing projects get the same "not found" badge
 * whoever asks, so a badge never reveals that a private project exists.
 */
@Path("/api/badge")
@PermitAll
@PublicRateLimited
public class BadgeResource {
  static final String SVG = "image/svg+xml";

  /** One badge: the right-hand text and its color. */
  record Badge(String message, String color) {}

  static final Badge PASSING = new Badge("passing", "#4c1");
  static final Badge FAILING = new Badge("failing", "#e05d44");
  static final Badge RUNNING = new Badge("running", "#dfb317");
  static final Badge CANCELLED = new Badge("cancelled", "#9f9f9f");
  static final Badge NO_BUILDS = new Badge("no builds", "#9f9f9f");
  static final Badge NOT_FOUND = new Badge("not found", "#9f9f9f");

  /** The badge of the newest build on a branch. The branch may contain slashes (feature/x). */
  @GET
  @Path("/{project}/{branch: .+\\.svg}")
  @Produces(SVG)
  public Response badge(@PathParam("project") String project, @PathParam("branch") String file) {
    String branch = file.substring(0, file.length() - ".svg".length());
    return Response.ok(svg(status(project, branch)), SVG)
        // README proxies such as GitHub's camo cache images unless told not to.
        .header("Cache-Control", "no-cache, max-age=0")
        .build();
  }

  static Badge status(String projectName, String branch) {
    Project p =
        Project.<Project>find("lower(name) = ?1", projectName.trim().toLowerCase(Locale.ROOT))
            .firstResult();
    if (p == null || p.privateRepository) {
      return NOT_FOUND;
    }
    Build b =
        Build.<Build>find(
                "projectId = ?1 and branch = ?2 and pullRequest is null order by number desc",
                p.id,
                branch)
            .firstResult();
    if (b == null) {
      return NO_BUILDS;
    }
    return switch (b.status) {
      case PASSED -> PASSING;
      case FAILED -> FAILING;
      case QUEUED, RUNNING -> RUNNING;
      case CANCELLED -> CANCELLED;
    };
  }

  private static final String TEMPLATE =
      """
      <svg xmlns="http://www.w3.org/2000/svg" width="%1$d" height="20" role="img" \
      aria-label="%2$s: %3$s">\
      <title>%2$s: %3$s</title>\
      <linearGradient id="s" x2="0" y2="100%%">\
      <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>\
      <stop offset="1" stop-opacity=".1"/></linearGradient>\
      <clipPath id="r"><rect width="%1$d" height="20" rx="3" fill="#fff"/></clipPath>\
      <g clip-path="url(#r)">\
      <rect width="%4$d" height="20" fill="#555"/>\
      <rect x="%4$d" width="%5$d" height="20" fill="%6$s"/>\
      <rect width="%1$d" height="20" fill="url(#s)"/></g>\
      <g fill="#fff" text-anchor="middle" \
      font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">\
      <text x="%7$d" y="14">%2$s</text>\
      <text x="%8$d" y="14">%3$s</text></g></svg>
      """;

  /** A flat two-part badge. Only fixed strings are drawn, so nothing needs escaping. */
  static String svg(Badge badge) {
    String label = "build";
    int left = textWidth(label) + 10;
    int right = textWidth(badge.message()) + 10;
    int width = left + right;
    return TEMPLATE.formatted(
        width, label, badge.message(), left, right, badge.color(), left / 2, left + right / 2);
  }

  // Close enough to Verdana at 11px for short lowercase labels.
  private static int textWidth(String text) {
    return (int) Math.ceil(text.length() * 6.5);
  }
}
