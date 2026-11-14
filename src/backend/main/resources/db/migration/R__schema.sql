-- The whole schema, as one repeatable migration: Flyway reruns it whenever this file changes.
-- Every statement must stay idempotent, as it runs on fresh databases, on existing ones and on
-- databases created by schema-management=update before migrations existed (baselined at version 0).

CREATE EXTENSION IF NOT EXISTS lo;

CREATE TABLE IF NOT EXISTS project (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    ownersubject character varying(255) NOT NULL,
    privaterepository boolean NOT NULL,
    repositoryurl character varying(2048) NOT NULL,
    CONSTRAINT project_pkey PRIMARY KEY (id),
    CONSTRAINT uk_project_name UNIQUE (name),
    CONSTRAINT uk_project_repositoryurl UNIQUE (repositoryurl)
);

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

-- Builds run the repository's workflow file, so they no longer store a command.
ALTER TABLE build DROP COLUMN IF EXISTS command;
-- The workflow file each build read, kept with the build.
ALTER TABLE build ADD COLUMN IF NOT EXISTS workflow character varying(65536);

-- Null uses the configured queue limits.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxconcurrentbuilds integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxqueuedbuilds integer;

-- Null uses the configured checkout limits.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxcheckoutmib integer;
ALTER TABLE settings ADD COLUMN IF NOT EXISTS maxcheckoutfiles integer;
