/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Queues builds and runs each one in a container, within the configured limits. */
@ApplicationScoped
public class BuildRunner {
  @Inject BuildLogs logs;
  @Inject RepositoryUrlValidator repositoryUrls;
  @Inject RepositoryMirror mirrors;

  @Inject ProjectCache caches;

  @ConfigProperty(name = "cisys.build.log-max-bytes", defaultValue = "1073741824")
  long logMaxBytes;

  String runtime;

  /** Runs a container runtime command. Replaced in tests so cleanup never reaches the host. */
  interface RuntimeCommand {
    Result run(List<String> args, int timeout, int limit) throws Exception;
  }

  RuntimeCommand commands = BuildRunner::command;

  /** Uses podman when it is installed, otherwise docker. */
  @PostConstruct
  void resolveRuntime() {
    runtime = onPath("podman") ? "podman" : onPath("docker") ? "docker" : "podman";
    Log.infof("Build runner uses the %s container runtime", runtime);
  }

  String runtime() {
    return runtime;
  }

  private static boolean onPath(String program) {
    for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
      if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, program))) {
        return true;
      }
    }
    return false;
  }

  /** The legacy builder enforces the resource flags; BuildKit accepts but ignores them. */
  static final Map<String, String> LEGACY_BUILDER = Map.of("DOCKER_BUILDKIT", "0");

  /** Resource flags understood by both the Docker legacy builder and Podman's build. */
  static List<String> resourceLimits(int memoryMib, int cpus, int pids) {
    return List.of(
        "--memory=" + memoryMib + "m",
        "--cpu-period=100000",
        "--cpu-quota=" + (cpus * 100_000L),
        "--ulimit=nproc=" + pids + ":" + pids);
  }

  /** One image build command; the caller appends the build context last. */
  List<String> buildCommand(Path file, String tag, int[] limits) {
    List<String> build =
        new ArrayList<>(List.of(runtime, "build", "--no-cache", "-f", file.toString(), "-t", tag));
    if (runtime.equals("podman")) {
      build.addAll(List.of("--pull=never", "--layers=false", "--security-opt=no-new-privileges"));
    } else {
      // The legacy builder keeps the container of a failed step unless told otherwise.
      build.add("--force-rm");
    }
    build.addAll(resourceLimits(limits[2], limits[3], limits[4]));
    return build;
  }

  /** The seconds left on a build budget, so the Docker preparation and steps share it. */
  static int remainingSeconds(long deadlineNanos) {
    long seconds = TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime());
    return (int) Math.max(1, seconds);
  }

  @ConfigProperty(name = "cisys.build.concurrency", defaultValue = "1")
  int concurrency;

  @ConfigProperty(name = "cisys.build.queue-limit", defaultValue = "100")
  int queueLimit;

  /** An admin's setting replaces the configured limit. */
  int concurrency() {
    Integer v = Settings.current().maxConcurrentBuilds;
    return v != null ? v : concurrency;
  }

  int queueLimit() {
    Integer v = Settings.current().maxQueuedBuilds;
    return v != null ? v : queueLimit;
  }

  @ConfigProperty(name = "cisys.build.max-checkout-mib", defaultValue = "1024")
  int maxCheckoutMib = 1024;

  @ConfigProperty(name = "cisys.build.max-checkout-files", defaultValue = "100000")
  int maxCheckoutFiles = 100000;

  int maxCheckoutMib() {
    Integer v = Settings.current().maxCheckoutMib;
    return v != null ? v : maxCheckoutMib;
  }

  int maxCheckoutFiles() {
    Integer v = Settings.current().maxCheckoutFiles;
    return v != null ? v : maxCheckoutFiles;
  }

  @ConfigProperty(name = "cisys.build.artifacts.max-mib", defaultValue = "100")
  int maxArtifactMib = 100;

  @ConfigProperty(name = "cisys.build.artifacts.max-files", defaultValue = "1000")
  int maxArtifactFiles = 1000;

  int maxArtifactMib() {
    Integer v = Settings.current().maxArtifactMib;
    return v != null ? v : maxArtifactMib;
  }

  int maxArtifactFiles() {
    Integer v = Settings.current().maxArtifactFiles;
    return v != null ? v : maxArtifactFiles;
  }

  @Inject BuildArtifacts artifacts;

  /**
   * Stores what the collect script wrote. The steps could also have written there, so the archive
   * must be a regular file, and is opened without following links; the build's processes have all
   * ended by now. A failure is only logged, as artifacts never change the build's result.
   */
  void collectArtifacts(long id, Workflow workflow, Path dir, Consumer<String> log) {
    Path archive = dir.resolve(Workflow.ARTIFACT_ARCHIVE);
    try {
      java.nio.file.attribute.BasicFileAttributes a;
      try {
        a =
            Files.readAttributes(
                archive,
                java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
      } catch (java.nio.file.NoSuchFileException e) {
        return;
      }
      if (!a.isRegularFile()) {
        log.accept(
            "==> Artifacts skipped: " + Workflow.ARTIFACT_ARCHIVE + " is not a regular file");
        return;
      }
      int[] limits =
          QuarkusTransaction.requiringNew()
              .call(() -> new int[] {maxArtifactMib(), maxArtifactFiles()});
      ArtifactArchive.Result r;
      try (InputStream in =
          new java.io.BufferedInputStream(
              Files.newInputStream(archive, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
        r =
            artifacts.store(
                id, workflow.artifacts(), in, limits[0] * 1024L * 1024L, limits[1], log);
      }
      log.accept(
          "==> Artifacts: "
              + r.files()
              + (r.files() == 1 ? " file, " : " files, ")
              + String.format(Locale.ROOT, "%.1f", r.bytes() / 1048576.0)
              + " MiB");
    } catch (Exception e) {
      Log.warnf(e, "Artifacts of build %d could not be stored", id);
      log.accept("==> Could not store the artifacts (" + e.getClass().getSimpleName() + ")");
    }
  }

  /** The host directory mounted at {@link Workflow#ARTIFACT_DIR}, writable by the steps' user. */
  static Path artifactDirectory(Path dir) throws IOException {
    Path artifacts = Files.createDirectory(dir.resolve("artifacts"));
    try {
      // The build's user is mapped to another host user; the parent directory keeps other host
      // users out.
      Files.setPosixFilePermissions(
          artifacts, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
    } catch (UnsupportedOperationException ignored) {
    }
    return artifacts;
  }

  @ConfigProperty(name = "cisys.build.max-memory-mib", defaultValue = "1024")
  int maxMemoryMib = 1024;

  @ConfigProperty(name = "cisys.build.max-cpus", defaultValue = "1")
  int maxCpus = 1;

  @ConfigProperty(name = "cisys.build.max-pids", defaultValue = "512")
  int maxPids = 512;

  int maxMemoryMib() {
    Integer v = Settings.current().maxBuildMemoryMib;
    return v != null ? v : maxMemoryMib;
  }

  int maxCpus() {
    Integer v = Settings.current().maxBuildCpus;
    return v != null ? v : maxCpus;
  }

  int maxPids() {
    Integer v = Settings.current().maxBuildPids;
    return v != null ? v : maxPids;
  }

  @ConfigProperty(name = "cisys.build.timeout-minutes", defaultValue = "10")
  int timeoutMinutes = 10;

  /** Minutes a build's image build may take. */
  int timeoutMinutes() {
    Integer v = Settings.current().buildTimeoutMinutes;
    return v != null ? v : timeoutMinutes;
  }

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<Long, Completion> completions = new ConcurrentHashMap<>();

  record Completion(
      Long id,
      Build.Status status,
      String revision,
      Integer exitCode,
      String imageDigest,
      boolean logTruncated,
      Instant finishedAt) {}

  /** Keep the result until its transaction commits; retry persistence, never execution. */
  void complete(Completion result) {
    completions.put(result.id(), result);
    retryCompletions();
  }

  private synchronized void retryCompletions() {
    // After shutdown there is no transaction manager; the next start marks the build failed.
    if (!running()) {
      if (!completions.isEmpty()) {
        Log.warnf(
            "Not saving the result of builds %s: the server has stopped", completions.keySet());
      }
      return;
    }
    for (Completion result : completions.values()) {
      try {
        QuarkusTransaction.requiringNew().run(() -> persistCompletion(result));
        completions.remove(result.id(), result);
      } catch (RuntimeException e) {
        Log.errorf(e, "Could not save result for build %d; will retry", result.id());
      }
    }
  }

  /**
   * Called inside a fresh transaction, also when retrying a failed commit. A cancellation requested
   * meanwhile wins.
   */
  void persistCompletion(Completion r) {
    Build.update(
        "status = case when cancelRequested = true then ?9 else ?1 end, revision = ?2, exitCode = ?3, imageDigest = ?4, logTruncated = ?5, finishedAt = ?6 where id = ?7 and status = ?8",
        r.status(),
        r.revision(),
        r.exitCode(),
        r.imageDigest(),
        r.logTruncated(),
        r.finishedAt(),
        r.id(),
        Build.Status.RUNNING,
        Build.Status.CANCELLED);
  }

  /** A build this runner executes, with the container command currently running for it. */
  static final class Running {
    volatile boolean cancelled;
    volatile Process process;
  }

  final Map<Long, Running> running = new ConcurrentHashMap<>();

  /**
   * A queued build is cancelled at once; a running one is stopped by the runner executing it. False
   * when it already finished.
   */
  boolean cancel(Long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          lockQueue();
          if (Build.update(
                  "status = ?1, finishedAt = ?2, cancelRequested = true where id = ?3 and status = ?4",
                  Build.Status.CANCELLED,
                  Instant.now(),
                  id,
                  Build.Status.QUEUED)
              == 1) {
            return true;
          }
          return Build.update(
                  "cancelRequested = true where id = ?1 and status = ?2", id, Build.Status.RUNNING)
              == 1;
        });
  }

  /** Stops this runner's builds whose cancellation was requested through the API. */
  void stopCancelled() {
    if (running.isEmpty()) {
      return;
    }
    List<Long> ids =
        QuarkusTransaction.requiringNew()
            .call(() ->
                Build.getEntityManager()
                    .createQuery(
                        "select b.id from Build b where b.status = ?1 and b.cancelRequested = true and b.id in ?2",
                        Long.class)
                    .setParameter(1, Build.Status.RUNNING)
                    .setParameter(2, List.copyOf(running.keySet()))
                    .getResultList());
    for (Long id : ids) {
      Running r = running.get(id);
      if (r != null && !r.cancelled) {
        kill(r);
      }
    }
  }

  /**
   * The flag is set before the process is read, and {@link #started} does the reverse, so one of
   * them always stops it.
   */
  void kill(Running r) {
    r.cancelled = true;
    Process p = r.process;
    if (p != null) {
      terminate(p);
    }
  }

  void started(Running r, Process p) {
    r.process = p;
    if (r.cancelled) {
      terminate(p);
    }
  }

  /** SIGTERM first so the runtime removes its build container, then forcibly. */
  private void terminate(Process p) {
    p.destroy();
    Runnable force =
        () -> {
          try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
              p.descendants().forEach(ProcessHandle::destroyForcibly);
              p.destroyForcibly();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    try {
      executor.submit(force);
    } catch (RejectedExecutionException e) {
      p.descendants().forEach(ProcessHandle::destroyForcibly);
      p.destroyForcibly();
    }
  }

  private volatile boolean ready;
  private volatile boolean stopping;

  /** Serialize admission and claims in the database, including the capacity check. */
  static void lockQueue() {
    Build.getEntityManager()
        .createNativeQuery("select 1 from pg_advisory_xact_lock(1128878931)")
        .getSingleResult();
  }

  void recover(@Observes StartupEvent event) {
    if (concurrency < 1 || queueLimit < 1) {
      throw new IllegalArgumentException("Build concurrency and queue limit must be positive");
    }
    if (maxCheckoutMib < 1 || maxCheckoutFiles < 1) {
      throw new IllegalArgumentException("Checkout size and file limits must be positive");
    }
    if (maxMemoryMib < 1 || maxCpus < 1 || maxPids < 1) {
      throw new IllegalArgumentException("Build memory, CPU and process limits must be positive");
    }
    if (timeoutMinutes < 1) {
      throw new IllegalArgumentException("Build timeout must be positive");
    }
    cleanupOrphans();
    List<Build> interrupted =
        QuarkusTransaction.requiringNew()
            .call(() -> Build.<Build>list("status", Build.Status.RUNNING));
    for (Build build : interrupted) {
      Long id = build.id;
      boolean cancelled = build.cancelRequested;
      Throwable failure =
          logs.appendLine(id, cancelled ? "Build cancelled" : "Server restarted during build");
      if (failure != null) {
        Log.errorf(failure, "Could not append to the log of build %d", id);
      }
      QuarkusTransaction.requiringNew()
          .run(() -> {
            Build b = Build.findById(id);
            b.status = cancelled ? Build.Status.CANCELLED : Build.Status.FAILED;
            b.finishedAt = Instant.now();
            if (failure != null) {
              b.logTruncated = true;
            }
          });
    }
    ready = true;
  }

  /** Containers a previous runner process left behind when it did not get to remove them. */
  List<String> staleContainers() {
    if (runtime == null) {
      return List.of();
    }
    try {
      // Podman lists the builder's working containers only with --external.
      List<String> ps = new ArrayList<>(List.of(runtime, "ps", "-a"));
      if (runtime.equals("podman")) {
        ps.add("--external");
      }
      ps.addAll(List.of("--format", "{{.Names}}"));
      Result r = commands.run(ps, 15, 1_000_000);
      if (r.timeout() || r.code() != 0) {
        throw new IllegalStateException("Container listing failed");
      }
      return r.output()
          .lines()
          .map(String::strip)
          .filter(n -> n.startsWith("cisys-build-"))
          .toList();
    } catch (Exception e) {
      throw new IllegalStateException("Could not list containers", e);
    }
  }

  /** Removes one stale build container. */
  void removeContainer(String name) {
    try {
      Result r = commands.run(List.of(runtime, "rm", "-f", name), 15, 4096);
      if (r.timeout() || r.code() != 0) {
        throw new IllegalStateException("Container command failed");
      }
    } catch (Exception e) {
      throw new IllegalStateException("Could not remove container " + name, e);
    }
  }

  /** The tag of a build's base image, which Podman's working containers are named after. */
  static String baseTag(long id) {
    return "localhost/cisys-build-" + id + "-base";
  }

  private void tag(String imageId, String tag) throws Exception {
    Result r = commands.run(List.of(runtime, "tag", imageId, tag), 30, 4096);
    if (r.timeout() || r.code() != 0) {
      throw new IllegalStateException("Could not tag the base image: " + r.output().strip());
    }
  }

  /**
   * The systemd slice of a build's {@code RUN} processes. Podman starts each in a scope of its own,
   * outside the build command's process tree, so a stopped build command leaves them running; the
   * slice stops them all.
   */
  static String slice(long id) {
    return "cisys-build-" + id + ".slice";
  }

  /** The parent slice of every build's slice. */
  static final String BUILDS_SLICE = "cisys-build.slice";

  private volatile Boolean systemdCgroups;

  /** Whether Podman manages cgroups with systemd, so builds can run in a slice of their own. */
  boolean systemdCgroups() {
    Boolean v = systemdCgroups;
    if (v == null) {
      try {
        Result r =
            commands.run(List.of(runtime, "info", "--format", "{{.Host.CgroupManager}}"), 30, 4096);
        v = !r.timeout() && r.code() == 0 && r.output().strip().equals("systemd");
      } catch (Exception e) {
        v = false;
      }
      if (!v) {
        Log.info("Podman does not use systemd cgroups; build processes are stopped with Podman");
      }
      systemdCgroups = v;
    }
    return v;
  }

  /** Stops a systemd slice, and so every process in it, of this user's manager. */
  private void stopSlice(String slice) throws Exception {
    List<String> stop = new ArrayList<>(List.of("systemctl"));
    if (!"root".equals(System.getProperty("user.name"))) {
      stop.add("--user");
    }
    stop.addAll(List.of("stop", slice));
    Result r = commands.run(stop, 30, 4096);
    if (r.timeout() || r.code() != 0) {
      throw new IllegalStateException("systemctl failed: " + r.output().strip());
    }
  }

  /**
   * Cleans up after one Podman build. A stopped, timed-out or cancelled build leaves its {@code
   * RUN} processes running, in {@code slice} when it is not null, and the builder's working
   * containers with everything the steps wrote. Stops those processes, removes the containers, then
   * the build's tag of its base image. Waits for the build command to exit first, so nothing is
   * removed under it.
   */
  void removeBase(String tag, String slice, Process build) {
    if (build != null) {
      try {
        build.waitFor(15, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (slice != null) {
      try {
        stopSlice(slice);
      } catch (Exception e) {
        Log.warnf(e, "Could not stop the processes of %s", slice);
      }
    }
    String container = tag.substring(tag.indexOf('/') + 1) + "-working-container";
    try {
      Result r =
          commands.run(
              List.of(runtime, "ps", "-a", "--external", "-q", "--filter", "name=^" + container),
              15,
              1_000_000);
      if (r.timeout() || r.code() != 0) {
        throw new IllegalStateException("Container listing failed: " + r.output().strip());
      }
      for (String c : r.output().lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
        removeContainer(c);
      }
    } catch (Exception e) {
      Log.warnf(e, "Could not remove the build containers of %s", tag);
    }
    try {
      removeImage(tag);
    } catch (Exception e) {
      Log.warnf(e, "Could not remove the tag %s", tag);
    }
  }

  /** Images of builds a previous runner process did not get to remove. */
  List<String> staleImages() {
    if (runtime == null) {
      return List.of();
    }
    try {
      Result r =
          commands.run(List.of(runtime, "images", "--format", "{{.Repository}}"), 15, 1_000_000);
      if (r.timeout() || r.code() != 0) {
        throw new IllegalStateException("Image listing failed");
      }
      return r.output()
          .lines()
          .map(String::strip)
          .filter(n -> n.matches("(localhost/)?cisys-build-[0-9]+(-prepare|-base)?"))
          .distinct()
          .toList();
    } catch (Exception e) {
      throw new IllegalStateException("Could not list images", e);
    }
  }

  /** A base tag is only untagged: the image it names may have no other name, and stays. */
  void removeImage(String name) {
    try {
      Result r =
          commands.run(
              name.endsWith("-base")
                  // With only the image, untag would remove every name of the image.
                  ? List.of(runtime, "untag", name, name)
                  : List.of(runtime, "rmi", "-f", name),
              60,
              4096);
      if (r.timeout() || r.code() != 0) {
        throw new IllegalStateException("Image command failed");
      }
    } catch (Exception e) {
      throw new IllegalStateException("Could not remove image " + name, e);
    }
  }

  /** Root of the checkout directories created by {@link #run}. */
  Path checkoutRoot() {
    return Path.of(System.getProperty("java.io.tmpdir"));
  }

  /**
   * Best-effort cleanup of a previous runner process. A failure is logged and does not stop
   * startup.
   */
  private void cleanupOrphans() {
    try {
      cleanupOrphansUnchecked();
    } catch (RuntimeException e) {
      Log.warn("Could not clean up orphaned runner state", e);
    }
  }

  private void cleanupOrphansUnchecked() {
    // RUN processes of builds a previous process did not stop.
    if ("podman".equals(runtime) && systemdCgroups()) {
      try {
        stopSlice(BUILDS_SLICE);
      } catch (Exception e) {
        Log.warnf(e, "Could not stop orphaned build processes");
      }
    }
    try {
      for (String name : staleContainers()) {
        try {
          removeContainer(name);
          Log.infof("Removed orphaned build container %s", name);
        } catch (Exception e) {
          Log.warnf(e, "Could not remove orphaned build container %s", name);
        }
      }
    } catch (RuntimeException e) {
      Log.warn("Could not list orphaned build containers", e);
    }
    try {
      for (String image : staleImages()) {
        try {
          removeImage(image);
          Log.infof("Removed orphaned build image %s", image);
        } catch (Exception e) {
          Log.warnf(e, "Could not remove orphaned build image %s", image);
        }
      }
    } catch (RuntimeException e) {
      Log.warn("Could not list orphaned build images", e);
    }
    Path root = checkoutRoot();
    if (!Files.isDirectory(root)) {
      return;
    }
    try (var entries = Files.list(root)) {
      for (Path path :
          entries.filter(p -> p.getFileName().toString().startsWith("cisys-")).toList()) {
        try {
          if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
          } else {
            delete(path);
          }
        } catch (Exception e) {
          Log.warnf(e, "Could not remove stale checkout %s", path);
        }
      }
    } catch (Exception e) {
      Log.warnf(e, "Could not list stale checkouts in %s", root);
    }
  }

  Build submit(Long projectId, String actor, String url, BuildResource.RunRequest request) {
    return submit(
        projectId,
        actor,
        url,
        request.branch(),
        request.credentialRef(),
        null,
        Build.Trigger.MANUAL,
        null,
        null,
        null);
  }

  /**
   * Queues one build. The poller passes the commit as {@code revision}; a manual request leaves it
   * null so the checkout follows the branch tip. A pull request build always stores the provider
   * and number that created it, and never receives a credential reference.
   */
  Build submit(
      Long projectId,
      String actor,
      String url,
      String branch,
      String credentialRef,
      String revision,
      Build.Trigger trigger,
      String triggerActor,
      Integer pullRequest,
      String pullRequestProvider) {
    Build.Trigger kind = trigger == null ? Build.Trigger.MANUAL : trigger;
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          lockQueue();
          if (Project.findById(projectId) == null) {
            throw new jakarta.ws.rs.NotFoundException();
          }
          if (Build.count("status", Build.Status.QUEUED) >= queueLimit()) {
            throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(429)
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Build queue is full. Try again later."))
                    .build());
          }
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.requestedBy = actor;
          b.status = Build.Status.QUEUED;
          b.requestedAt = Instant.now();
          b.repositoryUrl = url;
          b.branch = branch;
          b.credentialRef = kind == Build.Trigger.PULL_REQUEST ? null : credentialRef;
          b.revision = revision;
          b.trigger = kind;
          b.triggerActor = triggerActor;
          b.pullRequest = kind == Build.Trigger.PULL_REQUEST ? pullRequest : null;
          b.pullRequestProvider = kind == Build.Trigger.PULL_REQUEST ? pullRequestProvider : null;
          b.persist();
          return b;
        });
  }

  /** A pull request submission, and whether it created a new build or matched an existing one. */
  record Submission(Build build, boolean created) {}

  /**
   * Queues a pull request build, or succeeds without queueing when the project already has a usable
   * build for the same commit. A usable build is any non-cancelled build, whatever its trigger, so
   * a cancelled pull request row never masks a live poll or manual build. When the only build is a
   * pull request row that this poller cancelled before it started, that row is revived instead of
   * inserting a duplicate, which the partial unique index would reject anyway. Builds cancelled on
   * request or after they started keep the existing no-retry policy. The check and the insert share
   * the queue lock, and the partial unique index protects them across instances. The credential
   * reference is always null.
   */
  Submission submitPullRequest(
      Long projectId, String url, String branch, String revision, int number, String provider) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          lockQueue();
          if (Project.findById(projectId) == null) {
            throw new jakarta.ws.rs.NotFoundException();
          }
          List<Build> existing =
              Build.<Build>find("projectId = ?1 and revision = ?2 order by id", projectId, revision)
                  .list();
          for (Build b : existing) {
            if (b.status != Build.Status.CANCELLED) {
              return new Submission(b, false);
            }
          }
          Build revivable =
              existing.stream().filter(BuildRunner::revivablePullRequest).findFirst().orElse(null);
          if (revivable == null) {
            if (!existing.isEmpty()) {
              // A finished or intentionally cancelled build covers the revision, like before.
              return new Submission(existing.get(0), false);
            }
            if (Build.count("status", Build.Status.QUEUED) >= queueLimit()) {
              throw queueFull();
            }
            Build b = new Build();
            b.projectId = projectId;
            b.number = Build.nextNumber(projectId);
            b.requestedBy = "poll";
            b.status = Build.Status.QUEUED;
            b.requestedAt = Instant.now();
            b.repositoryUrl = url;
            b.branch = branch;
            b.credentialRef = null;
            b.revision = revision;
            b.trigger = Build.Trigger.PULL_REQUEST;
            b.triggerActor = "pull-request";
            b.pullRequest = number;
            b.pullRequestProvider = provider;
            b.persist();
            return new Submission(b, true);
          }
          if (Build.count("status", Build.Status.QUEUED) >= queueLimit()) {
            throw queueFull();
          }
          revivable.status = Build.Status.QUEUED;
          revivable.requestedAt = Instant.now();
          revivable.finishedAt = null;
          revivable.repositoryUrl = url;
          revivable.branch = branch;
          revivable.credentialRef = null;
          revivable.cancelRequested = false;
          revivable.pullRequest = number;
          revivable.pullRequestProvider = provider;
          return new Submission(revivable, true);
        });
  }

  /** A queued pull request row this poller cancelled before it ever started. */
  private static boolean revivablePullRequest(Build b) {
    return b.status == Build.Status.CANCELLED
        && b.trigger == Build.Trigger.PULL_REQUEST
        && b.startedAt == null
        && !b.cancelRequested;
  }

  private static WebApplicationException queueFull() {
    return new WebApplicationException(
        jakarta.ws.rs.core.Response.status(429)
            .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
            .entity(Map.of("error", "Build queue is full. Try again later."))
            .build());
  }

  /**
   * Cancels every queued pull request build of the project whose revision no currently eligible
   * pull request needs, and clears the submitted marker of the cursors that still point at it so a
   * returning head is submitted again. Running and terminal builds and poll or manual builds are
   * never touched. Runs on every successful discovery pass and is idempotent. Returns the number of
   * cancelled builds.
   */
  int reconcilePullRequestBuilds(Long projectId, Set<String> eligibleRevisions) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          lockQueue();
          List<Build> queued =
              Build.<Build>find(
                      "projectId = ?1 and trigger = ?2 and status = ?3",
                      projectId,
                      Build.Trigger.PULL_REQUEST,
                      Build.Status.QUEUED)
                  .list();
          int cancelled = 0;
          for (Build b : queued) {
            if (eligibleRevisions.contains(b.revision)) {
              continue;
            }
            // Only a build that is still QUEUED is cancelled; a build that started meanwhile is
            // left alone, and its cursor is not reset.
            if (Build.update(
                    "status = ?1, finishedAt = ?2 where id = ?3 and status = ?4",
                    Build.Status.CANCELLED,
                    Instant.now(),
                    b.id,
                    Build.Status.QUEUED)
                == 1) {
              cancelled++;
              ProjectPullRequest.update(
                  "lastSubmittedRevision = null where projectId = ?1 and lastSubmittedRevision = ?2",
                  projectId,
                  b.revision);
            }
          }
          return cancelled;
        });
  }

  /** Committed RUNNING rows reserve slots before any task is handed to the executor. */
  Build claim() {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          lockQueue();
          if (Build.count("status", Build.Status.RUNNING) >= concurrency()) {
            return null;
          }
          Build b =
              Build.<Build>find("status = ?1 order by requestedAt, id", Build.Status.QUEUED)
                  .firstResult();
          if (b == null) {
            return null;
          }
          int changed =
              Build.update(
                  "status = ?1, startedAt = ?2 where id = ?3 and status = ?4",
                  Build.Status.RUNNING,
                  Instant.now(),
                  b.id,
                  Build.Status.QUEUED);
          if (changed != 1) {
            return null;
          }
          Build.getEntityManager().refresh(b);
          return b;
        });
  }

  @io.quarkus.scheduler.Scheduled(
      every = "1s",
      concurrentExecution = io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP)
  synchronized void dispatch() {
    if (!ready || stopping) {
      return;
    }
    try {
      retryCompletions();
      stopCancelled();
      Build b;
      while ((b = claim()) != null) {
        Build task = b;
        try {
          schedule(() -> execute(task));
        } catch (RejectedExecutionException e) {
          QuarkusTransaction.requiringNew()
              .run(() ->
                  Build.update(
                      "status = ?1, startedAt = null where id = ?2 and status = ?3",
                      Build.Status.QUEUED,
                      task.id,
                      Build.Status.RUNNING));
          throw e;
        }
      }
    } catch (RuntimeException e) {
      Log.error("Build queue dispatch failed; will retry", e);
    }
  }

  /** Hands a claimed build to the executor. Overridden in tests to simulate a rejected schedule. */
  void schedule(Runnable task) {
    executor.submit(task);
  }

  void execute(Build b) {
    boolean pullRequest = b.trigger == Build.Trigger.PULL_REQUEST;
    run(
        b.id,
        b.repositoryUrl,
        b.branch,
        pullRequest ? null : b.credentialRef,
        b.revision,
        mirrorFor(b),
        pullRequest,
        b.projectId);
  }

  /**
   * The mirror a build checks out from, or null to clone from the repository. Poll and pull request
   * builds always use it. A manual build uses it when {@link Settings#mirrorManualBuilds} is on:
   * the mirror is fetched first, so the build gets the branch's current tip, and a failed fetch
   * falls back to the repository.
   */
  Path mirrorFor(Build b) {
    if (b.trigger == Build.Trigger.POLL || b.trigger == Build.Trigger.PULL_REQUEST) {
      return mirrors.path(b.projectId);
    }
    Project p =
        QuarkusTransaction.requiringNew()
            .call(() ->
                Settings.current().mirrorManualBuilds
                    ? Project.<Project>findById(b.projectId)
                    : null);
    if (p == null) {
      return null;
    }
    java.util.concurrent.locks.ReentrantLock lock = mirrors.lock(p.id);
    lock.lock();
    try {
      mirrors.fetch(p);
      return mirrors.path(p.id);
    } catch (Exception e) {
      Log.warnf("Project %d: could not fetch the mirror; cloning from the repository: %s", p.id, e);
      return null;
    } finally {
      lock.unlock();
    }
  }

  @Inject HostResolver resolver;

  @ConfigProperty(name = "cisys.build.network.ports", defaultValue = "443")
  String networkPorts;

  @ConfigProperty(name = "quarkus.datasource.jdbc.url")
  Optional<String> datasourceUrl;

  @ConfigProperty(name = "quarkus.oidc.auth-server-url")
  Optional<String> oidcUrl;

  @ConfigProperty(name = "cisys.keycloak.url")
  Optional<String> keycloakUrl;

  @ConfigProperty(name = "quarkus.http.host")
  Optional<String> httpHost;

  /**
   * Starts the build's egress proxy in {@code dir}, or returns null when the allowlist is empty.
   * Anything that keeps the proxy from enforcing its rules fails the build; a build never falls
   * back to unrestricted networking.
   */
  EgressProxy network(
      Path dir, NetworkAllowlist allowlist, java.util.function.Consumer<String> log) {
    if (allowlist.isEmpty()) {
      return null;
    }
    // Docker's build only forwards SSH agent sockets, so its steps cannot reach the proxy.
    if (!"podman".equals(runtime)) {
      throw new IllegalArgumentException("Network access is not supported with " + runtime);
    }
    try {
      EgressProxy.Policy policy = networkPolicy(allowlist);
      // What the proxy will allow, so a denied or unreachable host can be read against it.
      log.accept(
          "==> Network: HTTPS through the proxy to "
              + String.join(", ", allowlist.entries())
              + " on port"
              + (policy.ports().size() == 1 ? " " : "s ")
              + policy.ports().stream()
                  .sorted()
                  .map(String::valueOf)
                  .collect(java.util.stream.Collectors.joining(", ")));
      return EgressProxy.start(dir.resolve("proxy"), policy, resolver, EgressProxy.TCP, log);
    } catch (Exception e) {
      Log.errorf(e, "Could not start the network proxy");
      throw new IllegalArgumentException("Network access could not be set up");
    }
  }

  /**
   * The allowlist plus the backend's own addresses and the internal services it uses, which are
   * always denied.
   */
  EgressProxy.Policy networkPolicy(NetworkAllowlist allowlist) throws Exception {
    Set<Integer> ports = new HashSet<>();
    for (String p : networkPorts.split(",")) {
      if (p.isBlank()) {
        continue;
      }
      int port = Integer.parseInt(p.strip());
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("Invalid cisys.build.network.ports");
      }
      ports.add(port);
    }
    Set<String> hosts = new HashSet<>();
    for (Optional<String> u : List.of(datasourceUrl, oidcUrl, keycloakUrl)) {
      u.ifPresent(v -> hosts.addAll(hostsOf(v)));
    }
    httpHost.ifPresent(h -> hosts.add(h.strip()));
    hosts.removeIf(h -> h.isEmpty() || h.equals("0.0.0.0") || h.equals("::"));
    Set<java.net.InetAddress> addresses = new HashSet<>();
    for (var nic : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
      addresses.addAll(Collections.list(nic.getInetAddresses()));
    }
    for (String h : hosts) {
      java.net.InetAddress literal = NetworkAllowlist.literal(h);
      addresses.addAll(literal != null ? List.of(literal) : List.of(resolver.resolve(h)));
    }
    return new EgressProxy.Policy(allowlist, ports, hosts, addresses);
  }

  /** The host names in a URL such as {@code jdbc:postgresql://a:5432,b:5432/db}. */
  static List<String> hostsOf(String url) {
    int start = url.indexOf("//");
    if (start < 0) {
      return List.of();
    }
    String authority = url.substring(start + 2).split("[/?#]", 2)[0];
    authority = authority.substring(authority.lastIndexOf('@') + 1);
    List<String> hosts = new ArrayList<>();
    for (String part : authority.split(",")) {
      String h =
          part.startsWith("[")
              ? part.substring(1, Math.max(1, part.indexOf(']')))
              : part.replaceFirst(":[0-9]*$", "");
      if (!h.isBlank()) {
        hosts.add(h.toLowerCase(Locale.ROOT));
      }
    }
    return hosts;
  }

  /** For tests waiting for the queue to drain. */
  boolean hasPendingBuilds() {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            Build.count("status in ?1", List.of(Build.Status.QUEUED, Build.Status.RUNNING)) > 0);
  }

  /**
   * Prepares a build workspace from the project's local mirror (a {@code file:} URI) or from the
   * network, then checks out the requested revision. Package-private so a test can exercise the
   * mirror path without a container.
   */
  String prepareWorkspace(
      Path workspace,
      String url,
      String branch,
      String credentialRef,
      String expectedRevision,
      Path localMirror,
      int maxCheckoutMib,
      int maxCheckoutFiles,
      java.util.function.BooleanSupplier cancelled)
      throws Exception {
    return prepareWorkspace(
        workspace,
        url,
        branch,
        credentialRef,
        expectedRevision,
        localMirror,
        maxCheckoutMib,
        maxCheckoutFiles,
        cancelled,
        false);
  }

  /**
   * As above. A pull request build passes {@code requireLocalMirror} so a missing mirror fails the
   * build instead of falling back to a remote fetch, which could use a project credential.
   */
  String prepareWorkspace(
      Path workspace,
      String url,
      String branch,
      String credentialRef,
      String expectedRevision,
      Path localMirror,
      int maxCheckoutMib,
      int maxCheckoutFiles,
      java.util.function.BooleanSupplier cancelled,
      boolean requireLocalMirror)
      throws Exception {
    String cloneUrl;
    UsernamePasswordCredentialsProvider credentials = null;
    if (localMirror != null && Files.isDirectory(localMirror)) {
      cloneUrl = localMirror.toUri().toString();
    } else {
      if (requireLocalMirror) {
        throw new IllegalArgumentException(
            "The repository mirror is missing; a pull request build cannot run");
      }
      if (localMirror != null) {
        Log.infof("The repository mirror is missing; fetching the commit from the repository");
      }
      // Checked again here because DNS can change after the project was saved.
      cloneUrl = RepositoryUrls.cloneUrl(url);
      repositoryUrls.validate(cloneUrl);
      if (credentialRef != null) {
        String token = System.getenv(credentialRef);
        if (token == null || token.isBlank()) {
          throw new IllegalStateException("Missing Git credential");
        }
        credentials = new UsernamePasswordCredentialsProvider("oauth2", token);
      }
    }
    return Checkout.fetch(
        workspace,
        cloneUrl,
        branch,
        credentials,
        repositoryUrls.verifyTls(url),
        maxCheckoutMib,
        maxCheckoutFiles,
        expectedRevision,
        cancelled);
  }

  private void run(
      Long id,
      String url,
      String branch,
      String credentialRef,
      String expectedRevision,
      Path localMirror,
      boolean requireLocalMirror,
      Long projectId) {
    Path dir = null;
    String name = "cisys-build-" + id,
        prepare = name + "-prepare",
        revision = expectedRevision,
        status = "FAILED",
        digest = null;
    Integer exit = null;
    boolean truncated = false;
    Throwable captureError = null;
    BuildLogs.Writer log = null;
    EgressProxy proxy = null;
    String baseTag = null, slice = null;
    StepImages prepareImages = null, stepImages = null;
    Running handle = new Running();
    running.put(id, handle);
    try {
      log = logs.open(id);
      int[] limits =
          QuarkusTransaction.requiringNew()
              .call(() ->
                  new int[] {
                    maxCheckoutMib(),
                    maxCheckoutFiles(),
                    maxMemoryMib(),
                    maxCpus(),
                    maxPids(),
                    timeoutMinutes()
                  });
      dir = Files.createTempDirectory(checkoutRoot(), "cisys-");
      // The image copies this checkout, so what it gets is what was measured against the limits.
      Path context = Files.createDirectory(dir.resolve("context"));
      Path workspace = context.resolve(Workflow.WORKSPACE);
      revision =
          prepareWorkspace(
              workspace,
              url,
              branch,
              credentialRef,
              expectedRevision,
              localMirror,
              limits[0],
              limits[1],
              () -> handle.cancelled,
              requireLocalMirror);
      // Read when the build starts, like the limits. A pull request build reads the cache but never
      // saves it, so code from a pull request cannot change what later builds start from. Holds the
      // project's name when its cache is on, else null.
      String cache =
          projectId == null
              ? null
              : QuarkusTransaction.requiringNew()
                  .call(() -> {
                    Project p = Project.findById(projectId);
                    return p == null || !p.cache ? null : p.name;
                  });
      if (cache != null) {
        caches.copyIn(projectId, context.resolve(Workflow.CACHE));
        log.line("Cache: $HOME/.cisys/" + cache);
      }
      // Shown while the build runs, not only once it finishes.
      String fetched = revision;
      QuarkusTransaction.requiringNew()
          .run(() -> Build.update("revision = ?1 where id = ?2", fetched, id));
      // The workflow on the fetched branch describes the image.
      String text = Workflow.read(workspace);
      if (handle.cancelled) {
        throw new CancellationException();
      }
      if (text == null) {
        throw new IllegalArgumentException("The branch has no " + Workflow.PATH);
      }
      // Stored before parsing, so a build keeps the file that it ran or that failed validation.
      QuarkusTransaction.requiringNew()
          .run(() -> Build.update("workflow = ?1 where id = ?2", text, id));
      Workflow workflow = Workflow.parse(text);
      if (workflow.hasArtifacts() && !runtime.equals("podman")) {
        throw new IllegalArgumentException("Artifacts are not supported with docker");
      }
      String base = workflow.container();
      ImageReference.Resolved from = ImageReference.resolve(base, localImages());
      QuarkusTransaction.requiringNew()
          .run(() -> Build.update("image = ?1 where id = ?2", from.name(), id));
      // Allowed when the admin allowed any name of the image, whichever way the workflow names it.
      if (!QuarkusTransaction.requiringNew()
          .call(() -> from.names().stream().anyMatch(AllowedImage::isAllowed))) {
        throw new IllegalArgumentException("Image " + base + " is not allowed");
      }
      // A stored list that no longer validates fails the build rather than running it without its
      // rules.
      NetworkAllowlist allowlist = QuarkusTransaction.requiringNew().call(NetworkHost::allowlist);
      // Podman builds from a tag of the base image for this build only, as the builder names its
      // working containers after the image; so the build's containers can be found and removed.
      boolean podman = runtime.equals("podman");
      String containerfile =
          podman
              ? "# "
                  + baseTag(id)
                  + " is image "
                  + from.id()
                  + ", tagged for this build\n"
                  + workflow.containerfile(baseTag(id), !allowlist.isEmpty(), cache)
              : workflow.containerfile(from.id(), !allowlist.isEmpty(), cache);
      // Stored before it runs, so the build page shows what ran, also when it failed.
      String shown =
          podman
              ? containerfile
              : dockerfiles(
                  workflow.prepareContainerfile(from.id(), !allowlist.isEmpty()),
                  workflow.stepsContainerfile(prepare, !allowlist.isEmpty(), cache));
      QuarkusTransaction.requiringNew()
          .run(() -> Build.update("dockerfile = ?1 where id = ?2", shown, id));
      log.line(
          "Workflow "
              + workflow.name()
              + " on "
              + from.name()
              + " ("
              + from.id().substring(0, 12)
              + ")");
      BuildLogs.Writer buildLog = log;
      proxy = network(dir, allowlist, buildLog::line);
      // Read when the build starts, so a changed setting applies to the next build.
      int timeoutSeconds = limits[5] * 60;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      Streamed result;
      long outputBytes;
      Path artifactDir = null;
      if (podman) {
        Path file = Files.writeString(dir.resolve("Dockerfile"), containerfile);
        baseTag = baseTag(id);
        tag(from.id(), baseTag);
        List<String> build = buildCommand(file, name, limits);
        if (systemdCgroups()) {
          slice = slice(id);
          build.add("--cgroup-parent=" + slice);
        }
        if (workflow.hasArtifacts()) {
          artifactDir = artifactDirectory(dir);
          build.add("--volume=" + artifactDir + ":" + Workflow.ARTIFACT_DIR + ":rw,Z");
        }
        if (proxy != null) {
          build.add("--volume=" + proxy.directory() + ":" + Workflow.PROXY_DIR + ":ro,Z");
          // SELinux does not let a container connect to a socket that a host process serves, and
          // has no boolean for it. Builds with network therefore run without SELinux labels; they
          // stay rootless, without privileges and without network of their own.
          build.add("--security-opt=label=disable");
        }
        build.add(context.toString());
        header(log, "Dockerfile", containerfile, build);
        if (handle.cancelled) {
          throw new CancellationException();
        }
        result = stream(build, timeoutSeconds, log, logMaxBytes, p -> started(handle, p));
        outputBytes = result.totalBytes();
      } else {
        // The Docker legacy builder enforces the resource flags, but it has no per-RUN network
        // flag. Tools and dependencies are installed first with network, then the steps build
        // from that image with network off. BuildKit ignores the resource flags.
        Path prepareFile =
            Files.writeString(
                dir.resolve("Dockerfile.prepare"),
                workflow.prepareContainerfile(from.id(), !allowlist.isEmpty()));
        List<String> prepareBuild = buildCommand(prepareFile, prepare, limits);
        prepareBuild.add(context.toString());
        header(log, "Dockerfile.prepare", Files.readString(prepareFile), prepareBuild);
        if (handle.cancelled) {
          throw new CancellationException();
        }
        prepareImages = new StepImages(log);
        Streamed prepared =
            stream(
                prepareBuild,
                remainingSeconds(deadline),
                prepareImages,
                logMaxBytes,
                p -> started(handle, p),
                LEGACY_BUILDER);
        if (prepared.readerError() != null) {
          captureError = prepared.readerError();
        }
        outputBytes = prepared.totalBytes();
        if (prepared.timeout() || prepared.code() != 0) {
          result = prepared;
        } else {
          Path file =
              Files.writeString(
                  dir.resolve("Dockerfile"),
                  workflow.stepsContainerfile(prepare, !allowlist.isEmpty(), cache));
          List<String> build = buildCommand(file, name, limits);
          build.add("--network=none");
          build.add(context.toString());
          header(log, "Dockerfile", Files.readString(file), build);
          if (handle.cancelled) {
            throw new CancellationException();
          }
          stepImages = new StepImages(log);
          result =
              stream(
                  build,
                  remainingSeconds(deadline),
                  stepImages,
                  remainingLogBytes(logMaxBytes, prepared),
                  p -> started(handle, p),
                  LEGACY_BUILDER);
          outputBytes += result.totalBytes();
        }
      }
      if (result.readerError() != null) {
        captureError = result.readerError();
      }
      if (outputBytes > logMaxBytes) {
        truncated = true;
        log.line(
            "[output truncated after "
                + logMaxBytes
                + " bytes; "
                + (outputBytes - logMaxBytes)
                + " further bytes discarded]");
      }
      digest = digest(from.id());
      if (handle.cancelled) {
        throw new CancellationException();
      }
      if (result.timeout()) {
        log.line(
            "Build timed out after "
                + limits[5]
                + (limits[5] == 1 ? " minute" : " minutes")
                + "; an admin can change the timeout under Settings > Builds");
      } else {
        // The image build stops at the first failing RUN and exits with its exit code.
        exit = result.code();
        status = exit == 0 ? "PASSED" : "FAILED";
        if (exit == 0 && cache != null && !requireLocalMirror && !handle.cancelled) {
          saveCache(projectId, name, dir, log::line);
        }
      }
      // A timed-out build was killed, possibly while it wrote the archive.
      if (!handle.cancelled && !result.timeout() && artifactDir != null) {
        collectArtifacts(id, workflow, artifactDir, log::line);
      }
      if (!handle.cancelled) {
        runReports(id, workflow, workspace, log::line);
      }
    } catch (Exception e) {
      // A failure caused by the cancellation is not reported as one.
      if (!handle.cancelled) {
        if (e instanceof InterruptedException) {
          if (log != null) {
            log.line("Build stopped: the server is shutting down");
          } else {
            captureError = e;
          }
        } else if (e instanceof IllegalArgumentException) {
          if (log != null) {
            log.line("Build failed: " + e.getMessage());
          } else {
            captureError = e;
          }
        } else if (log != null) {
          log.line(
              stopping
                  ? "Build stopped: the server is shutting down"
                  : "Build failed during checkout or container execution ("
                      + e.getClass().getSimpleName()
                      + "). Check the runner configuration.");
        } else {
          captureError = e;
        }
      }
    } finally {
      // A shutdown interrupts the build; the cleanup and the result must still run, so clear the
      // flag until done.
      boolean interrupted = Thread.interrupted();
      running.remove(id, handle);
      // The request can arrive after the last dispatcher poll; persistCompletion also honours one
      // that arrives after this read.
      boolean cancelled = handle.cancelled;
      if (!cancelled && running()) {
        try {
          cancelled =
              QuarkusTransaction.requiringNew()
                  .call(() -> Build.count("id = ?1 and cancelRequested = true", id) > 0);
        } catch (RuntimeException e) {
          Log.warnf(e, "Could not check whether build %d was cancelled", id);
        }
      }
      if (cancelled) {
        status = "CANCELLED";
        exit = null;
        if (log != null) {
          log.line("Build cancelled");
        }
      }
      if (baseTag != null) {
        removeBase(baseTag, slice, handle.process);
      }
      // The image only exists to run the workflow.
      try {
        command(List.of(runtime, "rmi", "-f", name), 60, 4096);
      } catch (Exception ignored) {
      }
      // Docker builds prepare tools and dependencies in an intermediate image.
      if (!runtime.equals("podman")) {
        try {
          command(List.of(runtime, "rmi", "-f", prepare), 60, 4096);
        } catch (Exception ignored) {
        }
        removeStepImage(stepImages);
        removeStepImage(prepareImages);
      }
      if (proxy != null) {
        try {
          proxy.close();
        } catch (Exception e) {
          Log.errorf(e, "Could not stop the network proxy of build %d", id);
        }
      }
      if (dir != null) {
        try {
          delete(dir);
        } catch (Exception e) {
          // Directories a step made in the artifact mount belong to a user of Podman's namespace.
          if (runtime.equals("podman")) {
            try {
              command(List.of(runtime, "unshare", "rm", "-rf", dir.toString()), 60, 4096);
            } catch (Exception ignored) {
            }
          }
        }
      }
      // A log failure must not stop the status update.
      try {
        if (log != null && running()) {
          if (captureError == null) {
            captureError = log.failure();
          }
          if (captureError != null) {
            log.line("[log capture failed: " + captureError.getClass().getSimpleName() + "]");
          }
          log.close();
          if (captureError == null) {
            captureError = log.failure();
          }
        }
      } catch (Throwable t) {
        if (captureError == null) {
          captureError = t;
        }
      }
      if (captureError != null) {
        truncated = true;
        Log.errorf(captureError, "Log capture failed for build %d", id);
      }
      complete(
          new Completion(
              id, Build.Status.valueOf(status), revision, exit, digest, truncated, Instant.now()));
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Inject jakarta.enterprise.inject.Instance<ReportType> reportTypes;

  /**
   * Runs the reports the workflow asks for, in workflow order, against the checkout and stores each
   * one with the build. A report that fails or has no registered type is noted in the log; neither
   * changes the build's result.
   */
  void runReports(
      Long id, Workflow workflow, Path checkout, java.util.function.Consumer<String> log) {
    if (workflow.reports() == null) {
      return;
    }
    Map<String, ReportType> types = new HashMap<>();
    Map<String, ReportType> byClass = new HashMap<>();
    for (ReportType t : reportTypes) {
      types.put(t.type(), t);
      byClass.put(io.quarkus.arc.ClientProxy.unwrap(t).getClass().getName(), t);
    }
    // An admin's setting names the class that runs a type, and gives it its variables.
    Map<String, ReportPlugin> plugins = new HashMap<>();
    QuarkusTransaction.requiringNew()
        .run(() -> ReportPlugin.<ReportPlugin>listAll().forEach(p -> plugins.put(p.type, p)));
    for (Map.Entry<String, List<Map<String, String>>> e : workflow.reports().entrySet()) {
      ReportPlugin plugin = plugins.get(e.getKey());
      ReportType type = plugin == null ? types.get(e.getKey()) : byClass.get(plugin.className);
      Map<String, String> variables = plugin == null ? Map.of() : plugin.variableMap();
      for (Map<String, String> options : e.getValue()) {
        String name = options.get("name");
        if (type == null) {
          log.accept("Report " + name + " skipped: no report type " + e.getKey());
          continue;
        }
        try {
          ReportType.Report report =
              type.run(new ReportType.Context(checkout, workflow, Map.copyOf(options), variables));
          if (report == null || report.format() == null || report.content() == null) {
            throw new IllegalStateException("the report type returned no content");
          }
          String content = report.content();
          boolean truncated = content.length() > BuildReport.MAX_CONTENT;
          String kept = truncated ? content.substring(0, BuildReport.MAX_CONTENT) : content;
          QuarkusTransaction.requiringNew()
              .run(() -> {
                BuildReport r = new BuildReport();
                r.buildId = id;
                r.type = e.getKey();
                r.name = name;
                r.format = report.format();
                r.content = kept;
                r.truncated = truncated;
                r.createdAt = Instant.now();
                r.persist();
              });
          log.accept("Report " + name + " done");
        } catch (Exception ex) {
          Log.warnf(ex, "Report %s of build %d failed", name, id);
          log.accept("Report " + name + " failed: " + ex.getClass().getSimpleName());
        }
      }
    }
  }

  /**
   * Takes {@value Workflow#SAVED_CACHE} out of the build's image and makes it the project's cache.
   * A cache that cannot be saved is noted in the log; it does not change the build's result.
   */
  void saveCache(Long projectId, String image, Path dir, java.util.function.Consumer<String> log) {
    String container = image + "-cache";
    try {
      Result created =
          command(List.of(runtime, "create", "--name", container, image, "true"), 60, 4096);
      if (created.code() != 0) {
        throw new IOException("create exited with " + created.code());
      }
      Path saved = dir.resolve("saved-cache");
      Result copied =
          command(
              List.of(
                  runtime, "cp", container + ":" + Workflow.SAVED_CACHE + "/.", saved.toString()),
              600,
              4096);
      if (copied.code() != 0) {
        throw new IOException("cp exited with " + copied.code());
      }
      if (caches.save(projectId, saved)) {
        log.accept("Cache saved");
      } else {
        log.accept(
            "Cache not saved: larger than "
                + caches.maxMib
                + " MiB; an admin can change cisys.cache.max-mib");
      }
    } catch (Exception e) {
      Log.warnf(e, "Could not save the cache of project %d", projectId);
      log.accept("Cache not saved: " + e.getMessage());
    } finally {
      try {
        command(List.of(runtime, "rm", "-f", container), 60, 4096);
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * The Dockerfiles a build runs, as one text for the build page: Docker builds run a preparation
   * Dockerfile and then the steps' Dockerfile.
   */
  static String dockerfiles(String prepare, String steps) {
    return "# Dockerfile.prepare\n" + prepare + "\n# Dockerfile\n" + steps;
  }

  /**
   * Writes the generated Dockerfile, as it was written, and the command that builds it to the build
   * log, so a failing build can be read against what actually ran. Neither holds a secret: the
   * backend clones the branch itself and copies the checkout in, so no Git token reaches the build.
   */
  static void header(BuildLogs.Writer log, String title, String dockerfile, List<String> command) {
    log.line("==> " + title + ":");
    dockerfile.lines().forEach(log::line);
    log.line("==> Command: " + String.join(" ", command));
  }

  /** Every local image with its full ID, for resolving a workflow's base image. */
  List<ImageReference.Local> localImages() throws Exception {
    Result r =
        command(
            List.of(
                runtime, "images", "--no-trunc", "--format", "{{.Repository}}\t{{.Tag}}\t{{.ID}}"),
            30,
            1_000_000);
    if (r.timeout() || r.code() != 0) {
      throw new IllegalStateException("Container command failed");
    }
    List<ImageReference.Local> images = new ArrayList<>();
    for (String line : r.output().split("\n")) {
      String[] f = line.strip().split("\t");
      if (f.length != 3) {
        continue;
      }
      String id = f[2].replace("sha256:", "");
      if (id.matches("[0-9a-f]{64}")) {
        images.add(new ImageReference.Local(f[0], f[1], id));
      }
    }
    return images;
  }

  record LocalImage(String name, String id, String size, String created) {}

  /** Lists the tagged images in the local container runtime. */
  List<LocalImage> images() throws Exception {
    Result r =
        command(
            List.of(
                runtime,
                "images",
                "--format",
                "{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}"),
            30,
            1000000);
    if (r.timeout() || r.code() != 0) {
      throw new IllegalStateException("Container command failed");
    }
    Map<String, LocalImage> images = new TreeMap<>();
    for (String line : r.output().split("\n")) {
      String[] f = line.split("\t");
      if (f.length < 5 || f[0].equals("<none>") || f[1].equals("<none>")) {
        continue;
      }
      String name = f[0] + ":" + f[1];
      if (name.matches(AllowedImage.PATTERN)) {
        images.putIfAbsent(name, new LocalImage(name, f[2], f[3], f[4]));
      }
    }
    return List.copyOf(images.values());
  }

  /**
   * Pulls an image into the local container runtime. Returns the runtime's output when the pull
   * fails.
   */
  Optional<String> pull(String name) throws Exception {
    Result r = command(List.of(runtime, "pull", name), 600, 8192);
    return r.code() == 0 && !r.timeout()
        ? Optional.empty()
        : Optional.of(r.timeout() ? "Pull timed out" : tail(r.output()));
  }

  /**
   * Removes an image from the local container runtime. Returns the runtime's output when the
   * removal fails.
   */
  Optional<String> remove(String name) throws Exception {
    Result r = command(List.of(runtime, "rmi", name), 60, 8192);
    return r.code() == 0 && !r.timeout()
        ? Optional.empty()
        : Optional.of(r.timeout() ? "Removal timed out" : tail(r.output()));
  }

  private static String tail(String s) {
    s = s.strip();
    return s.length() > 500 ? "…" + s.substring(s.length() - 500) : s;
  }

  record Result(int code, boolean timeout, String output) {}

  static Result command(List<String> args, int timeout, int limit) throws Exception {
    Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
    p.getOutputStream().close();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    boolean[] truncated = {false};
    Thread reader =
        Thread.ofPlatform()
            .daemon(true)
            .start(() -> {
              try (var in = p.getInputStream()) {
                byte[] bytes = new byte[8192];
                int n;
                while ((n = in.read(bytes)) != -1) {
                  synchronized (buffer) {
                    int count = Math.min(n, limit - buffer.size());
                    buffer.write(bytes, 0, count);
                    if (count < n) {
                      truncated[0] = true;
                    }
                  }
                }
              } catch (IOException ignored) {
              }
            });
    try {
      boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        p.waitFor(5, TimeUnit.SECONDS);
      }
      reader.join(2000);
      synchronized (buffer) {
        return new Result(
            done ? p.exitValue() : -1,
            !done,
            buffer.toString(StandardCharsets.UTF_8)
                + (truncated[0] ? "\n[output truncated]\n" : ""));
      }
    } finally {
      if (p.isAlive()) {
        p.destroyForcibly();
      }
    }
  }

  static String checked(List<String> args, int timeout) throws Exception {
    Result r = command(args, timeout, 4096);
    if (r.timeout() || r.code() != 0) {
      throw new IllegalStateException("Container command failed");
    }
    return r.output();
  }

  String digest(String image) {
    try {
      String d =
          checked(
                  List.of(
                      runtime, "image", "inspect", "--format", "{{index .RepoDigests 0}}", image),
                  15)
              .trim();
      return d.length() <= 300 && d.matches("\\S+@sha256:[0-9a-f]{64}") ? d : null;
    } catch (Exception e) {
      return null;
    }
  }

  interface LogSink {
    void append(byte[] bytes, int off, int len);
  }

  /**
   * Passes the output of a Docker build on to the log, and remembers the image of the last step the
   * legacy builder committed, which it prints as {@code " ---> <id>"}. The first such line is the
   * image the build starts {@code FROM}, so it is not a step image.
   */
  static final class StepImages implements LogSink {
    private static final Pattern IMAGE = Pattern.compile(" ---> ([0-9a-f]{12})");
    private final LogSink log;
    private final StringBuilder line = new StringBuilder();
    private String from;
    private String last;

    StepImages(LogSink log) {
      this.log = log;
    }

    @Override
    public void append(byte[] bytes, int off, int len) {
      for (int i = off; i < off + len; i++) {
        if (bytes[i] == '\n') {
          Matcher m = IMAGE.matcher(line);
          if (m.matches()) {
            if (from == null) {
              from = m.group(1);
            } else {
              last = m.group(1);
            }
          }
          line.setLength(0);
        } else if (line.length() < 64) {
          line.append((char) bytes[i]);
        }
      }
      log.append(bytes, off, len);
    }

    /** The image of the last step, or null when no step got that far. */
    String last() {
      return last;
    }
  }

  /**
   * Removes the image of the last step of a failed Docker build. The build never got its tag, so
   * removing the tags leaves that image, and the untagged images under it, behind. Only a dangling
   * image is removed, so an image anything else uses stays.
   */
  void removeStepImage(StepImages images) {
    String id = images == null ? null : images.last();
    if (id == null) {
      return;
    }
    try {
      Result r =
          commands.run(
              List.of(runtime, "images", "-q", "--no-trunc", "--filter", "dangling=true"),
              15,
              1_000_000);
      if (r.timeout() || r.code() != 0) {
        throw new IllegalStateException("Image listing failed");
      }
      if (r.output().lines().map(String::strip).anyMatch(l -> l.startsWith("sha256:" + id))) {
        Result rm = commands.run(List.of(runtime, "rmi", id), 60, 4096);
        if (rm.timeout() || rm.code() != 0) {
          throw new IllegalStateException("Image command failed");
        }
      }
    } catch (Exception e) {
      Log.warnf(e, "Could not remove the step image %s", id);
    }
  }

  record Streamed(
      int code, boolean timeout, boolean truncated, long totalBytes, Throwable readerError) {}

  /** A later stage only gets what earlier stages left, so one build stores at most maxBytes. */
  static long remainingLogBytes(long maxBytes, Streamed earlier) {
    return Math.max(0, maxBytes - earlier.totalBytes());
  }

  static Streamed stream(List<String> args, int timeout, LogSink sink, long maxBytes)
      throws Exception {
    return stream(args, timeout, sink, maxBytes, p -> {});
  }

  static Streamed stream(
      List<String> args, int timeout, LogSink sink, long maxBytes, Consumer<Process> started)
      throws Exception {
    return stream(args, timeout, sink, maxBytes, 30_000, 5_000, started);
  }

  /** As above, with extra environment variables for the container command. */
  static Streamed stream(
      List<String> args,
      int timeout,
      LogSink sink,
      long maxBytes,
      Consumer<Process> started,
      Map<String, String> environment)
      throws Exception {
    return stream(args, timeout, sink, maxBytes, 30_000, 5_000, started, environment);
  }

  static Streamed stream(
      List<String> args,
      int timeout,
      LogSink sink,
      long maxBytes,
      long drainMillis,
      long forceMillis)
      throws Exception {
    return stream(args, timeout, sink, maxBytes, drainMillis, forceMillis, p -> {});
  }

  /**
   * Output beyond maxBytes, or after the sink fails, is still read and discarded so the process
   * never blocks.
   */
  static Streamed stream(
      List<String> args,
      int timeout,
      LogSink sink,
      long maxBytes,
      long drainMillis,
      long forceMillis,
      Consumer<Process> started)
      throws Exception {
    return stream(args, timeout, sink, maxBytes, drainMillis, forceMillis, started, Map.of());
  }

  static Streamed stream(
      List<String> args,
      int timeout,
      LogSink sink,
      long maxBytes,
      long drainMillis,
      long forceMillis,
      Consumer<Process> started,
      Map<String, String> environment)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
    builder.environment().putAll(environment);
    Process p = builder.start();
    p.getOutputStream().close();
    started.accept(p);
    InputStream raw = p.getInputStream();
    AtomicLong total = new AtomicLong();
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicBoolean forced = new AtomicBoolean();
    Thread reader =
        Thread.ofPlatform()
            .daemon(true)
            .name("cisys-log-reader")
            .start(() -> {
              byte[] buf = new byte[8192];
              boolean sinkOk = true;
              int n;
              try {
                while ((n = raw.read(buf)) != -1) {
                  long before = total.getAndAdd(n);
                  int keep = (int) Math.max(0, Math.min(n, maxBytes - before));
                  if (keep > 0 && sinkOk) {
                    try {
                      sink.append(buf, 0, keep);
                    } catch (Throwable t) {
                      error.compareAndSet(null, t);
                      sinkOk = false;
                    }
                  }
                }
              } catch (Throwable t) {
                if (!forced.get()) {
                  error.compareAndSet(null, t);
                }
              }
            });
    try {
      boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        p.waitFor(5, TimeUnit.SECONDS);
      }
      reader.join(drainMillis);
      if (reader.isAlive()) {
        // Something still holds the pipe open, such as a background child.
        forced.set(true);
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        try {
          raw.close();
        } catch (IOException ignored) {
        }
        reader.join(forceMillis);
      }
      Throwable err = error.get();
      if (err == null && reader.isAlive()) {
        err = new IllegalStateException("Log reader did not stop");
      }
      return new Streamed(
          done ? p.exitValue() : -1, !done, total.get() > maxBytes, total.get(), err);
    } finally {
      if (p.isAlive()) {
        p.destroyForcibly();
      }
    }
  }

  static void delete(Path dir) throws IOException {
    try (var files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    }
  }

  /** Whether the CDI container, and with it the database and transactions, is still available. */
  static boolean running() {
    var c = Arc.container();
    return c != null && c.isRunning();
  }

  /** Seconds a shutdown waits for running builds to save their log and result. */
  static final int STOP_SECONDS = 30;

  /** Runs while every bean still works, so interrupted builds can save their log and result. */
  void onShutdown(@Observes ShutdownEvent event) {
    stop();
  }

  /**
   * Interrupts the running builds and waits for them to finish their cleanup. Not synchronized: a
   * build's cleanup takes this object's lock to save its result.
   */
  @PreDestroy
  void stop() {
    stopping = true;
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(STOP_SECONDS, TimeUnit.SECONDS)) {
        Log.warnf("Builds still running after %d seconds of shutdown", STOP_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
