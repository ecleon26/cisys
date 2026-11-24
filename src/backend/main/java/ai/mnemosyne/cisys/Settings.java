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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

/**
 * Settings changed by admins. A singleton row with id 1; the defaults apply until it is first
 * saved.
 */
@Entity
public class Settings extends PanacheEntityBase {
  @Id public Long id = 1L;

  /** 0 turns the age limit off. */
  @Column(nullable = false)
  public int retentionDays = 30;

  /** 0 turns the count limit off. */
  @Column(nullable = false)
  public int retentionMaxBuilds = 50;

  /** Null uses cisys.build.concurrency. */
  public Integer maxConcurrentBuilds;

  /** Null uses cisys.build.queue-limit. */
  public Integer maxQueuedBuilds;

  /** Null uses cisys.build.max-checkout-mib. */
  public Integer maxCheckoutMib;

  /** Null uses cisys.build.max-checkout-files. */
  public Integer maxCheckoutFiles;

  /** Null uses cisys.build.max-memory-mib. */
  public Integer maxBuildMemoryMib;

  /** Null uses cisys.build.max-cpus. */
  public Integer maxBuildCpus;

  /** Null uses cisys.build.max-pids. */
  public Integer maxBuildPids;

  /** Null uses cisys.build.timeout-minutes. */
  public Integer buildTimeoutMinutes;

  /** Null uses cisys.build.artifacts.max-mib. */
  public Integer maxArtifactMib;

  /** Null uses cisys.build.artifacts.max-files. */
  public Integer maxArtifactFiles;

  /** Whether the timer queues builds for new commits. On by default. */
  @Column(nullable = false)
  public boolean pollEnabled = true;

  /** Per commit by default; TIP queues only the tip of a moved branch. */
  public enum Granularity {
    PER_COMMIT,
    TIP
  }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  public Granularity pollGranularity = Granularity.PER_COMMIT;

  /** WORKFLOW watches every branch whose tip has a workflow file; MAIN_ONLY adds main or master. */
  public enum Scope {
    WORKFLOW,
    MAIN_ONLY
  }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  public Scope pollScope = Scope.WORKFLOW;

  /** Null uses cisys.poll.every. */
  public Integer pollIntervalSeconds;

  /** Whether pull request and merge request heads are discovered and built automatically. */
  @Column(nullable = false)
  public boolean pullRequestsEnabled = false;

  /**
   * Whether manual builds, re-runs included, fetch into the project's repository mirror and check
   * out from it, like poll builds, instead of cloning from the repository. Off by default.
   */
  @Column(nullable = false)
  public boolean mirrorManualBuilds = false;

  /** Who may create, edit and delete articles. LEAD includes admins; MEMBER is any CISys role. */
  public enum ArticleEditors {
    ADMIN,
    LEAD,
    MEMBER
  }

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public ArticleEditors articleEditors = ArticleEditors.ADMIN;

  /** The saved settings, or the defaults. */
  public static Settings current() {
    Settings s = findById(1L);
    return s == null ? new Settings() : s;
  }
}
