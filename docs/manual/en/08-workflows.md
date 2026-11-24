\newpage

# Workflows

A **workflow** describes how a branch is built and tested. It lives in the repository at `.cisys/workflows/ci.yml`, so every branch carries its own workflow and a change to the workflow is reviewed like any other change.

Selecting **Build** next to a branch on a project page runs that branch's workflow. Leads and admins see the button.

## The workflow file

```yaml
name: main

container: alpine:3.23

environment:
  - name: MODE
    value: test

dependencies:
  - name: JSON processor
    value: jq

jobs:
  - job: Test
    environment:
      - name: DATABASE_PORT
        value: 5432
    steps:
      - name: Start
        run: make platform-start
      - name: Wait for 8180
        wait: 8180
      - name: Test
        run: make test
      - name: Stop
        run: make platform-stop
```

The workflow does not name a repository or a branch. The build uses the project's repository and the branch you selected.

| Key | Required | Meaning |
|-----|----------|---------|
| `name` | yes | The workflow name, shown in the build log. |
| `container` | yes | The container image the build starts from, for example `alpine:3.23`. |
| `environment` | no | Environment variables for every step, as a list of `name` and `value`. |
| `dependencies` | no | Packages to install before the checkout, as a list of `name` and `value`. Top level only. |
| `jobs` | yes | One or more jobs, run in order. |
| `jobs[].job` | yes | The job name. |
| `jobs[].environment` | no | Environment variables for this job's steps. |
| `jobs[].steps` | yes | One or more steps, run in order. |
| `steps[].name` | yes | The step name. |
| `steps[].environment` | no | Environment variables for this `run` step only. |
| `steps[].run` | one of `run` or `wait` | A shell command, at most 4000 characters. |
| `steps[].wait` | one of `run` or `wait` | A TCP port (1–65535). The step waits until a process listens on it. |

Each step has exactly one of `run` and `wait`.

The workflow is checked before anything runs. Unknown or duplicate keys, missing keys, wrong types and values out of range fail the build. The log names the problem and, where it can, the line. Names are at most 200 characters and the file at most 64 KiB.

### Environment

Environment variables are set with `environment`, a list of entries with a `name` and a `value`. It can appear at three levels, and each level is scoped to what it belongs to:

```yaml
environment:              # every step of every job
  - name: LEVEL
    value: workflow
  - name: GREETING
    value: "it's $HOME"

jobs:
  - job: Test
    environment:          # every step of this job
      - name: LEVEL
        value: job
    steps:
      - name: Start
        run: make platform-start      # LEVEL=job
      - name: Verbose test
        environment:      # this step only
          - name: LEVEL
            value: step
        run: make test                # LEVEL=step
      - name: Stop
        run: make platform-stop       # LEVEL=job again
  - job: Report
    steps:
      - name: Report
        run: make report              # LEVEL=workflow
```

* A job's variables are added to the workflow's, and a step's to its job's. Where a name appears at more than one level, the innermost wins.
* A variable is unset when its scope ends. A step's variables are gone in the next step, and a job's in the next job. A name the inner level replaced has its outer value again, as `LEVEL` above shows. This also holds within a job that has a `wait` step. A `wait` step cannot have `environment`.
* A step cannot pass variables on: `export` in a `run` command lasts only for that command. The variables are kept in `/etc/cisys-environment`, which root writes before the checkout and which the steps can only read.
* `name` is letters, digits and `_`, not starting with a digit, at most 200 characters, and appears once per list.
* `value` is taken literally: `$HOME`, backticks and quotes are not expanded. Numbers and `true`/`false` become their text, so `5432` is `"5432"`. A value is at most 4000 characters; write `""` for an empty value. Lists and nested blocks are rejected.
* A variable can replace one the image sets, such as `PATH` or `HOME`.
* Installing the tools and cloning the branch do not see these variables. They apply to the steps only.
* The workflow file is stored with each build and shown on the build page, so never put secrets in `environment`.

### Dependencies

