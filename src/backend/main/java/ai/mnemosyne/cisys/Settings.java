/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Settings changed by admins. A singleton row with id 1; the defaults apply until it is first saved. */
@Entity
public class Settings extends PanacheEntityBase {
    @Id public Long id = 1L;
    /** 0 turns the age limit off. */
    @Column(nullable = false) public int retentionDays = 30;
    /** 0 turns the count limit off. */
    @Column(nullable = false) public int retentionMaxBuilds = 50;
    /** Null uses cisys.build.concurrency. */
    public Integer maxConcurrentBuilds;
    /** Null uses cisys.build.queue-limit. */
    public Integer maxQueuedBuilds;
    /** Null uses cisys.build.max-checkout-mib. */
    public Integer maxCheckoutMib;
    /** Null uses cisys.build.max-checkout-files. */
    public Integer maxCheckoutFiles;

    public static Settings current() {
        Settings s = findById(1L);
        return s == null ? new Settings() : s;
    }
}
