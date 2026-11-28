-- The whole schema, as one repeatable migration: Flyway reruns it whenever this file changes.
-- Every statement must stay idempotent, as it runs on fresh databases, on existing ones and on
-- databases created by schema-management=update before migrations existed (baselined at version 0).

CREATE EXTENSION IF NOT EXISTS lo;

CREATE TABLE IF NOT EXISTS team (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    ownersubject character varying(255) NOT NULL,
    tokensecret bytea,
    tokenupdatedat timestamp(6) with time zone,
    tokenupdatedby character varying(255),
    createdat timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT team_pkey PRIMARY KEY (id),
    CONSTRAINT uk_team_name UNIQUE (name)
);

CREATE SEQUENCE IF NOT EXISTS team_seq START WITH 1 INCREMENT BY 50;

DO $$
DECLARE
    conflicts text;
BEGIN
    WITH duplicate_names AS (
        SELECT lower(name) AS normalized_name,
               string_agg(format('%s (id=%s)', name, id), ', ' ORDER BY id) AS rows
          FROM team
         GROUP BY lower(name)
        HAVING count(*) > 1
    )
    SELECT string_agg(format('%s: %s', normalized_name, rows), E'\n' ORDER BY normalized_name)
      INTO conflicts
      FROM duplicate_names;

    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot enforce case-insensitive team names. Resolve these conflicting rows first: %',
            conflicts;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_team_name_lower ON team (lower(name));

CREATE TABLE IF NOT EXISTS teammember (
    id bigint NOT NULL,
    teamid bigint NOT NULL,
    subject character varying(255) NOT NULL,
    role character varying(16) NOT NULL DEFAULT 'MEMBER',
    createdat timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT teammember_pkey PRIMARY KEY (id),
    CONSTRAINT teammember_team_subject UNIQUE (teamid, subject),
    CONSTRAINT teammember_team FOREIGN KEY (teamid) REFERENCES team (id) ON DELETE CASCADE,
    CONSTRAINT teammember_role_check CHECK (role IN ('MEMBER', 'LEAD'))
);
CREATE INDEX IF NOT EXISTS teammember_subject ON teammember (subject);
CREATE SEQUENCE IF NOT EXISTS teammember_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS project (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    ownersubject character varying(255) NOT NULL,
    privaterepository boolean NOT NULL,
    repositoryurl character varying(2048) NOT NULL,
    credentialref character varying(100),
    CONSTRAINT project_pkey PRIMARY KEY (id),
    CONSTRAINT uk_project_name UNIQUE (name),
    CONSTRAINT uk_project_repositoryurl UNIQUE (repositoryurl)
);