`dependencies` installs packages into the container, as a list of entries with a `name` and a `value`. `value` is the package; `name` describes it in the log. Unlike `environment`, `dependencies` is a top-level key only; jobs and steps cannot have it:

```yaml
container: debian:13-slim

dependencies:
  - name: JSON processor
    value: jq
  - name: Java
    value: openjdk-25-jdk-headless

jobs:
  - job: Build
    steps:
      - name: Compile
        run: make
```

The build finds out what the container is and installs with its package manager: `apk` (Alpine), `apt-get` (Debian, Ubuntu), `dnf` (Fedora), `microdnf` or `yum` (Red Hat Enterprise Linux, UBI, CentOS) or `zypper` (openSUSE). The log shows the container and the package manager, then one line per dependency and the version it was installed at:

```
==> Container: Debian GNU/Linux 13 (trixie), package manager: apt-get
==> Dependency: JSON processor (jq) with apt-get
==> Installed: jq 1.7.1-6+deb13u4
```

* `value` is the package name in the container's distribution, so it depends on `container`: Java is `openjdk25` on Alpine, `openjdk-25-jdk-headless` on Debian and `java-25-openjdk-devel` on Fedora. A version can be given as the package manager writes it, for example `git=1:2.47.3-0+deb13u1` for `apt-get`.
* `value` is one package: letters, digits and `+ . _ : = ~ @ -`, starting with a letter or digit, at most 200 characters. Options such as `-y`, spaces and shell characters are rejected.
* `name` is required, at most 200 characters, and appears once in the list.
* Packages are installed as root, with network access. The steps themselves still run as `65534:65534` without network, except through the proxy described under **Network for steps**.
* All dependencies are installed together, in one step, before the checkout is copied in, so every job and step can use them. `dependencies` on a job or a step is rejected.
* A package that does not exist, or a container without a supported package manager, fails the build with `==> Could not install …` or `==> Cannot install dependencies …`.
* The package manager's own output is kept out of the log unless the installation fails; then it is shown before `==> Could not install …`. Notes that packages print after installing, such as Alpine's Podman note about `/etc/subuid` and cgroups, therefore do not appear.
* Dependencies are always up to date. The package manager reads fresh package metadata on every build, never what the image cached, and installs the newest version. A package the image already has is upgraded. The log shows the version each one ended up at, for example `==> Installed: jq 1.7.1-6+deb13u4`. To stay on one version, give it in `value`.
* Packages are downloaded on every build. A `container` image that already has them starts faster.

### The container image

`container` names an image that is already on the build host. Admins pull images and allow them on the **Images** page. The build never pulls an image itself.

The image can be named in several ways. With `docker.io/library/alpine:3.23` (ID `164b5c6a230f`) as the only local Alpine image, all of these name it:

| `container` | Meaning |
|--------|---------|
| `alpine` | The official image on Docker Hub, any tag |
| `alpine:3.23` | The official image with that tag |
| `docker.io/library/alpine` | The full repository name, any tag |
| `docker.io/library/alpine:3.23` | The full name, as the **Images** page lists it |
| `164b5c6a230f` | The image ID, or a prefix of it of at least 12 characters |

* A name without a registry, such as `alpine` or `valkey/valkey`, means Docker Hub. An image from another registry is written in full, for example `quay.io/fedora/fedora:43`.
* A name without a tag is fine when one image of that repository is present. If several are present, `latest` is used if present; otherwise the build fails and lists the tags to choose from.
* Digest references (`alpine@sha256:…`) are not supported; use the image ID.
* The image must be allowed on the **Images** page under any of its names; the workflow may name it differently.
* A missing image fails the build with `Image … is not available on the build host`, and one that is not allowed with `Image … is not allowed`.

The build starts `FROM` the image ID it found, so it uses exactly that image even if a tag is moved during the build.

### Tools in every build image

