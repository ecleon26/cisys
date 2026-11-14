# Security

This is an early-stage project. The default configuration is for a **trusted local machine only**: it uses development credentials, plain HTTP, Keycloak development mode and permissive database schema management. Do not expose it to the Internet.

To report a vulnerability, contact the maintainers privately (see [AUTHORS](../AUTHORS)) rather than opening a public issue.

## Roles

The Keycloak realm roles are `member`, `lead` and `admin`. A user without one of these roles is rejected with `403 Forbidden`.

The default users installed by `contrib/keycloak/realm.json` in the development environment are

* Member: `member` / `member`
* Lead: `lead` / `lead`
* Admin: `admin` / `admin`

The Keycloak admin console (<http://localhost:8180/admin>) uses `admin` / `admin`. The `cisys-backend` service account, which signs users out when cisys shuts down, uses the secret `cisys` and can manage the realm's users. Replace all of these credentials before any shared deployment.

### Role-to-endpoint mapping

| Endpoint                                  | Member                    | Lead                 | Admin |
| :---------------------------------------- | :------------------------ | :------------------- | :---- |
| `GET /api/session`                        | yes                       | yes                  | yes   |
| `GET /api/projects`                       | public projects           | public and own       | all   |
| `POST /api/projects`                      | no                        | yes                  | yes   |
| `GET /api/projects/{id}/builds[/{id}]`    | visible projects          | visible projects     | all   |
| `GET /api/projects/{id}/branches`         | visible projects          | visible projects     | all   |
| `POST /api/projects/{id}/builds`          | no                        | yes                  | yes   |
| `POST` build with a `credentialRef`       | no                        | no                   | yes   |
| `PUT`/`DELETE .../builds/{id}/pin`        | no                        | own projects         | yes   |
| `GET /api/branding`                       | yes                       | yes                  | yes   |
| `PUT /api/branding`                       | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/retention`       | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/queue`           | no                        | no                   | yes   |
| `GET`/`PUT /api/settings/checkout`        | no                        | no                   | yes   |
| `GET /api/images` (allowed images)        | yes                       | yes                  | yes   |
| `PUT /api/images`                         | no                        | no                   | yes   |
| `GET/POST/DELETE /api/images/local`       | no                        | no                   | yes   |

Without signing in, `GET /api/branding/public`, `GET /api/branding/logo`, `GET /api/stats/public`, the public project page (`GET /api/project-pages/{name}`, `GET /api/project-pages/{name}/builds/{buildId}` and its build logs) and `GET /api/project-pages/{name}/branches` for public projects are readable. The project page accepts an optional `branch` query parameter, which filters the builds but applies the same public/private visibility rules. The statistics are aggregate counts computed from public projects only (public projects, their builds by status, success rate, last build time). Private projects and their builds are left out of every number, and no names, repository URLs, logs or users are exposed. The server caches the result for `STATS_CACHE_SECONDS` (default 30) seconds. The branch endpoint returns remote branch names for public projects. For private projects it requires the same owner/admin access as the page and returns only the branches that already have builds, so the private repository is never read; anonymous callers and users without access receive `404`.

Private projects (and their build history and logs) are visible only to their creator and admins. Ownership is recorded with the immutable Keycloak subject (`sub`), not the user name. A project that is not visible to the caller is reported as `404 Not Found`.

User subjects are returned to admins only:

| Field                                         | Member | Lead | Admin |
| :-------------------------------------------- | :----- | :--- | :---- |
| Project `owned` (the caller owns the project) | yes    | yes  | yes   |
| Project `ownerSubject`                        | no     | no   | yes   |
| Build `requestedBy`                           | no     | no   | yes   |
| Build `credentialRef`                         | no     | no   | yes   |

The public project page and the public statistics never include them.

The private flag controls visibility inside cisys; it does not grant or restrict Git access.

## Authentication

* The browser signs in with the Keycloak authorization-code flow with PKCE (`S256`) as the public client `cisys-ui`. Direct access grants are disabled in the development realm.
* The backend validates bearer access tokens and reads roles from `realm_access/roles`.
* CORS allows `http://localhost:5173` only. Change `quarkus.http.cors.origins` for any other frontend origin.

## Build runner

The runner is always on. It lets leads and admins run code from a Git repository in a container on the backend host, using only images an admin has allowed on the **Images** page.

**Trust boundary:** access to the container engine (in particular the Docker socket) grants host-level control to the backend. The limits below are not a hostile-code sandbox. Use a dedicated Linux machine, disk quotas and trusted repositories only.

Controls that are in place

* Only HTTPS repositories on an allowed host are accepted for builds and branch discovery; redirects, embedded credentials, query strings, fragments and non-default ports are rejected. See [Repository addresses](#repository-addresses).
* Branch discovery for private projects is not attempted because credentials are not stored on the project; the endpoint returns an empty list with reason `private_repository`.
* Branch names are validated as Git references. Builds may only use images an admin has allowed; image names are validated against a restrictive pattern.
* The branch is fetched afresh with JGit at depth 1 and its commit ID is recorded. The build then builds an image from the workflow: it installs `git`, `make`, `podman` and `podman-compose` and the workflow's `dependencies` as root, with network access, copies that checkout into the image as `65534:65534`, and runs the steps as `65534:65534` without network. Only the installs have network access; the image build does not fetch the repository again.
* A checkout is limited to `BUILD_MAX_CHECKOUT_MIB` (default 1024 MiB) and `BUILD_MAX_CHECKOUT_FILES` (default 100000 files); admins can change both under Settings. The received pack is capped while it is fetched, the files are measured as they will be written, after line ending conversion, before checkout, and the written working tree is measured again after checkout. A checkout over a limit fails the build with `Build failed: Repository checkout exceeds the limit of …` and its directory is removed.
* The image build runs with `no-new-privileges`, 1 GiB of memory and one CPU under Podman; Docker applies neither limit. The image is removed when the build ends.
* Builds run up to the concurrency limit (default 1, at most 16 when set by an admin); further requests wait in a bounded queue. Each build times out after ten minutes. Output is stored in full, up to the `BUILD_LOG_MAX_BYTES` safety limit (default 1 GiB); output beyond it is discarded and the build is marked truncated.
* Builds left in the `RUNNING` state by a server restart are marked `FAILED` at startup. Startup also removes leftover `cisys-build-*` containers and images and `cisys-*` checkout directories from an unclean stop; a cleanup failure is logged and does not stop startup.

### Repository addresses

* Only HTTPS repositories on `cisys.git.allowed-hosts` are accepted; the default is `github.com` and `gitlab.com`.
* Every address the host resolves to is checked. Loopback, private, link-local, multicast, documentation and metadata ranges are rejected, including IPv4-mapped IPv6 forms such as `::ffff:127.0.0.1`. An administrator can allow an internal range explicitly through `cisys.git.allowed-networks`.
* The check runs when a project is created, when a build starts, and again immediately before JGit fetches, because DNS can change after a project was saved. A host that resolves to a blocked address fails the build with `Build failed: The repository host resolves to a blocked address`.
* Branch discovery reads the project's own repository URL, never a URL supplied by the caller.

Known gaps

* Runner and Podman operation on Windows/WSL has not been verified.
* Credentials are not scoped to individual projects; only admins may reference one.
* DNS rebinding between the address check and JGit's own connection is not fully prevented, because JGit resolves the host again when it connects.

### Git credentials

For private repositories set an environment variable named `CISYS_GIT_<NAME>` to a read-only provider token in the backend environment before starting it, then enter the variable name in the build form as an admin. The value is never stored in the database. Only the backend's JGit fetch uses it; the image build does not receive it, so it is not in the image and steps cannot read it. Never place secrets in workflow files or build output; each build stores its workflow file.

## Public access limits

The public project page, its builds, logs and branches, and the public statistics are readable without signing in.

* An anonymous log request returns at most `cisys.public.log-max-bytes` (default 2 MiB) in one response. `offset`, `limit` and `tail` select a range; the `X-Log-Size`, `X-Log-Offset` and `X-Log-Length` headers let a client continue where the response ended. The UI pages through the limit for **Download log**; when a page is rate limited it waits for `Retry-After` and continues from the same offset, so a large anonymous download completes across windows.
* Signed-in visitors are not capped on this endpoint, so the build page keeps the complete log and the full download works as before. This assumes accounts are trusted; if registration is open, an account can be used to bypass the anonymous limits.
* Anonymous requests to `/api/project-pages/**` (including branch discovery, which runs `git ls-remote`) and `/api/stats/public` are counted per client address in a fixed window: `cisys.public.rate-limit.requests` per `cisys.public.rate-limit.window-seconds` (default 120 per 60 s). Exceeding it returns `429` with `Retry-After`. Signed-in callers are not counted.
* The counter lives in this process: it resets on restart, is not shared between instances, and tracks at most `cisys.public.rate-limit.max-clients` addresses (`0` removes the bound). When that many live windows exist, one window is evicted and the new visitor admitted, so a flood of addresses cannot lock new visitors out, and purging and eviction stay constant-time per request.
* Behind a reverse proxy, set `quarkus.http.proxy.proxy-address-forwarding=true` and `quarkus.http.proxy.enable-forwarded-host=true` and have the proxy send `Forwarded` or `X-Forwarded-For`. Otherwise every visitor shares the proxy's address and therefore one bucket.

## TLS

The application is not configured for TLS. Terminate TLS in a reverse proxy, or configure the [Quarkus TLS registry](https://quarkus.io/guides/tls-registry-reference), before any shared deployment. Keycloak must also be moved off `start-dev` and onto HTTPS in that case.

See [ROADMAP.md](ROADMAP.md) for the planned hardening work.
