/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Authorization rules shared by project pages, their builds, branches, team, and settings. Private
 * projects are only readable by their members and admins; everyone else gets a 404, so the
 * existence of a private project is not revealed. Visible but not allowed actions answer 403.
 * Admins bypass project team rules entirely.
 */
@ApplicationScoped
public class ProjectAccess {
  @Inject SecurityIdentity identity;

  /**
   * The project named {@code name}, ignoring case; 404 when it is missing or the caller cannot see
   * it.
   */
  public Project visible(String name) {
    Project p =
        Project.<Project>find("lower(name) = ?1", name.trim().toLowerCase(Locale.ROOT))
            .firstResult();
    if (p == null || !canView(p)) {
      throw new NotFoundException();
    }
    return p;
  }

  /** The project with {@code id}; 404 when missing or the caller cannot see it. */
  public Project visible(Long id) {
    requireCisysRole();
    Project p = Project.findById(id);
    if (p == null || (p.privateRepository && !canSeePrivate(p))) {
      throw new NotFoundException();
    }
    return p;
  }

  /** The projects the caller may see: everything for admins, public and assigned otherwise. */
  public List<Project> listVisible() {
    requireCisysRole();
    if (isAdmin()) {
      return Project.listAll();
    }
    return Project.list(
        "privateRepository = false or id in (select m.projectId from ProjectMember m where m.subject = ?1)",
        subject());
  }

  public boolean isAdmin() {
    return identity.hasRole("admin");
  }

  /** Whether the caller holds any membership row in the project, of any role. */
  public boolean isMember(Project p) {
    return memberRole(p) != null;
  }

  private TeamRole memberRole(Project p) {
    String sub = subjectOrNull();
    if (sub == null || p == null) {
      return null;
    }
    return ProjectMember.roleOf(p.id, sub);
  }

  /** True when the caller holds the given project role or a higher one, or is a global admin. */
  public boolean hasRole(Project p, TeamRole required) {
    if (isAdmin()) {
      return true;
    }
    TeamRole have = memberRole(p);
    return have != null && level(have) >= level(required);
  }

  private static int level(TeamRole role) {
    return role == TeamRole.LEAD ? 2 : 1;
  }

  /** True when the caller should see the {@link TeamRole}-or-higher view of the project. */
  private Project elevated(Long id, TeamRole required) {
    Project p = visible(id);
    if (isAdmin()) {
      return p;
    }
    if (!hasRole(p, required)) {
      throw new ForbiddenException();
    }
    return p;
  }

  /** The projects with any membership role are visible; private projects need it. */
  boolean canView(Project p) {
    if (!p.privateRepository || canSeePrivate(p)) {
      return true;
    }
    return hasCisysRole()
        && identity.getPrincipal() instanceof JsonWebToken jwt
        && ProjectMember.isMember(p.id, jwt.getSubject());
  }

  /**
   * The project when the caller is at least a project member; 404 when missing or private to the
   * caller, 403 otherwise.
   */
  public Project buildable(Long id) {
    return elevated(id, TeamRole.MEMBER);
  }

  /**
   * The project when it is visible; 404 when it is missing or private to the caller, 403 when the
   * caller is not a project LEAD.
   */
  public Project editable(Long id) {
    return elevated(id, TeamRole.LEAD);
  }

  /**
   * The project when the caller is a global admin; 403 otherwise, 404 when missing or private to
   * the caller.
   */
  public Project deletable(Long id) {
    Project p = visible(id);
    if (!isAdmin()) {
      throw new ForbiddenException();
    }
    return p;
  }

  /**
   * The project when the caller manages its team; 404 when missing or private to the caller, 403
   * when the caller is not a project LEAD.
   */
  public Project manageable(Long id) {
    return elevated(id, TeamRole.LEAD);
  }

  /** True when the caller holds the given project role or a higher one, or is a global admin. */
  public boolean canManage(Project p, TeamRole required) {
    return isAdmin() || hasRole(p, required);
  }

  /** Membership lists are visible to members of the project and admins. */
  public boolean canViewMembers(Project p) {
    return isAdmin() || isMember(p);
  }

  /** Pinning a build is an edit of the project, so it requires LEAD or global admin. */
  public boolean canPin(Project p) {
    return isAdmin() || hasRole(p, TeamRole.LEAD);
  }

  /** Commenting on a build follows {@link #buildable}: project members and admins. */
  public boolean canComment(Project p) {
    return hasCisysRole() && hasRole(p, TeamRole.MEMBER);
  }

  /** Whether the build is queued or running and {@link #mayCancel} allows it. */
  boolean canCancel(Project p, Build b) {
    return (b.status == Build.Status.QUEUED || b.status == Build.Status.RUNNING) && mayCancel(p, b);
  }

  /**
   * Admins, project LEADs, or the build's requester while they hold a global lead role.
   * Cancellation is not a project-level privilege for other people's builds; we keep that rule.
   */
  boolean mayCancel(Project p, Build b) {
    if (isAdmin()) {
      return true;
    }
    if (hasRole(p, TeamRole.LEAD)) {
      return true;
    }
    String sub = subjectOrNull();
    return sub != null && sub.equals(b.requestedBy) && identity.hasRole("lead");
  }

  /** Private projects are readable by members and admins; everyone else gets 404. */
  boolean canSeePrivate(Project p) {
    String sub = subjectOrNull();
    if (sub == null) {
      return false;
    }
    if (!hasCisysRole()) {
      return false;
    }
    return isAdmin() || memberRole(p) != null;
  }

  private boolean hasCisysRole() {
    return !identity.isAnonymous()
        && (identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"));
  }

  String subjectOrNull() {
    if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      return null;
    }
    return jwt.getSubject();
  }

  String subject() {
    String sub = subjectOrNull();
    if (sub == null || sub.isBlank()) {
      throw new NotAuthorizedException("Bearer");
    }
    return sub;
  }

  void requireCisysRole() {
    if (!hasCisysRole()) {
      throw new ForbiddenException();
    }
  }
}
