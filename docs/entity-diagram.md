# Entity diagram

The entities are defined in `src/backend/main/java/ai/mnemosyne/cisys`. The schema is managed by Flyway migrations in `src/backend/main/resources/db/migration`, and Hibernate validates the entities against it (`quarkus.hibernate-orm.schema-management.strategy=validate`).

```mermaid
erDiagram
    PROJECT {
        BIGINT id PK
        STRING name
        STRING repository_url
        BOOLEAN private_repository
        STRING owner_subject
    }

    BUILD {
        BIGINT id PK
        BIGINT project_id
        STRING requested_by
        STRING status
        STRING revision
        STRING branch
        STRING image
        STRING workflow
        STRING image_digest
        INTEGER exit_code
        BOOLEAN log_truncated
        BOOLEAN pinned
        BOOLEAN cancel_requested
        OID log_oid
        TIMESTAMP started_at
        TIMESTAMP finished_at
    }

    BRANDING {
        BIGINT id PK
        STRING name
        STRING header_color
        STRING heading_color
        STRING button_color
        TEXT logo
        TEXT background
    }

    SETTINGS {
        BIGINT id PK
        INTEGER retention_days
        INTEGER retention_max_builds
        INTEGER max_concurrent_builds
        INTEGER max_queued_builds
        INTEGER max_checkout_mib
        INTEGER max_checkout_files
    }

    PROJECT ||--o{ BUILD : has
```

* `PROJECT.owner_subject` and `BUILD.requested_by` hold the Keycloak subject (`sub`) of the user.
* `BUILD.project_id` is a plain column, not a foreign key constraint.
* `BUILD.logoid` (type `lo`, from the `lo` extension) references the build log, stored as one PostgreSQL large object. It is not mapped in the `Build` entity. The `lo_manage` trigger unlinks the large object when the build is deleted, so no logs are orphaned.
* `BUILD.status` is `QUEUED`, `RUNNING`, `PASSED`, `FAILED` or `CANCELLED`. `BUILD.cancel_requested` is set on a running build until the runner has stopped it.
* `BRANDING` and `SETTINGS` are singleton rows with `id = 1`. Until `SETTINGS` is first saved, its defaults apply.
* Users, roles and credentials live in Keycloak and are not stored in the cisys database.
