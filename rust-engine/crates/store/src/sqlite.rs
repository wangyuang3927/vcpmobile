use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
    time::Duration,
};

use chrono::{DateTime, Utc};
use rusqlite::{Connection, OptionalExtension, params};
use thiserror::Error;
use uuid::Uuid;
use vcpmobile_domain::{
    AgentConfig, AgentId, Conversation, ConversationId, GenerationState, MessageNode, MessagePart,
    MessagePartPayload, MessageRole, MessageVariant, NodeId, ToolPartState, TopicId, VariantStatus,
};
use vcpmobile_protocol::{NodeBundle, VariantBundle};

use crate::StoredConversation;

pub const CURRENT_SCHEMA_VERSION: i64 = 2;

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

const MIGRATION_0002_AGENT_CONFIG_STORE: &str = r#"
CREATE TABLE agent_configs (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    config_json TEXT NOT NULL,
    FOREIGN KEY (id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_configs_updated_at ON agent_configs(updated_at DESC, id DESC);
"#;

#[derive(Debug, Clone, Copy)]
struct MigrationSpec {
    version: i64,
    name: &'static str,
    sql: &'static str,
}

const MIGRATIONS: &[MigrationSpec] = &[
    MigrationSpec {
        version: 1,
        name: "0001_p0_truth_schema",
        sql: MIGRATION_0001_P0_TRUTH_SCHEMA,
    },
    MigrationSpec {
        version: 2,
        name: "0002_agent_config_store",
        sql: MIGRATION_0002_AGENT_CONFIG_STORE,
    },
];

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
    #[error("sqlite decode error: {0}")]
    Decode(String),
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

    pub fn has_conversations(&self) -> SqliteStoreResult<bool> {
        let connection = self.open()?;
        let count = connection.query_row("SELECT COUNT(*) FROM conversations", [], |row| {
            row.get::<_, i64>(0)
        })?;
        Ok(count > 0)
    }

    pub fn has_agent_configs(&self) -> SqliteStoreResult<bool> {
        let connection = self.open()?;
        let count = connection.query_row("SELECT COUNT(*) FROM agent_configs", [], |row| {
            row.get::<_, i64>(0)
        })?;
        Ok(count > 0)
    }

    pub fn import_conversations<I>(&self, conversations: I) -> SqliteStoreResult<()>
    where
        I: IntoIterator<Item = StoredConversation>,
    {
        let mut connection = self.open()?;
        let transaction = connection.transaction()?;
        for conversation in conversations {
            replace_conversation_tx(&transaction, &conversation)?;
        }
        transaction.commit()?;
        Ok(())
    }

    pub fn replace_conversation(&self, record: &StoredConversation) -> SqliteStoreResult<()> {
        let mut connection = self.open()?;
        let transaction = connection.transaction()?;
        replace_conversation_tx(&transaction, record)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn import_agents<I>(&self, agents: I) -> SqliteStoreResult<()>
    where
        I: IntoIterator<Item = AgentConfig>,
    {
        let mut connection = self.open()?;
        let transaction = connection.transaction()?;
        for agent in agents {
            upsert_agent_config_tx(&transaction, &agent)?;
        }
        transaction.commit()?;
        Ok(())
    }

    pub fn get_agent(&self, agent_id: &str) -> SqliteStoreResult<Option<AgentConfig>> {
        let connection = self.open()?;
        load_agent_config(&connection, agent_id)
    }

    pub fn upsert_agent(&self, agent: &AgentConfig) -> SqliteStoreResult<()> {
        let mut connection = self.open()?;
        let transaction = connection.transaction()?;
        upsert_agent_config_tx(&transaction, agent)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn list_agents(&self) -> SqliteStoreResult<Vec<AgentConfig>> {
        let connection = self.open()?;
        let mut statement = connection
            .prepare("SELECT id FROM agent_configs ORDER BY updated_at DESC, rowid DESC")?;
        let ids = statement
            .query_map([], |row| row.get::<_, String>(0))?
            .collect::<Result<Vec<_>, _>>()?;

        let mut agents = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(agent) = load_agent_config(&connection, &id)? {
                agents.push(agent);
            }
        }
        Ok(agents)
    }

    pub fn delete_agent(&self, agent_id: &str) -> SqliteStoreResult<Option<AgentConfig>> {
        let mut connection = self.open()?;
        let transaction = connection.transaction()?;
        let Some(agent) = load_agent_config(&transaction, agent_id)? else {
            return Ok(None);
        };

        transaction.execute("DELETE FROM agent_configs WHERE id = ?1", params![agent_id])?;
        transaction.execute(
            "DELETE FROM agents
             WHERE id = ?1
               AND NOT EXISTS (SELECT 1 FROM topics WHERE agent_id = ?1)
               AND NOT EXISTS (SELECT 1 FROM conversations WHERE agent_id = ?1)
               AND NOT EXISTS (
                    SELECT 1 FROM conversation_participants WHERE agent_id = ?1
               )",
            params![agent_id],
        )?;
        transaction.commit()?;
        Ok(Some(agent))
    }

    pub fn get_conversation(
        &self,
        conversation_id: ConversationId,
    ) -> SqliteStoreResult<Option<StoredConversation>> {
        let connection = self.open()?;
        load_conversation(&connection, &conversation_id.to_string())
    }

    pub fn read_selected_variant(
        &self,
        conversation_id: ConversationId,
        node_id: NodeId,
    ) -> SqliteStoreResult<Option<Uuid>> {
        let connection = self.open()?;
        connection
            .query_row(
                "SELECT message_variants.id
                 FROM message_nodes
                 JOIN message_variants
                   ON message_variants.node_id = message_nodes.id
                  AND message_variants.ordinal = message_nodes.selected_variant_ordinal
                 WHERE message_nodes.conversation_id = ?1
                   AND message_nodes.id = ?2",
                params![conversation_id.to_string(), node_id.to_string()],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .map(|value| parse_uuid(&value, "message_variants.id"))
            .transpose()
    }

    pub fn write_selected_variant(
        &self,
        conversation_id: ConversationId,
        node_id: NodeId,
        variant_id: Uuid,
    ) -> SqliteStoreResult<bool> {
        let mut connection = self.open()?;
        let transaction = connection.transaction()?;
        let Some(ordinal) = transaction
            .query_row(
                "SELECT message_variants.ordinal
                 FROM message_variants
                 JOIN message_nodes ON message_nodes.id = message_variants.node_id
                 WHERE message_nodes.conversation_id = ?1
                   AND message_nodes.id = ?2
                   AND message_variants.id = ?3",
                params![
                    conversation_id.to_string(),
                    node_id.to_string(),
                    variant_id.to_string()
                ],
                |row| row.get::<_, i64>(0),
            )
            .optional()?
        else {
            return Ok(false);
        };

        let now = Utc::now().to_rfc3339();
        let updated = transaction.execute(
            "UPDATE message_nodes
             SET selected_variant_ordinal = ?1, updated_at = ?2
             WHERE conversation_id = ?3 AND id = ?4",
            params![
                ordinal,
                now,
                conversation_id.to_string(),
                node_id.to_string()
            ],
        )?;
        if updated == 0 {
            return Ok(false);
        }
        transaction.execute(
            "UPDATE conversations SET updated_at = ?1 WHERE id = ?2",
            params![now, conversation_id.to_string()],
        )?;
        transaction.commit()?;
        Ok(true)
    }

    pub fn list_conversations(&self) -> SqliteStoreResult<Vec<StoredConversation>> {
        let connection = self.open()?;
        let mut statement = connection
            .prepare("SELECT id FROM conversations ORDER BY updated_at DESC, rowid DESC")?;
        let ids = statement
            .query_map([], |row| row.get::<_, String>(0))?
            .collect::<Result<Vec<_>, _>>()?;

        let mut conversations = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(conversation) = load_conversation(&connection, &id)? {
                conversations.push(conversation);
            }
        }
        Ok(conversations)
    }
}

fn replace_conversation_tx(
    transaction: &rusqlite::Transaction<'_>,
    record: &StoredConversation,
) -> SqliteStoreResult<()> {
    let conversation = &record.conversation;
    ensure_agent_placeholder(
        transaction,
        conversation.agent_id,
        conversation.created_at,
        conversation.updated_at,
    )?;
    ensure_topic_placeholder(
        transaction,
        conversation.topic_id,
        conversation.agent_id,
        conversation.created_at,
        conversation.updated_at,
    )?;

    transaction.execute(
        "DELETE FROM conversations WHERE id = ?1",
        params![conversation.id.to_string()],
    )?;

    transaction.execute(
        "INSERT INTO conversations (
            id,
            topic_id,
            agent_id,
            title,
            summary,
            pinned,
            generation_state,
            current_cursor_node_id,
            created_at,
            updated_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
        params![
            conversation.id.to_string(),
            conversation.topic_id.to_string(),
            conversation.agent_id.to_string(),
            conversation.title.as_str(),
            conversation.summary.as_deref(),
            bool_to_sqlite(conversation.pinned),
            generation_state_to_str(conversation.generation_state),
            conversation.current_cursor.map(|cursor| cursor.to_string()),
            conversation.created_at.to_rfc3339(),
            conversation.updated_at.to_rfc3339(),
        ],
    )?;

    for bundle in &record.nodes {
        transaction.execute(
            "INSERT INTO message_nodes (
                id,
                conversation_id,
                parent_node_id,
                role,
                selected_variant_ordinal,
                created_at,
                updated_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                bundle.node.id.to_string(),
                bundle.node.conversation_id.to_string(),
                bundle.node.parent_node_id.map(|parent| parent.to_string()),
                message_role_to_str(bundle.node.role),
                bundle.node.select_index as i64,
                bundle.node.created_at.to_rfc3339(),
                bundle.node.updated_at.to_rfc3339(),
            ],
        )?;

        for (ordinal, variant_bundle) in bundle.variants.iter().enumerate() {
            transaction.execute(
                "INSERT INTO message_variants (
                    id,
                    node_id,
                    ordinal,
                    status,
                    model_id,
                    usage_json,
                    created_at,
                    finished_at
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![
                    variant_bundle.variant.id.to_string(),
                    variant_bundle.variant.node_id.to_string(),
                    ordinal as i64,
                    variant_status_to_str(variant_bundle.variant.status),
                    variant_bundle.variant.model_id.as_deref(),
                    variant_bundle.variant.usage_json.as_deref(),
                    variant_bundle.variant.created_at.to_rfc3339(),
                    variant_bundle
                        .variant
                        .finished_at
                        .map(|value| value.to_rfc3339()),
                ],
            )?;

            for part in &variant_bundle.parts {
                insert_message_part(transaction, part)?;
            }
        }
    }

    Ok(())
}

fn upsert_agent_config_tx(
    transaction: &rusqlite::Transaction<'_>,
    agent: &AgentConfig,
) -> SqliteStoreResult<()> {
    let request_overrides_json = serde_json::to_string(&agent.request)
        .map_err(|error| SqliteStoreError::Decode(error.to_string()))?;
    let config_json = serde_json::to_string(agent)
        .map_err(|error| SqliteStoreError::Decode(error.to_string()))?;
    let description = agent.identity.description.clone().unwrap_or_default();
    let prompt_mode = serde_json::to_value(agent.prompt.prompt_mode)
        .map_err(|error| SqliteStoreError::Decode(error.to_string()))?
        .as_str()
        .unwrap_or("system_only")
        .to_string();

    transaction.execute(
        "INSERT INTO agents (
            id,
            name,
            description,
            avatar_uri,
            system_prompt,
            prompt_mode,
            provider_local_id,
            model_id,
            request_overrides_json,
            memory_enabled,
            local_tools_enabled,
            group_participation_mode,
            created_at,
            updated_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, NULL, ?7, ?8, ?9, ?10, 'invite_only', ?11, ?12)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            description = excluded.description,
            avatar_uri = excluded.avatar_uri,
            system_prompt = excluded.system_prompt,
            prompt_mode = excluded.prompt_mode,
            model_id = excluded.model_id,
            request_overrides_json = excluded.request_overrides_json,
            memory_enabled = excluded.memory_enabled,
            local_tools_enabled = excluded.local_tools_enabled,
            updated_at = excluded.updated_at",
        params![
            agent.id.to_string(),
            agent.identity.name.as_str(),
            description,
            agent.identity.avatar_uri.as_deref(),
            agent.prompt.system_prompt.as_str(),
            prompt_mode,
            agent.model.model_id.as_deref(),
            request_overrides_json,
            bool_to_sqlite(agent.memory.use_conversation_memory),
            bool_to_sqlite(agent.tools.enable_local_tools),
            agent.created_at.to_rfc3339(),
            agent.updated_at.to_rfc3339(),
        ],
    )?;

    transaction.execute(
        "INSERT INTO agent_configs (id, name, created_at, updated_at, config_json)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            created_at = excluded.created_at,
            updated_at = excluded.updated_at,
            config_json = excluded.config_json",
        params![
            agent.id.to_string(),
            agent.identity.name.as_str(),
            agent.created_at.to_rfc3339(),
            agent.updated_at.to_rfc3339(),
            config_json,
        ],
    )?;

    Ok(())
}

