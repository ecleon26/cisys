/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.util.Locale;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Visibility rules shared by project pages, their builds, logs and branches.
 * Private projects are only readable by their owner and admins; everyone else gets a 404,
 * so the existence of a private project is not revealed.
 */
@ApplicationScoped
public class ProjectAccess {
    @Inject SecurityIdentity identity;

    public Project visible(String name) {
        Project p = Project.<Project>find("lower(name) = ?1", name.trim().toLowerCase(Locale.ROOT)).firstResult();
        if (p == null || (p.privateRepository && !canSeePrivate(p))) throw new NotFoundException();
        return p;
    }

    /** Same rule as the authenticated project and build endpoints: a Cisys role, and admin or owner. */
    boolean canSeePrivate(Project p) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt) || jwt.getSubject() == null) return false;
        if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) return false;
        return identity.hasRole("admin") || jwt.getSubject().equals(p.ownerSubject);
    }

    /** Whether the build is queued or running and {@link #mayCancel} allows it. */
    boolean canCancel(Project p, Build b) {
        return (b.status == Build.Status.QUEUED || b.status == Build.Status.RUNNING) && mayCancel(p, b);
    }

    /** Admins, the project's owner and whoever requested the build. */
    boolean mayCancel(Project p, Build b) {
        if (canSeePrivate(p)) return true;
        if (!(identity.getPrincipal() instanceof JsonWebToken jwt) || jwt.getSubject() == null) return false;
        return (identity.hasRole("lead") || identity.hasRole("admin")) && jwt.getSubject().equals(b.requestedBy);
    }
}