ALTER TABLE project ADD COLUMN IF NOT EXISTS credentialref character varying(100);
-- Added separately so that existing databases gain the column, and declared only once.
ALTER TABLE project ADD COLUMN IF NOT EXISTS teamid bigint;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'project_team_fk') THEN
        ALTER TABLE project ADD CONSTRAINT project_team_fk
            FOREIGN KEY (teamid) REFERENCES team (id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$
DECLARE
    conflicts text;
BEGIN
    WITH duplicate_names AS (
        SELECT lower(name) AS normalized_name,
               string_agg(format('%s (id=%s)', name, id), ', ' ORDER BY id) AS rows
          FROM project
         GROUP BY lower(name)
        HAVING count(*) > 1
    )
    SELECT string_agg(format('%s: %s', normalized_name, rows), E'\n' ORDER BY normalized_name)
      INTO conflicts
      FROM duplicate_names;

    IF conflicts IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot enforce case-insensitive project names. Resolve these conflicting rows first: %',
            conflicts;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_project_name_lower ON project (lower(name));

CREATE SEQUENCE IF NOT EXISTS project_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS build (
    id bigint NOT NULL,
    finishedat timestamp(6) with time zone,
    projectid bigint,
    requestedby character varying(255),
    revision character varying(255),
    startedat timestamp(6) with time zone,
    status character varying(255),
    CONSTRAINT build_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS build_seq START WITH 1 INCREMENT BY 50;

-- Added separately so that existing databases gain the columns.
ALTER TABLE build ADD COLUMN IF NOT EXISTS branch character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS image character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS credentialref character varying(255);
ALTER TABLE build ADD COLUMN IF NOT EXISTS imagedigest character varying(300);
ALTER TABLE build ADD COLUMN IF NOT EXISTS exitcode integer;
ALTER TABLE build ADD COLUMN IF NOT EXISTS logtruncated boolean NOT NULL DEFAULT false;
ALTER TABLE build ADD COLUMN IF NOT EXISTS logoid lo;
ALTER TABLE build ADD COLUMN IF NOT EXISTS pinned boolean NOT NULL DEFAULT false;

-- Moves logs from the old output column into large objects.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'build' AND column_name = 'output') THEN
        -- Logs cut at the old 120 KB cap.
        UPDATE build SET logtruncated = true WHERE output LIKE '%[output truncated]' || chr(10);
        UPDATE build SET logoid = lo_from_bytea(0, convert_to(output, 'UTF8'))
            WHERE logoid IS NULL AND output IS NOT NULL AND output <> '';
        ALTER TABLE build DROP COLUMN output;
    END IF;
END $$;

-- Unlinks the large object when its build is deleted.
CREATE OR REPLACE TRIGGER build_log_lo_manage
    BEFORE UPDATE OF logoid OR DELETE ON build
    FOR EACH ROW EXECUTE FUNCTION lo_manage(logoid);

CREATE TABLE IF NOT EXISTS branding (
    id bigint NOT NULL,
    background character varying(350000),
    buttoncolor character varying(7) NOT NULL,
    headercolor character varying(7) NOT NULL,
    headingcolor character varying(7) NOT NULL,
    logo character varying(350000),
    name character varying(80) NOT NULL,
    CONSTRAINT branding_pkey PRIMARY KEY (id)
);

-- The font is no longer configurable: Red Hat Text is always used.
ALTER TABLE branding DROP COLUMN IF EXISTS fontfamily;

CREATE TABLE IF NOT EXISTS allowedimage (
    name character varying(201) NOT NULL,
    CONSTRAINT allowedimage_pkey PRIMARY KEY (name)
);

-- Settings changed by admins, a single row with id 1.
CREATE TABLE IF NOT EXISTS settings (
    id bigint NOT NULL,
    retentiondays integer NOT NULL DEFAULT 30,
    retentionmaxbuilds integer NOT NULL DEFAULT 50,
    CONSTRAINT settings_pkey PRIMARY KEY (id)
);

-- Queue requests are durable; startedAt remains null until claimed.
ALTER TABLE build ADD COLUMN IF NOT EXISTS requestedat timestamp(6) with time zone;
ALTER TABLE build ADD COLUMN IF NOT EXISTS repositoryurl character varying(2048);
CREATE INDEX IF NOT EXISTS build_queue_order ON build (requestedat, id) WHERE status = 'QUEUED';
-- Trend reports filter and group builds by coalesce(requestedat, startedat) within a project.
CREATE INDEX IF NOT EXISTS build_project_requested_at ON build (projectid, (coalesce(requestedat, startedat)));

-- Builds run the repository's workflow file, so they no longer store a command.
ALTER TABLE build DROP COLUMN IF EXISTS command;
-- The workflow file each build read, kept with the build.
ALTER TABLE build ADD COLUMN IF NOT EXISTS workflow character varying(65536);
-- The Dockerfile each build generated from its workflow, shown on the build page.
ALTER TABLE build ADD COLUMN IF NOT EXISTS dockerfile character varying(401000);

-- Null uses the configured queue limits.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxconcurrentbuilds integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxqueuedbuilds integer;
-- Destinations build steps may reach through the egress proxy; no rows means no network.
CREATE TABLE IF NOT EXISTS networkhost (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    host character varying(253) NOT NULL,
    createdby character varying(255) NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    CONSTRAINT networkhost_pkey PRIMARY KEY (id),
    CONSTRAINT networkhost_host_key UNIQUE (host)
);
CREATE SEQUENCE IF NOT EXISTS networkhost_seq START WITH 1 INCREMENT BY 50;

-- The allowlist used to be one text column on settings, one entry per line.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'settings' AND column_name = 'networkallowlist') THEN
        INSERT INTO networkhost (id, name, host, createdby, createdat)
        SELECT nextval('networkhost_seq'), entry, entry, 'migration', now()
        FROM (SELECT DISTINCT btrim(unnest(string_to_array(networkallowlist, E'\n'))) AS entry FROM settings) entries
        WHERE entry <> ''
        ON CONFLICT (host) DO NOTHING;
        ALTER TABLE settings DROP COLUMN networkallowlist;
    END IF;
