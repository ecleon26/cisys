/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowTest {
  @TempDir Path temp;

  static final String VALID =
      """
      name: main branch

      container: alpine:3.23

      environment:
        - name: MODE
          value: test
        - name: PORT
          value: 8180

      jobs:
        - job: Build and test
          steps:
            - name: Start
              run: make platform-start
            - name: Wait for 8180
              wait: 8180
            - name: Run
              run: make run
            - name: Stop
              run: make platform-stop
      """;

  @Test
  void parsesReportsInOrder() {
    Workflow w =
        Workflow.parse(
            VALID
                + """
                reports:
                  list:
                    - name: List
                  orangu:
                    - name: Review
                      prompt: /auto_review
                """);
    assertEquals(List.of("list", "orangu"), List.copyOf(w.reports().keySet()));
    assertEquals("/auto_review", w.reports().get("orangu").get(0).get("prompt"));
  }

  @Test
  void rejectsInvalidReports() {
    for (String reports :
        List.of(
            "reports:\n  list:\n    - prompt: x\n",
            "reports:\n  list: []\n",
            "reports:\n  Bad_Type:\n    - name: X\n",
            "reports:\n  list:\n    - name: Same\n  orangu:\n    - name: Same\n")) {
      assertThrows(
          Workflow.InvalidWorkflowException.class, () -> Workflow.parse(VALID + reports), reports);
    }
  }

  @Test
  void aCachedBuildCopiesTheCacheInAndSavesItAfterTheLastStep() {
    Workflow w = Workflow.parse(VALID);
    String id = "0".repeat(64);
    String plain = w.containerfile(id, false);
    String cached = w.containerfile(id, false, "my-project");
    assertFalse(plain.contains(".cisys/") || plain.contains("cisys-cache"), plain);
    assertTrue(
        cached.contains(
            "mkdir -p '/tmp/.cisys/my-project' /cisys-cache && chown 65534:65534 /tmp/.cisys"
                + " '/tmp/.cisys/my-project' /cisys-cache"),
        cached);
    String copy = "COPY --chown=65534:65534 cache /tmp/.cisys/my-project\n";
    assertTrue(cached.contains(copy) && cached.indexOf(copy) == cached.lastIndexOf(copy), cached);
    assertTrue(
        cached.indexOf(copy) > cached.indexOf("COPY --chown=65534:65534 workspace /workspace"),
        cached);
    assertTrue(cached.indexOf(copy) < cached.indexOf("==> Step:"), cached);
    String save = "mv '/tmp/.cisys/my-project' /cisys-cache/project ||";
    assertTrue(cached.contains(save) && cached.indexOf(save) == cached.lastIndexOf(save), cached);
    assertTrue(cached.lastIndexOf("==> Step:") < cached.indexOf(save), cached);
    assertTrue(cached.indexOf(save) < cached.indexOf("Removing the files the steps wrote"), cached);
    String steps = w.stepsContainerfile("prepared", false, "my-project");
    assertTrue(steps.contains(copy), steps);
    assertTrue(steps.contains(save), steps);
    assertFalse(w.stepsContainerfile("prepared", false).contains(".cisys/"));
    assertThrows(
        IllegalArgumentException.class, () -> w.containerfile(id, false, "a b'; rm -rf /"));
  }

  @Test
  void parsesWorkflow() {
    Workflow w = Workflow.parse(VALID);
    assertEquals("main branch", w.name());
    assertEquals("alpine:3.23", w.container());
    assertEquals(
        List.of(new Workflow.Variable("MODE", "test"), new Workflow.Variable("PORT", "8180")),
        w.environment());
    assertEquals(1, w.jobs().size());
    Workflow.Job job = w.jobs().get(0);
    assertEquals("Build and test", job.job());
    assertEquals(
        List.of("Start", "Wait for 8180", "Run", "Stop"),
        job.steps().stream().map(Workflow.Step::name).toList());
    assertTrue(job.steps().get(0).isRun());
    assertEquals("make platform-start", job.steps().get(0).run());
    assertFalse(job.steps().get(1).isRun());
    assertEquals(8180, job.steps().get(1).port());
  }

  @Test
  void parsesRepositoryWorkflow() throws Exception {
    assertNotNull(Workflow.load(Path.of(".")));
  }

  @Test
  void loadsFromCheckout() throws Exception {
    Path file = temp.resolve(Workflow.PATH);
    Files.createDirectories(file.getParent());
    Files.writeString(file, VALID);
    assertEquals("main branch", Workflow.load(temp).name());
  }

  @Test
  void missingFileIsNull() throws Exception {
    assertNull(Workflow.load(temp));
  }

  @Test
  void rejectsSymbolicLink() throws Exception {
    Path target = Files.writeString(temp.resolve("elsewhere.yml"), VALID);
    Path file = temp.resolve(Workflow.PATH);
    Files.createDirectories(file.getParent());
    Files.createSymbolicLink(file, target);
    assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.load(temp));
  }

  @Test
  void rejectsSymbolicLinkDirectory() throws Exception {
    Path outside = temp.resolve("outside/workflows");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("ci.yml"), "secret");
    Path checkout = Files.createDirectories(temp.resolve("checkout/.cisys"));
    Files.createSymbolicLink(checkout.resolve("workflows"), outside);
    assertThrows(
        Workflow.InvalidWorkflowException.class, () -> Workflow.read(checkout.getParent()));
  }

  @Test
  void rejectsSymbolicLinkTopDirectory() throws Exception {
    Path outside = temp.resolve("outside/workflows");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("ci.yml"), "secret");
    Path checkout = Files.createDirectories(temp.resolve("checkout"));
    Files.createSymbolicLink(checkout.resolve(".cisys"), outside.getParent());
    assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.read(checkout));
  }

  @Test
  void rejectsLargeFile() throws Exception {
    Path file = temp.resolve(Workflow.PATH);
    Files.createDirectories(file.getParent());
    Files.writeString(file, VALID + "#".repeat(Workflow.MAX_BYTES));
    assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.load(temp));
  }

  @Test
  void rejectsInvalidDocuments() {
    for (String yaml :
        List.of(
            "",
            "name: [",
            VALID.replace("name: main branch", "name: \"\""),
            VALID.replace("container: alpine:3.23", "container: \"-rm\""),
            VALID.replace("container: alpine:3.23\n", ""),
            VALID.replace("container: alpine:3.23", "container:\n  base: alpine:3.23"),
            VALID.replace("container: alpine:3.23", "environment:\n  base: alpine:3.23"),
            VALID.replace("    steps:", "    stepz:"),
            VALID.replace("        wait: 8180", "        wait: 70000"),
            VALID.replace("        wait: 8180", "        wait: 81.5"),
            VALID.replace("        wait: 8180", "        wait: 8180\n        run: true"),
            VALID.replace("        wait: 8180", "        timeout: 5"),
            VALID.replace("        run: make run", "        run: \" \""),
            VALID + "name: duplicate\n",
            VALID.replace("name: PORT", "name: MODE"),
            VALID.replace("name: MODE", "name: 1MODE"),
            VALID.replace("name: MODE", "name: MY-MODE"),
            VALID.replace("  - name: MODE\n    value: test\n", "  - value: test\n"),
            VALID.replace("    value: test", "    value:"),
            VALID.replace("    value: test", "    value: [a, b]"),
            VALID.replace("    value: test", "    value:\n      nested: x"),
            VALID.replace("    value: test", "    value: test\n    other: x"),
            VALID.replace("    value: test", "    value: " + "x".repeat(Workflow.MAX_COMMAND + 1)),
            VALID.replace("  - name: MODE\n    value: test\n", "  MODE: test\n"),
            VALID.replace(
                "        wait: 8180",
                "        wait: 8180\n        environment:\n          - name: X\n            value: y"),
            "name: x\ncontainer: alpine:3.23\njobs: []\n",
            "name: x\ncontainer: alpine:3.23\njobs:\n  - job: j\n    steps: []\n")) {
      assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
    }
  }

  private static Workflow workflow(String steps) {
    return Workflow.parse(
        "name: w\ncontainer: alpine:3.23\njobs:\n  - job: j\n    steps:\n" + steps);
  }

  private static BuildRunner.Result runScript(String script) throws Exception {
    return BuildRunner.command(List.of("sh", "-c", script), 30, 100_000);
  }

  @Test
  void scriptRunsStepsInOrderAndStopsAtFirstFailure() throws Exception {
    var r =
        runScript(
            workflow(
                    """
                          - name: First
                            run: echo "it's one"
                          - name: Fails
                            run: exit 3
                          - name: Never
                            run: echo never
                    """)
                .script(Workflow.WAIT_SECONDS));
    assertEquals(3, r.code(), r.output());
    assertTrue(
        r.output().contains("==> Job: j\n==> Step: First\nit's one\n==> Step: Fails\n"),
        r.output());
    assertTrue(r.output().contains("==> Step failed: Fails, exit code 3"), r.output());
    assertFalse(r.output().contains("never"), r.output());
  }

  @Test
  void stepsSeeTheEnvironment() throws Exception {
    Workflow w =
        Workflow.parse(
            """
            name: w
            container: alpine:3.23
            environment:
              - name: GREETING
                value: "it's $HOME `x` \\\\ \\"q\\""
              - name: EMPTY
                value: ""
              - name: LEVEL
                value: workflow
            jobs:
              - job: j
                environment:
                  - name: LEVEL
                    value: job
                steps:
                  - name: Show
                    run: printf '[%s][%s][%s]\\n' "$GREETING" "${EMPTY-unset}" "$LEVEL"
                  - name: Own
                    environment:
                      - name: LEVEL
                        value: step
                      - name: ONLY_HERE
                        value: "yes"
                    run: echo "own=$LEVEL $ONLY_HERE"
                  - name: After
                    run: echo "after=$LEVEL ${ONLY_HERE-unset}"
              - job: k
                steps:
                  - name: Other job
                    run: echo "other=$LEVEL"
            """);
    var r = runScript(w.script(Workflow.WAIT_SECONDS));
    assertEquals(0, r.code(), r.output());
    assertTrue(r.output().contains("[it's $HOME `x` \\ \"q\"][][job]"), r.output());
    assertTrue(r.output().contains("own=step yes"), r.output());
    assertTrue(r.output().contains("after=job unset"), r.output());
    assertTrue(r.output().contains("other=workflow"), r.output());
  }

  @Test
  void environmentIsOptional() {
    Workflow w = workflow("      - name: s\n        run: true\n");
    assertNull(w.environment());
    assertEquals("", w.exports());
  }

  @Test
  void waitStepPassesOnceThePortListens() throws Exception {
    try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      var r =
          runScript(
              workflow(
                      "      - name: Wait\n        wait: "
                          + server.getLocalPort()
                          + "\n      - name: After\n        run: echo ready\n")
                  .script(5));
      assertEquals(0, r.code(), r.output());
      assertTrue(r.output().contains("ready"), r.output());
    }
  }

  @Test
  void variablesAreUnsetWhenTheirScopeEnds() throws Exception {
    try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Workflow w =
          Workflow.parse(
              """
              name: w
              container: alpine:3.23
              environment:
                - name: LEVEL
                  value: workflow
              jobs:
                - job: Steps
                  environment:
                    - name: LEVEL
                      value: job
                    - name: JOB_ONLY
                      value: one
                  steps:
                    - name: Own
                      environment:
                        - name: STEP_ONLY
                          value: s1
                      run: echo "a=$LEVEL $JOB_ONLY $STEP_ONLY"
                    - name: Next
                      run: echo "b=$LEVEL $JOB_ONLY ${STEP_ONLY-unset}"
                - job: One RUN
                  environment:
                    - name: WAIT_JOB
                      value: two
                  steps:
                    - name: Own
                      environment:
                        - name: STEP_ONLY
                          value: s2
                        - name: LEVEL
                          value: step
                      run: echo "c=$LEVEL $WAIT_JOB $STEP_ONLY ${JOB_ONLY-unset}"
                    - name: Wait
                      wait: %d
                    - name: Next
                      run: echo "d=$LEVEL $WAIT_JOB ${STEP_ONLY-unset}"
                - job: Last
                  steps:
                    - name: Last
                      run: echo "e=$LEVEL ${JOB_ONLY-unset} ${WAIT_JOB-unset} ${STEP_ONLY-unset}"
              """
                  .formatted(server.getLocalPort()));
      var r = runScript(w.script(5));
      assertEquals(0, r.code(), r.output());
      for (String line :
          List.of(
              "a=job one s1",
              "b=job one unset",
              "c=step two s2 unset",
              "d=workflow two unset",
              "e=workflow unset unset unset")) {
        assertTrue(r.output().contains(line + "\n"), line + " in\n" + r.output());
      }
    }
  }

  @Test
  void waitStepFailsWhenThePortNeverListens() throws Exception {
    int port;
    try (var probe = new java.net.ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    var r = runScript(workflow("      - name: Wait\n        wait: " + port + "\n").script(1));
    assertEquals(1, r.code(), r.output());
    assertTrue(r.output().contains("port " + port + " not listening after 1 seconds"), r.output());
  }

  @Test
  void rejectsContainerfilesTooLargeToRun() {
    // Each quote grows to five characters in the Containerfile ('\'' in JSON), so 13 steps exceed
    // the limit.
    String step = "      - name: s\n        run: x" + "'".repeat(3999) + "\n";
    Workflow w = workflow(step.repeat(13));
    assertThrows(Workflow.InvalidWorkflowException.class, () -> w.containerfile(ID));
  }

  static final String ID = "164b5c6a230f645862b6123e07cf0a67f2f03371696aa82a12ccf3589e87c016";

  @Test
  void containerfileCopiesTheCheckoutThenRunsEachStepWithoutNetwork() {
    String file =
        Workflow.parse(VALID.replace("        wait: 8180\n", "        run: make wait\n"))
            .containerfile(ID);
    List<Instruction> in = instructions(file);
    assertEquals("FROM " + ID, in.get(0).line());
    assertEquals("USER 0", in.get(1).line());
    // Every RUN is a quoted heredoc, so it reads as the shell it runs.
    assertEquals("RUN <<'CISYS'", in.get(2).line());
    assertTrue(in.get(2).body().startsWith(". /etc/os-release"), in.get(2).body());
    assertTrue(
        in.get(2).body().contains("for tool in git make podman podman-compose; do"),
        in.get(2).body());
    // Root writes the variables read-only, before switching to the build user.
    assertEquals("RUN --network=none <<'CISYS'", in.get(3).line());
    assertEquals(
        """
        cat > /etc/cisys-environment <<'ENVIRONMENT'
        export MODE='test'
        export PORT='8180'
        ENVIRONMENT
        chown 0:0 /etc/cisys-environment && chmod 444 /etc/cisys-environment
        """,
        in.get(3).body());
    assertEquals("mkdir -p /workspace && chown 65534:65534 /workspace\n", in.get(4).body());
    assertEquals("USER 65534:65534", in.get(5).line());
    assertEquals("WORKDIR /workspace", in.get(6).line());
    // The image gets the checkout that was measured against the limits; it does not fetch the
    // branch again.
    assertEquals("COPY --chown=65534:65534 workspace /workspace", in.get(8).line());
    assertFalse(file.contains("git clone") || file.contains("secret"), file);
    List<Instruction> steps = in.subList(9, in.size());
    assertEquals(4, steps.size(), file);
    for (Instruction step : steps) {
      assertEquals("RUN --network=none <<'CISYS'", step.line());
      assertTrue(step.body().startsWith(". /etc/cisys-environment || exit 1\n"), step.body());
    }
    assertTrue(
        steps.get(0).body().contains("echo '==> Job: Build and test'\n")
            && steps.get(0).body().contains("sh -c 'make platform-start'"),
        steps.get(0).body());
    // Only the last step removes the files, after the steps succeeded; the earlier steps' files
    // are the later steps' input.
    for (Instruction step : steps.subList(0, 3)) {
      assertFalse(step.body().contains(Workflow.CLEAN_UP), step.body());
    }
    String last = steps.get(3).body();
    assertTrue(last.endsWith("exit \"$rc\"; }\n" + Workflow.CLEAN_UP), last);
  }

  @Test
  void dockerPreparationInstallsToolsAndDependenciesWithNetwork() {
    List<String> lines =
        Workflow.parse(DEPENDENCIES).prepareContainerfile(ID, false).lines().toList();
    assertEquals("FROM " + ID, lines.get(0));
    assertEquals("USER 0", lines.get(1));
    assertTrue(
        lines.get(2).contains("for tool in git make podman podman-compose; do"), lines.get(2));
    assertTrue(
        lines.get(3).contains("\"JSON processor\",\"jq\",\"Compiler\",\"gcc\"]"), lines.get(3));
    assertEquals(4, lines.size(), String.join("\n", lines));
  }

  @Test
  void dockerStepsStageRunsFromThePreparedImageWithoutNetworkFlags() {
    Workflow w = Workflow.parse(VALID.replace("        wait: 8180\n", "        run: make wait\n"));
    List<String> lines = w.stepsContainerfile("cisys-build-1-prepare", false).lines().toList();
    assertEquals("FROM cisys-build-1-prepare", lines.get(0));
    assertTrue(lines.stream().noneMatch(l -> l.contains("--network")), String.join("\n", lines));
    // Tools and dependencies are in the preparation image, not in this file.
    assertTrue(
        lines.stream().noneMatch(l -> l.contains("/etc/os-release")), String.join("\n", lines));
    assertTrue(
        lines.stream().anyMatch(l -> l.startsWith("COPY --chown=65534:65534 ")), file(lines));
    assertTrue(
        lines.stream().anyMatch(l -> l.startsWith("RUN [\"sh\",\"-c\",\". /etc/cisys-environment")),
        file(lines));
  }

  private static String file(List<String> lines) {
    return String.join("\n", lines);
  }

  @Test
  void jobWithAWaitStepIsOneRun() {
    var runs = Workflow.parse(VALID).runs(Workflow.WAIT_SECONDS);
    assertEquals(1, runs.size());
    String script = runs.get(0);
    assertTrue(
        script.contains("make platform-start") && script.contains("make platform-stop"), script);
  }

  static final String DEPENDENCIES =
      """
      name: w
      container: alpine:3.23
      dependencies:
        - name: JSON processor
          value: jq
        - name: Compiler
          value: gcc
      jobs:
        - job: Build
          steps:
            - name: Compile
              run: make
      """;

  /** One Dockerfile instruction; {@code body} is the text of a heredoc {@code RUN}, else null. */
  record Instruction(String line, String body) {}

  private static final java.util.regex.Pattern HEREDOC =
      java.util.regex.Pattern.compile("RUN .*<<'([A-Z_0-9]+)'");

  /** Splits a Dockerfile into its instructions, as the builder reads it. */
  static List<Instruction> instructions(String file) {
    List<Instruction> result = new java.util.ArrayList<>();
    List<String> lines = file.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      var heredoc = HEREDOC.matcher(lines.get(i));
      if (!heredoc.matches()) {
        result.add(new Instruction(lines.get(i), null));
        continue;
      }
      StringBuilder body = new StringBuilder();
      int start = i;
      while (!lines.get(++i).equals(heredoc.group(1))) {
        body.append(lines.get(i)).append('\n');
      }
      result.add(new Instruction(lines.get(start), body.toString()));
    }
    return result;
  }

  @Test
  void dependenciesAreInstalledAsRootBeforeTheCheckout() {
    List<Instruction> in = instructions(Workflow.parse(DEPENDENCIES).containerfile(ID));
    assertEquals("USER 0", in.get(1).line());
    String install = in.get(3).body();
    // The names and packages are the script's arguments, quoted for the shell.
    assertTrue(
        install.startsWith("set -- 'JSON processor' 'jq' 'Compiler' 'gcc'\n. /etc/os-release"),
        install);
    // Fresh package metadata, already installed packages upgraded, and the resulting versions
    // logged.
    for (String part :
        List.of(
            "apk add --no-cache --upgrade",
            "apt-get update",
            "dnf install -y -q --refresh",
            "dnf upgrade -y -q",
            "zypper --non-interactive refresh",
            "installed $packages")) {
      assertTrue(install.contains(part), part);
    }
    assertTrue(in.get(4).body().contains("/etc/cisys-environment"), in.get(4).body());
    assertEquals("USER 65534:65534", in.get(6).line());
    assertTrue(in.get(9).line().startsWith("COPY "), in.get(9).line());
    assertEquals(1, in.stream().filter(i -> i.line().equals("USER 0")).count(), in.toString());
  }

  @Test
  void dependenciesAreTopLevelOnly() {
    for (String yaml :
        List.of(
            DEPENDENCIES.replace(
                "    steps:\n",
                "    dependencies:\n      - name: Archiver\n        value: zip\n    steps:\n"),
            DEPENDENCIES.replace(
                "        run: make\n",
                "        run: make\n        dependencies:\n          - name: Archiver\n            value: zip\n"))) {
      assertFalse(yaml.equals(DEPENDENCIES), "the test document must differ");
      assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
    }
  }

  @Test
  void rejectsInvalidDependencies() {
    for (String yaml :
        List.of(
            DEPENDENCIES.replace("value: jq", "value: \"jq; rm -rf /\""),
            DEPENDENCIES.replace("value: jq", "value: \"-y\""),
            DEPENDENCIES.replace("value: jq", "value: \"\""),
            DEPENDENCIES.replace("value: jq", "value: [jq, gcc]"),
            DEPENDENCIES.replace("    value: jq\n", ""),
            DEPENDENCIES
                .replace("  - name: JSON processor\n", "  - value: jq\n")
                .replace("    value: jq\n    value: jq", "    value: jq"),
            DEPENDENCIES.replace("  - name: Compiler\n", "  - name: JSON processor\n"),
            DEPENDENCIES.replace(
                "dependencies:\n  - name: JSON processor\n    value: jq",
                "dependencies:\n  jq: JSON processor"))) {
      assertFalse(yaml.equals(DEPENDENCIES), "the test document must differ");
      assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
    }
    assertEquals(
        "git=2.47.3-0+deb13u1",
        Workflow.parse(DEPENDENCIES.replace("value: jq", "value: git=2.47.3-0+deb13u1"))
            .dependencies()
            .get(0)
            .value());
  }

  @Test
  void withoutNetworkTheContainerfileIsUnchanged() {
    Workflow w = Workflow.parse(DEPENDENCIES);
    String file = w.containerfile(ID, false);
    assertEquals(w.containerfile(ID), file);
    assertFalse(file.contains("socat"), file);
    assertFalse(file.contains("PROXY"), file);
  }

  @Test
  void withNetworkStepsUseTheProxyAndStillHaveNoNetwork() {
    Workflow w = Workflow.parse(VALID.replace("        wait: 8180\n", "        run: make wait\n"));
    List<Instruction> plain = instructions(w.containerfile(ID));
    List<Instruction> in = instructions(w.containerfile(ID, true));
    assertEquals(plain.size(), in.size());
    assertTrue(
        in.get(2).body().contains("for tool in git make podman podman-compose socat; do"),
        in.get(2).body());
    // Everything up to the steps, the checkout included, is unchanged.
    assertEquals(plain.subList(3, 9), in.subList(3, 9));
    String forward =
        "socat TCP-LISTEN:3128,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:/run/cisys/proxy.sock";
    for (Instruction step : in.subList(9, in.size())) {
      String body = step.body();
      assertEquals("RUN --network=none <<'CISYS'", step.line());
      // The socket is tried first, so an unreachable proxy fails the step with the reason.
      assertTrue(
          body.startsWith(
              ". /etc/cisys-environment || exit 1\n"
                  + "err=$(socat -u OPEN:/dev/null UNIX-CONNECT:/run/cisys/proxy.sock 2>&1) || {"),
          body);
      assertTrue(body.contains("==> Network proxy unreachable: /run/cisys/proxy.sock"), body);
      assertTrue(body.contains("\n" + forward), body);
      assertTrue(
          body.contains(
              "unset ALL_PROXY all_proxy HTTP_PROXY http_proxy FTP_PROXY ftp_proxy NO_PROXY no_proxy\nexport HTTPS_PROXY=http://127.0.0.1:3128 https_proxy=http://127.0.0.1:3128"),
          body);
      assertTrue(
          body.contains(
              "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Daether.connector.http.useSystemProperties=true"),
          body);
      assertTrue(body.contains("0100007F:0C38"), body);
      assertFalse(
          body.contains("HTTP_PROXY=") || body.contains("http_proxy="),
          "plain HTTP is not proxied: " + body);
    }
  }

  @Test
  void textCannotEscapeTheHeredoc() {
    // A name with a quote and a newline, and a command with a line equal to the delimiter.
    String file =
        workflow(
                "      - name: \"a'\\\"]\\nRUN evil\"\n        run: \"echo x\\nCISYS\\nRUN evil\"\n")
            .containerfile(ID);
    List<Instruction> in = instructions(file);
    assertEquals(10, in.size(), file);
    assertTrue(in.stream().noneMatch(i -> i.line().startsWith("RUN evil")), file);
    // The step's RUN picks a delimiter that no line of it equals.
    assertEquals("RUN --network=none <<'CISYS_1'", in.get(9).line());
    assertTrue(in.get(9).body().contains("\nCISYS\nRUN evil"), in.get(9).body());
  }

  @Test
  void dockerFilesKeepTheExecForm() {
    // Docker's legacy builder has no heredocs.
    Workflow w = Workflow.parse(DEPENDENCIES);
    String prepare = w.prepareContainerfile(ID, false);
    String steps = w.stepsContainerfile("cisys-build-1-prepare", false);
    assertFalse(prepare.contains("<<'") || steps.contains("<<'"), prepare + steps);
    assertTrue(
        prepare.lines().anyMatch(l -> l.startsWith("RUN [\"sh\",\"-c\",\". /etc/os-release")),
        prepare);
    assertTrue(prepare.contains(",\"dependencies\",\"JSON processor\",\"jq\","), prepare);
  }

  @Test
  void reportsUnknownKey() {
    var e =
        assertThrows(
            Workflow.InvalidWorkflowException.class,
            () -> Workflow.parse(VALID.replace("    steps:", "    stepz:")));
    assertTrue(e.getMessage().contains("stepz"), e.getMessage());
  }

  static final String ARTIFACTS =
      """
      artifacts:
        - name: Test results
          path: target/surefire-reports
        - name: Jars
          path: target/*.jar
      """;

  @Test
  void parsesArtifacts() {
    Workflow w = Workflow.parse(VALID + ARTIFACTS);
    assertEquals(
        List.of(
            new Workflow.Artifact("Test results", "target/surefire-reports"),
            new Workflow.Artifact("Jars", "target/*.jar")),
        w.artifacts());
    assertTrue(w.hasArtifacts());
    assertFalse(Workflow.parse(VALID).hasArtifacts());
  }

  @Test
  void rejectsInvalidArtifacts() {
    for (String path :
        List.of(
            "/etc/passwd",
            "../outside",
            "a/../b",
            "./a",
            "a//b",
            "a/",
            "-rf",
            "*/x",
            "a b",
            "a;b",
            "$(id)",
            "a`b",
            "~/x",
            "x".repeat(1001))) {
      String yaml = VALID + "artifacts:\n  - name: A\n    path: \"" + path + "\"\n";
      assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), path);
    }
    for (String artifacts :
        List.of(
            "artifacts: []\n",
            "artifacts:\n  - path: a\n",
            "artifacts:\n  - name: A\n",
            "artifacts:\n  - name: A\n    path: a\n  - name: A\n    path: b\n",
            "artifacts:\n  - name: A\n    path: a\n    when: always\n",
            "artifacts:\n"
                + java.util.stream.IntStream.range(0, Workflow.MAX_ARTIFACTS + 1)
                    .mapToObj(i -> "  - name: N" + i + "\n    path: p" + i + "\n")
                    .collect(java.util.stream.Collectors.joining()))) {
      assertThrows(
          Workflow.InvalidWorkflowException.class,
          () -> Workflow.parse(VALID + artifacts),
          artifacts);
    }
    assertThrows(
        Workflow.InvalidWorkflowException.class,
        () ->
            Workflow.parse(
                VALID.replace(
                    "    steps:", "    artifacts:\n      - name: A\n        path: a\n    steps:")));
  }

  @Test
  void containerfileCollectsArtifactsWhenAStepFailsAndAfterTheLastStep() {
    String file =
        Workflow.parse(
                VALID.replace("        wait: 8180\n", "        run: make wait\n") + ARTIFACTS)
            .containerfile(ID);
    List<Instruction> in = instructions(file);
    // tar joins the tools.
    assertTrue(
        in.get(2).body().contains("for tool in git make podman podman-compose tar; do"),
        in.get(2).body());
    // Root writes the collect script read-only, before switching to the build user.
    Instruction collect = in.get(4);
    assertTrue(collect.body().startsWith("cat > /etc/cisys-collect <<'COLLECT'\n"), collect.body());
    assertTrue(
        collect.body().contains("for p in ./target/surefire-reports ./target/*.jar; do"),
        collect.body());
    assertTrue(
        collect.body().endsWith("chown 0:0 /etc/cisys-collect && chmod 555 /etc/cisys-collect\n"),
        collect.body());
    assertEquals("USER 65534:65534", in.get(6).line());
    List<Instruction> steps = in.subList(10, in.size());
    assertEquals(4, steps.size(), file);
    for (Instruction step : steps) {
      // A failing step collects, then exits with its own code.
      assertTrue(
          step.body().contains("exit code' \"$rc\"; sh /etc/cisys-collect; exit \"$rc\"; }"),
          step.body());
    }
    String last = steps.get(3).body();
    assertTrue(last.endsWith("sh /etc/cisys-collect\n" + Workflow.CLEAN_UP), last);
    for (Instruction step : steps.subList(0, 3)) {
      assertFalse(step.body().contains("sh /etc/cisys-collect\n"), step.body());
    }
  }

  @Test
  void waitStepTimeoutCollectsArtifacts() {
    String file = Workflow.parse(VALID + ARTIFACTS).containerfile(ID);
    assertTrue(file.contains("seconds'; sh /etc/cisys-collect; exit 1; fi"), file);
  }

  @Test
  void withoutArtifactsTheContainerfileIsUnchanged() {
    String file = Workflow.parse(VALID).containerfile(ID);
    assertFalse(file.contains("cisys-collect"), file);
    assertFalse(file.contains(" tar;"), file);
  }

  @Test
  void collectScriptArchivesTheListedPathsWithoutFollowingLinks() throws Exception {
    Path workspace = Files.createDirectories(temp.resolve("workspace"));
    Path out = Files.createDirectories(temp.resolve("out"));
    Files.createDirectories(workspace.resolve("target/surefire-reports"));
    Files.writeString(workspace.resolve("target/surefire-reports/TEST-a.xml"), "<a/>");
    Files.writeString(workspace.resolve("target/app.jar"), "jar");
    Files.writeString(workspace.resolve("target/my lib.jar"), "lib");
    Path secret = Files.writeString(temp.resolve("secret"), "secret");
    Files.createSymbolicLink(workspace.resolve("target/link.jar"), secret);
    String script =
        Workflow.parse(VALID + ARTIFACTS + "  - name: Missing\n    path: nothing/here\n")
            .collectScript()
            .replace("/workspace", workspace.toString())
            .replace(Workflow.ARTIFACT_DIR, out.toString());

    var r = runScript(script);

    assertEquals(0, r.code(), r.output());
    assertTrue(r.output().contains("==> Artifact not found: nothing/here"), r.output());
    java.util.Map<String, String> files = new java.util.TreeMap<>();
    List<String> log = new java.util.ArrayList<>();
    try (var in = Files.newInputStream(out.resolve(Workflow.ARTIFACT_ARCHIVE))) {
      ArtifactArchive.read(
          in,
          1 << 20,
          100,
          path -> true,
          (path, size, data) ->
              files.put(
                  path, new String(data.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)),
          log::add);
    }
    assertEquals(
        java.util.Map.of(
            "target/surefire-reports/TEST-a.xml", "<a/>",
            "target/app.jar", "jar",
            "target/my lib.jar", "lib"),
        files);
    assertEquals(List.of("==> Artifact skipped: target/link.jar (symbolic link)"), log);
  }
}