fn ensure_agent_placeholder(
    transaction: &rusqlite::Transaction<'_>,
    agent_id: AgentId,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
) -> SqliteStoreResult<()> {
    let agent_id = agent_id.to_string();
    let created_at = created_at.to_rfc3339();
    let updated_at = updated_at.to_rfc3339();
    let title = format!("Agent {}", short_id(&agent_id));
    transaction.execute(
        "INSERT OR IGNORE INTO agents (id, name, description, created_at, updated_at)
         VALUES (?1, ?2, '', ?3, ?4)",
        params![agent_id, title, created_at, updated_at],
    )?;
    Ok(())
}

fn ensure_topic_placeholder(
    transaction: &rusqlite::Transaction<'_>,
    topic_id: TopicId,
    agent_id: AgentId,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
) -> SqliteStoreResult<()> {
    let topic_id = topic_id.to_string();
    let title = format!("Topic {}", short_id(&topic_id));
    transaction.execute(
        "INSERT OR IGNORE INTO topics (id, agent_id, title, created_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            topic_id,
            agent_id.to_string(),
            title,
            created_at.to_rfc3339(),
            updated_at.to_rfc3339(),
        ],
    )?;
    Ok(())
}

fn insert_message_part(
    transaction: &rusqlite::Transaction<'_>,
    part: &MessagePart,
) -> SqliteStoreResult<()> {
    let (
        kind,
        text_value,
        file_name,
        url,
        mime,
        alt_text,
        tool_name,
        tool_call_id,
        tool_state,
        input_json,
        output_json,
        error_message,
        source_text,
        language,
    ) = match &part.payload {
        MessagePartPayload::Text { text } => (
            "text",
            Some(text.as_str()),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::Reasoning { text } => (
            "reasoning",
            Some(text.as_str()),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::ToolCall {
            tool_name,
            arguments_json,
        } => (
            "tool_call",
            None,
            None,
            None,
            None,
            None,
            Some(tool_name.as_str()),
            None,
            None,
            Some(arguments_json.as_str()),
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::ToolResult {
            tool_name,
            result_json,
        } => (
            "tool_result",
            None,
            None,
            None,
            None,
            None,
            Some(tool_name.as_str()),
            None,
            None,
            None,
            Some(result_json.as_str()),
            None,
            None,
            None,
        ),
        MessagePartPayload::Image { url, mime, alt } => (
            "image",
            None,
            None,
            Some(url.as_str()),
            mime.as_deref(),
            alt.as_deref(),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::Document {
            file_name,
            url,
            mime,
        } => (
            "document",
            None,
            Some(file_name.as_str()),
            Some(url.as_str()),
            mime.as_deref(),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::Tool {
            tool_call_id,
            tool_name,
            state,
            input_json,
            output_json,
            error_message,
        } => (
            "tool",
            None,
            None,
            None,
            None,
            None,
            Some(tool_name.as_str()),
            tool_call_id.as_deref(),
            Some(tool_state_to_str(*state)),
            Some(input_json.as_str()),
            output_json.as_deref(),
            error_message.as_deref(),
            None,
            None,
        ),
        MessagePartPayload::File { name, url, mime } => (
            "file",
            None,
            Some(name.as_str()),
            Some(url.as_str()),
            mime.as_deref(),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::Quote { text, source } => (
            "quote",
            Some(text.as_str()),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            source.as_deref(),
            None,
        ),
        MessagePartPayload::CodeBlock { language, code } => (
            "code_block",
            Some(code.as_str()),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            language.as_deref(),
        ),
        MessagePartPayload::MarkdownBlock { markdown } => (
            "markdown_block",
            Some(markdown.as_str()),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
        MessagePartPayload::Error { message } => (
            "error",
            Some(message.as_str()),
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
        ),
    };

    transaction.execute(
        "INSERT INTO message_parts (
            id,
            variant_id,
            order_index,
            kind,
            text_value,
            file_name,
            url,
            mime,
            alt_text,
            tool_name,
            tool_call_id,
            tool_state,
            input_json,
            output_json,
            error_message,
            source_text,
            language
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17)",
        params![
            part.id.to_string(),
            part.variant_id.to_string(),
            part.order_index,
            kind,
            text_value,
            file_name,
            url,
            mime,
            alt_text,
            tool_name,
            tool_call_id,
            tool_state,
            input_json,
            output_json,
            error_message,
            source_text,
            language,
        ],
    )?;
    Ok(())
}

fn load_conversation(
    connection: &Connection,
    conversation_id: &str,
) -> SqliteStoreResult<Option<StoredConversation>> {
    let raw_conversation = connection
        .query_row(
            "SELECT
                id,
                topic_id,
                agent_id,
                title,
                summary,
                pinned,
                generation_state,
                current_cursor_node_id,
                created_at,
                updated_at
             FROM conversations
             WHERE id = ?1",
            params![conversation_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, Option<String>>(4)?,
                    row.get::<_, i64>(5)?,
                    row.get::<_, String>(6)?,
                    row.get::<_, Option<String>>(7)?,
                    row.get::<_, String>(8)?,
                    row.get::<_, String>(9)?,
                ))
            },
        )
        .optional()?;

    let Some(raw_conversation) = raw_conversation else {
        return Ok(None);
    };
    let conversation = Conversation {
        id: parse_uuid(&raw_conversation.0, "conversations.id")?,
        topic_id: parse_uuid(&raw_conversation.1, "conversations.topic_id")?,
        agent_id: parse_uuid(&raw_conversation.2, "conversations.agent_id")?,
        title: raw_conversation.3,
        summary: raw_conversation.4,
        pinned: sqlite_bool(raw_conversation.5),
        generation_state: parse_generation_state(&raw_conversation.6)?,
        current_cursor: raw_conversation
            .7
            .map(|value| parse_uuid(&value, "conversations.current_cursor_node_id"))
            .transpose()?,
        created_at: parse_timestamp(&raw_conversation.8, "conversations.created_at")?,
        updated_at: parse_timestamp(&raw_conversation.9, "conversations.updated_at")?,
    };

    let mut node_statement = connection.prepare(
        "SELECT
            id,
            parent_node_id,
            role,
            selected_variant_ordinal,
            created_at,
            updated_at
         FROM message_nodes
         WHERE conversation_id = ?1
         ORDER BY rowid ASC",
    )?;
    let node_rows = node_statement.query_map(params![conversation_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, Option<String>>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, i64>(3)?,
            row.get::<_, String>(4)?,
            row.get::<_, String>(5)?,
        ))
    })?;

    let mut nodes = Vec::new();
    for row in node_rows {
        let (node_id, parent_node_id, role, selected_variant_ordinal, created_at, updated_at) =
            row?;
        let variants = load_variants(connection, &node_id)?;
        nodes.push(NodeBundle {
            node: MessageNode {
                id: parse_uuid(&node_id, "message_nodes.id")?,
                conversation_id: conversation.id,
                parent_node_id: parent_node_id
                    .map(|value| parse_uuid(&value, "message_nodes.parent_node_id"))
                    .transpose()?,
                role: parse_message_role(&role)?,
                select_index: selected_variant_ordinal as usize,
                created_at: parse_timestamp(&created_at, "message_nodes.created_at")?,
                updated_at: parse_timestamp(&updated_at, "message_nodes.updated_at")?,
            },
            variants,
        });
    }

    Ok(Some(StoredConversation {
        conversation,
        nodes,
    }))
}

fn load_agent_config(
    connection: &Connection,
    agent_id: &str,
) -> SqliteStoreResult<Option<AgentConfig>> {
    let raw = connection
        .query_row(
            "SELECT config_json FROM agent_configs WHERE id = ?1",
            params![agent_id],
            |row| row.get::<_, String>(0),
        )
        .optional()?;

    raw.map(|config_json| {
        serde_json::from_str(&config_json).map_err(|error| {
            SqliteStoreError::Decode(format!("agent_configs.config_json: {error}"))
        })
    })
    .transpose()
}

fn load_variants(connection: &Connection, node_id: &str) -> SqliteStoreResult<Vec<VariantBundle>> {
    let mut variant_statement = connection.prepare(
        "SELECT
            id,
            ordinal,
            status,
            model_id,
            usage_json,
            created_at,
            finished_at
         FROM message_variants
         WHERE node_id = ?1
         ORDER BY ordinal ASC",
    )?;
    let variant_rows = variant_statement.query_map(params![node_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<String>>(3)?,
            row.get::<_, Option<String>>(4)?,
            row.get::<_, String>(5)?,
            row.get::<_, Option<String>>(6)?,
        ))
    })?;

    let mut variants = Vec::new();
    for row in variant_rows {
        let (variant_id, _ordinal, status, model_id, usage_json, created_at, finished_at) = row?;
        variants.push(VariantBundle {
            variant: MessageVariant {
                id: parse_uuid(&variant_id, "message_variants.id")?,
                node_id: parse_uuid(node_id, "message_variants.node_id")?,
                status: parse_variant_status(&status)?,
                model_id,
                usage_json,
                created_at: parse_timestamp(&created_at, "message_variants.created_at")?,
                finished_at: finished_at
                    .as_deref()
                    .map(|value| parse_timestamp(value, "message_variants.finished_at"))
                    .transpose()?,
            },
            parts: load_parts(connection, &variant_id)?,
        });
    }

    Ok(variants)
}

