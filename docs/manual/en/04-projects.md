\newpage

# Projects

A **project** registers a Git repository with cisys so that it can be built.

![Projects, Admin view](images/projects-admin.png){ width=100% }

## Visibility

* **Public** projects are visible to every signed-in user.
* **Private** projects are visible only to admins, direct project members, and members of the project's team. Effective project roles combine the direct role and the team role by taking the higher one.
* Direct project members are assigned on the project's settings page (Team tab, `/<name>/settings?tab=team`); team members are managed under **Users → Teams**.

The private flag controls visibility of the project and its build logs inside cisys. It does not grant or restrict access to the Git repository itself.

![Projects, Member view](images/projects-member.png){ width=100% }

## Public project page

Every public project has a page at `/<name>/`, for example `http://localhost:5173/cisys/`. It shows the repository branches in a panel on the left and the build history in a table on the right, newest first. Anonymous visitors can read it.

Selecting a branch in the left panel filters the build history to that branch. The page starts on `main`, or `master` for repositories that still use it. Each build row shows a green thumbs up for a passing build, a red thumbs down for a failing build, and a spinner while it runs, followed by the build number, branch, commit, start time, duration and exit code.

Selecting a build opens its own page at `/<name>/<branch>/build/<id>`, for example `http://localhost:5173/cisys/master/build/51`, with the build details and the full log. The `<id>` is the internal build ID, not the build number.

The page has **Builds** and **Commits** tabs. **Builds** is the default. **Commits** lists the selected branch's Git history, newest first, with the short commit ID, message, author, date and links to every build of that commit. Selecting a branch filters both tabs. Add `?tab=commits` to the project URL to open the Commits tab directly, for example `http://localhost:5173/cisys/?tab=commits`; after opening a build from that tab, the browser's Back button returns to it.

The history comes from cisys's repository mirror rather than from a fresh repository read on every page view. When the mirror directory or the selected branch is absent, the tab shows the commits that cisys already has builds for and explains that full history is unavailable. A private project without a usable credential is never polled, so it uses that fallback too. cisys shows at most the newest 5000 commits; if the branch has older history, it says that the history was truncated only when a request reaches that limit, so a branch with exactly 5000 commits is complete. The list loads 50 commits at a time; **Load older commits** fetches the next page. If the mirror exists but cannot be read, the tab shows an error and a retry button.

Private projects use the same page once you sign in as a direct member of the project, a member of its team or an administrator; everyone else is told the project does not exist. The branches panel reads the repository when the project has a usable credential — a team token or a credential reference (see **Credentials** below); without one it lists only the branches that already have builds.

## Create a project

Leads and admins can create projects. Enter

* **Name**: up to 100 letters, digits, `.`, `_` and `-`, starting with a letter or digit, for example `my-project`. The name is the project's URL, `/<name>/`. `manual`, `articles`, `home`, `images`, `users`, `reports`, `settings`, `profile` and `audit` are reserved for the application's own pages
* **Repository URL**: an HTTPS URL without embedded credentials, up to 2048 characters
* **Private**: whether the project is private
* **Team**: optional. The project is attached to a reusable team, and the creator must be a `LEAD` of that team unless they are an administrator
* **Credential reference**: admins only, and only for private projects. The name of an environment variable that holds a read-only token for the repository, for example `CISYS_GIT_PROJECT`. The token itself is never entered here or stored in the database.

The project credential is used for branch discovery, the poller's mirror fetches, automatic builds, and manual or rerun builds that do not supply a per-build credential. When the project belongs to a team with a stored token, that token takes precedence over the environment reference; see **Credentials** below. The Commits tab reads the local mirror and does not resolve the credential itself. An admin can name another reference for one build through the API (`POST /api/projects/{id}/builds` with `credentialRef`). See **Private Git repositories** in the **Builds** chapter for a worked example. An admin can change or clear the project credential later on the project's settings page (`PUT /api/projects/{id}`) or directly through `PUT /api/projects/{id}/credential`. When a lead changes the repository URL, the credential is cleared, so it is never sent to a new host; an admin must set it again for the new repository.

Admins, and the lead who owns a project, can open its settings page with the pencil button in the repositories list. **Save** applies the change and returns to the project page; **Cancel** leaves without saving. Saving keeps queued and running builds on the repository URL, branch, image, command and credential they started with; the change affects new page views and new builds. The **General** tab changes the name, URL, visibility, optional team and, for admins, the `credentialRef`; the **Team** tab manages direct project members and is separate from the reusable teams under **Users → Teams**.

**Cache** keeps the directory `$HOME/.cisys/<project name>` from one build to the next, for example for a local Maven repository (`mvn -Dmaven.repo.local=$HOME/.cisys/my-project/m2 package`). A build copies the stored cache there before the first step. After a build whose steps all passed, that directory replaces the stored cache; pull request builds use the cache but never save it. The cache is kept on the server under `cisys.cache.dir` by project id, so renaming the project keeps it; changing the repository URL or deleting the project removes it. A cache larger than `cisys.cache.max-mib` (default 2048) is not saved, and the build log says so. The project name must follow the rule above.

Projects created before this naming rule keep working. An administrator can rename one in the database; its builds follow, because they reference the project's id:

```sql
UPDATE project SET name = 'new-name' WHERE name = 'old name';
```

The repository must be on an allowed host and must not resolve to a loopback, private, link-local or metadata address. By default only `github.com` and `gitlab.com` on port 443 are allowed; the backend settings `cisys.git.allowed-hosts`, `cisys.git.allowed-ports` and `cisys.git.allowed-networks` extend that list. A local server with a self-signed certificate can be named in `cisys.git.insecure-tls-hosts`; adding its certificate to the JVM truststore is preferred.

