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
import java.util.List;

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
 * A step either runs a shell command ({@code run}) or waits until a TCP port accepts connections ({@code wait}).
 * A build turns the workflow into a Containerfile, see {@link #containerfile(String)}.
 */
public record Workflow(String name, String container, List<Variable> environment, List<Dependency> dependencies, List<Job> jobs) {
    /**
     * Where the steps read {@link #environment()} from. Root writes it before the checkout and makes it read-only, so a
     * step cannot add variables for the steps after it.
     */
    static final String ENVIRONMENT_FILE = "/etc/cisys-environment";
    static final int MAX_ENVIRONMENT = 100_000;
    /** Where the workflow lives, relative to the repository root. */
    public static final String PATH = ".cisys/workflows/ci.yml";
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_NAME = 200;
    static final int MAX_COMMAND = 4000;

    /** One entry of an {@code environment} list. */
    public record Variable(String name, String value) {}

    /**
     * One entry of the workflow's {@code dependencies} list: {@code value} is the package to install, {@code name}
     * labels it in the log. Only the workflow has dependencies, not jobs or steps.
     */
    public record Dependency(String name, String value) {}

    /** {@code environment} applies to this job's steps, over the workflow's. */
    public record Job(String job, List<Variable> environment, List<Step> steps) {}

    /**
     * Exactly one of {@code run} and {@code wait} is set; {@code wait} is held in {@link #port()}, as records cannot
     * have a {@code wait} component. {@code environment} applies to this {@code run} step only, over the job's.
     */
    public record Step(String name, List<Variable> environment, String run, @JsonProperty("wait") Integer port) {
        public boolean isRun() {
            return run != null;
        }
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder(new YAMLFactory())
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

    /** The workflow file's text, unparsed, or null when the repository has none. Builds store it as they read it. */
    public static String read(Path checkout) throws IOException {
        Path file = checkout.resolve(PATH);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new InvalidWorkflowException(PATH + " must be a regular file");
        if (Files.size(file) > MAX_BYTES) throw new InvalidWorkflowException(PATH + " is larger than " + MAX_BYTES + " bytes");
        String text;
        try {
            text = Files.readString(file);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new InvalidWorkflowException(PATH + " is not valid UTF-8");
        }
        // PostgreSQL text cannot hold NUL characters.
        if (text.indexOf('\0') >= 0) throw new InvalidWorkflowException(PATH + " contains a NUL character");
        return text;
    }

    /** Parses and validates a workflow document. */
    public static Workflow parse(String yaml) {
        if (yaml == null || yaml.isBlank()) throw new InvalidWorkflowException("The workflow is empty");
        if (yaml.length() > MAX_BYTES) throw new InvalidWorkflowException("The workflow is larger than " + MAX_BYTES + " bytes");
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
        if (container == null || container.isBlank()) throw new InvalidWorkflowException("container is required");
        if (!container.matches(AllowedImage.PATTERN)) throw new InvalidWorkflowException("container is not a valid image reference");
        if (jobs == null || jobs.isEmpty()) throw new InvalidWorkflowException("At least one job is required");
        for (int j = 0; j < jobs.size(); j++) {
            Job job = jobs.get(j);
            String where = "jobs[" + j + "]";
            if (job == null) throw new InvalidWorkflowException(where + " is empty");
            requireName(job.job(), where + ".job");
            validateEnvironment(job.environment(), where + ".environment");
            if (job.steps() == null || job.steps().isEmpty()) throw new InvalidWorkflowException(where + " needs at least one step");
            for (int s = 0; s < job.steps().size(); s++) {
                Step step = job.steps().get(s);
                String at = where + ".steps[" + s + "]";
                if (step == null) throw new InvalidWorkflowException(at + " is empty");
                requireName(step.name(), at + ".name");
                validateEnvironment(step.environment(), at + ".environment");
                if ((step.run() == null) == (step.port() == null)) throw new InvalidWorkflowException(at + " needs exactly one of run or wait");
                if (step.run() != null && (step.run().isBlank() || step.run().length() > MAX_COMMAND)) {
                    throw new InvalidWorkflowException(at + ".run must be a command of at most " + MAX_COMMAND + " characters");
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

    private static void validateEnvironment(List<Variable> environment, String where) {
        if (environment == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < environment.size(); i++) {
            Variable v = environment.get(i);
            String at = where + "[" + i + "]";
            if (v == null) throw new InvalidWorkflowException(at + " is empty");
            if (v.name() == null) throw new InvalidWorkflowException(at + ".name is required");
            if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]{0,199}")) {
                throw new InvalidWorkflowException(at + ".name '" + v.name() + "' must be a variable name: letters, digits and _, not starting with a digit");
            }
            if (!seen.add(v.name())) throw new InvalidWorkflowException(at + ".name " + v.name() + " appears more than once");
            if (v.value() == null) throw new InvalidWorkflowException(at + ".value is required; use \"\" for an empty one");
            if (v.value().length() > MAX_COMMAND) throw new InvalidWorkflowException(at + ".value is longer than " + MAX_COMMAND + " characters");
        }
        if (exports(environment).length() > MAX_ENVIRONMENT) throw new InvalidWorkflowException(where + " is too large");
    }

    /** A package name, optionally with a version as the package manager writes it, such as {@code openjdk-25-jdk} or {@code git=2.47.3-0+deb13u1}. */
    static final String PACKAGE = "[A-Za-z0-9][A-Za-z0-9+._:=~@-]{0,199}";

    private static void validateDependencies(List<Dependency> dependencies, String where) {
        if (dependencies == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency d = dependencies.get(i);
            String at = where + "[" + i + "]";
            if (d == null) throw new InvalidWorkflowException(at + " is empty");
            requireName(d.name(), at + ".name");
            if (!seen.add(d.name())) throw new InvalidWorkflowException(at + ".name " + d.name() + " appears more than once");
            if (d.value() == null) throw new InvalidWorkflowException(at + ".value is required");
            if (!d.value().matches(PACKAGE)) throw new InvalidWorkflowException(at + ".value '" + d.value() + "' is not a package name");
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
     * The Containerfile that builds this workflow's image from the local image {@code baseId}, which
     * {@link ImageReference#resolve} found for {@code container}; see {@link #containerfile(String, int)}.
     */
    public String containerfile(String baseId) {
        return containerfile(baseId, WAIT_SECONDS);
    }

    /**
     * Builds an image from the base image, named by its ID so the build uses exactly the image that was resolved:
     * <ol>
     * <li>installs {@link #TOOLS} (Git, Make, Podman and Podman Compose) as root when the image does not have them,</li>
     * <li>copies the build's checkout from {@value #WORKSPACE} in the build context into {@code /workspace}, owned by an
     * unprivileged user; the working tree is on the branch, with its {@code .git},</li>
     * <li>runs the steps as that user, without network, with the workflow's, the job's and the step's
     * {@code environment} exported, the innermost winning. A variable is unset again when its scope ends: a step's
     * variables when the step ends, a job's when the job ends. A step cannot change the variables of the steps after
     * it, as each step starts a new shell from the read-only {@link #ENVIRONMENT_FILE}.</li>
     * </ol>
     * {@code dependencies} are installed as root, with network, with the package manager the image has, before the
     * checkout is copied.
     * <p>
     * Each {@code run} step is its own {@code RUN}. A job with a {@code wait} step runs as a single {@code RUN},
     * because processes started in the background end with the {@code RUN} that started them. The first failing
     * step stops the build with its exit code. The image build does not fetch the repository, so it needs no Git token.
     */
    String containerfile(String baseId, int waitSeconds) {
        if (!baseId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Not an image ID: " + baseId);
        StringBuilder f = new StringBuilder();
        f.append("FROM ").append(baseId).append('\n');
        f.append("USER 0\n");
        f.append("RUN ").append(exec("sh", "-c", INSTALL_TOOLS)).append('\n');
        install(f, dependencies);
        // The variables travel as an argument, so neither the Containerfile parser nor a shell interprets them here.
        f.append("RUN --network=none ").append(exec("sh", "-c", "printf '%s' \"$1\" > " + ENVIRONMENT_FILE
                + " && chown 0:0 " + ENVIRONMENT_FILE + " && chmod 444 " + ENVIRONMENT_FILE, "environment", exports())).append('\n');
        f.append("RUN ").append(exec("sh", "-c", "mkdir -p /workspace && chown " + BUILD_USER + " /workspace")).append('\n');
        f.append("USER ").append(BUILD_USER).append('\n');
        f.append("WORKDIR /workspace\n");
        f.append("ENV HOME=/tmp GIT_TERMINAL_PROMPT=0\n");
        f.append("COPY --chown=").append(BUILD_USER).append(' ').append(WORKSPACE).append(" /workspace\n");
        for (String run : runs(waitSeconds)) {
            f.append("RUN --network=none ").append(exec("sh", "-c", ". " + ENVIRONMENT_FILE + " || exit 1\n" + run)).append('\n');
        }
        if (f.length() > MAX_CONTAINERFILE) throw new InvalidWorkflowException("The workflow is too large to run");
        return f.toString();
    }

    /** The commands every build image contains; each is installed from the package of the same name when missing. */
    static final List<String> TOOLS = List.of("git", "make", "podman", "podman-compose");

    /**
     * Shell code that finds the image's package manager, sets {@code pm} to it (empty when there is none) and defines
     * {@code install PACKAGE...} and {@code installed PACKAGE...}.
     * <p>
     * {@code install} always reads fresh package metadata, never the image's cached copy, and installs the newest
     * version; a package the image already has is upgraded to it. {@code installed} logs the version each package
     * ended up at.
     */
    private static final String PACKAGE_MANAGER = """
            . /etc/os-release 2>/dev/null
            for pm in apk apt-get dnf microdnf yum zypper ''; do [ -z "$pm" ] || ! command -v "$pm" >/dev/null 2>&1 || break; done
            install() {
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

    private static final String INSTALL_TOOLS = PACKAGE_MANAGER + """
            echo "==> Container: ${PRETTY_NAME:-unknown}, package manager: ${pm:-none}"
            missing=
            for tool in %1$s; do command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"; done
            [ -z "$missing" ] && exit 0
            [ -n "$pm" ] || { echo '==> Cannot install%2$s: the container has no supported package manager'; exit 1; }
            echo "==> Installing$missing with $pm"
            install $missing || { rc=$?; echo "==> Could not install$missing"; exit "$rc"; }
            for tool in %1$s; do command -v "$tool" >/dev/null 2>&1 || { echo "==> $tool is missing after the installation"; exit 1; }; done
            """.formatted(String.join(" ", TOOLS), String.join(", ", TOOLS).replaceFirst("^", " "));

    /** Arguments: name and value of each dependency, in turn. The values are package names, see {@link #PACKAGE}. */
    private static final String INSTALL_DEPENDENCIES = PACKAGE_MANAGER + """
            [ -n "$pm" ] || { echo '==> Cannot install dependencies: the container has no supported package manager'; exit 1; }
            packages=
            while [ "$#" -gt 1 ]; do echo "==> Dependency: $1 ($2) with $pm"; packages="$packages $2"; shift 2; done
            install $packages || { rc=$?; echo "==> Could not install$packages"; exit "$rc"; }
            installed $packages
            """;

    private static void install(StringBuilder f, List<Dependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) return;
        List<String> args = new java.util.ArrayList<>(List.of("sh", "-c", INSTALL_DEPENDENCIES, "dependencies"));
        for (Dependency d : dependencies) {
            args.add(d.name());
            args.add(d.value());
        }
        f.append("RUN ").append(exec(args.toArray(String[]::new))).append('\n');
    }

    private static final JsonMapper JSON = new JsonMapper();

    /** The exec (JSON) form, so nothing in the arguments is interpreted by the Containerfile parser or a shell. */
    private static String exec(String... args) {
        try {
            return JSON.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The environment and all steps as one shell script, as the Containerfile's {@code RUN}s run them in order. */
    String script(int waitSeconds) {
        // Each RUN is a new shell, so each runs in a subshell here.
        StringBuilder s = new StringBuilder(exports());
        for (String run : runs(waitSeconds)) s.append("(\n").append(run).append(") || exit $?\n");
        return s.toString();
    }

    /** The scripts of the Containerfile's step {@code RUN}s. */
    List<String> runs(int waitSeconds) {
        List<String> runs = new java.util.ArrayList<>();
        for (Job job : jobs) {
            String header = "echo " + quote("==> Job: " + job.job()) + "\n";
            // Each RUN of the job sets the job's variables again, as a RUN starts a new shell.
            String jobEnvironment = exports(job.environment());
            if (job.steps().stream().anyMatch(step -> !step.isRun())) {
                StringBuilder s = new StringBuilder(jobEnvironment + header);
                for (Step step : job.steps()) s.append(step(step, waitSeconds));
                runs.add(s.toString());
            } else {
                for (int i = 0; i < job.steps().size(); i++) {
                    Step step = job.steps().get(i);
                    runs.add(jobEnvironment + (i == 0 ? header : "") + step(step, waitSeconds));
                }
            }
        }
        return runs;
    }

    /**
     * A {@code run} step runs in its own shell from {@code /workspace}. A {@code wait} step polls the kernel's TCP
     * tables for a listening socket on the port, so the image needs only {@code sh} and {@code grep}.
     */
    private static String step(Step step, int waitSeconds) {
        StringBuilder s = new StringBuilder();
        s.append("echo ").append(quote("==> Step: " + step.name())).append('\n');
        if (step.isRun()) {
            // The step's variables are set in a subshell, so they do not reach the job's later steps.
            String own = exports(step.environment());
            s.append(own.isEmpty() ? "" : "(\n" + own + "exec ").append("sh -c ").append(quote(step.run())).append(own.isEmpty() ? "" : "\n)")
                    .append(" || { rc=$?; echo ").append(quote("==> Step failed: " + step.name() + ", exit code"))
                    .append(" \"$rc\"; exit \"$rc\"; }\n");
        } else {
            String port = String.format("%04X", step.port());
            s.append("i=0; until grep -Eqs '^ *[0-9]+: [0-9A-F]+:").append(port)
                    .append(" [0-9A-F]+:[0-9A-F]+ 0A ' /proc/net/tcp /proc/net/tcp6; do")
                    .append(" i=$((i+1)); if [ \"$i\" -ge ").append(waitSeconds).append(" ]; then echo ")
                    .append(quote("==> Step failed: " + step.name() + ", port " + step.port()
                            + " not listening after " + waitSeconds + " seconds"))
                    .append("; exit 1; fi; sleep 1; done\n");
        }
        return s.toString();
    }

    /** The workflow's {@link #environment()} as shell {@code export} lines, in the order of the file. */
    String exports() {
        return exports(environment);
    }

    private static String exports(List<Variable> environment) {
        StringBuilder s = new StringBuilder();
        if (environment != null) environment.forEach(v -> s.append("export ").append(v.name()).append('=').append(quote(v.value())).append('\n'));
        return s.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank()) throw new InvalidWorkflowException(field + " is required");
        if (value.length() > MAX_NAME) throw new InvalidWorkflowException(field + " is longer than " + MAX_NAME + " characters");
    }

    /** The workflow file is not valid; the message is safe to show to the user. */
    public static class InvalidWorkflowException extends IllegalArgumentException {
        public InvalidWorkflowException(String message) {
            super(message);
        }
    }
}