END $$;

-- Null uses the configured checkout limits.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxcheckoutmib integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxcheckoutfiles integer;

-- Set on a running build until the runner has stopped it.
ALTER TABLE build ADD COLUMN IF NOT EXISTS cancelrequested boolean NOT NULL DEFAULT false;

-- Null uses the configured build resource limits.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxbuildmemorymib integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxbuildcpus integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxbuildpids integer;
-- Null uses the configured build timeout.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS buildtimeoutminutes integer;

-- Builds are numbered per project; the counter never goes back, so deleted numbers are not reused.
ALTER TABLE project ADD COLUMN IF NOT EXISTS buildcounter bigint NOT NULL DEFAULT 0;
ALTER TABLE build ADD COLUMN IF NOT EXISTS number bigint;
UPDATE build b SET number = n.number
    FROM (SELECT id, coalesce((SELECT max(number) FROM build m WHERE m.projectid = u.projectid), 0)
                     + row_number() OVER (PARTITION BY projectid ORDER BY requestedat NULLS LAST, id) AS number
          FROM build u WHERE number IS NULL) n
    WHERE b.id = n.id;
UPDATE project p SET buildcounter = m.number
    FROM (SELECT projectid, max(number) AS number FROM build GROUP BY projectid) m
    WHERE p.id = m.projectid AND p.buildcounter < m.number;
ALTER TABLE build ALTER COLUMN number SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'build_project_number') THEN
        ALTER TABLE build ADD CONSTRAINT build_project_number UNIQUE (projectid, number);
    END IF;
END $$;

-- Serves the branch-filtered build list and the branch dropdown.
CREATE INDEX IF NOT EXISTS build_project_branch_number ON build (projectid, branch, number DESC);

-- Serves retention cleanup: final, unpinned builds, newest first.
CREATE INDEX IF NOT EXISTS build_retention
    ON build (projectid, (coalesce(finishedat, startedat)) DESC NULLS LAST, id DESC)
    WHERE status IN ('PASSED', 'FAILED', 'CANCELLED') AND NOT pinned;

-- Builds whose project is gone cannot satisfy the foreign key added below; remove them.
-- The lo_manage trigger unlinks their logs. This runs only until the foreign key exists, so
-- repeatable-migration re-runs do not scan the table. Rows with projectid IS NULL are kept.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'build_project_fk'
                     AND conrelid = 'build'::regclass) THEN
        DELETE FROM build
            WHERE projectid IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM project p WHERE p.id = build.projectid);
    END IF;
END $$;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'build_project_fk') THEN
        ALTER TABLE build ADD CONSTRAINT build_project_fk
            FOREIGN KEY (projectid) REFERENCES project (id) ON DELETE CASCADE;
    END IF;
END $$;

-- Counters never go negative.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'build_number_check') THEN
        ALTER TABLE build ADD CONSTRAINT build_number_check CHECK (number >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'project_buildcounter_check') THEN
        ALTER TABLE project ADD CONSTRAINT project_buildcounter_check CHECK (buildcounter >= 0);
    END IF;
END $$;

-- Automatic builds poll the repository on a timer. The workflow file stays execution-only.

-- How a build was started. Existing builds were started by hand.
ALTER TABLE build ADD COLUMN IF NOT EXISTS trigger character varying(20) NOT NULL DEFAULT 'MANUAL';

