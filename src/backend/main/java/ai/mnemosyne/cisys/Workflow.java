/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A CI workflow, read from {@value #PATH} in a project's repository.
 *
 * <pre>
 * name: main branch
 *
 * container: alpine:3.23
 *
 * environment:
 *   - name: MODE
 *     value: test
 *
 * jobs:
 *   - job: Build and test
 *     environment:
 *       - name: PROFILE
 *         value: ci
 *     steps:
 *       - name: Start
 *         environment:
 *           - name: VERBOSE
 *             value: "1"
 *         run: make platform-start
 *       - name: Wait for 8180
 *         wait: 8180
 * </pre>
 *
 * <p>A step either runs a shell command ({@code run}) or waits until a TCP port accepts connections
 * ({@code wait}). A build turns the workflow into a Containerfile, see {@link
 * #containerfile(String)}.
 */
public record Workflow(
    String name,
    String container,
    List<Variable> environment,
    List<Dependency> dependencies,
    List<Job> jobs,
    Map<String, List<Map<String, String>>> reports,
    List<Artifact> artifacts) {
  /**
   * Where the steps read {@link #environment()} from. Root writes it before the checkout and makes
   * it read-only, so a step cannot add variables for the steps after it.
   */
  static final String ENVIRONMENT_FILE = "/etc/cisys-environment";

  static final int MAX_ENVIRONMENT = 100_000;

  /** Where the workflow lives, relative to the repository root. */
  public static final String PATH = ".cisys/workflows/ci.yml";

  static final int MAX_BYTES = 64 * 1024;
  static final int MAX_NAME = 200;
  static final int MAX_COMMAND = 4000;

  /** The most reports one workflow can ask for. */
  static final int MAX_REPORTS = 20;

  /** A report type key under {@code reports}, such as {@code list}. */
  static final String REPORT_TYPE = "[a-z][a-z0-9-]{0,49}";

  /** The most entries under {@code artifacts}. */
  static final int MAX_ARTIFACTS = 20;

  /** The longest artifact path, in the workflow and in the archive. */
  static final int MAX_ARTIFACT_PATH = 1000;

  /**
   * Relative, without shell characters, {@code ..} or empty segments, and {@code *} only in the
   * last segment, so the collect script can expand it unquoted.
   */
  static final String ARTIFACT_PATH =
      "(?!-)(?!.*(^|/)\\.\\.?(/|$))(?!.*//)([A-Za-z0-9._+@-]+/)*[A-Za-z0-9._+@*-]+";

  /** Where the build mounts the directory the collect script writes the archive to. */
  static final String ARTIFACT_DIR = "/run/cisys-artifacts";

  /** The archive the collect script writes in {@link #ARTIFACT_DIR}. */
  static final String ARTIFACT_ARCHIVE = "artifacts.tar";

  /** The collect script, written by root so the steps cannot change it. */
  static final String COLLECT = "/etc/cisys-collect";

  /**
   * One entry of the workflow's {@code artifacts}: a file or directory under {@code /workspace},
   * with {@code *} allowed in the last segment.
   */
  public record Artifact(String name, String path) {}

  /** Whether the workflow lists artifacts. */
  public boolean hasArtifacts() {
    return artifacts != null && !artifacts.isEmpty();
  }

  /** One entry of an {@code environment} list. */
  public record Variable(String name, String value) {}

  /**
   * One entry of the workflow's {@code dependencies} list: {@code value} is the package to install,
   * {@code name} labels it in the log. Only the workflow has dependencies, not jobs or steps.
   */
  public record Dependency(String name, String value) {}

  /** {@code environment} applies to this job's steps, over the workflow's. */
  public record Job(String job, List<Variable> environment, List<Step> steps) {}

  /**
   * Exactly one of {@code run} and {@code wait} is set; {@code wait} is held in {@link #port()}, as
   * records cannot have a {@code wait} component. {@code environment} applies to this {@code run}
   * step only, over the job's.
   */
  public record Step(
      String name, List<Variable> environment, String run, @JsonProperty("wait") Integer port) {
    public boolean isRun() {
      return run != null;
    }
  }

  private static final ObjectMapper MAPPER =
      JsonMapper.builder(new YAMLFactory())
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .build();

  /** Reads the workflow from a checkout, or returns null when the repository has none. */
  public static Workflow load(Path checkout) throws IOException {
    String text = read(checkout);
    return text == null ? null : parse(text);
  }

  /**
   * The workflow file's text, unparsed, or null when the repository has none. Builds store it as
   * they read it.
   */
  public static String read(Path checkout) throws IOException {
    // NOFOLLOW_LINKS covers only the last name, so each directory on the way is checked too.
    Path file = checkout;
    for (Path part : Path.of(PATH)) {
      file = file.resolve(part.toString());
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      if (Files.isSymbolicLink(file)) {
        throw new InvalidWorkflowException(PATH + " must not be a symbolic link");
      }
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new InvalidWorkflowException(PATH + " must be a regular file");
    }
    if (Files.size(file) > MAX_BYTES) {
      throw new InvalidWorkflowException(PATH + " is larger than " + MAX_BYTES + " bytes");
    }
    String text;
    try {
      text = Files.readString(file);
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new InvalidWorkflowException(PATH + " is not valid UTF-8");
    }
    // PostgreSQL text cannot hold NUL characters.
    if (text.indexOf('\0') >= 0) {
      throw new InvalidWorkflowException(PATH + " contains a NUL character");
    }
    return text;
  }

  /** Parses and validates a workflow document. */
  public static Workflow parse(String yaml) {
    if (yaml == null || yaml.isBlank()) {
      throw new InvalidWorkflowException("The workflow is empty");
    }
    if (yaml.length() > MAX_BYTES) {
      throw new InvalidWorkflowException("The workflow is larger than " + MAX_BYTES + " bytes");
    }
    Workflow workflow;
    try {
      workflow = MAPPER.readValue(yaml, Workflow.class);
    } catch (UnrecognizedPropertyException e) {
      throw new InvalidWorkflowException("Unknown key '" + e.getPropertyName() + "'" + location(e));
    } catch (JsonProcessingException e) {
      throw new InvalidWorkflowException(e.getOriginalMessage() + location(e));
    }
    workflow.validate();
    return workflow;
  }

  private static String location(JsonProcessingException e) {
    return e.getLocation() == null ? "" : " at line " + e.getLocation().getLineNr();
  }

  private void validate() {
    requireName(name, "name");
    validateEnvironment(environment, "environment");
    validateDependencies(dependencies, "dependencies");
    if (container == null || container.isBlank()) {
      throw new InvalidWorkflowException("container is required");
    }
    if (!container.matches(AllowedImage.PATTERN)) {
      throw new InvalidWorkflowException("container is not a valid image reference");
    }
    validateReports(reports);
    validateArtifacts(artifacts);
    if (jobs == null || jobs.isEmpty()) {
      throw new InvalidWorkflowException("At least one job is required");
    }
    for (int j = 0; j < jobs.size(); j++) {
      Job job = jobs.get(j);
      String where = "jobs[" + j + "]";
      if (job == null) {
        throw new InvalidWorkflowException(where + " is empty");
      }
      requireName(job.job(), where + ".job");
      validateEnvironment(job.environment(), where + ".environment");
      if (job.steps() == null || job.steps().isEmpty()) {
        throw new InvalidWorkflowException(where + " needs at least one step");
      }
      for (int s = 0; s < job.steps().size(); s++) {
        Step step = job.steps().get(s);
        String at = where + ".steps[" + s + "]";
        if (step == null) {
          throw new InvalidWorkflowException(at + " is empty");
        }
        requireName(step.name(), at + ".name");
        validateEnvironment(step.environment(), at + ".environment");
        if ((step.run() == null) == (step.port() == null)) {
          throw new InvalidWorkflowException(at + " needs exactly one of run or wait");
        }
        if (step.run() != null && (step.run().isBlank() || step.run().length() > MAX_COMMAND)) {
          throw new InvalidWorkflowException(
              at + ".run must be a command of at most " + MAX_COMMAND + " characters");
        }
        if (step.port() != null && (step.port() < 1 || step.port() > 65535)) {
          throw new InvalidWorkflowException(at + ".wait must be a port between 1 and 65535");
        }
        if (step.port() != null && step.environment() != null) {
          throw new InvalidWorkflowException(at + ".environment applies to run steps only");
        }
      }
    }
  }

  /**
   * Each report type key holds a list of reports; every report has a {@code name}, unique in the
   * workflow as it labels the report's tab, and any further options are strings.
   */
  private static void validateReports(Map<String, List<Map<String, String>>> reports) {
    if (reports == null) {
      return;
    }
    Set<String> names = new HashSet<>();
    for (Map.Entry<String, List<Map<String, String>>> e : reports.entrySet()) {
      String type = e.getKey();
      if (type == null || !type.matches(REPORT_TYPE)) {
        throw new InvalidWorkflowException("reports." + type + " is not a valid report type");
      }
      List<Map<String, String>> list = e.getValue();
      if (list == null || list.isEmpty()) {
        throw new InvalidWorkflowException("reports." + type + " needs at least one report");
      }
      for (int i = 0; i < list.size(); i++) {
        String at = "reports." + type + "[" + i + "]";
        Map<String, String> report = list.get(i);
        if (report == null) {
          throw new InvalidWorkflowException(at + " is empty");
        }
        requireName(report.get("name"), at + ".name");
        for (Map.Entry<String, String> option : report.entrySet()) {
          if (option.getValue() == null || option.getValue().length() > MAX_COMMAND) {
            throw new InvalidWorkflowException(
                at
                    + "."
                    + option.getKey()
                    + " must be text of at most "
                    + MAX_COMMAND
                    + " characters");
          }
        }
        if (!names.add(report.get("name"))) {
          throw new InvalidWorkflowException(at + ".name is used by another report");
        }
        if (names.size() > MAX_REPORTS) {
          throw new InvalidWorkflowException("At most " + MAX_REPORTS + " reports are allowed");
        }
      }
    }
  }

  private static void validateArtifacts(List<Artifact> artifacts) {
    if (artifacts == null) {
      return;
    }
    if (artifacts.isEmpty()) {
      throw new InvalidWorkflowException("artifacts needs at least one entry");
    }
    if (artifacts.size() > MAX_ARTIFACTS) {
      throw new InvalidWorkflowException("At most " + MAX_ARTIFACTS + " artifacts are allowed");
    }
    Set<String> names = new HashSet<>();
    for (int i = 0; i < artifacts.size(); i++) {
      Artifact a = artifacts.get(i);
      String at = "artifacts[" + i + "]";
      if (a == null) {
        throw new InvalidWorkflowException(at + " is empty");
      }
      requireName(a.name(), at + ".name");
      if (!names.add(a.name())) {
        throw new InvalidWorkflowException(at + ".name " + a.name() + " appears more than once");
      }
      if (a.path() == null) {
        throw new InvalidWorkflowException(at + ".path is required");
      }
      if (a.path().length() > MAX_ARTIFACT_PATH || !a.path().matches(ARTIFACT_PATH)) {
        throw new InvalidWorkflowException(
            at
                + ".path '"
                + a.path()
                + "' must be a relative path of letters, digits and . _ + @ -, with * in the"
                + " last part only");
      }
    }
  }

  private static void validateEnvironment(List<Variable> environment, String where) {
    if (environment == null) {
      return;
    }
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < environment.size(); i++) {
      Variable v = environment.get(i);
      String at = where + "[" + i + "]";
      if (v == null) {
        throw new InvalidWorkflowException(at + " is empty");
      }
      if (v.name() == null) {
        throw new InvalidWorkflowException(at + ".name is required");
      }
      if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]{0,199}")) {
        throw new InvalidWorkflowException(
            at
                + ".name '"
                + v.name()
                + "' must be a variable name: letters, digits and _, not starting with a digit");
      }
      if (!seen.add(v.name())) {
        throw new InvalidWorkflowException(at + ".name " + v.name() + " appears more than once");
      }
      if (v.value() == null) {
        throw new InvalidWorkflowException(at + ".value is required; use \"\" for an empty one");
      }
      if (v.value().length() > MAX_COMMAND) {
        throw new InvalidWorkflowException(
            at + ".value is longer than " + MAX_COMMAND + " characters");
      }
    }
    if (exports(environment).length() > MAX_ENVIRONMENT) {
      throw new InvalidWorkflowException(where + " is too large");
    }
  }

  /**
   * A package name, optionally with a version as the package manager writes it, such as {@code
   * openjdk-25-jdk} or {@code git=2.47.3-0+deb13u1}.
   */
  static final String PACKAGE = "[A-Za-z0-9][A-Za-z0-9+._:=~@-]{0,199}";

  private static void validateDependencies(List<Dependency> dependencies, String where) {
    if (dependencies == null) {
      return;
    }
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < dependencies.size(); i++) {
      Dependency d = dependencies.get(i);
      String at = where + "[" + i + "]";
      if (d == null) {
        throw new InvalidWorkflowException(at + " is empty");
      }
      requireName(d.name(), at + ".name");
      if (!seen.add(d.name())) {
        throw new InvalidWorkflowException(at + ".name " + d.name() + " appears more than once");
      }
      if (d.value() == null) {
        throw new InvalidWorkflowException(at + ".value is required");
      }
      if (!d.value().matches(PACKAGE)) {
        throw new InvalidWorkflowException(at + ".value '" + d.value() + "' is not a package name");
      }
    }
  }

  /** How long a {@code wait} step waits for its port, in seconds. */
  static final int WAIT_SECONDS = 300;

  static final int MAX_CONTAINERFILE = 200_000;

  /** The user the checkout and the steps belong to. */
  static final String BUILD_USER = "65534:65534";

  /** The directory in the build context that holds the checkout. */
  static final String WORKSPACE = "workspace";

  /**
   * The directory in the build context that holds the project's cache, see {@link ProjectCache}.
   */
  static final String CACHE = "cache";

  /** The directory, writable by the steps, that the last step moves the cache into. */
  static final String SAVED_CACHE_PARENT = "/cisys-cache";

  /** Where the last step leaves the cache for the runner to take out of the image. */
  static final String SAVED_CACHE = SAVED_CACHE_PARENT + "/project";

  /** The project's cache directory in the build, {@code $HOME/.cisys/<project name>}. */
  static String cacheDir(String projectName) {
    if (!ProjectResource.NAME.matcher(projectName).matches()) {
      throw new IllegalArgumentException(ProjectResource.NAME_RULE + ": " + projectName);
    }
    return "/tmp/.cisys/" + projectName;
  }

  /**
   * The Containerfile that builds this workflow's image from the local image {@code baseId}, which
   * {@link ImageReference#resolve} found for {@code container}; see {@link #containerfile(String,
   * int)}.
   */
  public String containerfile(String baseId) {
    return containerfile(baseId, false, WAIT_SECONDS);
  }

  /**
   * As {@link #containerfile(String)}, with the steps given the egress proxy when {@code network}.
   */
  public String containerfile(String baseId, boolean network) {
    return containerfile(baseId, network, null);
  }

  /**
   * As {@link #containerfile(String, boolean)}, with the cache of the project {@code cacheName}, or
   * none when null: {@value #CACHE} in the build context is copied to {@link #cacheDir} and, when
   * every step passed, moved to {@value #SAVED_CACHE}.
   */
  public String containerfile(String baseId, boolean network, String cacheName) {
    return containerfile(baseId, network, WAIT_SECONDS, true, true, cacheName);
  }

  String containerfile(String baseId, int waitSeconds) {
    return containerfile(baseId, false, waitSeconds);
  }

  /** Where the build mounts the directory holding the {@link EgressProxy} socket. */
  static final String PROXY_DIR = "/run/cisys";

  /** The loopback port in the step where {@code socat} forwards to the proxy socket. */
  static final int PROXY_PORT = 3128;

  /**
   * Starts the forwarder and points the usual proxy settings at it. The step still has no network:
   * the forwarded socket is its only way out, and the proxy decides what it may reach. The socket
   * is tried once first, so a step that cannot reach it, for example because SELinux denies the
   * connection, fails with the reason instead of with errors from its own tools. Maven's resolver
   * ignores the Java proxy properties unless {@code aether.connector.http.useSystemProperties} is
   * set.
   */
  static final String PROXY =
      """
      err=$(socat -u OPEN:/dev/null UNIX-CONNECT:%2$s/%3$s 2>&1) || { echo "==> Network proxy unreachable: %2$s/%3$s: ${err##*: }"; [ -z "${err##*denied*}" ] && echo '==> The host denied the connection; on an SELinux host the build container may not connect to the proxy socket'; exit 1; }
      socat TCP-LISTEN:%1$d,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:%2$s/%3$s 2>/dev/null &
      i=0; until grep -Eqs '^ *[0-9]+: 0100007F:%4$04X [0-9A-F]+:[0-9A-F]+ 0A ' /proc/net/tcp; do i=$((i+1)); if [ "$i" -ge 100 ]; then echo '==> Network proxy did not start'; exit 1; fi; sleep 0.1; done
      unset ALL_PROXY all_proxy HTTP_PROXY http_proxy FTP_PROXY ftp_proxy NO_PROXY no_proxy
      export HTTPS_PROXY=http://127.0.0.1:%1$d https_proxy=http://127.0.0.1:%1$d
      export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=%1$d -Daether.connector.http.useSystemProperties=true${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"
      echo '==> Network: HTTPS_PROXY=http://127.0.0.1:%1$d; the step has no DNS, so a tool that ignores the proxy fails with an unknown host'
      """
          .formatted(PROXY_PORT, PROXY_DIR, EgressProxy.SOCKET, PROXY_PORT);

  /**
   * Builds an image from the base image, named by its ID so the build uses exactly the image that
   * was resolved:
   *
   * <ol>
   *   <li>installs {@link #TOOLS} (Git, Make, Podman and Podman Compose) as root when the image
   *       does not have them,
   *   <li>copies the build's checkout from {@value #WORKSPACE} in the build context into {@code
   *       /workspace}, owned by an unprivileged user; the working tree is on the branch, with its
   *       {@code .git},
   *   <li>runs the steps as that user, without network, with the workflow's, the job's and the
   *       step's {@code environment} exported, the innermost winning. A variable is unset again
   *       when its scope ends: a step's variables when the step ends, a job's when the job ends. A
   *       step cannot change the variables of the steps after it, as each step starts a new shell
   *       from the read-only {@link #ENVIRONMENT_FILE}.
   * </ol>
   *
   * {@code dependencies} are installed as root, with network, with the package manager the image
   * has, before the checkout is copied.
   *
   * <p>Each {@code run} step is its own {@code RUN}. A job with a {@code wait} step runs as a
   * single {@code RUN}, because processes started in the background end with the {@code RUN} that
   * started them. The first failing step stops the build with its exit code. The image build does
   * not fetch the repository, so it needs no Git token.
   *
   * <p>With {@code network}, {@code socat} is installed with the tools and every step starts with
   * {@link #PROXY}.
   */
  String containerfile(String baseId, boolean network, int waitSeconds) {
    return containerfile(baseId, network, waitSeconds, true, true, null);
  }

  /**
   * What a build may start {@code FROM}: an image ID, or the runner's tag of one for a single build
   * ({@code BuildRunner#baseTag}), which also names the builder's working containers.
   */
  static final String BASE = "[0-9a-f]{64}|localhost/cisys-build-[0-9]+-base";

  /**
   * The first Docker legacy-builder stage: installs the tools and the workflow's dependencies with
   * network. The second stage builds from this image with network off, because that builder has no
   * per-{@code RUN} network flag.
   */
  String prepareContainerfile(String baseId, boolean network) {
    if (!baseId.matches(BASE)) {
      throw new IllegalArgumentException("Not an image ID: " + baseId);
    }
    StringBuilder f = new StringBuilder();
    f.append("FROM ").append(baseId).append('\n');
    f.append("USER 0\n");
    run(f, false, "", installTools(network), "tools");
    install(f, false, dependencies);
    checkSize(f);
    return f.toString();
  }

  /**
   * The second Docker legacy-builder stage. The build disables network for every {@code RUN}, so
   * this file has no per-{@code RUN} flags.
   */
  String stepsContainerfile(String preparedImage, boolean network, int waitSeconds) {
    return containerfile(preparedImage, network, waitSeconds, false, false, null);
  }

  String stepsContainerfile(String preparedImage, boolean network) {
    return stepsContainerfile(preparedImage, network, null);
  }

  /**
   * As {@link #stepsContainerfile(String, boolean)}, with the cache of the project {@code
   * cacheName}.
   */
  String stepsContainerfile(String preparedImage, boolean network, String cacheName) {
    return containerfile(preparedImage, network, WAIT_SECONDS, false, false, cacheName);
  }

  private String containerfile(
      String baseId,
      boolean network,
      int waitSeconds,
      boolean installToolsAndDependencies,
      boolean perRunNetworkFlags,
      String cacheName) {
    String cache = cacheName == null ? null : cacheDir(cacheName);
    if (installToolsAndDependencies && !baseId.matches(BASE)) {
      throw new IllegalArgumentException("Not an image ID: " + baseId);
    }
    StringBuilder f = new StringBuilder();
    f.append("FROM ").append(baseId).append('\n');
    f.append("USER 0\n");
    // Podman reads heredocs, so its file is readable shell; Docker's legacy builder does not, so
    // its files keep the exec form.
    boolean readable = perRunNetworkFlags;
    if (installToolsAndDependencies) {
      run(f, readable, "", installTools(network), "tools");
      install(f, readable, dependencies);
    }
    String flags = perRunNetworkFlags ? "--network=none " : "";
    String protect = " && chown 0:0 " + ENVIRONMENT_FILE + " && chmod 444 " + ENVIRONMENT_FILE;
    if (readable) {
      // A quoted heredoc: neither the Containerfile parser nor the shell interprets the variables.
      String exports = exports();
      String end = delimiter("ENVIRONMENT", exports);
      run(
          f,
          true,
          flags,
          "cat > "
              + ENVIRONMENT_FILE
              + " <<'"
              + end
              + "'\n"
              + exports
              + end
              + "\n"
              + protect.substring(4)
              + "\n",
          "environment");
    } else {
      // The variables travel as an argument, so neither the Containerfile parser nor a shell
      // interprets them here.
      run(
          f,
          false,
          flags,
          "printf '%s' \"$1\" > " + ENVIRONMENT_FILE + protect,
          "environment",
          exports());
    }
    if (hasArtifacts()) {
      String collect = collectScript();
      String lock = " && chown 0:0 " + COLLECT + " && chmod 555 " + COLLECT;
      if (readable) {
        String end = delimiter("COLLECT", collect);
        run(
            f,
            true,
            flags,
            "cat > " + COLLECT + " <<'" + end + "'\n" + collect + end + "\n" + lock.substring(4),
            "artifacts");
      } else {
        run(f, false, flags, "printf '%s' \"$1\" > " + COLLECT + lock, "artifacts", collect);
      }
    }
    run(
        f,
        readable,
        "",
        "mkdir -p /workspace && chown "
            + BUILD_USER
            + " /workspace"
            + (cache == null
                ? ""
                : " && mkdir -p "
                    + quote(cache)
                    + " "
                    + SAVED_CACHE_PARENT
                    + " && chown "
                    + BUILD_USER
                    + " /tmp/.cisys "
                    + quote(cache)
                    + " "
                    + SAVED_CACHE_PARENT),
        "workspace");
    f.append("USER ").append(BUILD_USER).append('\n');
    f.append("WORKDIR /workspace\n");
    f.append("ENV HOME=/tmp GIT_TERMINAL_PROMPT=0\n");
    f.append("COPY --chown=")
        .append(BUILD_USER)
        .append(' ')
        .append(WORKSPACE)
        .append(" /workspace\n");
    if (cache != null) {
      // The name has no spaces or quotes, see cacheDir, so the destination needs no quoting here.
      f.append("COPY --chown=")
          .append(BUILD_USER)
          .append(' ')
          .append(CACHE)
          .append(' ')
          .append(cache)
          .append('\n');
    }
    List<String> runs = runs(waitSeconds, hasArtifacts());
    for (int i = 0; i < runs.size(); i++) {
      run(
          f,
          readable,
          flags,
          ". "
              + ENVIRONMENT_FILE
              + " || exit 1\n"
              + (network ? PROXY : "")
              + runs.get(i)
              + (i == runs.size() - 1
                  ? (hasArtifacts() ? COLLECT_CALL + "\n" : "") + saveCache(cache) + CLEAN_UP
                  : ""),
          "step");
    }
    checkSize(f);
    return f.toString();
  }

  /**
   * Ends the last step. The builder saves what each {@code RUN} wrote as an image layer, in its
   * temporary directory on the host, and the build image is removed afterwards; deleting the files
   * the steps wrote keeps a large build, such as a local Maven repository, from filling the host's
   * disk with a layer nobody reads.
   */
  static final String CLEAN_UP =
      "echo '==> Removing the files the steps wrote'\n"
          + "find /workspace /tmp /var/tmp -mindepth 1 -delete 2>/dev/null\n"
          + "exit 0\n";

  /**
   * Moves the cache directory to {@value #SAVED_CACHE}, before {@link #CLEAN_UP} deletes it. Only
   * the last step runs it, so only a build whose steps all passed saves its cache.
   */
  static String saveCache(String cache) {
    return cache == null
        ? ""
        : "echo '==> Saving the cache'\nmv "
            + quote(cache)
            + " "
            + SAVED_CACHE
            + " || echo '==> Could not save the cache'\n";
  }

  private static void checkSize(StringBuilder file) {
    if (file.length() > MAX_CONTAINERFILE) {
      throw new InvalidWorkflowException("The workflow is too large to run");
    }
  }

  /**
   * The commands every build image contains; each is installed from the package of the same name
   * when missing.
   */
  static final List<String> TOOLS = List.of("git", "make", "podman", "podman-compose");

  /**
   * Shell code that finds the image's package manager, sets {@code pm} to it (empty when there is
   * none) and defines {@code install PACKAGE...} and {@code installed PACKAGE...}.
   *
   * <p>{@code install} always reads fresh package metadata, never the image's cached copy, and
   * installs the newest version; a package the image already has is upgraded to it. {@code
   * installed} logs the version each package ended up at. The package manager's own output, such as
   * the notes a package prints after its installation, is only shown when the installation fails.
   */
  private static final String PACKAGE_MANAGER =
      """
      . /etc/os-release 2>/dev/null
      for pm in apk apt-get dnf microdnf yum zypper ''; do [ -z "$pm" ] || ! command -v "$pm" >/dev/null 2>&1 || break; done
      install() {
        packages "$@" > /tmp/.cisys-install.log 2>&1
        rc=$?
        [ "$rc" -eq 0 ] || cat /tmp/.cisys-install.log
        rm -f /tmp/.cisys-install.log
        return "$rc"
      }
      packages() {
        case "$pm" in
          apk) apk add --no-cache --upgrade "$@" ;;
          apt-get) apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ca-certificates "$@" && rm -rf /var/lib/apt/lists/* ;;
          dnf) dnf install -y -q --refresh "$@" && dnf upgrade -y -q "$@" && dnf clean all ;;
          microdnf) microdnf install -y --refresh "$@" && microdnf upgrade -y "$@" && microdnf clean all ;;
          yum) yum clean expire-cache -q && yum install -y -q "$@" && yum update -y -q "$@" && yum clean all ;;
          zypper) zypper --non-interactive refresh && zypper --non-interactive install "$@" && zypper --non-interactive update "$@" && zypper clean ;;
          *) return 1 ;;
        esac
      }
      installed() {
        for p in "$@"; do
          n=${p%%[=<>]*}
          case "$pm" in
            apk) v=$(apk list --installed "$n" 2>/dev/null | cut -d' ' -f1 | head -n 1) ;;
            apt-get) v=$(dpkg-query -W -f='${Package} ${Version}' "${n%%:*}" 2>/dev/null) ;;
            *) v=$(rpm -q "$n" 2>/dev/null || rpm -q --whatprovides "$n" 2>/dev/null) ;;
          esac
          echo "==> Installed: ${v:-$p}"
        done
      }
      """;

  /** {@link #TOOLS} plus {@code socat}, which forwards a step's proxy port to the proxy socket. */
  static final List<String> NETWORK_TOOLS =
      java.util.stream.Stream.concat(TOOLS.stream(), java.util.stream.Stream.of("socat")).toList();

  private static final String INSTALL_TOOLS = installTools(TOOLS);
  private static final String INSTALL_NETWORK_TOOLS = installTools(NETWORK_TOOLS);

  /** The tools this workflow needs: with network {@code socat}, with artifacts {@code tar}. */
  private String installTools(boolean network) {
    if (!hasArtifacts()) {
      return network ? INSTALL_NETWORK_TOOLS : INSTALL_TOOLS;
    }
    List<String> tools = new java.util.ArrayList<>(network ? NETWORK_TOOLS : TOOLS);
    tools.add("tar");
    return installTools(tools);
  }

  private static String installTools(List<String> tools) {
    return PACKAGE_MANAGER
        + """
        echo "==> Container: ${PRETTY_NAME:-unknown}, package manager: ${pm:-none}"
        missing=
        for tool in %1$s; do command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"; done
        [ -z "$missing" ] && exit 0
        [ -n "$pm" ] || { echo '==> Cannot install%2$s: the container has no supported package manager'; exit 1; }
        echo "==> Installing$missing with $pm"
        install $missing || { rc=$?; echo "==> Could not install$missing"; exit "$rc"; }
        installed $missing
        for tool in %1$s; do command -v "$tool" >/dev/null 2>&1 || { echo "==> $tool is missing after the installation"; exit 1; }; done
        """
            .formatted(String.join(" ", tools), String.join(", ", tools).replaceFirst("^", " "));
  }

  /**
   * Arguments: name and value of each dependency, in turn. The values are package names, see {@link
   * #PACKAGE}.
   */
  private static final String INSTALL_DEPENDENCIES =
      PACKAGE_MANAGER
          + """
          [ -n "$pm" ] || { echo '==> Cannot install dependencies: the container has no supported package manager'; exit 1; }
          packages=
          while [ "$#" -gt 1 ]; do echo "==> Dependency: $1 ($2) with $pm"; packages="$packages $2"; shift 2; done
          install $packages || { rc=$?; echo "==> Could not install$packages"; exit "$rc"; }
          installed $packages
          """;

  private static void install(StringBuilder f, boolean readable, List<Dependency> dependencies) {
    if (dependencies == null || dependencies.isEmpty()) {
      return;
    }
    List<String> args = new java.util.ArrayList<>();
    for (Dependency d : dependencies) {
      args.add(d.name());
      args.add(d.value());
    }
    run(f, readable, "", INSTALL_DEPENDENCIES, "dependencies", args.toArray(String[]::new));
  }

  /**
   * Appends a {@code RUN} of {@code script} with the positional arguments {@code args}. {@code
   * readable} writes a quoted heredoc, which reads as plain shell and is passed on as it is;
   * otherwise the exec (JSON) form, for builders without heredocs. Either way neither the
   * Containerfile parser nor a shell interprets the script or the arguments on the way in.
   */
  private static void run(
      StringBuilder f, boolean readable, String flags, String script, String name, String... args) {
    f.append("RUN ").append(flags);
    if (!readable) {
      List<String> exec = new java.util.ArrayList<>(List.of("sh", "-c", script, name));
      exec.addAll(List.of(args));
      f.append(exec(exec.toArray(String[]::new))).append('\n');
      return;
    }
    StringBuilder body = new StringBuilder();
    if (args.length > 0) {
      body.append("set --");
      for (String arg : args) {
        body.append(' ').append(quote(arg));
      }
      body.append('\n');
    }
    body.append(script);
    if (body.charAt(body.length() - 1) != '\n') {
      body.append('\n');
    }
    String end = delimiter("CISYS", body.toString());
    f.append("<<'").append(end).append("'\n").append(body).append(end).append('\n');
  }

  /**
   * {@code base}, or {@code base} with a number, so that no line of {@code body} is equal to it.
   */
  static String delimiter(String base, String body) {
    java.util.Set<String> lines = new java.util.HashSet<>(body.lines().toList());
    String end = base;
    for (int i = 1; lines.contains(end); i++) {
      end = base + "_" + i;
    }
    return end;
  }

  private static final JsonMapper JSON = new JsonMapper();

  /**
   * The exec (JSON) form, so nothing in the arguments is interpreted by the Containerfile parser or
   * a shell.
   */
  private static String exec(String... args) {
    try {
      return JSON.writeValueAsString(args);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The environment and all steps as one shell script, as the Containerfile's {@code RUN}s run them
   * in order.
   */
  String script(int waitSeconds) {
    // Each RUN is a new shell, so each runs in a subshell here.
    StringBuilder s = new StringBuilder(exports());
    for (String run : runs(waitSeconds, false)) {
      s.append("(\n").append(run).append(") || exit $?\n");
    }
    return s.toString();
  }

  /** Runs the collect script; it always exits 0, so the caller's exit code is kept. */
  static final String COLLECT_CALL = "sh " + COLLECT;

  /**
   * The collect script: writes the listed paths that exist to {@link #ARTIFACT_ARCHIVE} with {@code
   * tar}, which stores symbolic links as links instead of following them. Paths get a {@code ./}
   * prefix so an expansion cannot start with {@code -}.
   */
  String collectScript() {
    StringBuilder s = new StringBuilder();
    s.append("cd /workspace || exit 0\n");
    s.append("echo '==> Collecting artifacts'\n");
    s.append("set --\n");
    s.append("for p in");
    for (Artifact a : artifacts) {
      s.append(" ./").append(a.path());
    }
    s.append("; do\n");
    s.append("  if [ -e \"$p\" ] || [ -L \"$p\" ]; then set -- \"$@\" \"$p\";");
    s.append(" else echo \"==> Artifact not found: ${p#./}\"; fi\n");
    s.append("done\n");
    s.append("[ \"$#\" -gt 0 ] || exit 0\n");
    s.append("rm -f ").append(ARTIFACT_DIR).append('/').append(ARTIFACT_ARCHIVE).append('\n');
    s.append("tar -cf ")
        .append(ARTIFACT_DIR)
        .append('/')
        .append(ARTIFACT_ARCHIVE)
        .append(" \"$@\" 2>/dev/null || echo '==> Could not collect every artifact'\n");
    s.append("exit 0\n");
    return s.toString();
  }

  /** The scripts of the Containerfile's step {@code RUN}s. */
  List<String> runs(int waitSeconds) {
    return runs(waitSeconds, false);
  }

  /** As above; with {@code collect}, a failing step runs the collect script before it exits. */
  List<String> runs(int waitSeconds, boolean collect) {
    String onFailure = collect ? COLLECT_CALL + "; " : "";
    List<String> runs = new java.util.ArrayList<>();
    for (Job job : jobs) {
      String header = "echo " + quote("==> Job: " + job.job()) + "\n";
      // Each RUN of the job sets the job's variables again, as a RUN starts a new shell.
      String jobEnvironment = exports(job.environment());
      if (job.steps().stream().anyMatch(step -> !step.isRun())) {
        StringBuilder s = new StringBuilder(jobEnvironment + header);
        for (Step step : job.steps()) {
          s.append(step(step, waitSeconds, onFailure));
        }
        runs.add(s.toString());
      } else {
        for (int i = 0; i < job.steps().size(); i++) {
          Step step = job.steps().get(i);
          runs.add(jobEnvironment + (i == 0 ? header : "") + step(step, waitSeconds, onFailure));
        }
      }
    }
    return runs;
  }

  /**
   * A {@code run} step runs in its own shell from {@code /workspace}. A {@code wait} step polls the
   * kernel's TCP tables for a listening socket on the port, so the image needs only {@code sh} and
   * {@code grep}.
   */
  private static String step(Step step, int waitSeconds, String onFailure) {
    StringBuilder s = new StringBuilder();
    s.append("echo ").append(quote("==> Step: " + step.name())).append('\n');
    if (step.isRun()) {
      // The step's variables are set in a subshell, so they do not reach the job's later steps.
      String own = exports(step.environment());
      s.append(own.isEmpty() ? "" : "(\n" + own + "exec ")
          .append("sh -c ")
          .append(quote(step.run()))
          .append(own.isEmpty() ? "" : "\n)")
          .append(" || { rc=$?; echo ")
          .append(quote("==> Step failed: " + step.name() + ", exit code"))
          .append(" \"$rc\"; ")
          .append(onFailure)
          .append("exit \"$rc\"; }\n");
    } else {
      String port = String.format("%04X", step.port());
      s.append("i=0; until grep -Eqs '^ *[0-9]+: [0-9A-F]+:")
          .append(port)
          .append(" [0-9A-F]+:[0-9A-F]+ 0A ' /proc/net/tcp /proc/net/tcp6; do")
          .append(" i=$((i+1)); if [ \"$i\" -ge ")
          .append(waitSeconds)
          .append(" ]; then echo ")
          .append(
              quote(
                  "==> Step failed: "
                      + step.name()
                      + ", port "
                      + step.port()
                      + " not listening after "
                      + waitSeconds
                      + " seconds"))
          .append("; ")
          .append(onFailure)
          .append("exit 1; fi; sleep 1; done\n");
    }
    return s.toString();
  }

  /**
   * The workflow's {@link #environment()} as shell {@code export} lines, in the order of the file.
   */
  String exports() {
    return exports(environment);
  }

  private static String exports(List<Variable> environment) {
    StringBuilder s = new StringBuilder();
    if (environment != null) {
      environment.forEach(v ->
          s.append("export ").append(v.name()).append('=').append(quote(v.value())).append('\n'));
    }
    return s.toString();
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static void requireName(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new InvalidWorkflowException(field + " is required");
    }
    if (value.length() > MAX_NAME) {
      throw new InvalidWorkflowException(field + " is longer than " + MAX_NAME + " characters");
    }
  }

  /** The workflow file is not valid; the message is safe to show to the user. */
  public static class InvalidWorkflowException extends IllegalArgumentException {
    public InvalidWorkflowException(String message) {
      super(message);
    }
  }
}