## Webhooks

A project `LEAD` or an administrator adds webhooks on the project's settings page (Webhooks tab), at most 10 per project. Each webhook receives a JSON `POST` for the events it is subscribed to:

* **Finished** — every passed or failed build (`build.passed` or `build.failed`).
* **Failed** — a failed build (`build.failed`).
* **Fixed** — a passed build whose previous finished build on the same branch, or of the same pull request, failed (`build.fixed`).

Cancelled builds send nothing. A build sends at most one request per webhook, with the most specific event. A build that was running when the server restarted is marked failed and sends `build.failed` with `"reason": "server_restart"`; otherwise `reason` is `null`.

```json
{
  "event": "build.fixed",
  "delivery": 1201,
  "reason": null,
  "project": { "id": 51, "name": "my-project" },
  "build": {
    "id": 3401, "number": 42, "status": "PASSED", "trigger": "POLL",
    "branch": "main", "revision": "4f2c…", "pullRequest": null, "exitCode": 0,
    "startedAt": "2026-11-27T10:00:00Z", "finishedAt": "2026-11-27T10:03:12Z"
  }
}
```

Every request carries `X-Cisys-Event`, `X-Cisys-Delivery`, `X-Cisys-Timestamp` (Unix seconds) and `X-Cisys-Signature: sha256=<hex>`, the HMAC-SHA256 of `<timestamp>.<body>` keyed with the webhook's secret. The secret is shown once, when the webhook is created or its secret is rotated. A receiver should recompute the signature, compare it in constant time and reject old timestamps.

Any `2xx` answer counts as delivered. Other answers and network errors are retried after 30 seconds, 2, 8 and 32 minutes, five attempts in all; a `3xx` (redirects are not followed) and a `4xx` other than `408` and `429` are not retried. Each attempt must finish within 20 seconds, and up to four are sent at once, so a slow endpoint does not delay the others. Deliveries never change a build's result, and the tab lists the latest 50 of them. Finished deliveries are kept for 30 days or until their build is deleted.

The URL must use HTTPS on an allowed port (`cisys.webhooks.allowed-ports`, default 443) and must not resolve to a loopback, private, link-local or metadata address. It is checked when it is saved and again before every attempt, and the request goes to the address that check approved. Any public host is allowed unless `cisys.webhooks.allowed-hosts` lists hosts; `cisys.webhooks.allowed-networks` allows private CIDR ranges on purpose. The URL is shown only as `https://host/…`, because paths often carry a token.

## Ownership

Two different things are recorded per project:

* `owner_subject` — the Keycloak subject of the user who created the project. It is written once at creation and **never changes afterwards**, not even when the project's membership or team changes. It identifies the original creator and is useful as a marker of who started the project (for example, for audit). It is not used to drive current authorization, and it does not follow an administrator who promotes other users to `LEAD`.
* `projectmember` rows — one row per (project, user), holding `MEMBER` or `LEAD`. These rows decide what a user can do directly in the project: `MEMBER` can view and start builds; `LEAD` can also edit the project, pin and unpin builds, and manage its team. When the project is attached to a reusable team, that team's members also act on the project with their team role, and the effective role is the higher of the direct and team roles.

The creator is automatically assigned a `LEAD` membership at project creation. A project can have more than one `LEAD`. A project always keeps at least one direct `LEAD`; the system refuses to remove or demote the last direct `LEAD` and reports an `HTTP 409 Conflict` — even for an administrator. A project `LEAD` (including the creator themselves, and another `LEAD`) cannot remove the creator or demote them out of `LEAD` while they remain `LEAD`; that is reserved for administrators. Once an administrator has demoted the creator to `MEMBER`, the creator is no longer protected and the normal team rules apply; `owner_subject` is left untouched either way.

Changing a project's team requires the project creator or a direct project `LEAD` to also be a `LEAD` of the target team; administrators bypass this requirement. Clearing the team requires only the creator or a direct project `LEAD`. The target-team requirement prevents a project lead from making builds use a token controlled by a team they do not lead. Deleting a reusable team detaches it from its projects; direct project members and their roles remain.

Databases created before direct project membership was introduced do not carry any membership rows over: `R__schema.sql` repairs nothing, so direct project membership is absent for existing projects. Upgrading such a database requires `make reset`, which deletes the local PostgreSQL and Keycloak data volumes, including projects, builds and users. Databases created after that change are not affected, and not every future upgrade requires a reset. The reusable Teams feature needs no reset: `R__schema.sql` adds its tables and the project's `teamid` column to existing databases.

## Credentials

Private repository access uses the following resolution order:

1. An administrator-supplied per-build `credentialRef`.
2. The encrypted token stored on the project's team.
3. The admin-only project `credentialRef`, which names a `CISYS_GIT_*` environment variable.

Only private projects resolve a credential. A public project never uses its team's token; making a project public clears its `credentialRef`, and the team token is ignored until the project is private again.

The team token is managed at `/api/teams/{id}/token` or in **Users → Teams**. It is write-only, encrypted with AES-256-GCM using `CISYS_SECRET_KEY` and the team id as additional authenticated data, and is never returned. A token must be non-blank and at most 4096 characters. An optional private project id lets the server validate the token with `ls-remote` through the repository URL validator, allowlist and resolver before saving it. Without that id, the token is stored without repository validation.

Pull request builds receive no credential. A build queued with the team-token sentinel `@team-secret:<teamId>` fails closed if the project's repository URL or team changes before it runs. A build queued without a credential reference resolves the project's current team token while the repository URL matches the one it was queued with, so a team change can supply a different token to such a build.