fn load_parts(connection: &Connection, variant_id: &str) -> SqliteStoreResult<Vec<MessagePart>> {
    let mut part_statement = connection.prepare(
        "SELECT
            id,
            order_index,
            kind,
            text_value,
            file_name,
            url,
            mime,
            alt_text,
            tool_name,
            tool_call_id,
            tool_state,
            input_json,
            output_json,
            error_message,
            source_text,
            language
         FROM message_parts
         WHERE variant_id = ?1
         ORDER BY order_index ASC",
    )?;
    let part_rows = part_statement.query_map(params![variant_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i32>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<String>>(3)?,
            row.get::<_, Option<String>>(4)?,
            row.get::<_, Option<String>>(5)?,
            row.get::<_, Option<String>>(6)?,
            row.get::<_, Option<String>>(7)?,
            row.get::<_, Option<String>>(8)?,
            row.get::<_, Option<String>>(9)?,
            row.get::<_, Option<String>>(10)?,
            row.get::<_, Option<String>>(11)?,
            row.get::<_, Option<String>>(12)?,
            row.get::<_, Option<String>>(13)?,
            row.get::<_, Option<String>>(14)?,
            row.get::<_, Option<String>>(15)?,
        ))
    })?;

    let mut parts = Vec::new();
    for row in part_rows {
        let (
            part_id,
            order_index,
            kind,
            text_value,
            file_name,
            url,
            mime,
            alt_text,
            tool_name,
            tool_call_id,
            tool_state,
            input_json,
            output_json,
            error_message,
            source_text,
            language,
        ) = row?;

        let payload = match kind.as_str() {
            "text" => MessagePartPayload::Text {
                text: required_value(text_value, "message_parts.text_value")?,
            },
            "reasoning" => MessagePartPayload::Reasoning {
                text: required_value(text_value, "message_parts.text_value")?,
            },
            "tool_call" => MessagePartPayload::ToolCall {
                tool_name: required_value(tool_name, "message_parts.tool_name")?,
                arguments_json: required_value(input_json, "message_parts.input_json")?,
            },
            "tool_result" => MessagePartPayload::ToolResult {
                tool_name: required_value(tool_name, "message_parts.tool_name")?,
                result_json: required_value(output_json, "message_parts.output_json")?,
            },
            "image" => MessagePartPayload::Image {
                url: required_value(url, "message_parts.url")?,
                mime,
                alt: alt_text,
            },
            "document" => MessagePartPayload::Document {
                file_name: required_value(file_name, "message_parts.file_name")?,
                url: required_value(url, "message_parts.url")?,
                mime,
            },
            "tool" => MessagePartPayload::Tool {
                tool_call_id,
                tool_name: required_value(tool_name, "message_parts.tool_name")?,
                state: parse_tool_state(&required_value(tool_state, "message_parts.tool_state")?)?,
                input_json: required_value(input_json, "message_parts.input_json")?,
                output_json,
                error_message,
            },
            "file" => MessagePartPayload::File {
                name: required_value(file_name, "message_parts.file_name")?,
                url: required_value(url, "message_parts.url")?,
                mime,
            },
            "quote" => MessagePartPayload::Quote {
                text: required_value(text_value, "message_parts.text_value")?,
                source: source_text,
            },
            "code_block" => MessagePartPayload::CodeBlock {
                language,
                code: required_value(text_value, "message_parts.text_value")?,
            },
            "markdown_block" => MessagePartPayload::MarkdownBlock {
                markdown: required_value(text_value, "message_parts.text_value")?,
            },
            "error" => MessagePartPayload::Error {
                message: required_value(text_value, "message_parts.text_value")?,
            },
            other => {
                return Err(SqliteStoreError::Decode(format!(
                    "unsupported message_parts.kind `{other}`"
                )));
            }
        };

        parts.push(MessagePart {
            id: parse_uuid(&part_id, "message_parts.id")?,
            variant_id: parse_uuid(variant_id, "message_parts.variant_id")?,
            order_index,
            payload,
        });
    }

    Ok(parts)
}