Every build image contains `git`, `make`, `podman` and `podman-compose`. The build installs those the base image lacks, as root, with the image's package manager: `apk`, `apt-get`, `dnf`, `microdnf`, `yum` or `zypper`. The package names are the command names. The build fails if a tool cannot be installed, for example on an image without a supported package manager (such as a distroless image), or where the distribution does not package one of them. On Red Hat Enterprise Linux and UBI images, for instance, `podman-compose` is only in EPEL.

The base image also needs `sh`. Installing the tools downloads packages on every build. A base image that already contains them starts faster.

`podman` and `podman-compose` are installed, but containers cannot yet be started from a step: steps run as the unprivileged user `65534:65534`, with no subordinate UID range, no direct network, and inside the build's own container storage.

### Artifacts

`artifacts` lists files and directories to keep after the build, for passed and failed builds alike:

```yaml
artifacts:
  - name: Test results
    path: target/surefire-reports
  - name: Jars
    path: target/*.jar
```

| Key | Required | Meaning |
|-----|----------|---------|
| `artifacts` | no | Top level only. At most 20 entries. |
| `artifacts[].name` | yes | At most 200 characters, once in the list. |
| `artifacts[].path` | yes | A file or directory relative to `/workspace`. A directory is kept with everything below it. `*` is allowed in the last part only and, as in the shell, does not match a leading dot. |

* `path` is letters, digits and `. _ + @ -`, separated by `/`, with no `..`, `.` or empty parts, not starting with `/` or `-`, at most 1000 characters.
* The paths are collected when a step fails, before the build stops, and after the last step, before its files are removed. The step's exit code and the build's status do not change. A build that is cancelled or times out keeps no artifacts.
* A path that does not exist is a log line, `==> Artifact not found: …`, not a failure.
* The build installs `tar` with the other tools, and the steps' files are archived as `65534:65534`. Symbolic links are kept as links in the archive and then skipped (`==> Artifact skipped: … (symbolic link)`), so nothing outside `/workspace` is read through one. Hard links, devices and other special files are skipped as well.
* The log ends with `==> Artifacts: N files, M MiB`. A build stores at most the artifact limits, 100 MiB and 1000 files by default (see **Builds**); beyond that the rest is left out, the log shows `==> Artifacts truncated at …`, and the build page says so.
* Artifacts are listed on the build page, each with its size and a download button, plus **Download all** as a ZIP file. Downloading needs a signed-in user who can see the build, also on a public project. Files are always downloaded, never shown in the browser.
* Artifacts are kept with their build and deleted with it by retention or when the project is deleted; a pinned build keeps them.
* Artifacts work with Podman only. With Docker, a build whose workflow has `artifacts` fails with `Artifacts are not supported with docker`.
* The build mounts a directory at `/run/cisys-artifacts` for the archive. Steps can write there too; what is stored is checked the same way, but the space a step uses there while the build runs is not limited.

## How a workflow is built

A build turns the workflow into a Containerfile and builds an image from it with Podman (or Docker):

1. **Read.** The backend fetches the branch and records its commit ID. It reads `.cisys/workflows/ci.yml` and stores the text with the build, then validates it.
2. **Base.** `container` is looked up among the local images, and the image starts `FROM` its ID. Nothing is pulled.
   The build log then shows the generated Dockerfile, as it was written, under `==> Dockerfile:` and the command that builds it under `==> Command:`, so a failing step can be read against exactly what ran. With Podman every `RUN` is a quoted heredoc (`RUN <<'CISYS'` … `CISYS`), so the file reads as the shell it runs: the tool installation, the dependencies (passed as `set -- 'name' 'package' …`), the environment file and each step. Nothing in it is expanded by the builder. If a line of a step equals the delimiter, that `RUN` uses `CISYS_1` instead. Docker's legacy builder has no heredocs, so with Docker each `RUN` is in the JSON exec form, and the preparation image's `Dockerfile.prepare` is shown first. Neither contains a secret: the backend clones the branch itself.
