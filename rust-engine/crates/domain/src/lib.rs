use std::collections::BTreeSet;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

// Canonical chat truth is a Rust-owned graph:
// `Conversation -> MessageNode -> MessageVariant -> ordered MessagePart`.
//
// The graph shape is the truth source. Android may project it for presentation, but
// must not invent missing branch, selection, or typed-part semantics.
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

/// Rust-owned conversation truth.
///
/// Invariants:
/// - A conversation owns one message graph; every referenced node/variant/part belongs to this
///   conversation through the node lineage.
/// - `current_cursor` is the leaf node of the active branch in Rust truth, or `None` only when the
///   conversation has not materialized any nodes yet.
/// - Active branch identity comes from Rust via the cursor plus each node's selected variant;
///   Android may project that path but must not invent it.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Conversation {
    pub id: ConversationId,
    pub topic_id: TopicId,
    pub agent_id: AgentId,
    pub title: String,
    pub summary: Option<String>,
    pub pinned: bool,
    pub generation_state: GenerationState,
    /// Points to the leaf `MessageNode` on the currently selected branch.
    ///
    /// This is always a node identity, never a variant identity. If set, it must resolve
    /// inside the same conversation.
    pub current_cursor: Option<NodeId>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// Stable structural slot for one logical turn in the conversation graph.
///
/// Invariants:
/// - `conversation_id`, `parent_node_id`, and `role` define the node's position and stay stable
///   once the node is created.
/// - A new turn or a changed parent edge creates a new node ID rather than rewriting an existing
///   node. Editing a persisted user turn therefore branches by creating a new node from the old
///   parent.
/// - `select_index` is the canonical selected-variant pointer for the node and must resolve to an
///   existing variant in full Rust-owned node truth.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MessageNode {
    pub id: NodeId,
    pub conversation_id: ConversationId,
    /// Root nodes use `None`. Non-root nodes must point to another node in the same
    /// conversation, so the active branch can be reconstructed from parent links.
    pub parent_node_id: Option<NodeId>,
    pub role: MessageRole,
    /// Single source of truth for the selected variant on this node.
    ///
    /// App-facing projections may omit unselected variants, but reducers must treat this
    /// index as authoritative whenever multiple variants exist.
    pub select_index: usize,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// One content revision or generation attempt for a node.
///
/// Invariants:
/// - A variant belongs to exactly one node and never changes `node_id`.
/// - Assistant regenerate/retry for the same logical turn appends a new variant and moves
///   selection at the node; previously selected variants remain immutable history.
/// - `Streaming` is the only non-terminal status. Terminal states should record `finished_at`.
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

/// Typed payload atom owned by one variant.
///
/// Invariants:
/// - A part belongs to exactly one variant and is never re-parented.
/// - `order_index` is unique within the variant and reflects append order.
/// - Typed payloads remain Rust truth even if Android later renders a flattened presentation.
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
        let local_id = self.local_id.trim().to_string();
        if local_id.is_empty() || !is_stable_local_id(&local_id, PROVIDER_PRESET_LOCAL_ID_PREFIX) {
            let name_seed = if !local_id.is_empty() {
                local_id
            } else {
                self.name.trim().to_string()
            };
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
        let existing_local_id = self.local_id.trim().to_string();
        let requested_default_preset_id = self
            .default_preset_local_id
            .as_ref()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());

        if existing_local_id.is_empty()
            || !is_stable_local_id(&existing_local_id, PROVIDER_LOCAL_ID_PREFIX)
        {
            self.local_id = if is_stable_local_id(&seed, PROVIDER_LOCAL_ID_PREFIX) {
                seed.clone()
            } else {
                migrated_provider_local_id(&seed)
            };
        }

        self.register_reference_alias(existing_local_id);
        self.register_reference_alias(seed);
        self.register_reference_alias(base_url_alias);

        let mut migrated_default_preset_id = None;
        for (index, preset) in self.presets.iter_mut().enumerate() {
            let previous_local_id = preset.local_id.trim().to_string();
            let previous_name = preset.name.trim().to_string();
            preset.ensure_local_id(&self.local_id, index);

            if migrated_default_preset_id.is_none()
                && requested_default_preset_id
                    .as_ref()
                    .is_some_and(|default_id| {
                        default_id == &preset.local_id
                            || default_id == &previous_local_id
                            || default_id == &previous_name
                    })
            {
                migrated_default_preset_id = Some(preset.local_id.clone());
            }
        }

        self.default_preset_local_id = requested_default_preset_id.and_then(|default_id| {
            self.presets
                .iter()
                .any(|preset| preset.local_id == default_id)
                .then_some(default_id)
                .or(migrated_default_preset_id)
        });
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

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_provider(base_url: &str) -> ProviderConfig {
        ProviderConfig::new(
            ProviderAdapterKind::OpenAiCompatible,
            "Sample Provider",
            base_url,
        )
    }

    #[test]
    fn ensure_stable_ids_keeps_stable_provider_id_across_base_url_edits() {
        let mut provider = sample_provider("https://old.example.com/v1");
        let local_id = provider.local_id.clone();
        let old_base_url = provider.base_url.clone();

        provider.base_url = "https://new.example.com/v1".to_string();
        provider.ensure_stable_ids(&local_id);

        assert_eq!(provider.local_id, local_id);
        assert!(provider.matches_reference(&provider.local_id));
        assert!(provider.matches_reference(&old_base_url));
        assert!(provider.matches_reference("https://new.example.com/v1"));
    }

    #[test]
    fn ensure_stable_ids_remaps_default_preset_reference_from_legacy_name() {
        let mut provider = sample_provider("https://preset-name.example.com/v1");
        let provider_local_id = provider.local_id.clone();

        let mut balanced = ProviderPreset::new("balanced");
        balanced.local_id.clear();
        let mut fast = ProviderPreset::new("fast");
        fast.local_id.clear();

        provider.presets = vec![balanced, fast];
        provider.default_preset_local_id = Some("balanced".to_string());

        provider.ensure_stable_ids(&provider_local_id);

        assert_eq!(
            provider.default_preset_local_id,
            Some(provider.presets[0].local_id.clone())
        );
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
    }

    #[test]
    fn ensure_stable_ids_remaps_default_preset_reference_from_nonstable_preset_id() {
        let mut provider = sample_provider("https://preset-id.example.com/v1");
        let provider_local_id = provider.local_id.clone();

        let mut balanced = ProviderPreset::new("balanced");
        balanced.local_id = "balanced-v1".to_string();
        let mut fast = ProviderPreset::new("fast");
        fast.local_id = "fast-v1".to_string();

        provider.presets = vec![balanced, fast];
        provider.default_preset_local_id = Some("balanced-v1".to_string());

        provider.ensure_stable_ids(&provider_local_id);

        assert_eq!(
            provider.default_preset_local_id,
            Some(provider.presets[0].local_id.clone())
        );
        assert_ne!(provider.presets[0].local_id, "balanced-v1");
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
    }
}
