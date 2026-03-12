use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
    time::Duration,
};

use rusqlite::{Connection, OptionalExtension, params};
use thiserror::Error;

pub const CURRENT_SCHEMA_VERSION: i64 = 1;

const BOOTSTRAP_SQL: &str = r#"
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
"#;

const MIGRATION_0001_P0_TRUTH_SCHEMA: &str = r#"
CREATE TABLE providers (
    local_id TEXT PRIMARY KEY,
    adapter_kind TEXT NOT NULL CHECK (adapter_kind IN ('openai_compatible', 'google_compatible', 'anthropic_compatible', 'vcptoolbox')),
    display_name TEXT NOT NULL,
    avatar_uri TEXT,
    base_url TEXT NOT NULL,
    auth_kind TEXT NOT NULL CHECK (auth_kind IN ('none', 'bearer_token', 'api_key', 'basic')),
    auth_header_name TEXT,
    auth_value TEXT,
    auth_username TEXT,
    auth_password TEXT,
    default_model TEXT,
    default_preset_local_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (default_preset_local_id) REFERENCES provider_presets(local_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE provider_reference_aliases (
    provider_local_id TEXT NOT NULL,
    alias TEXT NOT NULL,
    PRIMARY KEY (provider_local_id, alias),
    FOREIGN KEY (provider_local_id) REFERENCES providers(local_id) ON DELETE CASCADE
);

CREATE TABLE provider_model_catalog_entries (
    provider_local_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    model_id TEXT NOT NULL,
    display_name TEXT,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    PRIMARY KEY (provider_local_id, order_index),
    UNIQUE (provider_local_id, model_id),
    FOREIGN KEY (provider_local_id) REFERENCES providers(local_id) ON DELETE CASCADE
);

CREATE TABLE provider_headers (
    provider_local_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (provider_local_id, order_index),
    FOREIGN KEY (provider_local_id) REFERENCES providers(local_id) ON DELETE CASCADE
);

CREATE TABLE provider_body_fragments (
    provider_local_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    pointer TEXT NOT NULL,
    value_json TEXT NOT NULL,
    PRIMARY KEY (provider_local_id, order_index),
    FOREIGN KEY (provider_local_id) REFERENCES providers(local_id) ON DELETE CASCADE
);

CREATE TABLE provider_presets (
    local_id TEXT PRIMARY KEY,
    provider_local_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    name TEXT NOT NULL,
    description TEXT,
    model_id TEXT,
    FOREIGN KEY (provider_local_id) REFERENCES providers(local_id) ON DELETE CASCADE,
    UNIQUE (provider_local_id, ordinal)
);

CREATE TABLE provider_preset_headers (
    preset_local_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    name TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (preset_local_id, order_index),
    FOREIGN KEY (preset_local_id) REFERENCES provider_presets(local_id) ON DELETE CASCADE
);

CREATE TABLE provider_preset_body_fragments (
    preset_local_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    pointer TEXT NOT NULL,
    value_json TEXT NOT NULL,
    PRIMARY KEY (preset_local_id, order_index),
    FOREIGN KEY (preset_local_id) REFERENCES provider_presets(local_id) ON DELETE CASCADE
);

CREATE TABLE agents (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    avatar_uri TEXT,
    system_prompt TEXT NOT NULL DEFAULT '',
    prompt_mode TEXT NOT NULL DEFAULT 'direct',
    provider_local_id TEXT,
    model_id TEXT,
    request_overrides_json TEXT,
    memory_enabled INTEGER NOT NULL DEFAULT 0 CHECK (memory_enabled IN (0, 1)),
    local_tools_enabled INTEGER NOT NULL DEFAULT 1 CHECK (local_tools_enabled IN (0, 1)),
    group_participation_mode TEXT NOT NULL DEFAULT 'invite_only',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (provider_local_id) REFERENCES providers(local_id) ON DELETE SET NULL
);

CREATE TABLE agent_placeholder_bindings (
    agent_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    binding_key TEXT NOT NULL,
    binding_value TEXT NOT NULL,
    category TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (agent_id, order_index),
    UNIQUE (agent_id, binding_key, order_index),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE TABLE agent_tool_toggles (
    agent_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    PRIMARY KEY (agent_id, tool_name),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE TABLE topics (
    id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    title TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE TABLE conversations (
    id TEXT PRIMARY KEY,
    topic_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT,
    pinned INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
    generation_state TEXT NOT NULL CHECK (generation_state IN ('idle', 'requesting', 'started', 'streaming', 'completed', 'failed', 'cancelled')),
    current_cursor_node_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE RESTRICT,
    FOREIGN KEY (id, current_cursor_node_id) REFERENCES message_nodes(conversation_id, id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE conversation_participants (
    conversation_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    participation_mode TEXT NOT NULL DEFAULT 'invite_only',
    joined_at TEXT NOT NULL,
    PRIMARY KEY (conversation_id, agent_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE TABLE message_nodes (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    parent_node_id TEXT,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    selected_variant_ordinal INTEGER NOT NULL CHECK (selected_variant_ordinal >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (conversation_id, id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (conversation_id, parent_node_id) REFERENCES message_nodes(conversation_id, id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (id, selected_variant_ordinal) REFERENCES message_variants(node_id, ordinal) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE message_variants (
    id TEXT PRIMARY KEY,
    node_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    status TEXT NOT NULL CHECK (status IN ('streaming', 'completed', 'failed', 'cancelled')),
    model_id TEXT,
    usage_json TEXT,
    created_at TEXT NOT NULL,
    finished_at TEXT,
    UNIQUE (node_id, ordinal),
    FOREIGN KEY (node_id) REFERENCES message_nodes(id) ON DELETE CASCADE
);

CREATE TABLE message_parts (
    id TEXT PRIMARY KEY,
    variant_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    kind TEXT NOT NULL CHECK (kind IN ('text', 'reasoning', 'tool_call', 'tool_result', 'image', 'document', 'tool', 'file', 'quote', 'code_block', 'markdown_block', 'error')),
    text_value TEXT,
    file_name TEXT,
    url TEXT,
    mime TEXT,
    alt_text TEXT,
    tool_name TEXT,
    tool_call_id TEXT,
    tool_state TEXT CHECK (tool_state IN ('pending', 'running', 'completed', 'failed')),
    input_json TEXT,
    output_json TEXT,
    error_message TEXT,
    source_text TEXT,
    language TEXT,
    UNIQUE (variant_id, order_index),
    FOREIGN KEY (variant_id) REFERENCES message_variants(id) ON DELETE CASCADE,
    CHECK (
        (kind IN ('text', 'reasoning', 'markdown_block', 'error') AND text_value IS NOT NULL)
        OR (kind = 'quote' AND text_value IS NOT NULL)
        OR (kind = 'code_block' AND text_value IS NOT NULL)
        OR (kind = 'image' AND url IS NOT NULL)
        OR (kind IN ('document', 'file') AND file_name IS NOT NULL AND url IS NOT NULL)
        OR (kind = 'tool_call' AND tool_name IS NOT NULL AND input_json IS NOT NULL)
        OR (kind = 'tool_result' AND tool_name IS NOT NULL AND output_json IS NOT NULL)
        OR (kind = 'tool' AND tool_name IS NOT NULL AND input_json IS NOT NULL AND tool_state IS NOT NULL)
    )
);

CREATE TABLE conversation_drafts (
    conversation_id TEXT PRIMARY KEY,
    text TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE TABLE draft_attachments (
    conversation_id TEXT NOT NULL,
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    attachment_id TEXT NOT NULL,
    PRIMARY KEY (conversation_id, order_index),
    UNIQUE (conversation_id, attachment_id),
    FOREIGN KEY (conversation_id) REFERENCES conversation_drafts(conversation_id) ON DELETE CASCADE
);

CREATE TABLE trusted_devices (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    public_key TEXT NOT NULL,
    paired_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE TABLE pairing_sessions (
    id TEXT PRIMARY KEY,
    device_label TEXT,
    bootstrap_token TEXT NOT NULL,
    qr_payload TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending', 'paired', 'expired', 'revoked')),
    trusted_device_id TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    completed_at TEXT,
    FOREIGN KEY (trusted_device_id) REFERENCES trusted_devices(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_conversations_updated_at ON conversations(updated_at DESC);
CREATE INDEX idx_message_nodes_parent ON message_nodes(conversation_id, parent_node_id);
CREATE INDEX idx_message_variants_node ON message_variants(node_id, ordinal);
CREATE INDEX idx_message_parts_variant_order ON message_parts(variant_id, order_index);
CREATE INDEX idx_provider_reference_aliases_alias ON provider_reference_aliases(alias);
CREATE INDEX idx_pairing_sessions_status ON pairing_sessions(status, expires_at);
"#;

#[derive(Debug, Clone, Copy)]
struct MigrationSpec {
    version: i64,
    name: &'static str,
    sql: &'static str,
}

const MIGRATIONS: &[MigrationSpec] = &[MigrationSpec {
    version: 1,
    name: "0001_p0_truth_schema",
    sql: MIGRATION_0001_P0_TRUTH_SCHEMA,
}];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MigrationRecord {
    pub version: i64,
    pub name: String,
    pub applied_at: String,
}

#[derive(Debug, Clone)]
pub struct SqliteStore {
    path: PathBuf,
}

#[derive(Debug, Error)]
pub enum SqliteStoreError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("unsupported sqlite schema version: {0}")]
    UnsupportedSchemaVersion(i64),
}

pub type SqliteStoreResult<T> = Result<T, SqliteStoreError>;

impl SqliteStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn open(&self) -> SqliteStoreResult<Connection> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }

        let mut connection = Connection::open(&self.path)?;
        connection.busy_timeout(Duration::from_secs(5))?;
        migrate_sqlite_schema(&mut connection)?;
        Ok(connection)
    }
}

pub fn migrate_sqlite_schema(connection: &mut Connection) -> SqliteStoreResult<()> {
    connection.pragma_update(None, "foreign_keys", "ON")?;
    connection.execute_batch(BOOTSTRAP_SQL)?;

    let current_version = connection
        .pragma_query_value(None, "user_version", |row| row.get::<_, i64>(0))
        .unwrap_or(0);
    if current_version > CURRENT_SCHEMA_VERSION {
        return Err(SqliteStoreError::UnsupportedSchemaVersion(current_version));
    }

    let applied_versions = applied_versions(connection)?;
    let transaction = connection.transaction()?;
    for migration in MIGRATIONS {
        if applied_versions.contains(&migration.version) {
            continue;
        }

        transaction.execute_batch(migration.sql)?;
        transaction.execute(
            "INSERT INTO schema_migrations (version, name) VALUES (?1, ?2)",
            params![migration.version, migration.name],
        )?;
        transaction.pragma_update(None, "user_version", migration.version)?;
    }
    transaction.commit()?;
    Ok(())
}

fn applied_versions(connection: &Connection) -> SqliteStoreResult<BTreeSet<i64>> {
    let mut statement =
        connection.prepare("SELECT version FROM schema_migrations ORDER BY version ASC")?;
    let versions = statement
        .query_map([], |row| row.get::<_, i64>(0))?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(versions.into_iter().collect())
}

pub fn list_applied_migrations(connection: &Connection) -> SqliteStoreResult<Vec<MigrationRecord>> {
    let mut statement = connection
        .prepare("SELECT version, name, applied_at FROM schema_migrations ORDER BY version ASC")?;
    let rows = statement.query_map([], |row| {
        Ok(MigrationRecord {
            version: row.get(0)?,
            name: row.get(1)?,
            applied_at: row.get(2)?,
        })
    })?;

    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::params;
    use uuid::Uuid;

    fn open_memory_db() -> Connection {
        Connection::open_in_memory().expect("open sqlite memory database")
    }

    fn scalar_i64(connection: &Connection, sql: &str) -> i64 {
        connection
            .query_row(sql, [], |row| row.get::<_, i64>(0))
            .expect("query scalar i64")
    }

    fn insert_agent_graph(connection: &Connection) -> (String, String, String) {
        let agent_id = Uuid::new_v4().to_string();
        let topic_id = Uuid::new_v4().to_string();
        let conversation_id = Uuid::new_v4().to_string();
        let now = "2026-03-13T00:00:00Z";

        connection
            .execute(
                "INSERT INTO agents (id, name, description, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4)",
                params![agent_id, "Agent", "P0 agent", now],
            )
            .expect("insert agent");
        connection
            .execute(
                "INSERT INTO topics (id, agent_id, title, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?4)",
                params![topic_id, agent_id, "Topic", now],
            )
            .expect("insert topic");
        connection
            .execute(
                "INSERT INTO conversations (id, topic_id, agent_id, title, generation_state, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, 'idle', ?5, ?5)",
                params![conversation_id, topic_id, agent_id, "Conversation", now],
            )
            .expect("insert conversation");

        (agent_id, topic_id, conversation_id)
    }

    #[test]
    fn migration_entrypoint_creates_p0_truth_tables() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply migrations");

        let tables = [
            "providers",
            "provider_presets",
            "agents",
            "topics",
            "conversations",
            "conversation_participants",
            "message_nodes",
            "message_variants",
            "message_parts",
            "conversation_drafts",
            "draft_attachments",
            "pairing_sessions",
            "trusted_devices",
        ];

        for table in tables {
            let exists = connection
                .query_row(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?1",
                    [table],
                    |_| Ok(()),
                )
                .optional()
                .expect("lookup sqlite_master")
                .is_some();
            assert!(exists, "expected table {table} to exist");
        }

        assert_eq!(
            scalar_i64(&connection, "PRAGMA user_version"),
            CURRENT_SCHEMA_VERSION
        );
        let applied = list_applied_migrations(&connection).expect("list applied migrations");
        assert_eq!(applied.len(), 1);
        assert_eq!(applied[0].version, 1);
        assert_eq!(applied[0].name, "0001_p0_truth_schema");
    }

    #[test]
    fn migration_entrypoint_is_idempotent() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply first migration");
        migrate_sqlite_schema(&mut connection).expect("apply second migration");

        assert_eq!(
            scalar_i64(&connection, "SELECT COUNT(*) FROM schema_migrations"),
            1
        );
    }

    #[test]
    fn selected_variant_ordinal_must_resolve_on_commit() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply migrations");
        let (_, _, conversation_id) = insert_agent_graph(&connection);
        let node_id = Uuid::new_v4().to_string();
        let variant_id = Uuid::new_v4().to_string();
        let now = "2026-03-13T00:00:00Z";

        let transaction = connection.transaction().expect("start transaction");
        transaction
            .execute(
                "INSERT INTO message_nodes (id, conversation_id, role, selected_variant_ordinal, created_at, updated_at) VALUES (?1, ?2, 'assistant', 0, ?3, ?3)",
                params![node_id, conversation_id, now],
            )
            .expect("insert node");
        transaction
            .execute(
                "INSERT INTO message_variants (id, node_id, ordinal, status, created_at) VALUES (?1, ?2, 0, 'completed', ?3)",
                params![variant_id, node_id, now],
            )
            .expect("insert variant");
        transaction
            .commit()
            .expect("commit valid node/variant pair");

        assert_eq!(
            scalar_i64(
                &connection,
                "SELECT COUNT(*) FROM message_variants WHERE node_id IS NOT NULL"
            ),
            1
        );
    }

    #[test]
    fn selected_variant_ordinal_rejects_missing_variant() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply migrations");
        let (_, _, conversation_id) = insert_agent_graph(&connection);
        let node_id = Uuid::new_v4().to_string();
        let now = "2026-03-13T00:00:00Z";

        let transaction = connection.transaction().expect("start transaction");
        transaction
            .execute(
                "INSERT INTO message_nodes (id, conversation_id, role, selected_variant_ordinal, created_at, updated_at) VALUES (?1, ?2, 'assistant', 1, ?3, ?3)",
                params![node_id, conversation_id, now],
            )
            .expect("insert node");

        let commit_error = transaction
            .commit()
            .expect_err("commit should reject unresolved selected variant");
        assert!(matches!(commit_error, rusqlite::Error::SqliteFailure(_, _)));
    }

    #[test]
    fn conversation_cursor_must_point_to_node_in_same_conversation() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply migrations");

        let (_, _, first_conversation_id) = insert_agent_graph(&connection);
        let (_, _, second_conversation_id) = insert_agent_graph(&connection);
        let node_id = Uuid::new_v4().to_string();
        let variant_id = Uuid::new_v4().to_string();
        let now = "2026-03-13T00:00:00Z";

        let transaction = connection.transaction().expect("start transaction");
        transaction
            .execute(
                "INSERT INTO message_nodes (id, conversation_id, role, selected_variant_ordinal, created_at, updated_at) VALUES (?1, ?2, 'assistant', 0, ?3, ?3)",
                params![node_id, second_conversation_id, now],
            )
            .expect("insert node");
        transaction
            .execute(
                "INSERT INTO message_variants (id, node_id, ordinal, status, created_at) VALUES (?1, ?2, 0, 'completed', ?3)",
                params![variant_id, node_id, now],
            )
            .expect("insert variant");
        transaction
            .execute(
                "UPDATE conversations SET current_cursor_node_id = ?1 WHERE id = ?2",
                params![node_id, first_conversation_id],
            )
            .expect("update conversation cursor");

        let commit_error = transaction
            .commit()
            .expect_err("cursor should reject node from another conversation");
        assert!(matches!(commit_error, rusqlite::Error::SqliteFailure(_, _)));
    }

    #[test]
    fn parts_keep_stable_order_within_variant() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply migrations");
        let (_, _, conversation_id) = insert_agent_graph(&connection);
        let node_id = Uuid::new_v4().to_string();
        let variant_id = Uuid::new_v4().to_string();
        let now = "2026-03-13T00:00:00Z";

        let transaction = connection.transaction().expect("start transaction");
        transaction
            .execute(
                "INSERT INTO message_nodes (id, conversation_id, role, selected_variant_ordinal, created_at, updated_at) VALUES (?1, ?2, 'assistant', 0, ?3, ?3)",
                params![node_id, conversation_id, now],
            )
            .expect("insert node");
        transaction
            .execute(
                "INSERT INTO message_variants (id, node_id, ordinal, status, created_at) VALUES (?1, ?2, 0, 'streaming', ?3)",
                params![variant_id, node_id, now],
            )
            .expect("insert variant");
        transaction
            .execute(
                "INSERT INTO message_parts (id, variant_id, order_index, kind, text_value) VALUES (?1, ?2, 0, 'text', 'hello')",
                params![Uuid::new_v4().to_string(), variant_id],
            )
            .expect("insert first part");
        let duplicate = transaction.execute(
            "INSERT INTO message_parts (id, variant_id, order_index, kind, text_value) VALUES (?1, ?2, 0, 'reasoning', 'thinking')",
            params![Uuid::new_v4().to_string(), variant_id],
        );
        assert!(duplicate.is_err(), "duplicate part order must fail");
    }
}