-- Status and trigger are enforced by the database, not only by the Java enums.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'build_status_check') THEN
        ALTER TABLE build ADD CONSTRAINT build_status_check
            CHECK (status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'CANCELLED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'build_trigger_check') THEN
        ALTER TABLE build ADD CONSTRAINT build_trigger_check
            CHECK (trigger IN ('MANUAL', 'POLL', 'PULL_REQUEST'));
    END IF;
END $$;

ALTER TABLE build ADD COLUMN IF NOT EXISTS triggeractor character varying(255);

-- Settings for the poller: on by default, per commit by default, every workflow branch by default.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS pollenabled boolean NOT NULL DEFAULT true;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS pollgranularity character varying(20) NOT NULL DEFAULT 'PER_COMMIT';
ALTER TABLE settings ADD COLUMN IF NOT EXISTS pollscope character varying(20) NOT NULL DEFAULT 'WORKFLOW';
-- Null uses cisys.poll.every.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS pollintervalseconds integer;

-- The last commit queued for each watched branch. A missing row seeds the tip without building.
CREATE TABLE IF NOT EXISTS projectbranch (
    id bigint NOT NULL,
    projectid bigint NOT NULL,
    branch character varying(255) NOT NULL,
    lastseenrevision character varying(64) NOT NULL,
    CONSTRAINT projectbranch_pkey PRIMARY KEY (id),
    CONSTRAINT uk_projectbranch UNIQUE (projectid, branch)
);
CREATE SEQUENCE IF NOT EXISTS projectbranch_seq START WITH 1 INCREMENT BY 50;

-- A polled commit is built at most once per project, however many branches reach it and across
-- restarts, so the cursor can be advanced without a second build. Manual builds may share a
-- revision. The partial index also serves the per-project revision lookup.
CREATE UNIQUE INDEX IF NOT EXISTS build_project_poll_revision
    ON build (projectid, revision)
    WHERE trigger = 'POLL' AND revision IS NOT NULL;

-- Users assigned to a project, by Keycloak subject; the users themselves live in Keycloak.
-- A project member is either a MEMBER (read the project) or a LEAD (MEMBER + manage its membership and settings).
CREATE TABLE IF NOT EXISTS projectmember (
    id bigint NOT NULL,
    projectid bigint NOT NULL,
    subject character varying(255) NOT NULL,
    role character varying(16) NOT NULL DEFAULT 'MEMBER',
    createdat timestamp(6) with time zone NOT NULL DEFAULT now(),
    CONSTRAINT projectmember_pkey PRIMARY KEY (id),
    CONSTRAINT projectmember_project_subject UNIQUE (projectid, subject),
    CONSTRAINT projectmember_project FOREIGN KEY (projectid) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT projectmember_role_check CHECK (role IN ('MEMBER', 'LEAD'))
);
CREATE INDEX IF NOT EXISTS projectmember_subject ON projectmember (subject);
CREATE SEQUENCE IF NOT EXISTS projectmember_seq START WITH 1 INCREMENT BY 50;

-- Pull request and merge request builds are opt-in and off by default.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS pullrequestsenabled boolean NOT NULL DEFAULT false;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS mirrormanualbuilds boolean NOT NULL DEFAULT false;

-- The provider detected when PR builds were enabled, and the highest number present at that time.
-- A null baseline means the next discovery seeds the current pull requests without building them.
ALTER TABLE project ADD COLUMN IF NOT EXISTS pullrequestprovider character varying(20);
ALTER TABLE project ADD COLUMN IF NOT EXISTS pullrequestbaseline integer;
ALTER TABLE project ADD COLUMN IF NOT EXISTS cache boolean NOT NULL DEFAULT false;

-- A pull request build stores the provider and number that created it, never deriving them later.
ALTER TABLE build ADD COLUMN IF NOT EXISTS pullrequest integer;
ALTER TABLE build ADD COLUMN IF NOT EXISTS pullrequestprovider character varying(20);

-- Upgrade a database created before pull request builds were released. That schema identified a
-- pull request by (provider, number) and kept a separate approval table. Those cursors, approvals
-- and the old per-provider build index are disposable: clearing the baseline makes the next
-- discovery seed the current pull requests without building them. The block is skipped on a fresh
-- or already upgraded database, where number already exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'projectpullrequest' AND column_name = 'pullrequest')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'projectpullrequest' AND column_name = 'number') THEN
        DROP INDEX IF EXISTS build_project_pull_request_revision;
        DELETE FROM projectpullrequest;
        UPDATE project SET pullrequestbaseline = NULL, pullrequestprovider = NULL;
        -- The old index allowed one build per provider pull request at a commit; the new one allows
        -- one build per commit. Drop duplicate pull request builds first, keeping the lowest id.
        DELETE FROM build b
            USING build duplicate
            WHERE b.trigger = 'PULL_REQUEST'
              AND duplicate.trigger = 'PULL_REQUEST'
              AND b.projectid = duplicate.projectid
              AND b.revision IS NOT NULL
              AND b.revision = duplicate.revision
              AND b.id > duplicate.id;
        ALTER TABLE projectpullrequest DROP CONSTRAINT IF EXISTS projectpullrequest_project_provider_pr;
        ALTER TABLE projectpullrequest RENAME COLUMN pullrequest TO number;
        ALTER TABLE projectpullrequest ADD COLUMN lastsubmittedrevision character varying(64);
        ALTER TABLE projectpullrequest ALTER COLUMN lastseenrevision DROP NOT NULL;
        ALTER TABLE projectpullrequest DROP COLUMN IF EXISTS provider;
        ALTER TABLE projectpullrequest DROP COLUMN IF EXISTS hasworkflow;
        ALTER TABLE projectpullrequest DROP COLUMN IF EXISTS eligible;
        ALTER TABLE projectpullrequest
            ADD CONSTRAINT projectpullrequest_project_number UNIQUE (projectid, number);
        DROP TABLE IF EXISTS pullrequestapproval;
        DROP SEQUENCE IF EXISTS pullrequestapproval_seq;
    END IF;
