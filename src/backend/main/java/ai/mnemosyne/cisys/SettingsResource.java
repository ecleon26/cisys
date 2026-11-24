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
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** Admin settings. */
@Path("/api/settings")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {
  static final int MAX_DAYS = 3650,
      MAX_BUILDS = 10000,
      MAX_CONCURRENT = 16,
      MAX_QUEUED = 1000,
      MAX_TIMEOUT_MINUTES = 1440,
      MAX_CHECKOUT_MIB = 102400,
      MAX_CHECKOUT_FILES = 1000000,
      MAX_ARTIFACT_MIB = 10240,
      MAX_ARTIFACT_FILES = 100000,
      MIN_MEMORY_MIB = 64,
      MAX_MEMORY_MIB = 65536,
      MAX_CPUS = 16,
      MIN_PIDS = 16,
      MAX_PIDS = 32768;

  @Inject SecurityIdentity identity;
  @Inject BuildRunner runner;
  @Inject CommitPoller polls;
  @Inject PullRequestPoller pullRequests;
  @Inject AuditService audit;

  /** 0 turns a limit off. */
  public record Retention(Integer maxAgeDays, Integer maxBuilds) {}

  /** The build queue and how long one build may take. */
  public record Builds(Integer maxConcurrent, Integer maxQueued, Integer timeoutMinutes) {}

  /** Limits on a build's repository checkout. */
  public record Checkout(Integer maxSizeMib, Integer maxFiles) {}

  /** Limits on what one build stores as artifacts. */
  public record Artifacts(Integer maxSizeMib, Integer maxFiles) {}

  /** Limits applied to every build container. */
  public record Performance(Integer maxMemoryMib, Integer maxCpus, Integer maxPids) {}

  /**
   * Automatic builds: on/off, per-commit or tip-only, all workflow branches or main/master only.
   */
  public record Polling(
      Boolean enabled,
      Settings.Granularity granularity,
      Settings.Scope scope,
      Integer intervalSeconds,
      Boolean mirrorManualBuilds) {}

  /** Whether pull request and merge request builds are enabled. */
  public record PullRequests(Boolean enabled) {}

  /** Settings that are not about builds. */
  public record General(Settings.ArticleEditors articleEditors) {}

  /** The general settings. */
  @GET
  @Path("/general")
  public General general() {
    requireAdmin();
    return new General(Settings.current().articleEditors);
  }

  /** Saves the general settings. */
  @PUT
  @Path("/general")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_GENERAL_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update general settings")
  public General updateGeneral(General input) {
    requireAdmin();
    if (input == null || input.articleEditors() == null) {
      throw new BadRequestException("Article editors are required");
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    Settings.ArticleEditors previousEditors = s.articleEditors;
    s.articleEditors = input.articleEditors();
    audit.success(
        AuditAction.SETTINGS_GENERAL_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "general",
        (Long) null,
        (String) null,
        "Updated the general settings",
        AuditDetails.of(
            "articleEditorsBefore",
            previousEditors.name(),
            "articleEditorsAfter",
            s.articleEditors.name()));
    return new General(s.articleEditors);
  }

  /** The retention limits. */
  @GET
  @Path("/retention")
  public Retention retention() {
    requireAdmin();
    Settings s = Settings.current();
    return new Retention(s.retentionDays, s.retentionMaxBuilds);
  }

  /** Saves the retention limits. */
  @PUT
  @Path("/retention")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_RETENTION_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update retention settings")
  public Retention updateRetention(Retention input) {
    requireAdmin();
    if (input == null
        || input.maxAgeDays() == null
        || input.maxAgeDays() < 0
        || input.maxAgeDays() > MAX_DAYS) {
      throw new BadRequestException("Days must be between 0 and " + MAX_DAYS);
    }
    if (input.maxBuilds() == null || input.maxBuilds() < 0 || input.maxBuilds() > MAX_BUILDS) {
      throw new BadRequestException("Builds must be between 0 and " + MAX_BUILDS);
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    int previousDays = s.retentionDays;
    int previousBuilds = s.retentionMaxBuilds;
    s.retentionDays = input.maxAgeDays();
    s.retentionMaxBuilds = input.maxBuilds();
    audit.success(
        AuditAction.SETTINGS_RETENTION_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "retention",
        (Long) null,
        (String) null,
        "Updated the retention settings",
        AuditDetails.of(
            "maxAgeDaysBefore",
            previousDays,
            "maxAgeDaysAfter",
            s.retentionDays,
            "maxBuildsBefore",
            previousBuilds,
            "maxBuildsAfter",
            s.retentionMaxBuilds));
    return new Retention(s.retentionDays, s.retentionMaxBuilds);
  }

  /** The concurrency, queue and timeout limits for builds. */
  @GET
  @Path("/builds")
  public Builds builds() {
    requireAdmin();
    return new Builds(runner.concurrency(), runner.queueLimit(), runner.timeoutMinutes());
  }

  /** Saves the concurrency, queue and timeout limits. */
  @PUT
  @Path("/builds")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_BUILDS_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update build settings")
  public Builds updateBuilds(Builds input) {
    requireAdmin();
    if (input == null
        || input.maxConcurrent() == null
        || input.maxConcurrent() < 1
        || input.maxConcurrent() > MAX_CONCURRENT) {
      throw new BadRequestException("Concurrent builds must be between 1 and " + MAX_CONCURRENT);
    }
    if (input.maxQueued() == null || input.maxQueued() < 1 || input.maxQueued() > MAX_QUEUED) {
      throw new BadRequestException("Queued builds must be between 1 and " + MAX_QUEUED);
    }
    if (input.timeoutMinutes() == null
        || input.timeoutMinutes() < 1
        || input.timeoutMinutes() > MAX_TIMEOUT_MINUTES) {
      throw new BadRequestException(
          "Timeout must be between 1 and " + MAX_TIMEOUT_MINUTES + " minutes");
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    Integer previousConcurrent = s.maxConcurrentBuilds;
    Integer previousQueued = s.maxQueuedBuilds;
    Integer previousTimeout = s.buildTimeoutMinutes;
    s.maxConcurrentBuilds = input.maxConcurrent();
    s.maxQueuedBuilds = input.maxQueued();
    s.buildTimeoutMinutes = input.timeoutMinutes();
    audit.success(
        AuditAction.SETTINGS_BUILDS_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "builds",
        (Long) null,
        (String) null,
        "Updated the build settings",
        AuditDetails.of(
            "maxConcurrentBefore",
            previousConcurrent,
            "maxConcurrentAfter",
            s.maxConcurrentBuilds,
            "maxQueuedBefore",
            previousQueued,
            "maxQueuedAfter",
            s.maxQueuedBuilds,
            "timeoutMinutesBefore",
            previousTimeout,
            "timeoutMinutesAfter",
            s.buildTimeoutMinutes));
    return new Builds(s.maxConcurrentBuilds, s.maxQueuedBuilds, s.buildTimeoutMinutes);
  }

  /** The checkout limits. */
  @GET
  @Path("/checkout")
  public Checkout checkout() {
    requireAdmin();
    return new Checkout(runner.maxCheckoutMib(), runner.maxCheckoutFiles());
  }

  /** Saves the checkout limits. */
  @PUT
  @Path("/checkout")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_CHECKOUT_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update checkout settings")
  public Checkout updateCheckout(Checkout input) {
    requireAdmin();
    if (input == null
        || input.maxSizeMib() == null
        || input.maxSizeMib() < 1
        || input.maxSizeMib() > MAX_CHECKOUT_MIB) {
      throw new BadRequestException(
          "Checkout size must be between 1 and " + MAX_CHECKOUT_MIB + " MiB");
    }
    if (input.maxFiles() == null || input.maxFiles() < 1 || input.maxFiles() > MAX_CHECKOUT_FILES) {
      throw new BadRequestException("Checkout files must be between 1 and " + MAX_CHECKOUT_FILES);
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    Integer previousSize = s.maxCheckoutMib;
    Integer previousFiles = s.maxCheckoutFiles;
    s.maxCheckoutMib = input.maxSizeMib();
    s.maxCheckoutFiles = input.maxFiles();
    audit.success(
        AuditAction.SETTINGS_CHECKOUT_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "checkout",
        (Long) null,
        (String) null,
        "Updated the checkout settings",
        AuditDetails.of(
            "maxSizeMibBefore",
            previousSize,
            "maxSizeMibAfter",
            s.maxCheckoutMib,
            "maxFilesBefore",
            previousFiles,
            "maxFilesAfter",
            s.maxCheckoutFiles));
    return new Checkout(s.maxCheckoutMib, s.maxCheckoutFiles);
  }

  /** The artifact limits. */
  @GET
  @Path("/artifacts")
  public Artifacts artifacts() {
    requireAdmin();
    return new Artifacts(runner.maxArtifactMib(), runner.maxArtifactFiles());
  }

  /** Saves the artifact limits. */
  @PUT
  @Path("/artifacts")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_ARTIFACTS_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update artifact settings")
  public Artifacts updateArtifacts(Artifacts input) {
    requireAdmin();
    if (input == null
        || input.maxSizeMib() == null
        || input.maxSizeMib() < 1
        || input.maxSizeMib() > MAX_ARTIFACT_MIB) {
      throw new BadRequestException(
          "Artifact size must be between 1 and " + MAX_ARTIFACT_MIB + " MiB");
    }
    if (input.maxFiles() == null || input.maxFiles() < 1 || input.maxFiles() > MAX_ARTIFACT_FILES) {
      throw new BadRequestException("Artifact files must be between 1 and " + MAX_ARTIFACT_FILES);
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    Integer previousSize = s.maxArtifactMib;
    Integer previousFiles = s.maxArtifactFiles;
    s.maxArtifactMib = input.maxSizeMib();
    s.maxArtifactFiles = input.maxFiles();
    audit.success(
        AuditAction.SETTINGS_ARTIFACTS_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "artifacts",
        (Long) null,
        (String) null,
        "Updated the artifact settings",
        AuditDetails.of(
            "maxSizeMibBefore",
            previousSize,
            "maxSizeMibAfter",
            s.maxArtifactMib,
            "maxFilesBefore",
            previousFiles,
            "maxFilesAfter",
            s.maxArtifactFiles));
    return new Artifacts(s.maxArtifactMib, s.maxArtifactFiles);
  }

  /** The container limits. */
  @GET
  @Path("/performance")
  public Performance performance() {
    requireAdmin();
    return new Performance(runner.maxMemoryMib(), runner.maxCpus(), runner.maxPids());
  }

  /** Saves the container limits. */
  @PUT
  @Path("/performance")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_PERFORMANCE_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update performance settings")
  public Performance updatePerformance(Performance input) {
    requireAdmin();
    if (input == null
        || input.maxMemoryMib() == null
        || input.maxMemoryMib() < MIN_MEMORY_MIB
        || input.maxMemoryMib() > MAX_MEMORY_MIB) {
      throw new BadRequestException(
          "Memory must be between " + MIN_MEMORY_MIB + " and " + MAX_MEMORY_MIB + " MiB");
    }
    if (input.maxCpus() == null || input.maxCpus() < 1 || input.maxCpus() > MAX_CPUS) {
      throw new BadRequestException("CPUs must be between 1 and " + MAX_CPUS);
    }
    if (input.maxPids() == null || input.maxPids() < MIN_PIDS || input.maxPids() > MAX_PIDS) {
      throw new BadRequestException("Processes must be between " + MIN_PIDS + " and " + MAX_PIDS);
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    Integer previousMemory = s.maxBuildMemoryMib;
    Integer previousCpus = s.maxBuildCpus;
    Integer previousPids = s.maxBuildPids;
    s.maxBuildMemoryMib = input.maxMemoryMib();
    s.maxBuildCpus = input.maxCpus();
    s.maxBuildPids = input.maxPids();
    audit.success(
        AuditAction.SETTINGS_PERFORMANCE_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "performance",
        (Long) null,
        (String) null,
        "Updated the performance settings",
        AuditDetails.of(
            "maxMemoryMibBefore",
            previousMemory,
            "maxMemoryMibAfter",
            s.maxBuildMemoryMib,
            "maxCpusBefore",
            previousCpus,
            "maxCpusAfter",
            s.maxBuildCpus,
            "maxPidsBefore",
            previousPids,
            "maxPidsAfter",
            s.maxBuildPids));
    return new Performance(s.maxBuildMemoryMib, s.maxBuildCpus, s.maxBuildPids);
  }

  private static final int MIN_POLL_SECONDS = CommitPoller.MIN_INTERVAL_SECONDS,
      MAX_POLL_SECONDS = CommitPoller.MAX_INTERVAL_SECONDS;

  /** The automatic-build settings. */
  @GET
  @Path("/polling")
  public Polling polling() {
    requireAdmin();
    Settings s = Settings.current();
    return new Polling(
        s.pollEnabled, s.pollGranularity, s.pollScope, effectiveInterval(s), s.mirrorManualBuilds);
  }

  /** Saves the automatic-build settings. */
  @PUT
  @Path("/polling")
  @Transactional
  @Audited(
      action = AuditAction.SETTINGS_POLLING_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update polling settings")
  public Polling updatePolling(Polling input) {
    requireAdmin();
    if (input == null || input.enabled() == null) {
      throw new BadRequestException("Automatic builds must be on or off");
    }
    if (input.granularity() == null) {
      throw new BadRequestException("Granularity is required");
    }
    if (input.scope() == null) {
      throw new BadRequestException("Scope is required");
    }
    if (input.intervalSeconds() != null
        && (input.intervalSeconds() < MIN_POLL_SECONDS
            || input.intervalSeconds() > MAX_POLL_SECONDS)) {
      throw new BadRequestException(
          "Poll interval must be between "
              + MIN_POLL_SECONDS
              + " and "
              + MAX_POLL_SECONDS
              + " seconds");
    }
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    boolean previousEnabled = s.pollEnabled;
    Settings.Granularity previousGranularity = s.pollGranularity;
    Settings.Scope previousScope = s.pollScope;
    Integer previousInterval = s.pollIntervalSeconds;
    boolean previousMirror = s.mirrorManualBuilds;
    s.pollEnabled = input.enabled();
    s.pollGranularity = input.granularity();
    s.pollScope = input.scope();
    s.pollIntervalSeconds = input.intervalSeconds();
    // Left out by an older client: keep the stored value.
    if (input.mirrorManualBuilds() != null) {
      s.mirrorManualBuilds = input.mirrorManualBuilds();
    }
    audit.success(
        AuditAction.SETTINGS_POLLING_UPDATE,
        AuditTargetType.SETTINGS,
        1L,
        "polling",
        (Long) null,
        (String) null,
        "Updated the automatic-build settings",
        AuditDetails.of(
            "enabledBefore",
            previousEnabled,
            "enabledAfter",
            s.pollEnabled,
            "granularityBefore",
            previousGranularity.name(),
            "granularityAfter",
            s.pollGranularity.name(),
            "scopeBefore",
            previousScope.name(),
            "scopeAfter",
            s.pollScope.name(),
            "intervalSecondsBefore",
            previousInterval,
            "intervalSecondsAfter",
            s.pollIntervalSeconds,
            "mirrorManualBuildsBefore",
            previousMirror,
            "mirrorManualBuildsAfter",
            s.mirrorManualBuilds));
    return new Polling(
        s.pollEnabled, s.pollGranularity, s.pollScope, effectiveInterval(s), s.mirrorManualBuilds);
  }

  /** The pull request build setting. */
  @GET
  @Path("/pull-requests")
  public PullRequests pullRequests() {
    requireAdmin();
    return new PullRequests(Settings.current().pullRequestsEnabled);
  }

  /**
   * Saves the pull request build setting. Turning it on or off clears every project's cursors and
   * seed point in the same transaction, so a later enable seeds the heads that exist then instead
   * of building what happened while the feature was off. Builds and branch cursors are kept.
   */
  @PUT
  @Path("/pull-requests")
  @Audited(
      action = AuditAction.SETTINGS_PULL_REQUESTS_UPDATE,
      target = AuditTargetType.SETTINGS,
      summary = "Update pull request build settings")
  public PullRequests updatePullRequests(PullRequests input) {
    requireAdmin();
    if (input == null || input.enabled() == null) {
      throw new BadRequestException("Pull request builds must be on or off");
    }
    // Invalidate first: a discovery pass that read heads before this change must discard its result
    // even if it reaches the database after the new setting commits.
    pullRequests.invalidate();
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(() -> {
          // Discovery locks this row before it writes cursors. Holding it here makes the
          // setting change and the cursor reset atomic against an in-flight discovery pass,
          // without waiting for any project lock or provider operation.
          Settings s = Settings.findById(1L, LockModeType.PESSIMISTIC_WRITE);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          boolean changed = s.pullRequestsEnabled != input.enabled();
          boolean previous = s.pullRequestsEnabled;
          s.pullRequestsEnabled = input.enabled();
          if (changed) {
            Project.update("pullRequestBaseline = null");
            ProjectPullRequest.deleteAll();
          }
          audit.success(
              AuditAction.SETTINGS_PULL_REQUESTS_UPDATE,
              AuditTargetType.SETTINGS,
              1L,
              "pull-requests",
              (Long) null,
              (String) null,
              "Updated the pull request build setting",
              AuditDetails.of(
                  "enabledBefore", previous,
                  "enabledAfter", s.pullRequestsEnabled,
                  "changed", changed));
        });
    return new PullRequests(input.enabled());
  }

  /** The stored interval, or the configured default so the form always has a number. */
  private int effectiveInterval(Settings s) {
    return s.pollIntervalSeconds != null ? s.pollIntervalSeconds : polls.defaultIntervalSeconds();
  }

  private static WebApplicationException badRequest(String message) {
    return new WebApplicationException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) {
      throw new ForbiddenException();
    }
  }
}
