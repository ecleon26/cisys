\newpage

# Audit log

Cisys records administrative, ownership, security-sensitive and privileged build actions in an append-only audit log. Open it with **Audit** in the header. Only users with the global `admin` realm role can read it; the menu entry is hidden for everyone else, but the backend enforces the same rule on every request.

The log answers **who did what, to which object, when, and whether it succeeded**. It does not replace the build history or the application logs.

## What is recorded

Every event stores a snapshot of the actor, the project and the target, so the entry remains readable after the project or user is deleted:

* The time, the stable action code and the outcome (`SUCCESS`, `DENIED` or `FAILURE`).
* The actor's Keycloak subject (`sub`), user name and roles at the time.
* The project id and name, when the action belongs to a project.
* The target type, id and a display name.
* A short summary and a small JSON object with allowlisted details.
* The request method, path, caller-supplied `X-Request-Id` header and response status, when available.

## Event catalog

| Action code | Recorded when |
| :---------- | :------------ |
| `project.create` | A project is created. A team chosen at creation is recorded as the `teamId` detail of this event; it does not emit `project.team.update`. |
| `project.update` | A project's name, repository URL or private flag changes. Details name the changed fields. |
| `project.credential.update` | A project's Git credential reference is set, changed or cleared. Only whether it was cleared is stored (`{"cleared": true}` or `{"cleared": false}`); the reference and its value are never recorded. |
| `project.delete` | A project is deleted. |
| `project.member.add` | A user is assigned to a project, including the creator's initial `LEAD` row. |
| `project.member.role` | A project member's role changes between `MEMBER` and `LEAD`. |
| `project.member.remove` | A user is removed from a project, including when the user is deleted. |
| `project.team.update` | A project's team is attached, changed or cleared, and once per project when its attached team is deleted. Details record the team id before the change as `teamBefore`; a cleared team has no `teamAfter`, because details with no value are not stored. |
| `team.create` | A team is created, including the creator's initial `LEAD` membership as a separate `team.member.add` event. |
| `team.update` | A team is renamed. Details record the name before and after. |
| `team.delete` | A team is deleted; every project it was attached to gets a `project.team.update` event. |
| `team.member.add` | A user is added to a team. |
| `team.member.role` | A team member's role changes between `MEMBER` and `LEAD`. |
| `team.member.remove` | A user is removed from a team, including when the user is deleted. |
| `team.token.update` | A team's repository token is set, rotated or cleared. Only whether it is set (`{"set": true}` or `{"set": false}`) is stored; the token itself is never recorded. |
| `project.webhook.create` | A webhook is added to a project. The target type is `WEBHOOK`. Details hold the host and the chosen events; the URL path, query and secret are never recorded. |
| `project.webhook.update` | A webhook's URL or events change. Details name the changed fields. |
| `project.webhook.secret.rotate` | A webhook's signing secret is replaced. |
| `project.webhook.delete` | A webhook is removed, together with its delivery history. |
| `user.create` | A user is created in Keycloak and assigned projects. |
| `user.update` | A user's profile, role, enabled flag or project assignments change. |
| `user.password.reset` | An administrator or scoped lead resets a user's temporary password. |
| `user.delete` | A user is deleted. |
| `settings.general.update` | The article editor policy changes. |
| `settings.retention.update` | Build retention limits change. |
| `settings.builds.update` | Concurrency, queue and timeout settings change. |
| `settings.checkout.update` | Checkout size and file limits change. |
| `settings.artifacts.update` | Artifact size and file limits change. |
| `settings.performance.update` | Build memory, CPU and process limits change. |
| `settings.polling.update` | Automatic-build settings change. |
| `settings.pullrequests.update` | Pull request builds are enabled or disabled. |
| `branding.update` | Branding changes. Details name the changed fields; logo and background bytes are never stored. |
| `image.allowlist.update` | The allowed build images change. |
| `image.pull` | An administrator pulls an image into the local runtime. |
| `image.remove` | An administrator removes an image. |
| `network.host.create` | An administrator allows a network host. |
| `network.host.update` | An allowed host's name or host name changes. |
| `network.host.delete` | An allowed host is removed. |
| `network.template.add` | A network template is applied. The target type is `NETWORK_TEMPLATE`. |
| `article.create` | An article is created. |
| `article.update` | An article's title or body changes. |
| `article.delete` | An article is deleted. |
| `build.start` | A manual build is queued. |
| `build.rerun` | A successful or failed build is rerun. |
| `build.pin` | A build is pinned. |
| `build.unpin` | A build is unpinned. |
| `build.cancel` | A queued or running build is cancelled. |

Comments (create, edit and delete), webhook deliveries, anonymous reads, failed sign-ins and ordinary read requests are intentionally not audited. Failed sign-ins happen in Keycloak and are available from its own event log.

## Outcomes and refused calls

`SUCCESS` events are written by the action itself with the exact target details.

A mapped failure is written from the HTTP response and keeps the original response unchanged:

