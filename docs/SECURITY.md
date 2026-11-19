# Security

This is an early-stage project. The default configuration is for a **trusted local machine only**: it uses development credentials, plain HTTP, Keycloak development mode and permissive database schema management. Do not expose it to the Internet.

To report a vulnerability, contact the maintainers privately (see [AUTHORS](../AUTHORS)) rather than opening a public issue.

## Roles

The Keycloak realm roles are `member`, `lead` and `admin`. A user without one of these roles is rejected with `403 Forbidden`.

The default users installed by `contrib/keycloak/realm.json` in the development environment are

* Member: `member` / `member`
* Lead: `lead` / `lead`
* Admin: `admin` / `admin`

The Keycloak admin console (<http://localhost:8180/admin>) uses `admin` / `admin`. The `cisys-backend` service account, which signs users out when cisys shuts down and manages users for the Users page, uses the secret `cisys` and needs the realm-management roles `manage-users`, `view-users` and `view-realm`. A realm imported before these roles were added to `contrib/keycloak/realm.json` needs them granted in the Keycloak admin console, or `make reset`. Replace all of these credentials before any shared deployment.

### Role-to-endpoint mapping

| Endpoint                                  | Member                    | Lead                 | Admin |
| :---------------------------------------- | :------------------------ | :------------------- | :---- |
| `GET /api/session`                        | yes                       | yes                  | yes   |
| `GET /api/projects`                       | public and assigned       | public, own and assigned | all |
| `POST /api/projects`                      | no                        | yes                  | yes   |
| `GET /api/projects/{id}/builds[/{id}]`    | visible projects          | visible projects     | all   |
| `GET /api/projects/{id}/branches`         | visible projects          | visible projects     | all   |
| `POST /api/projects/{id}/builds`          | no                        | yes                  | yes   |
| `POST .../builds/{id}/rerun`              | assigned projects         | assigned projects    | yes   |
| `POST` build with a `credentialRef`       | no                        | no                   | yes   |
| `PUT`/`DELETE .../builds/{id}/pin`        | no                        | own projects         | yes   |
| `POST .../builds/{id}/comments`           | assigned projects         | assigned projects    | yes   |
| `PUT .../builds/{id}/comments/{id}`       | own comments              | own comments         | own comments |
| `GET /api/projects/{id}/team`             | assigned members          | assigned members     | all   |
| `POST /api/projects/{id}/team`            | no                        | assigned (`LEAD`)    | yes   |
| `PUT`/`DELETE /api/projects/{id}/team/{username}` | no                        | assigned (`LEAD`)    | yes   |
| `GET /api/branding`                       | yes                       | yes                  | yes   |
| `PUT /api/branding`                       | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/retention`       | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/builds`          | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/checkout`        | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/polling`         | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/pull-requests`   | no                        | no                   | yes   |
| `GET /api/images` (allowed images)        | yes                       | yes                  | yes   |
| `PUT /api/images`                         | no                        | no                   | yes   |
| `GET/POST/DELETE /api/images/local`       | no                        | no                   | yes   |
| `GET /api/users`, `GET /api/users/projects` | no                      | users of own projects; members when searching | all |
| `POST /api/users`                         | no                        | members in own projects | yes |
| `PUT /api/users/{id}`                     | no                        | a member's own projects | yes |
| `PUT /api/users/{id}/password`            | no                        | members of own projects | yes |
| `DELETE /api/users/{id}`                  | no                        | no                   | yes, not themselves |

Without signing in, `GET /api/branding/public`, `GET /api/branding/logo`, `GET /api/stats/public`, the public project page (`GET /api/project-pages/{name}`, `GET /api/project-pages/{name}/builds/{buildId}` with its build logs and comments) and `GET /api/project-pages/{name}/branches` for public projects are readable. The project page accepts an optional `branch` query parameter, which filters the builds but applies the same public/private visibility rules. The statistics are aggregate counts computed from public projects only (public projects, their builds by status, success rate, last build time). Private projects and their builds are left out of every number, and no names, repository URLs, logs or users are exposed. The server caches the result for `STATS_CACHE_SECONDS` (default 30) seconds. The branch endpoint returns remote branch names for public projects. For private projects it requires the same access as the page (project member or admin) and returns only the branches that already have builds, so the private repository is never read; anonymous callers and users without access receive `404`.

Private projects (and their build history and logs) are visible only to their members and admins. A project's leads and members are users assigned to it with roles `LEAD` and `MEMBER` respectively; the creator is automatically assigned `LEAD` when the project is created. Visibility is recorded with the immutable Keycloak subject (`sub`), not the user name. A project that is not visible to the caller is reported as `404 Not Found`.

User subjects are returned to admins only:

| Field                                         | Member | Lead | Admin |
| :-------------------------------------------- | :----- | :--- | :---- |
| Project `owned` (the caller owns the project) | yes    | yes  | yes   |
| Project `ownerSubject`                        | no     | no   | yes   |
| Build `requestedBy`                           | no     | no   | yes   |
| Build `credentialRef`                         | no     | no   | yes   |

The public project page and the public statistics never include them.

The private flag controls visibility inside cisys; it does not grant or restrict Git access.

Automatic builds fetch each project's repository on a server-side timer; nothing in the repository or a provider needs to reach cisys, so there is no inbound push endpoint to authenticate. Polling uses the same host, port and redirect rules as a manual build, and it revalidates the URL immediately before every fetch because DNS can change after a project was saved.

## Authentication

* The browser signs in with the Keycloak authorization-code flow with PKCE (`S256`) as the public client `cisys-ui`. Direct access grants are disabled in the development realm.
* The backend validates bearer access tokens and reads roles from `realm_access/roles`.
* CORS allows `http://localhost:5173` only. Change `quarkus.http.cors.origins` for any other frontend origin.

## Health and metrics

The backend serves four monitoring endpoints from the Quarkus health and metrics extensions on the **management interface**, a separate HTTP server on port `9000`:

| Endpoint | Purpose |
| :------- | :------ |
| `GET /health` | All health checks of the application |
| `GET /health/live` | The application is running |
| `GET /health/ready` | The application can serve requests |
| `GET /metrics` | Metrics in the Prometheus exposition format |

* They are plain HTTP routes, not Jakarta REST resources. The `@Authenticated` rules of the API do not reach them, and the public rate limiter (`PublicRateLimitFilter`, bound with `@PublicRateLimited` to the anonymous `/api` resources) does not count them. Every other endpoint keeps the authentication described above.
* The management interface has no authentication by default: it installs no authentication mechanism and ignores an `Authorization` header. A token does not protect it. Do not expose port `9000` to untrusted networks; keep it on an internal network or behind a firewall.
* In a production build the management interface binds to `0.0.0.0`, like the main HTTP server. Override the address with `quarkus.management.host` or the environment variable `QUARKUS_MANAGEMENT_HOST` to bind it to `localhost` or an internal address.
* `/health/ready` includes the check **Database connections health check**, which validates the PostgreSQL connection. It reports `DOWN` when the database is unreachable, so it is the readiness signal of the instance. `/health/live` stays `UP` while the application runs, so it is the liveness signal.
* `/metrics` reports operational information about the process, among it the JVM memory and threads, the HTTP request counters and the database connection pool. It is unauthenticated on purpose, because there is no authentication on the management interface.
* The Health UI at `/health-ui` is disabled with `quarkus.smallrye-health.ui.enabled=false`.

## Build runner

The runner is always on. It lets leads and admins run code from a Git repository in a container on the backend host, using only images an admin has allowed on the **Images** page.

**Trust boundary:** access to the container engine (in particular the Docker socket) grants host-level control to the backend. The limits below are not a hostile-code sandbox. Use a dedicated Linux machine, disk quotas and trusted repositories only.

Controls that are in place

* Only HTTPS repositories on an allowed host are accepted for builds and branch discovery; redirects, embedded credentials, query strings, fragments and non-default ports are rejected. See [Repository addresses](#repository-addresses).
* Branch discovery for private projects is not attempted because credentials are not stored on the project; the endpoint returns an empty list with reason `private_repository`.
* Branch names are validated as Git references. Builds may only use images an admin has allowed; image names are validated against a restrictive pattern.
* The branch is fetched afresh with JGit at depth 1 and its commit ID is recorded. The build then builds an image from the workflow: it installs `git`, `make`, `podman` and `podman-compose` and the workflow's `dependencies` as root, with network access, copies that checkout into the image as `65534:65534`, and runs the steps as `65534:65534` without network, except through the egress proxy (see [Network for build steps](#network-for-build-steps)). Only the installs have network access; the image build does not fetch the repository again.
* A checkout is limited to `BUILD_MAX_CHECKOUT_MIB` (default 1024 MiB) and `BUILD_MAX_CHECKOUT_FILES` (default 100000 files); admins can change both under Settings. The received pack is capped while it is fetched, the files are measured as they will be written, after line ending conversion, before checkout, and the written working tree is measured again after checkout. A checkout over a limit fails the build with `Build failed: Repository checkout exceeds the limit of …` and its directory is removed.
* The image build runs with 1 GiB of memory, one CPU and 512 processes by default under both Docker and Podman; admins can change the memory, CPU and process limits under Settings → Performance. Podman also runs the build with `no-new-privileges`. Docker uses its legacy builder because BuildKit ignores these limits; the tools and dependencies build first with network, then the steps build from that image with network off. The process limit is `ulimit nproc` for the unprivileged build user. The images are removed when the build ends.
* Builds run up to the concurrency limit (default 1, at most 16 when set by an admin); further requests wait in a bounded queue. Each build times out after `BUILD_TIMEOUT_MINUTES` (`cisys.build.timeout-minutes`, default 10) minutes, or the timeout an admin sets under Settings → Builds (1–1440 minutes). Output is stored in full, up to the `BUILD_LOG_MAX_BYTES` safety limit (default 1 GiB); output beyond it is discarded and the build is marked truncated.
* Builds left in the `RUNNING` state by a server restart are marked `FAILED` at startup. Startup also removes leftover `cisys-build-*` containers and images and `cisys-*` checkout directories from an unclean stop; a cleanup failure is logged and does not stop startup.

### Network for build steps

Steps run with `--network=none`. An admin can allow destinations under **Settings → Network**, stored as `NetworkHost` rows; no hosts (the default) means steps have no network at all.

How it is enforced

* With a non-empty list, the backend starts one `EgressProxy` per build on a Unix socket in the build's private temporary directory (`0700`). The socket's own directory is `0711`, so the step user can reach the socket but not list it. Podman mounts that directory read-only into every `RUN` (`--volume …:/run/cisys:ro,Z`). SELinux does not let a container (`container_t`) connect to a Unix socket served by a host process, and has no boolean for it, so builds with network run with `--security-opt=label=disable`. They lose SELinux confinement but keep rootless Podman, `no-new-privileges`, the user `65534:65534`, `--network=none` and the resource limits. Builds without network keep their SELinux labels. Each step tries the socket before it starts; if it cannot connect, the step fails with `==> Network proxy unreachable: … (reason)`. The step keeps `--network=none`; `socat` forwards `127.0.0.1:3128` in the step to the socket. Bypassing the proxy variables therefore gains nothing: there is no other route out.
* The proxy accepts HTTP `CONNECT host:port` only. The port must be in `cisys.build.network.ports` (`BUILD_NETWORK_PORTS`, default `443`). The host must match an entry: an exact name, `*.suffix` for subdomains, a public IP or CIDR range, or `*` (also `all`) for every public host. `*` does not lift any of the denials below.
* Protected destinations are always denied, whatever the list says: loopback, private, CGNAT, link-local (including `169.254.169.254`), multicast, documentation and reserved ranges, IPv4-mapped, IPv4-compatible, NAT64, 6to4 and Teredo forms, every address of the backend host's interfaces, and the hosts and addresses of the datasource, Keycloak and `quarkus.http.host`. Entries that are or overlap protected ranges are rejected when saved.
* The proxy resolves the host once, checks every address it resolves to, and connects to the checked address, so a DNS change cannot redirect an approved tunnel.
* Fail closed: an unresolvable host, a protected address, a refused or timed-out connection, or any other request is denied and logged in the build log as `==> Network denied: host:port (reason)`. A stored list that no longer validates, a proxy that cannot start, or internal service hosts that cannot be resolved fail the build. A build never falls back to ordinary networking.
* The proxy limits concurrent connections (64), the time to send the request (10 s), the connect time (10 s) and idle tunnels (5 min). It is closed, and its socket and directory removed, on every exit path of the build.

Docker and Podman

* Tested on Podman 4.9 and Docker 29.9 with BuildKit (buildx 0.38), on 2026-11-14. With rootless Podman, the mounted socket worked from `RUN --network=none` as `65534:65534`, and so did several concurrent connections, while direct connections failed with *Network is unreachable*.
* Docker's builder can forward a socket only through `--ssh`, which accepts the connection but passes no data unless it speaks the SSH agent protocol. Docker builds cannot mount host directories. With Docker, a build with a non-empty list therefore fails with `Network access is not supported with docker`.

Residual risk

* The proxy sees only the host and port, not the paths or the content inside TLS. Any allowed host can receive whatever a step sends it, so an allowed host is a possible exfiltration channel; a wildcard such as `*.example.com` widens that to every subdomain. Allow only registries you trust, and prefer exact names.
* `*` widens that to every public host: a step can then upload to any server on the Internet. It still cannot reach protected addresses, the backend or its services, or ports outside `cisys.build.network.ports`.
* Installing the tools and dependencies and cloning the branch still have ordinary network access, as before.

### Repository addresses

* Only HTTPS repositories on `cisys.git.allowed-hosts` are accepted; the default is `github.com` and `gitlab.com`.
* Only ports listed in `cisys.git.allowed-ports` are accepted; the default is `443`, and a URL without a port counts as `443`.
* Every address the host resolves to is checked. Loopback, private, link-local, multicast, documentation and metadata ranges are rejected, including IPv4-mapped IPv6 forms such as `::ffff:127.0.0.1`. An administrator can allow an internal range explicitly through `cisys.git.allowed-networks`.
* A host listed in `cisys.git.insecure-tls-hosts` is fetched without certificate verification, for a local server with a self-signed certificate. Prefer adding the certificate to the JVM truststore instead; the list turns verification off for that host only, and the host must still be on the allowlist and pass the address check.
* The check runs when a project is created, when a build starts, and again immediately before JGit fetches, because DNS can change after a project was saved. A host that resolves to a blocked address fails the build with `Build failed: The repository host resolves to a blocked address`.
* Branch discovery reads the project's own repository URL, never a URL supplied by the caller.

Known gaps

* Runner and Podman operation on Windows/WSL has not been verified.
* Credentials are not scoped to individual projects; only admins may reference one.
* DNS rebinding between the address check and JGit's own connection is not fully prevented, because JGit resolves the host again when it connects.

### Automatic builds

Automatic builds are a global, admin-only switch. A server-side timer polls each project's repository and queues a build for every new commit; no push hook, provider API or inbound endpoint is involved, so the same code path serves GitHub, GitLab, self-hosted and private repositories.

* The timer keeps one bare mirror per project under `cisys.poll.mirror-dir` (`POLL_MIRROR_DIR`), outside the temporary build area and rejected if it is under `java.io.tmpdir`; directories are owner-only where the OS supports it. It fetches all branch heads with forced refs and no automatic garbage collection, so a commit a queued build still needs stays readable even after it leaves the remote; each project's mirror has its own lock, and the URL rules above are revalidated before every fetch. The size limit is checked after the fetch completes, so one fetch can exceed it before polling stops for that project.
* With the default settings (on, all branches whose tip has `ci.yml`, one build per commit), anyone who can push a commit to such a branch can make the backend run its container build. Treat repository write access as build-trigger access.
* A private project without a `CISYS_GIT_*` credential reference is skipped and logged once, never polled anonymously. A project whose fetch keeps failing is logged once and retried later; one failing project never stops the tick for the others.
* The first sighting of a branch seeds its tip without building, so enabling automatic builds never builds history, and a commit already built for the project is never queued twice, whatever branch reaches it. The last-seen commit of a branch advances only after a commit was queued or found to have been built, so a full build queue slows polling down instead of dropping commits.
* A build whose commit is missing from the mirror fails with a clear message rather than building the branch tip. Turning automatic builds off deletes every branch cursor; mirrors are deleted only when pull request builds are also off, and a project with a queued or running poll or pull request build keeps its mirror until that build finishes.

**Trust boundary:** automatic execution runs the repository's code in a container on the backend host. That the commit was discovered by cisys says nothing about whether the code is safe. Keep automatic builds for trusted repositories, and do not expose the rest of the application, until isolated build workers exist.

### Pull request builds

Pull request and merge request builds are a separate, admin-only global switch, off by default. Discovery reads GitHub `refs/pull/N/head` and GitLab `refs/merge-requests/N/head` from `github.com` and `gitlab.com`; merge refs are never used, and there is still no inbound endpoint, provider API or webhook.

* There is no approval step in 0.1.0. The switch is global and admin-only; write access to a repository therefore allows a user to trigger a build of that repository's code. Fork heads are never fetched into the mirror; a fork commit that a same-repository branch already reaches is built like any other commit.
* A head is built only when its commit is in the project's trusted mirror, a local branch reaches it, and `.cisys/workflows/ci.yml` exists at that commit. Only the head is built. Discovery is independent of the branch scope and of the automatic-build switch.
* Reachability does not prove that the head came from the base repository. A fork pull request whose head commit already exists in the base repository is built, because the poller already trusts that commit.
* A pull request build never resolves or receives a project credential or any other secret. Its checkout comes from the project mirror, and if the mirror or the commit is missing the build fails closed instead of fetching the repository remotely. Discovery and the mirror fetch may use the project credential, like automatic builds.
* If the project already has a usable build for the same commit, no new pull request build is queued and the existing build keeps its trigger. A usable build is any build whose status is not `CANCELLED`. Every successful discovery pass cancels queued pull request builds whose commit no longer belongs to any tracked, eligible head; running, finished, poll and manual builds are left alone, and a queued build cancelled this way is queued again if its commit becomes needed later. A build cancelled by a user or admin, or one that had already started, is never revived; under the one-row-per-commit rule such a row keeps covering that commit.
* Turning the feature off clears every project's discovery cursors and baseline as part of the setting change, even while a project is being polled, and re-enabling seeds the heads that exist then instead of building changes made while the feature was off. Branch polling and its cursors are unaffected. Changing the repository URL resets the cursors. Discovery was verified once for each provider, on public repositories without a credential: GitHub still listed `refs/pull/1/head` for closed `octocat/Hello-World` pull request 1, and GitLab still listed `refs/merge-requests/3/head` for merged `gitlab-org/gitlab-test` merge request 3. Not verified: whether a head ref updates after a push, whether GitLab deletes merge request refs after some time, and private repositories read with a credential.
* The same container, network and resource controls as other builds apply. Pull request code is still untrusted repository code, and building it executes that commit on the backend host.

### Git credentials

For private repositories set an environment variable named `CISYS_GIT_<NAME>` to a read-only provider token in the backend environment before starting it. The value is never stored in the database. Only the backend's JGit fetch uses it; the image build does not receive it, so it is not in the image and steps cannot read it. Never place secrets in workflow files or build output; each build stores its workflow file.

* An admin can store the **variable name** on a private project when creating it, and change or clear it later on the project edit form (`PUT /api/projects/{id}`) or through `PUT /api/projects/{id}/credential`. Only the name is stored; it is returned to admins and left out for everyone else.
* Branch discovery uses that credential for the project page. Without one, a private project keeps showing only the branches that already have builds.
* The credential belongs to the repository. When a lead changes the repository URL, the credential is cleared, so it is never sent to a new host. Admins may change the URL and the credential in the same request.
* Builds keep using the per-build credential reference from the build form. A project credential is not used for builds.
* A credential cannot be set on a public project, and the name must match `CISYS_GIT_[A-Z0-9_]{1,80}`.

## Public access limits

The public project page, its builds, logs and branches, and the public statistics are readable without signing in.

* An anonymous log request returns at most `cisys.public.log-max-bytes` (default 2 MiB) in one response. `offset`, `limit` and `tail` select a range; the `X-Log-Size`, `X-Log-Offset` and `X-Log-Length` headers let a client continue where the response ended. The UI pages through the limit for **Download log**; when a page is rate limited it waits for `Retry-After` and continues from the same offset, so a large anonymous download completes across windows.
* Signed-in visitors are not capped on this endpoint, so the build page keeps the complete log and the full download works as before. This assumes accounts are trusted; if registration is open, an account can be used to bypass the anonymous limits.
* Anonymous requests to `/api/project-pages/**` (including branch discovery, which runs `git ls-remote`) and `/api/stats/public` are counted per client address in a fixed window: `cisys.public.rate-limit.requests` per `cisys.public.rate-limit.window-seconds` (default 120 per 60 s). Exceeding it returns `429` with `Retry-After`. Signed-in callers are not counted.
* The counter lives in this process: it resets on restart, is not shared between instances, and tracks at most `cisys.public.rate-limit.max-clients` addresses (`0` removes the bound). When that many live windows exist, one window is evicted and the new visitor admitted, so a flood of addresses cannot lock new visitors out, and purging and eviction stay constant-time per request.
* Behind a reverse proxy, set `quarkus.http.proxy.proxy-address-forwarding=true` and set `quarkus.http.proxy.trusted-proxies` to the proxy's address or range. Otherwise every visitor shares the proxy's address and therefore one bucket.
* The proxy must replace `X-Forwarded-For` with the client's address instead of appending to it (in nginx, `$remote_addr`, not `$proxy_add_x_forwarded_for`). Quarkus uses the left-most address, so an appended header lets a client choose its own address and evade the limit.

## TLS

The application is not configured for TLS. Terminate TLS in a reverse proxy, or configure the [Quarkus TLS registry](https://quarkus.io/guides/tls-registry-reference), before any shared deployment. Keycloak must also be moved off `start-dev` and onto HTTPS in that case.

See [ROADMAP.md](ROADMAP.md) for the planned hardening work.