3. **Tools and dependencies.** `git`, `make`, `podman` and `podman-compose` are installed as root if the image lacks them, then the workflow's `dependencies`. This step has network access.
4. **Checkout.** The checkout from step 1 is copied into `/workspace`, owned by the unprivileged user `65534:65534`, so the image builds exactly the recorded commit, within the checkout limits. The working tree is **on the branch being tested**, with its `.git` directory, so `git status`, `git log -1` and `git rev-parse HEAD` work. `HOME` is `/tmp`.
5. **Steps.** The jobs and steps run in order as `65534:65534`, in `/workspace`, **without network** (except through the egress proxy, see **Network for steps**), with the workflow's, the job's and the step's `environment` variables set. The log marks every job and step with `==> Job:` and `==> Step:` lines.
6. **Result.** The first failing step stops the build. The log shows `==> Step failed:` with the step name and exit code, and the build records that exit code and the status `FAILED`. When every step succeeds, the build is `PASSED`.
7. **Cleanup.** The images are removed when the build ends. The log, the workflow text, the commit ID and the base image digest are kept.

The whole image build has the build timeout, ten minutes by default. After that it is stopped and reported as timed out. Admins set the timeout under **Settings** → **Builds**; see **Builds**.

### Steps and shells

* Each `run` step runs in a new `sh -c`, so a `cd` or a variable set in a step does not carry over to the next step; use `environment` for variables every step needs. Files written to `/workspace` or `/tmp` do carry over.
* In a job **without** `wait` steps, each step is its own image layer (`RUN`). A process started in the background ends when its step ends.
* A job **with** a `wait` step runs as one `RUN`. A service started in the background by one step, such as `make platform-start`, therefore keeps running for the later steps of that job and stops when the job ends.
* A `wait` step checks the kernel's listening sockets (`/proc/net/tcp` and `/proc/net/tcp6`) once a second, for up to five minutes, so it needs only `sh` and `grep`. If the port is not listening by then, the step fails.

### Network for steps

By default, steps cannot reach the network. A step that downloads dependencies, such as `mvn package` or `npm install` without a local cache, fails. Either use a base image that already has them, or have an admin allow the registries under **Settings > Network**.

That page lists the allowed hosts like the **Projects** page lists projects: each host has a name, and admins add, edit and remove hosts one at a time. **Templates** add every host a well-known registry needs in one click: npm, Yarn, Maven Central, Gradle, PyPI, crates.io, Go modules, RubyGems and GitHub. A template skips hosts that are already on the list.

Once the list has hosts, every step gets a proxy for HTTPS:

* The step itself still has no network. The only way out is a socket to the backend's egress proxy. Each step starts `socat` on `127.0.0.1:3128` to forward to it, and the build installs `socat` along with the other tools.
* The build log shows what the network allows and what was used: `==> Network: HTTPS through the proxy to …` lists the allowed hosts and ports when the build starts, `==> Network connected: host:port (address)` appears once for each destination a step reached, and `==> Network denied: host:port (reason)` for each refusal. A tool that ignores the proxy never reaches it; it fails with an unknown host instead, as the step has no DNS.
* Each step first checks that it can reach the socket. If it cannot, the step fails with `==> Network proxy unreachable: /run/cisys/proxy.sock: …` and the reason, rather than with errors from `npm` or Maven.
* On SELinux hosts, such as Fedora and Red Hat Enterprise Linux, a container may not connect to a socket that the backend serves. Builds with network therefore run without SELinux labels (`--security-opt=label=disable`). They are still rootless, without privileges, as `65534:65534` and without network of their own. Builds without network keep their labels.
* `ALL_PROXY`, `HTTP_PROXY`, `FTP_PROXY` and `NO_PROXY` (in both cases) are cleared, and `HTTPS_PROXY` and `https_proxy` are set to `http://127.0.0.1:3128`, so `npm`, `pip`, `cargo`, `curl` and Git over HTTPS use the proxy. Java tools such as Maven and Gradle get `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128` through `JAVA_TOOL_OPTIONS`, plus `-Daether.connector.http.useSystemProperties=true`, without which Maven ignores the proxy and fails with `Unknown host repo.maven.apache.org`. Maven Central is `repo.maven.apache.org`; add it under **Settings** → **Network**.
* Only HTTPS goes through, on port 443 unless the server sets other ports in `cisys.build.network.ports`. Plain HTTP, SSH and other ports are refused. A refused connection is shown in the log as `==> Network denied: host:port (reason)`.
* The proxy allows hosts on the list only, or every public host with `*`. It never connects to loopback, private, link-local or metadata addresses (such as `169.254.169.254`), the backend host's own addresses, or the database and Keycloak hosts, even when a listed name resolves to one of them.
* This works with Podman only. With Docker, a build fails with `Network access is not supported with docker` while the list has hosts.