END
$$;

-- At most one pull request build for one commit. Poll builds are separate: they may carry the
-- project credential, while pull request builds never do.
CREATE UNIQUE INDEX IF NOT EXISTS build_project_pull_request_revision
    ON build (projectid, revision)
    WHERE trigger = 'PULL_REQUEST' AND revision IS NOT NULL;

-- The commit view maps every revision on a page to all of its builds in one query.
CREATE INDEX IF NOT EXISTS build_project_revision ON build (projectid, revision);

-- The last head discovered for each pull request. The submitted revision is stored separately so
-- deleting an old build through retention can never make the same head build twice.
CREATE TABLE IF NOT EXISTS projectpullrequest (
    id bigint NOT NULL,
    projectid bigint NOT NULL,
    number integer NOT NULL,
    lastseenrevision character varying(64),
    lastsubmittedrevision character varying(64),
    branch character varying(255),
    refpresent boolean NOT NULL DEFAULT true,
    seenat timestamp(6) with time zone,
    CONSTRAINT projectpullrequest_pkey PRIMARY KEY (id),
    CONSTRAINT projectpullrequest_project_number UNIQUE (projectid, number)
);
CREATE SEQUENCE IF NOT EXISTS projectpullrequest_seq START WITH 1 INCREMENT BY 50;
-- Upgrades a projectmember table created before membership roles existed: rows gain the default MEMBER role.
ALTER TABLE projectmember ADD COLUMN IF NOT EXISTS role character varying(16) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE projectmember ADD COLUMN IF NOT EXISTS createdat timestamp(6) with time zone NOT NULL DEFAULT now();
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'projectmember_role_check') THEN
        ALTER TABLE projectmember ADD CONSTRAINT projectmember_role_check CHECK (role IN ('MEMBER', 'LEAD'));
    END IF;
END $$;