fn bool_to_sqlite(value: bool) -> i64 {
    if value { 1 } else { 0 }
}

fn sqlite_bool(value: i64) -> bool {
    value != 0
}

fn generation_state_to_str(value: GenerationState) -> &'static str {
    match value {
        GenerationState::Idle => "idle",
        GenerationState::Requesting => "requesting",
        GenerationState::Started => "started",
        GenerationState::Streaming => "streaming",
        GenerationState::Completed => "completed",
        GenerationState::Failed => "failed",
        GenerationState::Cancelled => "cancelled",
    }
}

fn parse_generation_state(value: &str) -> SqliteStoreResult<GenerationState> {
    match value {
        "idle" => Ok(GenerationState::Idle),
        "requesting" => Ok(GenerationState::Requesting),
        "started" => Ok(GenerationState::Started),
        "streaming" => Ok(GenerationState::Streaming),
        "completed" => Ok(GenerationState::Completed),
        "failed" => Ok(GenerationState::Failed),
        "cancelled" => Ok(GenerationState::Cancelled),
        other => Err(SqliteStoreError::Decode(format!(
            "unsupported generation_state `{other}`"
        ))),
    }
}

fn message_role_to_str(value: MessageRole) -> &'static str {
    match value {
        MessageRole::User => "user",
        MessageRole::Assistant => "assistant",
        MessageRole::System => "system",
        MessageRole::Tool => "tool",
    }
}