| Entry | Allows |
|-------|--------|
| `registry.npmjs.org` | That host only |
| `*.maven.org` | Every subdomain of `maven.org`, but not `maven.org` itself |
| `151.101.0.0/16` | Public addresses in the range, when a step connects to an address directly |
| `*` or `all` | Every public host (the **All public hosts** template) |

Private, loopback and link-local addresses and ranges cannot be entered. An empty list means no network.

`*` is still not unrestricted networking: protected addresses, the backend and its services stay unreachable, and only the allowed ports go through. But a step can then send data to any public host, so prefer naming the registries a build needs.

## Private repositories

For a private repository, an admin starts the build with a credential reference. The backend reads the token from its environment for its own fetch only. The image build does not receive it, so it is not written into a layer, the Containerfile, the build arguments or the log, and steps cannot read it. See **Private Git repositories** in the **Builds** chapter.

Never put secrets in a workflow file, because each build stores the file and shows it on the build page.

## Build reports

A workflow can ask for reports, which run after the jobs against the build's checkout. A report never changes the build's status: a report that fails or times out is noted in the build log and the build keeps its result. Each report is a tab on the build page, labelled with its `name`, which must be unique in the workflow. Output is kept up to 1 MiB, and a report is stopped after 10 minutes.

```yaml
reports:
  list:
    - name: List
  orangu:
    - name: Review
      prompt: /auto_review
```

* `list` lists the workflow's dependencies and the files of the checkout.
* `orangu` runs `orangu -p "<prompt>"` in the checkout and keeps what it prints, such as the review of `/auto_review`. `prompt` is required. The program runs on the cisys host, not in the build container, with no input.

### Settings > Reports

Admins set up report types under **Settings** → **Reports**: the type (the key under `reports`), the installed Java class that runs it, and its variables, one `key=value` per line. The `orangu` class takes `path`, the path to the orangu executable; without it `orangu` is looked up on the `PATH` of cisys. The path is set here and cannot be set from a workflow. A type without a setting uses the built-in class of the same name.

| Method | Path | Result |
| :----- | :--- | :----- |
| GET | `/api/report-plugins` | The configured types and the installed classes |
| PUT | `/api/report-plugins/{type}` | Sets the class and variables of a type |
| DELETE | `/api/report-plugins/{type}` | Removes the setting of a type |

All three are for admins. A class that is not an installed report type answers `400`.

## On the build page

The build page shows the workflow file the build read, under **Workflow**, and the Dockerfile the build generated from it, under **Dockerfile** (with Docker, the preparation file comes first). A queued build shows *Not read yet* and *Not generated yet*. A build that failed before it got that far shows *None*. Entries with more than one line show only their first line; the arrow in front of it unfolds the rest. **Image** shows the full name of the base image the build found, for example `docker.io/library/alpine:3.23`.

## Limits

* One workflow file per branch, at a fixed path. Builds can be started from the web UI or by the automatic-build timer; the global automatic-build settings, not the workflow file, decide whether new commits trigger builds and which branches are watched.
* All jobs run one after another in a single image build. Jobs do not run in parallel and have no separate images.
* Steps reach the network only through the HTTPS proxy, for the hosts an admin allowed, and there are no per-step timeouts or per-step caches. A project can keep one cache directory between builds, see **Cache** in the **Projects** chapter.
* `dependencies` names one package per entry for the container's distribution; there is no per-distribution choice of package names in a single workflow.
