use std::collections::BTreeSet;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

pub type AgentId = Uuid;
pub type TopicId = Uuid;
pub type ConversationId = Uuid;
pub type NodeId = Uuid;
pub type VariantId = Uuid;
pub type PartId = Uuid;
pub type ProviderLocalId = String;
pub type ProviderPresetLocalId = String;

pub const PROVIDER_LOCAL_ID_PREFIX: &str = "provider_local_";
pub const PROVIDER_PRESET_LOCAL_ID_PREFIX: &str = "provider_preset_local_";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MessageRole {
    User,
    Assistant,
    System,
    Tool,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VariantStatus {
    Streaming,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum GenerationState {
    Idle,
    Streaming,
    WaitingTool,
    Failed,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ProviderAdapterKind {
    #[serde(rename = "openai_compatible")]
    OpenAiCompatible,
    #[serde(rename = "google_compatible")]
    GoogleCompatible,
    #[serde(rename = "anthropic_compatible")]
    AnthropicCompatible,
    #[serde(rename = "vcptoolbox")]
    VcpToolBox,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentProfile {
    pub id: AgentId,
    pub name: String,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Topic {
    pub id: TopicId,
    pub agent_id: AgentId,
    pub title: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Conversation {
    pub id: ConversationId,
    pub topic_id: TopicId,
    pub agent_id: AgentId,
    pub title: String,
    pub summary: Option<String>,
    pub pinned: bool,
    pub generation_state: GenerationState,
    pub current_cursor: Option<NodeId>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MessageNode {
    pub id: NodeId,
    pub conversation_id: ConversationId,
    pub parent_node_id: Option<NodeId>,
    pub role: MessageRole,
    pub select_index: usize,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MessageVariant {
    pub id: VariantId,
    pub node_id: NodeId,
    pub status: VariantStatus,
    pub model_id: Option<String>,
    pub usage_json: Option<String>,
    pub created_at: DateTime<Utc>,
    pub finished_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum MessagePartPayload {
    Text {
        text: String,
    },
    Reasoning {
        text: String,
    },
    ToolCall {
        tool_name: String,
        arguments_json: String,
    },
    ToolResult {
        tool_name: String,
        result_json: String,
    },
    Image {
        url: String,
        alt: Option<String>,
    },
    File {
        name: String,
        url: String,
        mime: Option<String>,
    },
    Quote {
        text: String,
        source: Option<String>,
    },
    CodeBlock {
        language: Option<String>,
        code: String,
    },
    MarkdownBlock {
        markdown: String,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MessagePart {
    pub id: PartId,
    pub variant_id: VariantId,
    pub order_index: i32,
    pub payload: MessagePartPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DraftState {
    pub conversation_id: ConversationId,
    pub text: String,
    pub attachment_ids: Vec<String>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderBodyFragment {
    pub pointer: String,
    pub value: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderModelCatalogEntry {
    pub model_id: String,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderModelCatalog {
    #[serde(default)]
    pub default_model: Option<String>,
    #[serde(default)]
    pub entries: Vec<ProviderModelCatalogEntry>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ProviderAuthConfig {
    #[default]
    None,
    BearerToken {
        token: String,
    },
    ApiKey {
        header_name: String,
        value: String,
    },
    Basic {
        username: String,
        password: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderPreset {
    #[serde(default)]
    pub local_id: ProviderPresetLocalId,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub model_id: Option<String>,
    #[serde(default)]
    pub headers: Vec<ProviderHeader>,
    #[serde(default)]
    pub body_fragments: Vec<ProviderBodyFragment>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderConfig {
    #[serde(default)]
    pub local_id: ProviderLocalId,
    pub adapter_kind: ProviderAdapterKind,
    #[serde(alias = "name")]
    pub display_name: String,
    #[serde(default)]
    pub avatar_uri: Option<String>,
    pub base_url: String,
    #[serde(default)]
    pub auth: ProviderAuthConfig,
    #[serde(default)]
    pub model_catalog: ProviderModelCatalog,
    #[serde(default)]
    pub custom_headers: Vec<ProviderHeader>,
    #[serde(default)]
    pub custom_body_fragments: Vec<ProviderBodyFragment>,
    #[serde(default)]
    pub presets: Vec<ProviderPreset>,
    #[serde(default)]
    pub default_preset_local_id: Option<ProviderPresetLocalId>,
    #[serde(default, alias = "legacy_keys")]
    pub reference_aliases: Vec<String>,
    #[serde(default = "current_timestamp")]
    pub created_at: DateTime<Utc>,
    #[serde(default = "current_timestamp")]
    pub updated_at: DateTime<Utc>,
}

impl ProviderPreset {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            local_id: new_provider_preset_local_id(),
            name: name.into(),
            description: None,
            model_id: None,
            headers: Vec::new(),
            body_fragments: Vec::new(),
        }
    }

    pub fn ensure_local_id(&mut self, provider_local_id: &str, ordinal: usize) {
        if self.local_id.trim().is_empty() {
            let name_seed = self.name.trim();
            let seed = if name_seed.is_empty() {
                format!("{provider_local_id}:{ordinal}")
            } else {
                format!("{provider_local_id}:{name_seed}:{ordinal}")
            };
            self.local_id = migrated_provider_preset_local_id(&seed);
        }
    }
}

impl ProviderConfig {
    pub fn new(
        adapter_kind: ProviderAdapterKind,
        display_name: impl Into<String>,
        base_url: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        let base_url = base_url.into();
        let mut provider = Self {
            local_id: new_provider_local_id(),
            adapter_kind,
            display_name: display_name.into(),
            avatar_uri: None,
            base_url,
            auth: ProviderAuthConfig::default(),
            model_catalog: ProviderModelCatalog::default(),
            custom_headers: Vec::new(),
            custom_body_fragments: Vec::new(),
            presets: Vec::new(),
            default_preset_local_id: None,
            reference_aliases: Vec::new(),
            created_at: now,
            updated_at: now,
        };
        let base_url_alias = provider.base_url.clone();
        provider.register_reference_alias(base_url_alias);
        provider
    }

    pub fn ensure_stable_ids(&mut self, reference_seed: &str) {
        let seed = if reference_seed.trim().is_empty() {
            self.base_url.trim().to_string()
        } else {
            reference_seed.trim().to_string()
        };
        let base_url_alias = self.base_url.trim().to_string();

        if self.local_id.trim().is_empty() {
            self.local_id = if is_stable_local_id(&seed, PROVIDER_LOCAL_ID_PREFIX) {
                seed.clone()
            } else {
                migrated_provider_local_id(&seed)
            };
        }

        self.register_reference_alias(seed);
        self.register_reference_alias(base_url_alias);

        for (index, preset) in self.presets.iter_mut().enumerate() {
            preset.ensure_local_id(&self.local_id, index);
        }

        if self
            .default_preset_local_id
            .as_ref()
            .is_some_and(|default_id| {
                !self
                    .presets
                    .iter()
                    .any(|preset| preset.local_id == *default_id)
            })
        {
            self.default_preset_local_id = None;
        }
    }

    pub fn matches_reference(&self, reference: &str) -> bool {
        let reference = reference.trim();
        !reference.is_empty()
            && (self.local_id == reference
                || self
                    .reference_aliases
                    .iter()
                    .any(|alias| alias.as_str() == reference))
    }

    pub fn register_reference_alias(&mut self, alias: impl AsRef<str>) {
        let alias = alias.as_ref().trim();
        if alias.is_empty() || alias == self.local_id {
            return;
        }

        let mut aliases = self
            .reference_aliases
            .iter()
            .cloned()
            .collect::<BTreeSet<_>>();
        aliases.insert(alias.to_string());
        self.reference_aliases = aliases.into_iter().collect();
    }
}

pub fn new_provider_local_id() -> ProviderLocalId {
    prefixed_local_id(PROVIDER_LOCAL_ID_PREFIX, Uuid::new_v4())
}

pub fn new_provider_preset_local_id() -> ProviderPresetLocalId {
    prefixed_local_id(PROVIDER_PRESET_LOCAL_ID_PREFIX, Uuid::new_v4())
}

pub fn migrated_provider_local_id(seed: &str) -> ProviderLocalId {
    seeded_local_id(PROVIDER_LOCAL_ID_PREFIX, &format!("provider:{seed}"))
}

pub fn migrated_provider_preset_local_id(seed: &str) -> ProviderPresetLocalId {
    seeded_local_id(
        PROVIDER_PRESET_LOCAL_ID_PREFIX,
        &format!("provider_preset:{seed}"),
    )
}

fn prefixed_local_id(prefix: &str, id: Uuid) -> String {
    format!("{prefix}{}", id.simple())
}

fn seeded_local_id(prefix: &str, seed: &str) -> String {
    format!("{prefix}{}", stable_seed_hex(seed))
}

fn current_timestamp() -> DateTime<Utc> {
    Utc::now()
}

fn default_enabled() -> bool {
    true
}

fn is_stable_local_id(value: &str, prefix: &str) -> bool {
    value.starts_with(prefix) && value.len() > prefix.len()
}

fn stable_seed_hex(seed: &str) -> String {
    format!(
        "{:016x}{:016x}",
        fnv1a_64(seed.as_bytes(), 0xcbf29ce484222325),
        fnv1a_64(seed.as_bytes(), 0x9ae16a3b2f90404f)
    )
}

fn fnv1a_64(bytes: &[u8], offset_basis: u64) -> u64 {
    const FNV_PRIME: u64 = 0x100000001b3;

    let mut hash = offset_basis;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}
