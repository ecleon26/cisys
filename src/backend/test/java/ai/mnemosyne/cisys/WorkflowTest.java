/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTest {
    @TempDir Path temp;

    static final String VALID = """
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
    void parsesWorkflow() {
        Workflow w = Workflow.parse(VALID);
        assertEquals("main branch", w.name());
        assertEquals("alpine:3.23", w.container());
        assertEquals(List.of(new Workflow.Variable("MODE", "test"), new Workflow.Variable("PORT", "8180")), w.environment());
        assertEquals(1, w.jobs().size());
        Workflow.Job job = w.jobs().get(0);
        assertEquals("Build and test", job.job());
        assertEquals(List.of("Start", "Wait for 8180", "Run", "Stop"), job.steps().stream().map(Workflow.Step::name).toList());
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
    void rejectsLargeFile() throws Exception {
        Path file = temp.resolve(Workflow.PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, VALID + "#".repeat(Workflow.MAX_BYTES));
        assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.load(temp));
    }

    @Test
    void rejectsInvalidDocuments() {
        for (String yaml : List.of(
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
                VALID.replace("        wait: 8180", "        wait: 8180\n        environment:\n          - name: X\n            value: y"),
                "name: x\ncontainer: alpine:3.23\njobs: []\n",
                "name: x\ncontainer: alpine:3.23\njobs:\n  - job: j\n    steps: []\n")) {
            assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
        }
    }

    private static Workflow workflow(String steps) {
        return Workflow.parse("name: w\ncontainer: alpine:3.23\njobs:\n  - job: j\n    steps:\n" + steps);
    }

    private static BuildRunner.Result runScript(String script) throws Exception {
        return BuildRunner.command(List.of("sh", "-c", script), 30, 100_000);
    }

    @Test
    void scriptRunsStepsInOrderAndStopsAtFirstFailure() throws Exception {
        var r = runScript(workflow("""
                      - name: First
                        run: echo "it's one"
                      - name: Fails
                        run: exit 3
                      - name: Never
                        run: echo never
                """).script(Workflow.WAIT_SECONDS));
        assertEquals(3, r.code(), r.output());
        assertTrue(r.output().contains("==> Job: j\n==> Step: First\nit's one\n==> Step: Fails\n"), r.output());
        assertTrue(r.output().contains("==> Step failed: Fails, exit code 3"), r.output());
        assertFalse(r.output().contains("never"), r.output());
    }

    @Test
    void stepsSeeTheEnvironment() throws Exception {
        Workflow w = Workflow.parse("""
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
            var r = runScript(workflow("      - name: Wait\n        wait: " + server.getLocalPort()
                    + "\n      - name: After\n        run: echo ready\n").script(5));
            assertEquals(0, r.code(), r.output());
            assertTrue(r.output().contains("ready"), r.output());
        }
    }

    @Test
    void variablesAreUnsetWhenTheirScopeEnds() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Workflow w = Workflow.parse("""
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
                    """.formatted(server.getLocalPort()));
            var r = runScript(w.script(5));
            assertEquals(0, r.code(), r.output());
            for (String line : List.of("a=job one s1", "b=job one unset", "c=step two s2 unset", "d=workflow two unset",
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
        // Each quote grows to five characters in the Containerfile ('\'' in JSON), so 13 steps exceed the limit.
        String step = "      - name: s\n        run: x" + "'".repeat(3999) + "\n";
        Workflow w = workflow(step.repeat(13));
        assertThrows(Workflow.InvalidWorkflowException.class, () -> w.containerfile(ID));
    }

    static final String ID = "164b5c6a230f645862b6123e07cf0a67f2f03371696aa82a12ccf3589e87c016";

    @Test
    void containerfileCopiesTheCheckoutThenRunsEachStepWithoutNetwork() {
        List<String> lines = Workflow.parse(VALID.replace("        wait: 8180\n", "        run: make wait\n"))
                .containerfile(ID).lines().toList();
        assertEquals("FROM " + ID, lines.get(0));
        assertEquals("USER 0", lines.get(1));
        assertTrue(lines.get(2).startsWith("RUN [\"sh\",\"-c\",\". /etc/os-release"), lines.get(2));
        assertTrue(lines.get(2).contains("for tool in git make podman podman-compose; do"), lines.get(2));
        // Root writes the variables read-only, before switching to the build user.
        assertEquals("RUN --network=none [\"sh\",\"-c\",\"printf '%s' \\\"$1\\\" > /etc/cisys-environment && chown 0:0 /etc/cisys-environment && chmod 444 /etc/cisys-environment\",\"environment\",\"export MODE='test'\\nexport PORT='8180'\\n\"]", lines.get(3));
        assertEquals("USER 65534:65534", lines.get(5));
        assertEquals("WORKDIR /workspace", lines.get(6));
        // The image gets the checkout that was measured against the limits; it does not fetch the branch again.
        assertEquals("COPY --chown=65534:65534 workspace /workspace", lines.get(8));
        assertTrue(lines.stream().noneMatch(l -> l.contains("git clone") || l.contains("secret")), String.join("\n", lines));
        List<String> steps = lines.subList(9, lines.size());
        assertEquals(4, steps.size(), String.join("\n", lines));
        assertTrue(steps.stream().allMatch(l -> l.startsWith("RUN --network=none [\"sh\",\"-c\",\". /etc/cisys-environment || exit 1\\n")), String.join("\n", steps));
        assertTrue(steps.get(0).contains("==> Job: Build and test") && steps.get(0).contains("make platform-start"), steps.get(0));
    }

    @Test
    void jobWithAWaitStepIsOneRun() {
        var runs = Workflow.parse(VALID).runs(Workflow.WAIT_SECONDS);
        assertEquals(1, runs.size());
        String script = runs.get(0);
        assertTrue(script.contains("make platform-start") && script.contains("make platform-stop"), script);
    }

    static final String DEPENDENCIES = """
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

    private static String installs(String line) {
        return line.substring(line.indexOf(",\"dependencies\",") + 16);
    }

    @Test
    void dependenciesAreInstalledAsRootBeforeTheCheckout() {
        List<String> lines = Workflow.parse(DEPENDENCIES).containerfile(ID).lines().toList();
        assertEquals("USER 0", lines.get(1));
        assertTrue(lines.get(3).startsWith("RUN [\"sh\",\"-c\",\". /etc/os-release"), lines.get(3));
        assertEquals("\"JSON processor\",\"jq\",\"Compiler\",\"gcc\"]", installs(lines.get(3)));
        // Fresh package metadata, already installed packages upgraded, and the resulting versions logged.
        for (String part : List.of("apk add --no-cache --upgrade", "apt-get update", "dnf install -y -q --refresh", "dnf upgrade -y -q",
                "zypper --non-interactive refresh", "installed $packages")) {
            assertTrue(lines.get(3).contains(part), part);
        }
        assertTrue(lines.get(4).contains("/etc/cisys-environment"), lines.get(4));
        assertEquals("USER 65534:65534", lines.get(6));
        assertTrue(lines.get(9).startsWith("COPY "), lines.get(9));
        assertEquals(1, lines.stream().filter(l -> l.equals("USER 0")).count(), String.join("\n", lines));
    }

    @Test
    void dependenciesAreTopLevelOnly() {
        for (String yaml : List.of(
                DEPENDENCIES.replace("    steps:\n", "    dependencies:\n      - name: Archiver\n        value: zip\n    steps:\n"),
                DEPENDENCIES.replace("        run: make\n", "        run: make\n        dependencies:\n          - name: Archiver\n            value: zip\n"))) {
            assertFalse(yaml.equals(DEPENDENCIES), "the test document must differ");
            assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
        }
    }

    @Test
    void rejectsInvalidDependencies() {
        for (String yaml : List.of(
                DEPENDENCIES.replace("value: jq", "value: \"jq; rm -rf /\""),
                DEPENDENCIES.replace("value: jq", "value: \"-y\""),
                DEPENDENCIES.replace("value: jq", "value: \"\""),
                DEPENDENCIES.replace("value: jq", "value: [jq, gcc]"),
                DEPENDENCIES.replace("    value: jq\n", ""),
                DEPENDENCIES.replace("  - name: JSON processor\n", "  - value: jq\n").replace("    value: jq\n    value: jq", "    value: jq"),
                DEPENDENCIES.replace("  - name: Compiler\n", "  - name: JSON processor\n"),
                DEPENDENCIES.replace("dependencies:\n  - name: JSON processor\n    value: jq", "dependencies:\n  jq: JSON processor"))) {
            assertFalse(yaml.equals(DEPENDENCIES), "the test document must differ");
            assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(yaml), yaml);
        }
        assertEquals("git=2.47.3-0+deb13u1", Workflow.parse(DEPENDENCIES.replace("value: jq", "value: git=2.47.3-0+deb13u1"))
                .dependencies().get(0).value());
    }

    @Test
    void argumentsCannotEscapeTheExecForm() {
        String file = workflow("      - name: \"a\\\"]\\nRUN evil\"\n        run: echo \"\\\\\\n\"\n")
                .containerfile(ID);
        assertEquals(10, file.lines().count(), file);
        assertTrue(file.lines().noneMatch(l -> l.startsWith("RUN evil")), file);
    }

    @Test
    void reportsUnknownKey() {
        var e = assertThrows(Workflow.InvalidWorkflowException.class, () -> Workflow.parse(VALID.replace("    steps:", "    stepz:")));
        assertTrue(e.getMessage().contains("stepz"), e.getMessage());
    }
}