| Response | Outcome | Examples |
| :------- | :------ | :------- |
| `403 Forbidden` | `DENIED` | A member tries to create a project or pin a build. |
| `409 Conflict` | `FAILURE` | A duplicate name, a host that is already allowed, or removing the last project `LEAD`. |
| `502 Bad Gateway` | `FAILURE` | An external image or build-runner operation fails. |

`400`, `401`, `404` and `429` are not recorded. They are validation, authentication, concealment or throttling responses, and recording them would either log malformed input or reveal whether a target exists. An unauthenticated caller is never recorded.

The same action code distinguishes success from refusal through the outcome, so filtering `project.create` and `DENIED` shows attempted unauthorized creations.

## Redaction and data that is never stored

The audit log must never become a second copy of secrets or build output. The backend drops details whose key names a password, token, secret, authorization, cookie, request or response body, log, command, script, workflow, Dockerfile, image data, logo or background. Values that look like a JWT or a `data:` URL are replaced with `[redacted]`, long strings are truncated and collections are capped. Callers must still avoid passing sensitive data in the first place.

Specifically, the audit log never stores:

* passwords or temporary passwords;
* access or refresh tokens, client secrets or credential values;
* credential references (`CISYS_GIT_*`); only whether a credential was cleared is stored;
* request or response bodies;
* build logs or workflow contents;
* base64 images or branding bytes;
* sensitive headers such as `Authorization` or `Cookie`.

The credential reference (`CISYS_GIT_*`) is never stored in the audit log. A credential change records only whether the reference was cleared, never the reference or its value.

## Transactions and external systems

Database-only actions write their audit event in the same transaction as the change. If the audit insert fails, the change is rolled back: for these actions the log and the data can never disagree.

Some effects happen outside the database: Keycloak user operations, container image pulls and removals, build submission and cancellation. Their success event is written after the external call succeeds, in its own transaction. An audit write failure is logged but does not turn the successful operation into an error. This is deliberate, because failing the response would not undo the external effect; the trade-off is a small, documented window in which a successful external action has no audit entry. Retry or reconcile from the external system (Keycloak events, the container runtime or the build history) if that matters.

Denied and failed events are also best-effort and never replace the original response.

## Retention, growth and integrity

There is **no automatic audit retention or deletion**. Every recorded action adds a row, so the table grows with administrative activity, build starts and reruns, and user changes. Database details are indexed by time, actor, action, project, outcome and target.

Expected scale is modest: a busy instance that queues 100 builds per day writes roughly 36,000 build rows a year, and administrative, user, project and settings changes add a few thousand more. A small team usually stays in the tens of thousands of rows; a large installation with many builds and frequent reruns can reach low millions per year. Search uses case-insensitive `LIKE '%term%'`, which cannot use a B-tree index, so very large tables make search slower. Keep the table at a size operators are comfortable searching by archiving old rows, and narrow a search with the action, actor, project, outcome or date filters.

Operators should:

* monitor table and index size, and the database backup size;
* include `auditevent` in backups;
* archive or delete old rows with an operator-owned SQL policy if required, after checking the organisation's retention requirements.

Manual retention is deliberately not automated. A safe sequence is to export the rows first, then delete them in bounded batches so a large delete does not lock the table for long:

```sql
-- 1. Archive a copy outside the database, for example with pg_dump or COPY.
COPY (SELECT * FROM auditevent WHERE occurredat < now() - interval '1 year')
  TO '/var/backups/auditevent-old.csv' WITH (FORMAT csv, HEADER true);

-- 2. Delete in batches; repeat until no rows are returned.
DELETE FROM auditevent
 WHERE id IN (
   SELECT id FROM auditevent
    WHERE occurredat < now() - interval '1 year'
    ORDER BY id
    LIMIT 10000
 );
```

Verify the archive before deleting, and keep the deleted rows for as long as the organisation's audit policy requires. `VACUUM` (or autovacuum) reclaims the space afterwards.

Rows are append-only from the application's point of view: the API exposes no write, update or delete endpoint, and the application never modifies an existing event. This is an application-level guarantee, not tamper-proof storage. A database administrator can still change rows; use database permissions, backups and, where required, external log shipping or a write-once archive if audit evidence must be resistant to insiders.

## Reading the log

The **Audit** page lists events newest first. Use the search box (summary, action, actor, project and target names), the action field, and the outcome, target type, from and to filters. A search term must be at least 2 characters; blank search is ignored. Select an event to see its full redacted details and request metadata. The list pages 50 events at a time and shows the total number of matching events.

The API is admin-only:

| Method | Path | Result |
| :----- | :--- | :----- |
| `GET` | `/api/audit?search=&action=&actor=&outcome=&project=&targetType=&target=&from=&to=&first=&max=` | One page of events and the total |
| `GET` | `/api/audit/{id}` | One event with its details |

`first` defaults to `0` and must be zero or more; `max` defaults to `50` and must be between `1` and `200`. Values outside those ranges, or values that are not numbers, are rejected with `400` instead of being clamped. A `search` term must be at least 2 characters. `from` and `to` are ISO-8601 instants and bound `occurredAt`. `action`, `outcome` and `targetType` are matched against the action code and enum names. A caller without the `admin` role receives `403`.
