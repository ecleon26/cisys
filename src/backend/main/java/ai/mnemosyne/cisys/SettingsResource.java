/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Admin settings. */
@Path("/api/settings")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {
    static final int MAX_DAYS = 3650, MAX_BUILDS = 10000, MAX_CONCURRENT = 16, MAX_QUEUED = 1000,
            MAX_CHECKOUT_MIB = 102400, MAX_CHECKOUT_FILES = 1000000;

    @Inject SecurityIdentity identity;
    @Inject BuildRunner runner;

    /** 0 turns a limit off. */
    public record Retention(Integer maxAgeDays, Integer maxBuilds) {}

    public record Queue(Integer maxConcurrent, Integer maxQueued) {}

    public record Checkout(Integer maxSizeMib, Integer maxFiles) {}

    @GET @Path("/retention") public Retention retention() {
        requireAdmin();
        Settings s = Settings.current();
        return new Retention(s.retentionDays, s.retentionMaxBuilds);
    }

    @PUT @Path("/retention") @Transactional public Retention updateRetention(Retention input) {
        requireAdmin();
        if (input == null || input.maxAgeDays() == null || input.maxAgeDays() < 0 || input.maxAgeDays() > MAX_DAYS)
            throw new BadRequestException("Days must be between 0 and " + MAX_DAYS);
        if (input.maxBuilds() == null || input.maxBuilds() < 0 || input.maxBuilds() > MAX_BUILDS)
            throw new BadRequestException("Builds must be between 0 and " + MAX_BUILDS);
        Settings s = Settings.findById(1L);
        if (s == null) { s = new Settings(); s.persist(); }
        s.retentionDays = input.maxAgeDays();
        s.retentionMaxBuilds = input.maxBuilds();
        return new Retention(s.retentionDays, s.retentionMaxBuilds);
    }

    @GET @Path("/queue") public Queue queue() {
        requireAdmin();
        return new Queue(runner.concurrency(), runner.queueLimit());
    }

    @PUT @Path("/queue") @Transactional public Queue updateQueue(Queue input) {
        requireAdmin();
        if (input == null || input.maxConcurrent() == null || input.maxConcurrent() < 1 || input.maxConcurrent() > MAX_CONCURRENT)
            throw new BadRequestException("Concurrent builds must be between 1 and " + MAX_CONCURRENT);
        if (input.maxQueued() == null || input.maxQueued() < 1 || input.maxQueued() > MAX_QUEUED)
            throw new BadRequestException("Queued builds must be between 1 and " + MAX_QUEUED);
        Settings s = Settings.findById(1L);
        if (s == null) { s = new Settings(); s.persist(); }
        s.maxConcurrentBuilds = input.maxConcurrent();
        s.maxQueuedBuilds = input.maxQueued();
        return new Queue(s.maxConcurrentBuilds, s.maxQueuedBuilds);
    }

    @GET @Path("/checkout") public Checkout checkout() {
        requireAdmin();
        return new Checkout(runner.maxCheckoutMib(), runner.maxCheckoutFiles());
    }

    @PUT @Path("/checkout") @Transactional public Checkout updateCheckout(Checkout input) {
        requireAdmin();
        if (input == null || input.maxSizeMib() == null || input.maxSizeMib() < 1 || input.maxSizeMib() > MAX_CHECKOUT_MIB)
            throw new BadRequestException("Checkout size must be between 1 and " + MAX_CHECKOUT_MIB + " MiB");
        if (input.maxFiles() == null || input.maxFiles() < 1 || input.maxFiles() > MAX_CHECKOUT_FILES)
            throw new BadRequestException("Checkout files must be between 1 and " + MAX_CHECKOUT_FILES);
        Settings s = Settings.findById(1L);
        if (s == null) { s = new Settings(); s.persist(); }
        s.maxCheckoutMib = input.maxSizeMib();
        s.maxCheckoutFiles = input.maxFiles();
        return new Checkout(s.maxCheckoutMib, s.maxCheckoutFiles);
    }

    private void requireAdmin() {
        if (!identity.hasRole("admin")) throw new ForbiddenException();
    }
}
