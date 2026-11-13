\newpage

# Builds

A **build** runs a branch's workflow, `.cisys/workflows/ci.yml`, by building a container image from it on the backend host. The **Workflows** chapter describes the file and the image build.

![Manual build configuration](images/builds.png){ width=100% }

## Start a build

Leads and admins select **Build** next to a branch on a project page. That runs the branch's workflow. The **Build history** form starts a build by entering

* **Branch**: the branch to fetch
* **Credential reference** (admins only, optional): see below

Members can view build history and logs for the projects they can access. Private projects and their logs are visible only to their creator and admins.

## How a build runs

1. The branch is fetched afresh with JGit at depth 1, so a force push is reflected in the next build, and its commit ID is recorded. The checkout must stay within the size and file limits.
2. The branch's `.cisys/workflows/ci.yml` is read and stored with the build, then validated. A branch without the file, an invalid file, or a `container` image that is not on the build host or that an admin has not allowed on the **Images** page fails the build with a message in the log.
3. The workflow becomes a Containerfile. Podman builds it with `no-new-privileges`, 1 GiB of memory, one CPU and no layer cache. The image installs `git`, `make`, `podman` and `podman-compose` if they are missing and the workflow's `dependencies`, copies the checkout from step 1, and runs the steps as `65534:65534` without network. See **Workflows**.
4. The status is `PASSED` when the image build succeeds, otherwise `FAILED` with the exit code of the failing step. A build that exceeds ten minutes is stopped and reported as timed out. The image is removed afterwards.

Build requests are stored as `QUEUED` and claimed in request order. By default one build runs at a time. Set `BUILD_CONCURRENCY` (`cisys.build.concurrency`) to change the number of concurrent builds and `BUILD_QUEUE_LIMIT` (`cisys.build.queue-limit`, default 100) to limit waiting requests. Both limits must be positive. Admins can change both under **Settings** → **Queue** (1–16 concurrent builds, 1–1000 queued builds); saved values replace the configured ones. A full queue returns HTTP 429 with a clear error. Requests keep their repository URL, branch and credential reference; credentials themselves are read from the environment when execution begins.

A checkout is limited to `BUILD_MAX_CHECKOUT_MIB` (`cisys.build.max-checkout-mib`, default 1024) MiB and `BUILD_MAX_CHECKOUT_FILES` (`cisys.build.max-checkout-files`, default 100000) files. Both limits must be positive. Admins can change both under **Settings** → **Checkout** (1–102400 MiB, 1–1000000 files); saved values replace the configured ones. The fetched data is checked during the fetch, the files are measured as they will be written, after line ending conversion such as `eol=crlf` in `.gitattributes`, before checkout, and the working tree is measured again after checkout. The image copies this checkout and does not fetch the branch again. A repository over a limit fails the build with `Build failed: Repository checkout exceeds the limit of N MiB` (or `N files`) in the log, and the checkout directory is removed.

If saving a completed result fails, the runner retains it in memory and retries on each dispatcher tick without rerunning the build. Its slot stays reserved until the result is saved.

On restart, interrupted `RUNNING` builds become `FAILED`, or `CANCELLED` when a cancellation was requested, and retain their partial logs. `QUEUED` builds remain queued and run when a slot is available. Startup also removes leftover `cisys-build-*` containers and images and `cisys-*` checkout directories from an unclean stop; a cleanup failure is logged and does not stop startup. Queued and running builds are protected from retention cleanup and prevent project deletion. This runner supports one backend instance; startup recovery assumes the previous instance has stopped.

## Cancel a build

Admins, the project's creator and whoever started a build can cancel it while it is `QUEUED` or `RUNNING` with the cancel button on its build page. A queued build becomes `CANCELLED` at once and never runs. For a running build, the runner stops the fetch or the image build within about a second, forcibly after ten more seconds, removes the image and records `CANCELLED` with `Build cancelled` at the end of the log. A cancellation that arrives while the build is finishing still wins. Cancelling a finished build returns HTTP 409.

## Workflow file

The workflow file format, how the image is built, and its limits are described in the **Workflows** chapter.

## What a build records

Each build stores the requested branch, the text of the workflow file it read (shown on the build page), the full name of the base image it found, the credential reference name if one was used (shown to admins only), the base image digest when the container runtime reports one, the commit ID, the exit code of the image build, which is that of the failing step (none when the build timed out, was cancelled or never ran), who started it, and when it started and finished; the duration is derived from these times.