fn parse_message_role(value: &str) -> SqliteStoreResult<MessageRole> {
    match value {
        "user" => Ok(MessageRole::User),
        "assistant" => Ok(MessageRole::Assistant),
        "system" => Ok(MessageRole::System),
        "tool" => Ok(MessageRole::Tool),
        other => Err(SqliteStoreError::Decode(format!(
            "unsupported message role `{other}`"
        ))),
    }
}

fn variant_status_to_str(value: VariantStatus) -> &'static str {
    match value {
        VariantStatus::Streaming => "streaming",
        VariantStatus::Completed => "completed",
        VariantStatus::Failed => "failed",
        VariantStatus::Cancelled => "cancelled",
    }
}

fn parse_variant_status(value: &str) -> SqliteStoreResult<VariantStatus> {
    match value {
        "streaming" => Ok(VariantStatus::Streaming),
        "completed" => Ok(VariantStatus::Completed),
        "failed" => Ok(VariantStatus::Failed),
        "cancelled" => Ok(VariantStatus::Cancelled),
        other => Err(SqliteStoreError::Decode(format!(
            "unsupported variant status `{other}`"
        ))),
    }
}

fn tool_state_to_str(value: ToolPartState) -> &'static str {
    match value {
        ToolPartState::Pending => "pending",
        ToolPartState::Running => "running",
        ToolPartState::Completed => "completed",
        ToolPartState::Failed => "failed",
    }
}

