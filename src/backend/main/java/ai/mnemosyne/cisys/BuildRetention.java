/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes, per project, the finished unpinned builds that are older than the maximum age or beyond the newest
 * maximum count. Queued, running and pinned builds are never deleted. Logs go with their build (lo_manage trigger).
 */
@ApplicationScoped
public class BuildRetention {
    static final int BATCH = 500;

    private static final String DELETE = """
            delete from build where status in ('PASSED', 'FAILED', 'CANCELLED') and not pinned and id in (
                select id from (
                    select id, coalesce(finishedat, startedat) as at, row_number() over (
                        partition by projectid order by coalesce(finishedat, startedat) desc nulls last, id desc) as n
                    from build where status in ('PASSED', 'FAILED', 'CANCELLED') and not pinned) eligible
                where n > :maxBuilds or at < :cutoff
                limit :batch)
            """;

    /** The limits are set by admins under Settings, see {@link Settings}. */
    @Scheduled(every = "{cisys.retention.every}", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduled() {
        try {
            Settings s = QuarkusTransaction.requiringNew().call(Settings::current);
            int deleted = purge(Instant.now(), s.retentionDays, s.retentionMaxBuilds);
            if (deleted > 0) Log.infof("Retention deleted %d builds", deleted);
        } catch (RuntimeException e) {
            Log.error("Build retention failed", e);
        }
    }

    /** A limit of 0 or less is turned off. Returns the number of builds deleted. */
    int purge(Instant now, int maxAgeDays, int maxBuilds) {
        Instant cutoff = maxAgeDays > 0 ? now.minus(Duration.ofDays(maxAgeDays)) : Instant.EPOCH;
        int max = maxBuilds > 0 ? maxBuilds : Integer.MAX_VALUE;
        int total = 0, deleted;
        do {
            deleted = QuarkusTransaction.requiringNew().call(() -> Build.getEntityManager().createNativeQuery(DELETE)
                    .setParameter("maxBuilds", max).setParameter("cutoff", cutoff).setParameter("batch", BATCH)
                    .executeUpdate());
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