## Build logs

The complete output of the build, standard output and standard error together, is stored in PostgreSQL as one large object per build, appended while the build runs. It is stored as the raw bytes the build wrote and shown as UTF-8. Output from a running build is visible within about five seconds; use **Refresh** on the build.

* `BUILD_LOG_MAX_BYTES` (default 1 GiB) is a safety limit per build, not a normal cap. Output beyond it is read and discarded, a marker line is added, and the build is marked as truncated. The limit is exact in bytes, so if it falls inside a multi-byte character, the last character shown may be incomplete.
* A build is also marked as truncated if the log could not be stored completely. Logs from before this version were capped at 120 KB; those that reached the cap are marked as truncated.
* The build page shows the first 2 MiB of a log. For a running build, **Refresh** appends only the new output instead of downloading the log from the start; once 2 MiB are shown, it switches to the latest 2 MiB so new output stays visible. **Download log** saves the full log; in Chromium-based browsers it is written straight to the chosen file, in other browsers it is held in browser memory first. The backend never holds a whole log in memory.
* A visitor who is not signed in can read at most `PUBLIC_LOG_MAX_BYTES` (2 MiB by default) of a log in one response. The page requests the log in chunks and retries a rate-limited page after `Retry-After` from the same offset, so **Download log** can save a log larger than the limit, even across rate-limit windows. Signed-in users, including the owner of a private project, are not capped.
* Public pages pause their refresh while the tab is hidden and refresh less often when no build is running. Anonymous requests to a public page, its branches, its logs and the public statistics are also rate limited; a client that sends too many requests is told to wait and retry.

## Pinned builds

The project's creator and admins can **Pin** a build on its build page, and **Unpin** it again. A pinned build, with its log, is kept permanently; build retention cleanup skips it. Pinned builds are marked with a pin icon in the build history.

## Build retention

Every hour, and a minute after startup, cisys deletes old builds of each project together with their logs. A finished or cancelled, unpinned build is deleted when either

* it finished more than the maximum age ago (default 30 days), or
* the project has more newer finished or cancelled, unpinned builds than the maximum count (default 50).

Admins change both limits under **Settings** → **Retention**; `0` turns a limit off. Running and pinned builds are never deleted, and pinned builds do not count towards the limit. Deleted builds cannot be restored, so pin a build to keep it. `BUILD_RETENTION_EVERY` (default `1h`) changes how often the cleanup runs.

## Repository URLs

Only `github.com` and `gitlab.com` HTTPS URLs are accepted for builds. Redirects, embedded credentials, query strings, fragments and non-default ports are rejected.

## Private Git repositories

Set an environment variable whose name starts with `CISYS_GIT_`, for example `CISYS_GIT_PROJECT`, to a read-only provider token in the backend environment before starting it. Then, as an admin, enter that variable name in the build form.

* The value is never stored in the project database. Only the backend's fetch uses it; the image build does not receive it, so it is not stored in the image and steps cannot read it.
* Only admins can use credential references until credentials are scoped to individual projects.
* Never include secrets in workflow files or build output.

## Images

Admins manage build images under **Images** in the header. The page lists the images present in the container runtime on the backend host, with their ID, size and age.

* **Pull** downloads an image from its registry, for example `docker.io/library/alpine:3.23`.
* **Remove** deletes a local image and takes it off the allowed list.
* Tick the images that builds may use and select **Save**. A workflow whose `container` resolves to any other image fails. A workflow may name an allowed image by its short name, full name or ID; see **Workflows**.

An image that is allowed but no longer present locally is listed separately, where it can be pulled again or cleared.

## Container runtime

The runner is always on. It uses Podman when it is installed and Docker otherwise.

**Trust boundary:** the runner allows trusted leads and admins to execute code through the local container daemon. Docker socket access grants host-level control to the backend. These limits are not a hostile-code sandbox. Use a dedicated machine, disk quotas and trusted repositories, and do not expose this setup to the Internet. Repository checkout sizes are not bounded. Installing the build tools and cloning the branch happen with network access, as root and as `65534:65534` respectively; the workflow's steps run without network.

## Verification status

The image build was verified with Podman 5.8 on Linux with `alpine:3.23` and `debian:13-slim` base images and a public GitHub repository. With Docker it needs BuildKit, and the memory and CPU limits are not applied. It has not been verified end-to-end with a real private Git provider, Docker, or Windows/WSL.