fn parse_tool_state(value: &str) -> SqliteStoreResult<ToolPartState> {
    match value {
        "pending" => Ok(ToolPartState::Pending),
        "running" => Ok(ToolPartState::Running),
        "completed" => Ok(ToolPartState::Completed),
        "failed" => Ok(ToolPartState::Failed),
        other => Err(SqliteStoreError::Decode(format!(
            "unsupported tool state `{other}`"
        ))),
    }
}

fn parse_uuid<T>(value: &str, field: &str) -> SqliteStoreResult<T>
where
    T: From<Uuid>,
{
    Uuid::parse_str(value).map(T::from).map_err(|error| {
        SqliteStoreError::Decode(format!("{field} invalid uuid `{value}`: {error}"))
    })
}

fn parse_timestamp(value: &str, field: &str) -> SqliteStoreResult<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .map(|timestamp| timestamp.with_timezone(&Utc))
        .map_err(|error| {
            SqliteStoreError::Decode(format!("{field} invalid timestamp `{value}`: {error}"))
        })
}

fn required_value(value: Option<String>, field: &str) -> SqliteStoreResult<String> {
    value.ok_or_else(|| SqliteStoreError::Decode(format!("missing required field `{field}`")))
}

fn short_id(value: &str) -> &str {
    value.get(..8).unwrap_or(value)
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
    use std::{env, fs};
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
            "agent_configs",
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
        assert_eq!(applied.len(), 2);
        assert_eq!(applied[0].version, 1);
        assert_eq!(applied[0].name, "0001_p0_truth_schema");
        assert_eq!(applied[1].version, 2);
        assert_eq!(applied[1].name, "0002_agent_config_store");
    }

    #[test]
    fn migration_entrypoint_is_idempotent() {
        let mut connection = open_memory_db();
        migrate_sqlite_schema(&mut connection).expect("apply first migration");
        migrate_sqlite_schema(&mut connection).expect("apply second migration");

        assert_eq!(
            scalar_i64(&connection, "SELECT COUNT(*) FROM schema_migrations"),
            2
        );
    }

    #[test]
    fn sqlite_store_persists_and_restores_agent_configs() {
        let path =
            env::temp_dir().join(format!("vcpmobile-agent-config-{}.sqlite3", Uuid::new_v4()));
        let store = SqliteStore::new(&path);
        let mut agent = AgentConfig::new("Planner", "You are a focused helper.");
        agent.group.aliases = vec!["planner".to_string()];
        agent
            .prompt
            .placeholders
            .push(vcpmobile_domain::AgentPromptVariable {
                key: "cur_date".to_string(),
                label: Some("Current date".to_string()),
                value: "2026-03-13".to_string(),
                description: Some("Injected runtime date".to_string()),
            });

        store.upsert_agent(&agent).expect("persist agent config");

        let loaded = store
            .get_agent(&agent.id.to_string())
            .expect("get agent config")
            .expect("stored agent config");
        assert_eq!(loaded, agent);

        let listed = store.list_agents().expect("list agent configs");
        assert_eq!(listed, vec![agent]);

        fs::remove_file(path).ok();
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
