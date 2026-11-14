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
* Packages are installed as root, with network access. The steps themselves still run as `65534:65534` without network.
* All dependencies are installed together, in one step, before the checkout is copied in, so every job and step can use them. `dependencies` on a job or a step is rejected.
* A package that does not exist, or a container without a supported package manager, fails the build with `==> Could not install …` or `==> Cannot install dependencies …`.
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

`podman` and `podman-compose` are installed, but containers cannot yet be started from a step: steps run as the unprivileged user `65534:65534`, with no subordinate UID range, no network, and inside the build's own container storage.

## How a workflow is built

A build turns the workflow into a Containerfile and builds an image from it with Podman (or Docker):

1. **Read.** The backend fetches the branch and records its commit ID. It reads `.cisys/workflows/ci.yml` and stores the text with the build, then validates it.
2. **Base.** `container` is looked up among the local images, and the image starts `FROM` its ID. Nothing is pulled.
3. **Tools and dependencies.** `git`, `make`, `podman` and `podman-compose` are installed as root if the image lacks them, then the workflow's `dependencies`. This step has network access.
4. **Checkout.** The checkout from step 1 is copied into `/workspace`, owned by the unprivileged user `65534:65534`, so the image builds exactly the recorded commit, within the checkout limits. The working tree is **on the branch being tested**, with its `.git` directory, so `git status`, `git log -1` and `git rev-parse HEAD` work. `HOME` is `/tmp`.
5. **Steps.** The jobs and steps run in order as `65534:65534`, in `/workspace`, **without network**, with the workflow's, the job's and the step's `environment` variables set. The log marks every job and step with `==> Job:` and `==> Step:` lines.
6. **Result.** The first failing step stops the build. The log shows `==> Step failed:` with the step name and exit code, and the build records that exit code and the status `FAILED`. When every step succeeds, the build is `PASSED`.
7. **Cleanup.** The image is removed when the build ends. The log, the workflow text, the commit ID and the base image digest are kept.

The whole image build has ten minutes. After that it is stopped and reported as timed out.

### Steps and shells

* Each `run` step runs in a new `sh -c`, so a `cd` or a variable set in a step does not carry over to the next step; use `environment` for variables every step needs. Files written to `/workspace` or `/tmp` do carry over.
* In a job **without** `wait` steps, each step is its own image layer (`RUN`). A process started in the background ends when its step ends.
* A job **with** a `wait` step runs as one `RUN`. A service started in the background by one step, such as `make platform-start`, therefore keeps running for the later steps of that job and stops when the job ends.
* A `wait` step checks the kernel's listening sockets (`/proc/net/tcp` and `/proc/net/tcp6`) once a second, for up to five minutes, so it needs only `sh` and `grep`. If the port is not listening by then, the step fails.

### No network for steps

Steps cannot reach the network. A step that downloads dependencies, such as a `mvn` or `npm install` without a local cache, fails. Use a base image that already contains the tools and dependencies the build needs.


## Private repositories

For a private repository, an admin starts the build with a credential reference. The backend reads the token from its environment for its own fetch only. The image build does not receive it, so it is not written into a layer, the Containerfile, the build arguments or the log, and steps cannot read it. See **Private Git repositories** in the **Builds** chapter.

Never put secrets in a workflow file, because each build stores the file and shows it on the build page.

## On the build page

The build page shows the workflow file the build read, under **Workflow**. A queued build shows *Not read yet*. A build that failed before it could read the file shows *None*. **Image** shows the full name of the base image the build found, for example `docker.io/library/alpine:3.23`.

## Limits

* One workflow file per branch, at a fixed path. There is no trigger on push yet; builds are started from the web UI.
* All jobs run one after another in a single image build. Jobs do not run in parallel and have no separate images.
* Steps have no network access, and there are no per-step timeouts, caches or artifacts.
* `dependencies` names one package per entry for the container's distribution; there is no per-distribution choice of package names in a single workflow.