-- Comments on a build, by Keycloak subject. They go with the build, including bulk deletes.
CREATE TABLE IF NOT EXISTS buildcomment (
    id bigint NOT NULL,
    buildid bigint NOT NULL,
    authorsubject character varying(255) NOT NULL,
    body character varying(10000) NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    updatedat timestamp(6) with time zone,
    CONSTRAINT buildcomment_pkey PRIMARY KEY (id),
    CONSTRAINT buildcomment_build FOREIGN KEY (buildid) REFERENCES build (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS buildcomment_build ON buildcomment (buildid);
CREATE SEQUENCE IF NOT EXISTS buildcomment_seq START WITH 1 INCREMENT BY 50;

-- Reports a build's workflow asked for, in workflow order. They go with the build.
CREATE TABLE IF NOT EXISTS buildreport (
    id bigint NOT NULL,
    buildid bigint NOT NULL,
    type character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    format character varying(10) NOT NULL,
    content text NOT NULL,
    truncated boolean NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    CONSTRAINT buildreport_pkey PRIMARY KEY (id),
    CONSTRAINT buildreport_build FOREIGN KEY (buildid) REFERENCES build (id) ON DELETE CASCADE,
    CONSTRAINT buildreport_format_check CHECK (format IN ('TEXT', 'HTML'))
);
CREATE INDEX IF NOT EXISTS buildreport_build ON buildreport (buildid);
CREATE SEQUENCE IF NOT EXISTS buildreport_seq START WITH 1 INCREMENT BY 50;

-- Files a build's workflow listed under artifacts, each in a large object. They go with the build,
-- and the lo_manage trigger unlinks the content. Only BuildArtifacts reads and writes them.
CREATE TABLE IF NOT EXISTS buildartifact (
    id bigint GENERATED ALWAYS AS IDENTITY,
    buildid bigint NOT NULL,
    name character varying(200) NOT NULL,
    path character varying(1000) NOT NULL,
    size bigint NOT NULL,
    sha256 character(64) NOT NULL,
    dataoid lo NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    CONSTRAINT buildartifact_pkey PRIMARY KEY (id),
    CONSTRAINT buildartifact_build FOREIGN KEY (buildid) REFERENCES build (id) ON DELETE CASCADE,
    CONSTRAINT buildartifact_path UNIQUE (buildid, path)
);
CREATE OR REPLACE TRIGGER buildartifact_lo_manage
    BEFORE UPDATE OF dataoid OR DELETE ON buildartifact
    FOR EACH ROW EXECUTE FUNCTION lo_manage(dataoid);
ALTER TABLE build ADD COLUMN IF NOT EXISTS artifactstruncated boolean NOT NULL DEFAULT false;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxartifactmib integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxartifactfiles integer;

-- Who may create, edit and delete articles: ADMIN, LEAD (and admins) or MEMBER (any CISys role).
ALTER TABLE settings ADD COLUMN IF NOT EXISTS articleeditors character varying(16) NOT NULL DEFAULT 'ADMIN';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'settings_articleeditors_check') THEN
        ALTER TABLE settings ADD CONSTRAINT settings_articleeditors_check
            CHECK (articleeditors IN ('ADMIN', 'LEAD', 'MEMBER'));
    END IF;
END $$;

-- Markdown articles, readable by anyone.
CREATE TABLE IF NOT EXISTS article (
    id bigint NOT NULL,
    title character varying(200) NOT NULL,
    body character varying(100000) NOT NULL,
    authorsubject character varying(255) NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    updatedat timestamp(6) with time zone NOT NULL,
    CONSTRAINT article_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS article_updated ON article (updatedat DESC, id DESC);
CREATE SEQUENCE IF NOT EXISTS article_seq START WITH 1 INCREMENT BY 50;

-- Administrative and security-sensitive actions. Rows are append-only from the application's
-- point of view: there are no foreign keys, so a record survives the deletion of its target, and
-- the actor and project names are snapshots taken when the action happened. Details is an
-- allowlisted, redacted JSON object; it never holds passwords, tokens, image data or build logs.
CREATE TABLE IF NOT EXISTS auditevent (
    id bigint NOT NULL,
    occurredat timestamp(6) with time zone NOT NULL,
    action character varying(60) NOT NULL,
    outcome character varying(16) NOT NULL,
    actorsubject character varying(255),
    actorusername character varying(255),
    actorroles character varying(255),
    projectid bigint,
    projectname character varying(100),
    targettype character varying(40),
    targetid character varying(255),
    targetname character varying(255),
    summary character varying(500) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    requestid character varying(64),
    httpmethod character varying(10),
    path character varying(512),
    statuscode integer,
    CONSTRAINT auditevent_pkey PRIMARY KEY (id)
);
CREATE SEQUENCE IF NOT EXISTS auditevent_seq START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS auditevent_occurredat ON auditevent (occurredat DESC, id DESC);
CREATE INDEX IF NOT EXISTS auditevent_actor ON auditevent (actorsubject);
CREATE INDEX IF NOT EXISTS auditevent_action ON auditevent (action);
CREATE INDEX IF NOT EXISTS auditevent_project ON auditevent (projectid);
CREATE INDEX IF NOT EXISTS auditevent_outcome ON auditevent (outcome);
CREATE INDEX IF NOT EXISTS auditevent_target ON auditevent (targettype, targetid);

-- How an admin configured a report type: the class that runs it and its variables, one key=value per line.
CREATE TABLE IF NOT EXISTS reportplugin (
    id bigint NOT NULL,
    type character varying(50) NOT NULL,
    classname character varying(255) NOT NULL,
    variables text NOT NULL DEFAULT '',
    CONSTRAINT reportplugin_pkey PRIMARY KEY (id),
    CONSTRAINT reportplugin_type_key UNIQUE (type)
);
CREATE SEQUENCE IF NOT EXISTS reportplugin_seq START WITH 1 INCREMENT BY 50;

-- Webhooks a project LEAD configured. The secret signs each payload, so it is stored as is.
CREATE TABLE IF NOT EXISTS projectwebhook (
    id bigint NOT NULL,
    projectid bigint NOT NULL,
    url character varying(2048) NOT NULL,
    secret character varying(64) NOT NULL,
    onfinished boolean NOT NULL,
    onfailed boolean NOT NULL,
    onfixed boolean NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    CONSTRAINT projectwebhook_pkey PRIMARY KEY (id),
    CONSTRAINT projectwebhook_project FOREIGN KEY (projectid) REFERENCES project (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS projectwebhook_project ON projectwebhook (projectid);
CREATE SEQUENCE IF NOT EXISTS projectwebhook_seq START WITH 1 INCREMENT BY 50;

-- One row per webhook and build event: written with the build result, then sent and retried by
-- WebhookDispatcher. Rows go with their webhook or build.
CREATE TABLE IF NOT EXISTS webhookdelivery (
    id bigint NOT NULL,
    webhookid bigint NOT NULL,
    buildid bigint NOT NULL,
    event character varying(20) NOT NULL,
    payload character varying(4000) NOT NULL,
    status character varying(16) NOT NULL,
    attempts integer NOT NULL,
    nextattemptat timestamp(6) with time zone,
    lastattemptat timestamp(6) with time zone,
    responsestatus integer,
    error character varying(500),
    createdat timestamp(6) with time zone NOT NULL,
    CONSTRAINT webhookdelivery_pkey PRIMARY KEY (id),
    CONSTRAINT webhookdelivery_webhook FOREIGN KEY (webhookid) REFERENCES projectwebhook (id) ON DELETE CASCADE,
    CONSTRAINT webhookdelivery_build FOREIGN KEY (buildid) REFERENCES build (id) ON DELETE CASCADE,
    CONSTRAINT webhookdelivery_status_check CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT webhookdelivery_event_check CHECK (event IN ('build.passed', 'build.failed', 'build.fixed'))
);
CREATE INDEX IF NOT EXISTS webhookdelivery_pending ON webhookdelivery (nextattemptat) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS webhookdelivery_webhook ON webhookdelivery (webhookid, id DESC);
CREATE INDEX IF NOT EXISTS webhookdelivery_build ON webhookdelivery (buildid);
CREATE SEQUENCE IF NOT EXISTS webhookdelivery_seq START WITH 1 INCREMENT BY 50;
