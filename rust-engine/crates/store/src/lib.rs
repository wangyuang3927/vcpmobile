mod sqlite;

use std::{
    collections::{BTreeMap, BTreeSet, btree_map::Entry},
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

use serde::{Deserialize, Serialize, Serializer, de::Deserializer};
use thiserror::Error;
use vcpmobile_domain::{
    AgentConfig, Conversation, ConversationId, GenerationState, MessageNode, NodeId,
    ProviderAuthConfig, ProviderConfig, ProviderModelCatalog, VariantId,
};
use vcpmobile_protocol::{NodeBundle, PairingDevicePlatform, VariantBundle};

pub use sqlite::{
    CURRENT_SCHEMA_VERSION, MigrationRecord, SqliteStore, SqliteStoreError, SqliteStoreResult,
    migrate_sqlite_schema,
};
#[derive(Debug, Clone)]
pub struct StoredConversation {
    pub conversation: Conversation,
    pub nodes: Vec<NodeBundle>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredConversationRecord {
    conversation: Conversation,
    nodes: Vec<StoredNodeBundleRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredNodeBundleRecord {
    node: MessageNode,
    variants: Vec<VariantBundle>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    selected_variant_id: Option<VariantId>,
}

impl Serialize for StoredConversation {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        StoredConversationRecord::from(self).serialize(serializer)
    }
}

impl<'de> Deserialize<'de> for StoredConversation {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        StoredConversationRecord::deserialize(deserializer).map(StoredConversation::from)
    }
}

impl From<&StoredConversation> for StoredConversationRecord {
    fn from(value: &StoredConversation) -> Self {
        Self {
            conversation: value.conversation.clone(),
            nodes: value
                .nodes
                .iter()
                .map(StoredNodeBundleRecord::from)
                .collect(),
        }
    }
}

impl From<StoredConversationRecord> for StoredConversation {
    fn from(value: StoredConversationRecord) -> Self {
        Self {
            conversation: value.conversation,
            nodes: value.nodes.into_iter().map(NodeBundle::from).collect(),
        }
    }
}

impl From<&NodeBundle> for StoredNodeBundleRecord {
    fn from(value: &NodeBundle) -> Self {
        Self {
            node: value.node.clone(),
            variants: value.variants.clone(),
            selected_variant_id: value
                .variants
                .get(value.node.select_index)
                .map(|variant| variant.variant.id),
        }
    }
}

impl From<StoredNodeBundleRecord> for NodeBundle {
    fn from(value: StoredNodeBundleRecord) -> Self {
        let mut node = value.node;
        if let Some(selected_variant_id) = value.selected_variant_id {
            node.select_index = value
                .variants
                .iter()
                .position(|variant| variant.variant.id == selected_variant_id)
                .unwrap_or(value.variants.len());
        }
        NodeBundle {
            node,
            variants: value.variants,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredConversationCatalogItem {
    pub conversation_id: ConversationId,
    pub title: String,
    pub summary: Option<String>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
    pub generation_state: GenerationState,
    pub pinned: bool,
    pub current_cursor: Option<NodeId>,
    pub is_recoverable: bool,
    pub resume_anchor: Option<StoredConversationResumeAnchor>,
    pub node_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredConversationResumeAnchor {
    pub message_id: String,
    pub node_id: NodeId,
    pub variant_id: VariantId,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StoredPairingSessionStatus {
    Pending,
    Paired,
    Expired,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredTrustedDevice {
    pub trusted_device_id: String,
    pub namespace: String,
    pub device_name: String,
    pub device_platform: PairingDevicePlatform,
    pub device_public_key: String,
    pub paired_at: chrono::DateTime<chrono::Utc>,
    pub last_seen_at: chrono::DateTime<chrono::Utc>,
    pub revoked_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredPairingSession {
    pub pairing_session_id: String,
    pub namespace: String,
    pub bootstrap_token: String,
    pub expires_at: chrono::DateTime<chrono::Utc>,
    pub status: StoredPairingSessionStatus,
    pub trusted_device_id: Option<String>,
    pub completed_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredMobileSession {
    pub access_token: String,
    pub pairing_session_id: String,
    pub namespace: String,
    pub trusted_device_id: String,
    pub issued_at: chrono::DateTime<chrono::Utc>,
    pub expires_at: chrono::DateTime<chrono::Utc>,
    pub revoked_at: Option<chrono::DateTime<chrono::Utc>>,
    pub resume_anchor: String,
    pub resume_anchor_expires_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Default, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StoredPairingState {
    #[serde(default)]
    pub pairing_sessions: BTreeMap<String, StoredPairingSession>,
    #[serde(default)]
    pub trusted_devices: BTreeMap<String, StoredTrustedDevice>,
    #[serde(default)]
    pub mobile_sessions: BTreeMap<String, StoredMobileSession>,
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct StoreData {
    #[serde(default)]
    pub conversations: BTreeMap<String, StoredConversation>,
    #[serde(default, alias = "agents")]
    pub agent_configs: BTreeMap<String, AgentConfig>,
    #[serde(default, alias = "providers")]
    pub provider_configs: BTreeMap<String, ProviderConfig>,
    #[serde(default)]
    pub pairing_sessions: BTreeMap<String, StoredPairingSession>,
    #[serde(default)]
    pub trusted_devices: BTreeMap<String, StoredTrustedDevice>,
    #[serde(default)]
    pub mobile_sessions: BTreeMap<String, StoredMobileSession>,
}

#[derive(Debug, Clone)]
struct NormalizedProviderRecord {
    provider: ProviderConfig,
    origin: ProviderNormalizationOrigin,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct ProviderNormalizationOrigin {
    canonical_key: bool,
    canonical_embedded_id: bool,
}

impl NormalizedProviderRecord {
    fn from_raw_key(key: &str, original_local_id: &str, provider: ProviderConfig) -> Self {
        Self {
            origin: ProviderNormalizationOrigin::from_raw_key(
                key,
                original_local_id,
                &provider.local_id,
            ),
            provider,
        }
    }

    fn merge(mut self, other: Self) -> Self {
        let keep_self = self.origin > other.origin
            || (self.origin == other.origin
                && self.provider.updated_at >= other.provider.updated_at);

        if keep_self {
            merge_duplicate_provider_metadata(&mut self.provider, other.provider);
            self
        } else {
            let mut other = other;
            merge_duplicate_provider_metadata(&mut other.provider, self.provider);
            other
        }
    }
}

impl ProviderNormalizationOrigin {
    fn from_raw_key(key: &str, original_local_id: &str, normalized_local_id: &str) -> Self {
        let key = key.trim();
        let original_local_id = original_local_id.trim();
        let normalized_local_id = normalized_local_id.trim();

        Self {
            canonical_key: key == normalized_local_id,
            canonical_embedded_id: original_local_id == normalized_local_id,
        }
    }
}

fn merge_duplicate_provider_metadata(primary: &mut ProviderConfig, duplicate: ProviderConfig) {
    let duplicate_is_newer = duplicate.updated_at > primary.updated_at;
    let should_replace_headers = !duplicate.custom_headers.is_empty()
        && (duplicate_is_newer || primary.custom_headers.is_empty());
    let should_replace_body_fragments = !duplicate.custom_body_fragments.is_empty()
        && (duplicate_is_newer || primary.custom_body_fragments.is_empty());
    let should_replace_presets =
        !duplicate.presets.is_empty() && (duplicate_is_newer || primary.presets.is_empty());
    let should_replace_model_catalog =
        provider_model_catalog_is_meaningful(&duplicate.model_catalog)
            && (duplicate_is_newer
                || !provider_model_catalog_is_meaningful(&primary.model_catalog));
    let should_replace_auth = duplicate.auth != ProviderAuthConfig::None
        && (duplicate_is_newer || primary.auth == ProviderAuthConfig::None);

    let primary_base_url = primary.base_url.clone();
    primary.register_reference_alias(primary_base_url);
    primary.created_at = primary.created_at.min(duplicate.created_at);
    primary.updated_at = primary.updated_at.max(duplicate.updated_at);

    if duplicate_is_newer {
        if !duplicate.display_name.trim().is_empty() {
            primary.display_name = duplicate.display_name.clone();
        }
        if !duplicate.base_url.trim().is_empty() {
            primary.base_url = duplicate.base_url.clone();
        }
        primary.adapter_kind = duplicate.adapter_kind;
    } else {
        if primary.display_name.trim().is_empty() {
            primary.display_name = duplicate.display_name.clone();
        }
        if primary.base_url.trim().is_empty() {
            primary.base_url = duplicate.base_url.clone();
        }
    }

    if duplicate.avatar_uri.is_some() || primary.avatar_uri.is_none() {
        primary.avatar_uri = duplicate.avatar_uri.clone().or(primary.avatar_uri.clone());
    }
    if should_replace_auth {
        primary.auth = duplicate.auth.clone();
    }
    if should_replace_model_catalog {
        primary.model_catalog = duplicate.model_catalog.clone();
    }
    if should_replace_headers {
        primary.custom_headers = duplicate.custom_headers.clone();
    }
    if should_replace_body_fragments {
        primary.custom_body_fragments = duplicate.custom_body_fragments.clone();
    }
    if should_replace_presets {
        primary.presets = duplicate.presets.clone();
    }
    if duplicate.default_preset_local_id.is_some()
        && (duplicate_is_newer || primary.default_preset_local_id.is_none())
    {
        primary.default_preset_local_id = duplicate.default_preset_local_id.clone();
    }

    primary.register_reference_alias(duplicate.base_url.as_str());
    for alias in duplicate.reference_aliases {
        primary.register_reference_alias(alias);
    }
}

fn provider_model_catalog_is_meaningful(catalog: &ProviderModelCatalog) -> bool {
    catalog.default_model.is_some() || !catalog.entries.is_empty()
}

fn resolve_provider_for_upsert(
    provider_configs: &BTreeMap<String, ProviderConfig>,
    provider: &ProviderConfig,
) -> Option<ProviderConfig> {
    let mut references = BTreeSet::new();
    for reference in std::iter::once(provider.local_id.as_str())
        .chain(std::iter::once(provider.base_url.as_str()))
        .chain(provider.reference_aliases.iter().map(String::as_str))
    {
        let trimmed = reference.trim();
        if trimmed.is_empty() {
            continue;
        }
        references.insert(trimmed.to_string());
    }

    for reference in references {
        if let Some(existing) = provider_configs.get(&reference).cloned() {
            return Some(existing);
        }
        if let Some(existing) = provider_configs
            .values()
            .find(|candidate| candidate.matches_reference(&reference))
            .cloned()
        {
            return Some(existing);
        }
    }

    None
}

impl StoreData {
    fn normalize_provider_configs(&mut self) {
        let mut normalized = BTreeMap::new();

        for (key, mut provider) in std::mem::take(&mut self.provider_configs) {
            let original_local_id = provider.local_id.clone();
            // Legacy stores may key providers by endpoint; normalize them back to stable local IDs.
            provider.ensure_stable_ids(&key);
            let local_id = provider.local_id.clone();
            let incoming =
                NormalizedProviderRecord::from_raw_key(&key, &original_local_id, provider);
            match normalized.entry(local_id) {
                Entry::Vacant(slot) => {
                    slot.insert(incoming);
                }
                Entry::Occupied(mut slot) => {
                    let merged = slot.get().clone().merge(incoming);
                    slot.insert(merged);
                }
            }
        }

        self.provider_configs = normalized
            .into_iter()
            .map(|(local_id, record)| (local_id, record.provider))
            .collect();
    }
}

#[derive(Debug, Clone)]
pub struct FileStore {
    sqlite_path: PathBuf,
    legacy_path: PathBuf,
    bootstrap_report: Arc<Mutex<Option<ConversationBootstrapReport>>>,
}

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("sqlite error: {0}")]
    Sqlite(#[from] SqliteStoreError),
    #[error("validation error: {0}")]
    Validation(String),
}

pub type StoreResult<T> = Result<T, StoreError>;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ConversationBootstrapReport {
    pub legacy_conversation_count: usize,
    pub imported_conversation_count: usize,
    pub already_present_conversation_count: usize,
}

impl FileStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        let requested_path = path.into();
        let extension = requested_path
            .extension()
            .and_then(|value| value.to_str())
            .map(|value| value.to_ascii_lowercase());

        let (sqlite_path, legacy_path) = match extension.as_deref() {
            Some("json") => (requested_path.with_extension("sqlite3"), requested_path),
            Some("sqlite") | Some("sqlite3") | Some("db") => (
                requested_path.clone(),
                requested_path.with_extension("json"),
            ),
            _ => (
                requested_path.with_extension("sqlite3"),
                requested_path.with_extension("json"),
            ),
        };

        Self {
            sqlite_path,
            legacy_path,
            bootstrap_report: Arc::new(Mutex::new(None)),
        }
    }

    pub fn path(&self) -> &Path {
        &self.sqlite_path
    }

    pub fn load(&self) -> StoreResult<StoreData> {
        if !self.legacy_path.exists() {
            return Ok(StoreData::default());
        }
        let raw = fs::read_to_string(&self.legacy_path)?;
        if raw.trim().is_empty() {
            return Ok(StoreData::default());
        }

        let mut data: StoreData = serde_json::from_str(&raw)?;
        data.normalize_provider_configs();
        Ok(data)
    }

    pub fn save(&self, data: &StoreData) -> StoreResult<()> {
        if let Some(parent) = self.legacy_path.parent() {
            fs::create_dir_all(parent)?;
        }

        let mut canonical = data.clone();
        canonical.normalize_provider_configs();

        let raw = serde_json::to_string_pretty(&canonical)?;
        fs::write(&self.legacy_path, raw)?;
        *self
            .bootstrap_report
            .lock()
            .expect("lock bootstrap report cache") = None;
        Ok(())
    }

    pub fn get_conversation(
        &self,
        conversation_id: ConversationId,
    ) -> StoreResult<Option<StoredConversation>> {
        self.ensure_sqlite_conversations()?;
        Ok(self.sqlite_store().get_conversation(conversation_id)?)
    }

    pub fn upsert_conversation(&self, record: StoredConversation) -> StoreResult<()> {
        self.ensure_sqlite_conversations()?;
        self.sqlite_store().replace_conversation(&record)?;
        Ok(())
    }

    pub fn read_selected_variant(
        &self,
        conversation_id: ConversationId,
        node_id: NodeId,
    ) -> StoreResult<Option<VariantId>> {
        self.ensure_sqlite_conversations()?;
        Ok(self
            .sqlite_store()
            .read_selected_variant(conversation_id, node_id)?)
    }

    pub fn write_selected_variant(
        &self,
        conversation_id: ConversationId,
        node_id: NodeId,
        variant_id: VariantId,
    ) -> StoreResult<bool> {
        self.ensure_sqlite_conversations()?;
        Ok(self
            .sqlite_store()
            .write_selected_variant(conversation_id, node_id, variant_id)?)
    }

    pub fn list_conversations(&self) -> StoreResult<Vec<StoredConversation>> {
        self.ensure_sqlite_conversations()?;
        Ok(self.sqlite_store().list_conversations()?)
    }

    pub fn list_conversation_catalog(&self) -> StoreResult<Vec<StoredConversationCatalogItem>> {
        let mut items = self
            .list_conversations()?
            .into_iter()
            .map(|stored| {
                let is_recoverable = cursor_resolves_to_leaf(&stored);
                let resume_anchor = recovery_resume_anchor(&stored);
                let node_count = stored.nodes.len();
                let conversation = stored.conversation;

                StoredConversationCatalogItem {
                    conversation_id: conversation.id,
                    title: conversation.title,
                    summary: conversation.summary,
                    updated_at: conversation.updated_at,
                    generation_state: conversation.generation_state,
                    pinned: conversation.pinned,
                    current_cursor: conversation.current_cursor,
                    is_recoverable,
                    resume_anchor,
                    node_count,
                }
            })
            .collect::<Vec<_>>();

        items.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        Ok(items)
    }

    pub fn get_provider(&self, local_id: &str) -> StoreResult<Option<ProviderConfig>> {
        let data = self.load()?;
        Ok(data.provider_configs.get(local_id).cloned())
    }

    pub fn get_agent(&self, agent_id: &str) -> StoreResult<Option<AgentConfig>> {
        self.ensure_sqlite_agents()?;
        Ok(self.sqlite_store().get_agent(agent_id)?)
    }

    pub fn upsert_agent(&self, mut agent: AgentConfig) -> StoreResult<AgentConfig> {
        agent
            .validate()
            .map_err(|error| StoreError::Validation(error.to_string()))?;

        self.ensure_sqlite_agents()?;
        let existing = self.sqlite_store().get_agent(&agent.id.to_string())?;
        let now = chrono::Utc::now();
        if let Some(existing) = existing {
            agent.created_at = existing.created_at;
        }
        agent.updated_at = now;

        self.sqlite_store().upsert_agent(&agent)?;
        Ok(agent)
    }

    pub fn list_agents(&self) -> StoreResult<Vec<AgentConfig>> {
        self.ensure_sqlite_agents()?;
        Ok(self.sqlite_store().list_agents()?)
    }

    pub fn delete_agent(&self, agent_id: &str) -> StoreResult<Option<AgentConfig>> {
        self.ensure_sqlite_agents()?;
        Ok(self.sqlite_store().delete_agent(agent_id)?)
    }

    pub fn resolve_provider_reference(
        &self,
        reference: &str,
    ) -> StoreResult<Option<ProviderConfig>> {
        let data = self.load()?;
        if let Some(provider) = data.provider_configs.get(reference).cloned() {
            return Ok(Some(provider));
        }

        Ok(data
            .provider_configs
            .into_values()
            .find(|provider| provider.matches_reference(reference)))
    }

    pub fn upsert_provider(&self, mut provider: ProviderConfig) -> StoreResult<ProviderConfig> {
        let mut data = self.load()?;
        let existing_provider = resolve_provider_for_upsert(&data.provider_configs, &provider);
        let reference_seed = existing_provider
            .as_ref()
            .map(|existing| existing.local_id.clone())
            .filter(|local_id| !local_id.trim().is_empty())
            .unwrap_or_else(|| {
                if provider.local_id.trim().is_empty() {
                    provider.base_url.clone()
                } else {
                    provider.local_id.clone()
                }
            });
        provider.ensure_stable_ids(&reference_seed);

        let now = chrono::Utc::now();
        if let Some(existing) =
            existing_provider.or_else(|| data.provider_configs.get(&provider.local_id).cloned())
        {
            provider.created_at = existing.created_at;
            // Preserve prior endpoint references so chat history survives later endpoint edits.
            provider.register_reference_alias(existing.base_url.as_str());
            for alias in existing.reference_aliases {
                provider.register_reference_alias(alias);
            }
        }
        provider.updated_at = now;

        data.provider_configs
            .insert(provider.local_id.clone(), provider.clone());
        self.save(&data)?;
        Ok(provider)
    }

    pub fn list_providers(&self) -> StoreResult<Vec<ProviderConfig>> {
        let mut providers = self
            .load()?
            .provider_configs
            .into_values()
            .collect::<Vec<_>>();
        providers.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        Ok(providers)
    }

    pub fn delete_provider(&self, local_id: &str) -> StoreResult<Option<ProviderConfig>> {
        let mut data = self.load()?;
        let removed = data.provider_configs.remove(local_id);
        self.save(&data)?;
        Ok(removed)
    }

    pub fn bootstrap_conversations_from_legacy(&self) -> StoreResult<ConversationBootstrapReport> {
        if let Some(report) = self
            .bootstrap_report
            .lock()
            .expect("lock bootstrap report cache")
            .clone()
        {
            return Ok(report);
        }

        let report = self.bootstrap_conversations_from_legacy_uncached()?;
        *self
            .bootstrap_report
            .lock()
            .expect("lock bootstrap report cache") = Some(report.clone());
        Ok(report)
    }

    pub fn load_pairing_state(&self) -> StoreResult<StoredPairingState> {
        let data = self.load()?;
        Ok(StoredPairingState {
            pairing_sessions: data.pairing_sessions,
            trusted_devices: data.trusted_devices,
            mobile_sessions: data.mobile_sessions,
        })
    }

    pub fn save_pairing_state(&self, state: &StoredPairingState) -> StoreResult<()> {
        let mut data = self.load()?;
        data.pairing_sessions = state.pairing_sessions.clone();
        data.trusted_devices = state.trusted_devices.clone();
        data.mobile_sessions = state.mobile_sessions.clone();
        self.save(&data)
    }

    fn sqlite_store(&self) -> SqliteStore {
        SqliteStore::new(&self.sqlite_path)
    }

    fn ensure_sqlite_conversations(&self) -> StoreResult<()> {
        self.bootstrap_conversations_from_legacy()?;
        Ok(())
    }

    fn bootstrap_conversations_from_legacy_uncached(
        &self,
    ) -> StoreResult<ConversationBootstrapReport> {
        if !self.legacy_path.exists() {
            return Ok(ConversationBootstrapReport::default());
        }

        let data = self.load()?;
        let mut report = ConversationBootstrapReport {
            legacy_conversation_count: data.conversations.len(),
            imported_conversation_count: 0,
            already_present_conversation_count: 0,
        };

        if data.conversations.is_empty() {
            return Ok(report);
        }

        let sqlite_store = self.sqlite_store();
        for stored in data.conversations.into_values() {
            let conversation_id = stored.conversation.id;
            if sqlite_store.get_conversation(conversation_id)?.is_some() {
                report.already_present_conversation_count += 1;
                continue;
            }

            sqlite_store.replace_conversation(&stored)?;
            report.imported_conversation_count += 1;
        }

        Ok(report)
    }

    fn ensure_sqlite_agents(&self) -> StoreResult<()> {
        let sqlite_store = self.sqlite_store();
        if sqlite_store.has_agent_configs()? || !self.legacy_path.exists() {
            return Ok(());
        }

        let data = self.load()?;
        if data.agent_configs.is_empty() {
            return Ok(());
        }

        sqlite_store.import_agents(data.agent_configs.into_values())?;
        Ok(())
    }
}

fn cursor_resolves_to_leaf(stored: &StoredConversation) -> bool {
    let Some(mut current_cursor) = stored.conversation.current_cursor else {
        return false;
    };
    let node_index = stored
        .nodes
        .iter()
        .map(|bundle| (bundle.node.id, bundle))
        .collect::<BTreeMap<_, _>>();
    let Some(cursor_node) = node_index.get(&current_cursor) else {
        return false;
    };
    if cursor_node.node.conversation_id != stored.conversation.id {
        return false;
    }
    if stored.nodes.iter().any(|bundle| {
        bundle.node.conversation_id == stored.conversation.id
            && bundle.node.parent_node_id == Some(current_cursor)
    }) {
        return false;
    }

    let mut visited = BTreeSet::new();
    loop {
        if !visited.insert(current_cursor) {
            return false;
        }
        let Some(bundle) = node_index.get(&current_cursor) else {
            return false;
        };
        if bundle.node.conversation_id != stored.conversation.id {
            return false;
        }
        if bundle.variants.get(bundle.node.select_index).is_none() {
            return false;
        }
        current_cursor = match bundle.node.parent_node_id {
            Some(parent_node_id) => parent_node_id,
            None => break,
        };
    }

    true
}

fn recovery_resume_anchor(stored: &StoredConversation) -> Option<StoredConversationResumeAnchor> {
    if !stored.conversation.generation_state.can_resume() || !cursor_resolves_to_leaf(stored) {
        return None;
    }

    let current_cursor = stored.conversation.current_cursor?;
    let cursor_bundle = stored
        .nodes
        .iter()
        .find(|bundle| bundle.node.id == current_cursor)?;
    if cursor_bundle.node.conversation_id != stored.conversation.id {
        return None;
    }
    if cursor_bundle.node.role != vcpmobile_domain::MessageRole::Assistant {
        return None;
    }

    let selected_variant = cursor_bundle
        .variants
        .get(cursor_bundle.node.select_index)?;
    Some(StoredConversationResumeAnchor {
        message_id: format!("{}:{}", current_cursor, selected_variant.variant.id),
        node_id: current_cursor,
        variant_id: selected_variant.variant.id,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{collections::BTreeMap, env, fs};
    use uuid::Uuid;
    use vcpmobile_domain::{
        AgentConfig, AgentId, MessageNode, MessagePart, MessagePartPayload, MessageRole,
        MessageVariant, PROVIDER_LOCAL_ID_PREFIX, PROVIDER_PRESET_LOCAL_ID_PREFIX,
        ProviderAdapterKind, ProviderBodyFragment, ProviderModelCatalogEntry, ProviderPreset,
        TopicId, VariantStatus,
    };
    use vcpmobile_protocol::VariantBundle;

    fn temp_store_path(name: &str) -> PathBuf {
        env::temp_dir().join(format!("vcpmobile-store-{name}-{}.json", Uuid::new_v4()))
    }

    fn cleanup_store_paths(path: &Path) {
        fs::remove_file(path).ok();
        fs::remove_file(path.with_extension("sqlite3")).ok();
    }

    fn sample_provider(display_name: &str, base_url: &str) -> ProviderConfig {
        let mut provider = ProviderConfig::new(
            ProviderAdapterKind::OpenAiCompatible,
            display_name,
            base_url,
        );
        provider.model_catalog.default_model = Some("gpt-4.1-mini".to_string());
        provider
            .model_catalog
            .entries
            .push(ProviderModelCatalogEntry {
                model_id: "gpt-4.1-mini".to_string(),
                display_name: Some("GPT-4.1 mini".to_string()),
                enabled: true,
            });
        provider.custom_body_fragments.push(ProviderBodyFragment {
            pointer: "/temperature".to_string(),
            value: serde_json::json!(0.2),
        });
        provider.presets.push(ProviderPreset::new("balanced"));
        provider.default_preset_local_id = provider
            .presets
            .first()
            .map(|preset| preset.local_id.clone());
        provider
    }

    fn sample_agent(name: &str) -> AgentConfig {
        let mut agent = AgentConfig::new(name, "You are a focused helper.");
        agent.group.aliases = vec!["planner".to_string()];
        agent
    }

    fn sample_variant_switch_conversation(
        now: chrono::DateTime<chrono::Utc>,
    ) -> (StoredConversation, ConversationId, NodeId, Uuid, Uuid) {
        let conversation_id = ConversationId::new_v4();
        let user_node_id = NodeId::new_v4();
        let assistant_node_id = NodeId::new_v4();
        let first_variant_id = Uuid::new_v4();
        let second_variant_id = Uuid::new_v4();

        let stored = StoredConversation {
            conversation: Conversation {
                id: conversation_id,
                topic_id: TopicId::new_v4(),
                agent_id: AgentId::new_v4(),
                title: "variant persistence".to_string(),
                summary: Some("selected variant survives restart".to_string()),
                pinned: false,
                generation_state: GenerationState::Completed,
                current_cursor: Some(assistant_node_id),
                created_at: now,
                updated_at: now,
            },
            nodes: vec![
                NodeBundle {
                    node: MessageNode {
                        id: user_node_id,
                        conversation_id,
                        parent_node_id: None,
                        role: MessageRole::User,
                        select_index: 0,
                        created_at: now,
                        updated_at: now,
                    },
                    variants: vec![VariantBundle {
                        variant: MessageVariant {
                            id: Uuid::new_v4(),
                            node_id: user_node_id,
                            status: VariantStatus::Completed,
                            model_id: None,
                            usage_json: None,
                            created_at: now,
                            finished_at: Some(now),
                        },
                        parts: vec![],
                    }],
                },
                NodeBundle {
                    node: MessageNode {
                        id: assistant_node_id,
                        conversation_id,
                        parent_node_id: Some(user_node_id),
                        role: MessageRole::Assistant,
                        select_index: 0,
                        created_at: now,
                        updated_at: now,
                    },
                    variants: vec![
                        VariantBundle {
                            variant: MessageVariant {
                                id: first_variant_id,
                                node_id: assistant_node_id,
                                status: VariantStatus::Completed,
                                model_id: Some("rust-session-engine".to_string()),
                                usage_json: None,
                                created_at: now,
                                finished_at: Some(now),
                            },
                            parts: vec![],
                        },
                        VariantBundle {
                            variant: MessageVariant {
                                id: second_variant_id,
                                node_id: assistant_node_id,
                                status: VariantStatus::Completed,
                                model_id: Some("rust-session-engine".to_string()),
                                usage_json: None,
                                created_at: now,
                                finished_at: Some(now),
                            },
                            parts: vec![],
                        },
                    ],
                },
            ],
        };

        (
            stored,
            conversation_id,
            assistant_node_id,
            first_variant_id,
            second_variant_id,
        )
    }

    fn sample_text_conversation(
        title: &str,
        text: &str,
        now: chrono::DateTime<chrono::Utc>,
    ) -> (StoredConversation, ConversationId) {
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        (
            StoredConversation {
                conversation: Conversation {
                    id: conversation_id,
                    topic_id: TopicId::new_v4(),
                    agent_id: AgentId::new_v4(),
                    title: title.to_string(),
                    summary: Some(text.to_string()),
                    pinned: false,
                    generation_state: GenerationState::Completed,
                    current_cursor: Some(node_id),
                    created_at: now,
                    updated_at: now,
                },
                nodes: vec![NodeBundle {
                    node: MessageNode {
                        id: node_id,
                        conversation_id,
                        parent_node_id: None,
                        role: MessageRole::Assistant,
                        select_index: 0,
                        created_at: now,
                        updated_at: now,
                    },
                    variants: vec![VariantBundle {
                        variant: MessageVariant {
                            id: variant_id,
                            node_id,
                            status: VariantStatus::Completed,
                            model_id: Some("rust-session-engine".to_string()),
                            usage_json: None,
                            created_at: now,
                            finished_at: Some(now),
                        },
                        parts: vec![MessagePart {
                            id: Uuid::new_v4(),
                            variant_id,
                            order_index: 0,
                            payload: MessagePartPayload::Text {
                                text: text.to_string(),
                            },
                        }],
                    }],
                }],
            },
            conversation_id,
        )
    }

    #[test]
    fn list_conversation_catalog_sorts_by_updated_at_desc() {
        let source = fs::read_to_string(
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data/verify-store.json"),
        )
        .expect("read fixture store");
        let path = temp_store_path("catalog");
        fs::write(&path, source).expect("write temp store");

        let store = FileStore::new(&path);
        let items = store
            .list_conversation_catalog()
            .expect("load catalog projection");

        assert!(!items.is_empty());
        assert!(
            items
                .windows(2)
                .all(|pair| pair[0].updated_at >= pair[1].updated_at),
            "catalog should be sorted by updated_at desc"
        );
        assert!(
            items
                .iter()
                .all(|item| !item.conversation_id.to_string().is_empty() && !item.title.is_empty())
        );
        assert!(items.iter().all(|item| item.node_count > 0));

        fs::remove_file(path).ok();
    }

    #[test]
    fn upsert_agent_persists_and_lists_local_agent_configs() {
        let path = temp_store_path("agents");
        let store = FileStore::new(&path);

        let agent = store
            .upsert_agent(sample_agent("Planner"))
            .expect("upsert agent");
        let listed = store.list_agents().expect("list agents");

        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, agent.id);
        assert_eq!(
            store
                .get_agent(&agent.id.to_string())
                .expect("get agent")
                .expect("stored agent")
                .identity
                .name,
            "Planner"
        );
        assert!(
            !path.exists(),
            "agent CRUD should no longer depend on the legacy json store"
        );
        assert!(
            path.with_extension("sqlite3").exists(),
            "agent CRUD should persist into sqlite"
        );

        cleanup_store_paths(&path);
    }

    #[test]
    fn upsert_agent_rejects_invalid_required_fields() {
        let path = temp_store_path("agents-invalid");
        let store = FileStore::new(&path);
        let mut agent = sample_agent("Planner");
        agent.prompt.system_prompt = " ".to_string();

        let error = store.upsert_agent(agent).expect_err("validation failure");
        assert!(matches!(error, StoreError::Validation(_)));

        cleanup_store_paths(&path);
    }

    #[test]
    fn list_agents_imports_legacy_agent_configs_into_sqlite() {
        let path = temp_store_path("agents-legacy-import");
        let agent = sample_agent("Legacy planner");
        let legacy = StoreData {
            conversations: BTreeMap::new(),
            agent_configs: BTreeMap::from([(agent.id.to_string(), agent.clone())]),
            provider_configs: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy agents"),
        )
        .expect("write legacy agents");

        let store = FileStore::new(&path);
        let listed = store.list_agents().expect("import agents from legacy json");

        assert_eq!(listed, vec![agent]);
        assert_eq!(
            store
                .get_agent(&listed[0].id.to_string())
                .expect("get imported agent")
                .expect("stored imported agent")
                .identity
                .name,
            "Legacy planner"
        );
        assert!(path.with_extension("sqlite3").exists());

        cleanup_store_paths(&path);
    }

    #[test]
    fn catalog_rejects_legacy_conversation_with_missing_cursor_during_sqlite_import() {
        let path = temp_store_path("missing-cursor");
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        let legacy = StoreData {
            conversations: BTreeMap::from([(
                conversation_id.to_string(),
                StoredConversation {
                    conversation: Conversation {
                        id: conversation_id,
                        topic_id: TopicId::new_v4(),
                        agent_id: AgentId::new_v4(),
                        title: "broken".to_string(),
                        summary: None,
                        pinned: false,
                        generation_state: GenerationState::Idle,
                        current_cursor: Some(NodeId::new_v4()),
                        created_at: now,
                        updated_at: now,
                    },
                    nodes: vec![NodeBundle {
                        node: MessageNode {
                            id: node_id,
                            conversation_id,
                            parent_node_id: None,
                            role: MessageRole::Assistant,
                            select_index: 0,
                            created_at: now,
                            updated_at: now,
                        },
                        variants: vec![VariantBundle {
                            variant: MessageVariant {
                                id: variant_id,
                                node_id,
                                status: VariantStatus::Completed,
                                model_id: None,
                                usage_json: None,
                                created_at: now,
                                finished_at: Some(now),
                            },
                            parts: vec![],
                        }],
                    }],
                },
            )]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
            pairing_sessions: BTreeMap::new(),
            trusted_devices: BTreeMap::new(),
            mobile_sessions: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy fixture"),
        )
        .expect("write legacy fixture");

        let store = FileStore::new(&path);
        let error = store
            .list_conversation_catalog()
            .expect_err("broken legacy cursor should fail sqlite import");
        assert!(matches!(error, StoreError::Sqlite(_)));

        cleanup_store_paths(&path);
    }

    #[test]
    fn catalog_rejects_legacy_conversation_with_missing_branch_ancestor_during_sqlite_import() {
        let path = temp_store_path("missing-ancestor");
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        let legacy = StoreData {
            conversations: BTreeMap::from([(
                conversation_id.to_string(),
                StoredConversation {
                    conversation: Conversation {
                        id: conversation_id,
                        topic_id: TopicId::new_v4(),
                        agent_id: AgentId::new_v4(),
                        title: "broken ancestry".to_string(),
                        summary: None,
                        pinned: false,
                        generation_state: GenerationState::Idle,
                        current_cursor: Some(node_id),
                        created_at: now,
                        updated_at: now,
                    },
                    nodes: vec![NodeBundle {
                        node: MessageNode {
                            id: node_id,
                            conversation_id,
                            parent_node_id: Some(NodeId::new_v4()),
                            role: MessageRole::Assistant,
                            select_index: 0,
                            created_at: now,
                            updated_at: now,
                        },
                        variants: vec![VariantBundle {
                            variant: MessageVariant {
                                id: variant_id,
                                node_id,
                                status: VariantStatus::Completed,
                                model_id: None,
                                usage_json: None,
                                created_at: now,
                                finished_at: Some(now),
                            },
                            parts: vec![],
                        }],
                    }],
                },
            )]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
            pairing_sessions: BTreeMap::new(),
            trusted_devices: BTreeMap::new(),
            mobile_sessions: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy fixture"),
        )
        .expect("write legacy fixture");

        let store = FileStore::new(&path);
        let error = store
            .list_conversation_catalog()
            .expect_err("broken branch ancestry should fail sqlite import");
        assert!(matches!(error, StoreError::Sqlite(_)));

        cleanup_store_paths(&path);
    }

    #[test]
    fn catalog_rejects_legacy_conversation_with_invalid_selected_variant_index_during_sqlite_import()
     {
        let path = temp_store_path("invalid-select-index");
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        let legacy = StoreData {
            conversations: BTreeMap::from([(
                conversation_id.to_string(),
                StoredConversation {
                    conversation: Conversation {
                        id: conversation_id,
                        topic_id: TopicId::new_v4(),
                        agent_id: AgentId::new_v4(),
                        title: "broken select index".to_string(),
                        summary: None,
                        pinned: false,
                        generation_state: GenerationState::Idle,
                        current_cursor: Some(node_id),
                        created_at: now,
                        updated_at: now,
                    },
                    nodes: vec![NodeBundle {
                        node: MessageNode {
                            id: node_id,
                            conversation_id,
                            parent_node_id: None,
                            role: MessageRole::Assistant,
                            select_index: 1,
                            created_at: now,
                            updated_at: now,
                        },
                        variants: vec![VariantBundle {
                            variant: MessageVariant {
                                id: variant_id,
                                node_id,
                                status: VariantStatus::Completed,
                                model_id: None,
                                usage_json: None,
                                created_at: now,
                                finished_at: Some(now),
                            },
                            parts: vec![],
                        }],
                    }],
                },
            )]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
            pairing_sessions: BTreeMap::new(),
            trusted_devices: BTreeMap::new(),
            mobile_sessions: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy fixture"),
        )
        .expect("write legacy fixture");

        let store = FileStore::new(&path);
        let error = store
            .list_conversation_catalog()
            .expect_err("invalid selected variant should fail sqlite import");
        assert!(matches!(error, StoreError::Sqlite(_)));

        cleanup_store_paths(&path);
    }

    #[test]
    fn legacy_conversation_reads_migrate_to_sqlite_and_survive_json_removal() {
        let path = temp_store_path("legacy-conversation-import");
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();
        let part_id = Uuid::new_v4();
        let legacy = StoreData {
            conversations: BTreeMap::from([(
                conversation_id.to_string(),
                StoredConversation {
                    conversation: Conversation {
                        id: conversation_id,
                        topic_id: TopicId::new_v4(),
                        agent_id: AgentId::new_v4(),
                        title: "legacy resume".to_string(),
                        summary: Some("migrate me".to_string()),
                        pinned: false,
                        generation_state: GenerationState::Started,
                        current_cursor: Some(node_id),
                        created_at: now,
                        updated_at: now,
                    },
                    nodes: vec![NodeBundle {
                        node: MessageNode {
                            id: node_id,
                            conversation_id,
                            parent_node_id: None,
                            role: MessageRole::Assistant,
                            select_index: 0,
                            created_at: now,
                            updated_at: now,
                        },
                        variants: vec![VariantBundle {
                            variant: MessageVariant {
                                id: variant_id,
                                node_id,
                                status: VariantStatus::Streaming,
                                model_id: Some("gpt-4.1-mini".to_string()),
                                usage_json: None,
                                created_at: now,
                                finished_at: None,
                            },
                            parts: vec![MessagePart {
                                id: part_id,
                                variant_id,
                                order_index: 0,
                                payload: MessagePartPayload::Text {
                                    text: "legacy payload".to_string(),
                                },
                            }],
                        }],
                    }],
                },
            )]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
            pairing_sessions: BTreeMap::new(),
            trusted_devices: BTreeMap::new(),
            mobile_sessions: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy store"),
        )
        .expect("write legacy store");

        let store = FileStore::new(&path);
        let loaded = store
            .get_conversation(conversation_id)
            .expect("load migrated conversation")
            .expect("conversation exists");
        assert_eq!(loaded.conversation.id, conversation_id);
        assert_eq!(loaded.conversation.current_cursor, Some(node_id));
        assert_eq!(loaded.nodes.len(), 1);
        assert_eq!(loaded.nodes[0].variants[0].parts.len(), 1);
        assert!(store.path().exists(), "sqlite truth should be materialized");

        fs::remove_file(&path).ok();

        let catalog = store
            .list_conversation_catalog()
            .expect("load catalog from sqlite after json removal");
        assert_eq!(catalog.len(), 1);
        assert!(catalog[0].is_recoverable);
        assert_eq!(catalog[0].conversation_id, conversation_id);
        assert_eq!(
            catalog[0].resume_anchor,
            Some(StoredConversationResumeAnchor {
                message_id: format!("{node_id}:{variant_id}"),
                node_id,
                variant_id,
            })
        );

        let reloaded = store
            .get_conversation(conversation_id)
            .expect("reload migrated conversation")
            .expect("conversation still exists");
        assert_eq!(reloaded.nodes[0].variants[0].variant.id, variant_id);
        assert_eq!(
            reloaded.nodes[0].variants[0].parts[0].order_index, 0,
            "sqlite-backed recovery should preserve part ordering"
        );

        cleanup_store_paths(&path);
    }

    #[test]
    fn explicit_bootstrap_imports_legacy_json_when_store_path_is_sqlite3() {
        let legacy_path = temp_store_path("explicit-bootstrap");
        let sqlite_path = legacy_path.with_extension("sqlite3");
        let now = chrono::Utc::now();
        let (legacy_conversation, conversation_id) =
            sample_text_conversation("legacy bootstrap", "migrate me", now);

        let legacy = StoreData {
            conversations: BTreeMap::from([(
                conversation_id.to_string(),
                legacy_conversation.clone(),
            )]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
        };
        fs::write(
            &legacy_path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy store"),
        )
        .expect("write legacy store");

        let store = FileStore::new(&sqlite_path);
        let report = store
            .bootstrap_conversations_from_legacy()
            .expect("bootstrap legacy conversations");

        assert_eq!(
            report,
            ConversationBootstrapReport {
                legacy_conversation_count: 1,
                imported_conversation_count: 1,
                already_present_conversation_count: 0,
            }
        );
        assert!(store.path().exists(), "sqlite truth should be materialized");
        assert_eq!(
            store
                .get_conversation(conversation_id)
                .expect("load bootstrapped conversation")
                .expect("bootstrapped conversation")
                .conversation
                .title,
            "legacy bootstrap"
        );

        cleanup_store_paths(&legacy_path);
    }

    #[test]
    fn upsert_conversation_bootstraps_legacy_json_before_first_sqlite_write() {
        let path = temp_store_path("upsert-bootstrap");
        let now = chrono::Utc::now();
        let (legacy_conversation, legacy_conversation_id) =
            sample_text_conversation("legacy thread", "legacy payload", now);
        let (new_conversation, new_conversation_id) =
            sample_text_conversation("fresh thread", "fresh payload", now);
        let legacy = StoreData {
            conversations: BTreeMap::from([(
                legacy_conversation_id.to_string(),
                legacy_conversation,
            )]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy store"),
        )
        .expect("write legacy store");

        let store = FileStore::new(&path);
        store
            .upsert_conversation(new_conversation)
            .expect("persist new sqlite conversation");

        fs::remove_file(&path).ok();

        let mut titles = store
            .list_conversations()
            .expect("list sqlite conversations after bootstrap")
            .into_iter()
            .map(|stored| stored.conversation.title)
            .collect::<Vec<_>>();
        titles.sort();

        assert_eq!(
            titles,
            vec!["fresh thread".to_string(), "legacy thread".to_string()]
        );
        assert!(
            store
                .get_conversation(legacy_conversation_id)
                .expect("load legacy conversation from sqlite")
                .is_some()
        );
        assert!(
            store
                .get_conversation(new_conversation_id)
                .expect("load new conversation from sqlite")
                .is_some()
        );

        cleanup_store_paths(&path);
    }

    #[test]
    fn bootstrap_imports_missing_legacy_conversations_into_nonempty_sqlite_store() {
        let path = temp_store_path("mixed-bootstrap");
        let now = chrono::Utc::now();
        let (already_bootstrapped, already_bootstrapped_id) =
            sample_text_conversation("already sqlite", "kept in sqlite", now);
        let (legacy_only, legacy_only_id) =
            sample_text_conversation("legacy only", "imported from json", now);
        let legacy = StoreData {
            conversations: BTreeMap::from([
                (
                    already_bootstrapped_id.to_string(),
                    already_bootstrapped.clone(),
                ),
                (legacy_only_id.to_string(), legacy_only),
            ]),
            agent_configs: BTreeMap::new(),
            provider_configs: BTreeMap::new(),
        };
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy store"),
        )
        .expect("write legacy store");

        let store = FileStore::new(&path);
        store
            .sqlite_store()
            .replace_conversation(&already_bootstrapped)
            .expect("seed sqlite with one conversation");

        let report = store
            .bootstrap_conversations_from_legacy()
            .expect("bootstrap missing legacy conversations");
        assert_eq!(
            report,
            ConversationBootstrapReport {
                legacy_conversation_count: 2,
                imported_conversation_count: 1,
                already_present_conversation_count: 1,
            }
        );

        fs::remove_file(&path).ok();

        let conversations = store
            .list_conversations()
            .expect("list reconciled conversations after json removal");
        assert_eq!(conversations.len(), 2);
        assert!(
            store
                .get_conversation(already_bootstrapped_id)
                .expect("load already bootstrapped conversation")
                .is_some()
        );
        assert!(
            store
                .get_conversation(legacy_only_id)
                .expect("load imported legacy conversation")
                .is_some()
        );

        cleanup_store_paths(&path);
    }

    #[test]
    fn completed_catalog_entry_does_not_expose_resume_anchor() {
        let path = temp_store_path("completed-resume-anchor");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let (stored, conversation_id, ..) = sample_variant_switch_conversation(now);

        store
            .upsert_conversation(stored)
            .expect("persist completed conversation");

        let catalog = store
            .list_conversation_catalog()
            .expect("load catalog projection");
        let item = catalog
            .iter()
            .find(|item| item.conversation_id == conversation_id)
            .expect("catalog entry");
        assert!(item.resume_anchor.is_none());

        cleanup_store_paths(&path);
    }

    #[test]
    fn save_persists_selected_variant_id_as_store_truth() {
        let path = temp_store_path("selected-variant-id");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let (mut stored, conversation_id, assistant_node_id, _first_variant_id, second_variant_id) =
            sample_variant_switch_conversation(now);
        stored.nodes[1].node.select_index = 1;

        store
            .upsert_conversation(stored)
            .expect("persist selected variant");

        assert_eq!(
            store
                .read_selected_variant(conversation_id, assistant_node_id)
                .expect("read selected variant"),
            Some(second_variant_id)
        );

        cleanup_store_paths(&path);
    }

    #[test]
    fn write_selected_variant_survives_reload() {
        let path = temp_store_path("selected-variant-reload");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let (stored, conversation_id, assistant_node_id, first_variant_id, second_variant_id) =
            sample_variant_switch_conversation(now);

        store
            .upsert_conversation(stored)
            .expect("persist conversation");
        assert_eq!(
            store
                .read_selected_variant(conversation_id, assistant_node_id)
                .expect("read initial selected variant"),
            Some(first_variant_id)
        );
        assert!(
            store
                .write_selected_variant(conversation_id, assistant_node_id, second_variant_id)
                .expect("switch selected variant")
        );

        let reloaded = FileStore::new(&path);
        let stored = reloaded
            .get_conversation(conversation_id)
            .expect("load reloaded conversation")
            .expect("stored conversation");

        assert_eq!(
            stored.nodes[1].node.select_index, 1,
            "selected variant should be restored from persisted variant id"
        );
        assert_eq!(
            reloaded
                .read_selected_variant(conversation_id, assistant_node_id)
                .expect("read selected variant after reload"),
            Some(second_variant_id)
        );

        cleanup_store_paths(&path);
    }

    #[test]
    fn provider_upsert_keeps_stable_local_id_when_base_url_changes() {
        let path = temp_store_path("provider-stable-id");
        let store = FileStore::new(&path);

        let initial = store
            .upsert_provider(sample_provider("OpenAI", "https://old.example.com/v1"))
            .expect("persist initial provider");
        let local_id = initial.local_id.clone();
        let old_base_url = initial.base_url.clone();

        let mut edited = initial.clone();
        edited.base_url = "https://new.example.com/v1".to_string();
        let edited = store
            .upsert_provider(edited)
            .expect("persist edited provider");

        assert_eq!(edited.local_id, local_id);
        assert_eq!(
            store
                .resolve_provider_reference(&old_base_url)
                .expect("resolve old base url")
                .expect("provider via old base url")
                .local_id,
            local_id
        );
        assert_eq!(
            store
                .resolve_provider_reference("https://new.example.com/v1")
                .expect("resolve new base url")
                .expect("provider via new base url")
                .local_id,
            local_id
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_migrates_legacy_provider_keys_into_local_ids() {
        let legacy = serde_json::json!({
            "provider_configs": {
                "https://legacy.example.com/v1": {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Legacy API",
                    "base_url": "https://legacy.example.com/v1",
                    "model_catalog": {
                        "default_model": "gpt-4.1-mini",
                        "entries": [
                            {
                                "model_id": "gpt-4.1-mini"
                            }
                        ]
                    },
                    "presets": [
                        {
                            "name": "fast"
                        }
                    ],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-legacy");
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy fixture"),
        )
        .expect("write legacy fixture");

        let store = FileStore::new(&path);
        let providers = store.list_providers().expect("list providers");

        assert_eq!(providers.len(), 1);
        let provider = providers.first().expect("migrated provider");
        assert!(provider.local_id.starts_with(PROVIDER_LOCAL_ID_PREFIX));
        assert_ne!(provider.local_id, "https://legacy.example.com/v1");
        assert_eq!(
            provider.reference_aliases,
            vec!["https://legacy.example.com/v1".to_string()]
        );
        assert_eq!(provider.presets.len(), 1);
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
        assert_eq!(
            store
                .resolve_provider_reference("https://legacy.example.com/v1")
                .expect("resolve legacy reference")
                .expect("legacy provider")
                .local_id,
            provider.local_id
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_preserves_existing_local_id_keys_when_embedded_field_is_missing() {
        let existing_local_id = "provider_local_existing123";
        let fixture = serde_json::json!({
            "provider_configs": {
                existing_local_id: {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Existing Stable Provider",
                    "base_url": "https://stable.example.com/v1",
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-existing-local-id");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let provider = store
            .get_provider(existing_local_id)
            .expect("load provider")
            .expect("provider exists");

        assert_eq!(provider.local_id, existing_local_id);
        assert_eq!(
            store
                .resolve_provider_reference(existing_local_id)
                .expect("resolve by existing local id")
                .expect("provider by local id")
                .local_id,
            existing_local_id
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_prefers_stable_map_key_when_embedded_provider_id_is_legacy() {
        let existing_local_id = "provider_local_existing123";
        let fixture = serde_json::json!({
            "provider_configs": {
                existing_local_id: {
                    "local_id": "https://old-embedded.example.com/v1",
                    "adapter_kind": "openai_compatible",
                    "display_name": "Mixed Legacy Provider",
                    "base_url": "https://stable.example.com/v1",
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-mixed-legacy-id");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let provider = store
            .get_provider(existing_local_id)
            .expect("load provider")
            .expect("provider exists");

        assert_eq!(provider.local_id, existing_local_id);
        assert!(
            provider
                .reference_aliases
                .contains(&"https://old-embedded.example.com/v1".to_string())
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_assigns_distinct_preset_ids_for_duplicate_legacy_names() {
        let fixture = serde_json::json!({
            "provider_configs": {
                "https://duplicate.example.com/v1": {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Duplicate Presets",
                    "base_url": "https://duplicate.example.com/v1",
                    "presets": [
                        { "name": "balanced" },
                        { "name": "balanced" }
                    ],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-duplicate-presets");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let providers = store.list_providers().expect("list providers");
        let provider = providers.first().expect("provider exists");

        assert_eq!(provider.presets.len(), 2);
        assert_ne!(provider.presets[0].local_id, provider.presets[1].local_id);

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_normalizes_nonstable_preset_ids() {
        let fixture = serde_json::json!({
            "provider_configs": {
                "https://preset-id.example.com/v1": {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Preset IDs",
                    "base_url": "https://preset-id.example.com/v1",
                    "presets": [
                        { "local_id": "balanced", "name": "balanced" },
                        { "local_id": "balanced", "name": "balanced" }
                    ],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-nonstable-preset-ids");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let provider = store
            .list_providers()
            .expect("list providers")
            .into_iter()
            .next()
            .expect("provider exists");

        assert_eq!(provider.presets.len(), 2);
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
        assert!(
            provider.presets[1]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
        assert_ne!(provider.presets[0].local_id, provider.presets[1].local_id);

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_migrates_legacy_default_preset_reference() {
        let fixture = serde_json::json!({
            "provider_configs": {
                "https://default-preset.example.com/v1": {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Legacy Default Preset",
                    "base_url": "https://default-preset.example.com/v1",
                    "default_preset_local_id": "balanced",
                    "presets": [
                        { "name": "balanced" },
                        { "name": "fast" }
                    ],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-default-preset");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let provider = store
            .list_providers()
            .expect("list providers")
            .into_iter()
            .next()
            .expect("provider exists");

        assert_eq!(
            provider.default_preset_local_id,
            Some(provider.presets[0].local_id.clone())
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_migrates_legacy_default_preset_reference_from_nonstable_preset_id() {
        let fixture = serde_json::json!({
            "provider_configs": {
                "https://default-preset-id.example.com/v1": {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Legacy Default Preset ID",
                    "base_url": "https://default-preset-id.example.com/v1",
                    "default_preset_local_id": "balanced-v1",
                    "presets": [
                        { "local_id": "balanced-v1", "name": "balanced" },
                        { "local_id": "fast-v1", "name": "fast" }
                    ],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-default-preset-id");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let provider = store
            .list_providers()
            .expect("list providers")
            .into_iter()
            .next()
            .expect("provider exists");

        assert_eq!(
            provider.default_preset_local_id,
            Some(provider.presets[0].local_id.clone())
        );
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn save_rewrites_legacy_provider_records_into_canonical_stable_ids() {
        let legacy = serde_json::json!({
            "provider_configs": {
                "https://legacy-save.example.com/v1": {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Legacy Save Provider",
                    "base_url": "https://legacy-save.example.com/v1",
                    "default_preset_local_id": "balanced-v1",
                    "presets": [
                        { "local_id": "balanced-v1", "name": "balanced" }
                    ],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-canonical-save");
        fs::write(
            &path,
            serde_json::to_string_pretty(&legacy).expect("serialize legacy fixture"),
        )
        .expect("write legacy fixture");

        let store = FileStore::new(&path);
        let data = store.load().expect("load legacy fixture");
        store.save(&data).expect("save canonical store");

        let saved_raw = fs::read_to_string(&path).expect("read canonical store");
        let saved_json: serde_json::Value =
            serde_json::from_str(&saved_raw).expect("parse canonical store");
        let providers = saved_json["provider_configs"]
            .as_object()
            .expect("provider_configs object");

        assert_eq!(providers.len(), 1);
        let (stored_key, stored_provider) = providers.iter().next().expect("stored provider");
        assert!(stored_key.starts_with(PROVIDER_LOCAL_ID_PREFIX));
        assert_eq!(
            stored_provider["local_id"].as_str(),
            Some(stored_key.as_str())
        );
        assert_eq!(
            stored_provider["reference_aliases"],
            serde_json::json!(["https://legacy-save.example.com/v1"])
        );

        let default_preset_local_id = stored_provider["default_preset_local_id"]
            .as_str()
            .expect("default preset local id");
        assert!(default_preset_local_id.starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX));
        assert_eq!(
            stored_provider["presets"][0]["local_id"].as_str(),
            Some(default_preset_local_id)
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn load_merges_newer_duplicate_provider_fields_when_legacy_duplicate_collides() {
        let canonical_local_id = "provider_local_canonical123";
        let fixture = serde_json::json!({
            "provider_configs": {
                canonical_local_id: {
                    "adapter_kind": "openai_compatible",
                    "display_name": "Canonical Provider",
                    "base_url": "https://current.example.com/v1",
                    "presets": [
                        {
                            "local_id": "provider_preset_local_balanced123",
                            "name": "balanced"
                        }
                    ],
                    "default_preset_local_id": "provider_preset_local_balanced123",
                    "reference_aliases": ["https://older.example.com/v1"],
                    "created_at": "2026-03-11T00:00:00Z",
                    "updated_at": "2026-03-11T00:00:00Z"
                },
                "https://legacy.example.com/v1": {
                    "local_id": canonical_local_id,
                    "adapter_kind": "openai_compatible",
                    "display_name": "Merged Provider",
                    "base_url": "https://newer.example.com/v1",
                    "auth": {
                        "type": "bearer_token",
                        "token": "secret"
                    },
                    "model_catalog": {
                        "default_model": "gpt-4.1",
                        "entries": [
                            {
                                "model_id": "gpt-4.1"
                            }
                        ]
                    },
                    "reference_aliases": ["imported-provider-id"],
                    "created_at": "2026-03-10T00:00:00Z",
                    "updated_at": "2026-03-12T00:00:00Z"
                }
            }
        });
        let path = temp_store_path("provider-duplicate-collision");
        fs::write(
            &path,
            serde_json::to_string_pretty(&fixture).expect("serialize fixture"),
        )
        .expect("write fixture");

        let store = FileStore::new(&path);
        let provider = store
            .get_provider(canonical_local_id)
            .expect("load provider")
            .expect("provider exists");

        assert_eq!(provider.display_name, "Merged Provider");
        assert_eq!(provider.base_url, "https://newer.example.com/v1");
        assert_eq!(
            provider.auth,
            ProviderAuthConfig::BearerToken {
                token: "secret".to_string()
            }
        );
        assert_eq!(
            provider.model_catalog.default_model.as_deref(),
            Some("gpt-4.1")
        );
        assert_eq!(
            provider.default_preset_local_id,
            Some("provider_preset_local_balanced123".to_string())
        );
        assert_eq!(provider.presets.len(), 1);
        assert_eq!(
            provider.created_at.to_rfc3339(),
            "2026-03-10T00:00:00+00:00"
        );
        assert_eq!(
            provider.updated_at.to_rfc3339(),
            "2026-03-12T00:00:00+00:00"
        );
        assert!(
            provider
                .reference_aliases
                .contains(&"https://current.example.com/v1".to_string())
        );
        assert!(
            provider
                .reference_aliases
                .contains(&"https://older.example.com/v1".to_string())
        );
        assert!(
            provider
                .reference_aliases
                .contains(&"https://legacy.example.com/v1".to_string())
        );
        assert!(
            provider
                .reference_aliases
                .contains(&"imported-provider-id".to_string())
        );
        assert_eq!(
            store
                .resolve_provider_reference("https://legacy.example.com/v1")
                .expect("resolve legacy endpoint")
                .expect("provider via legacy endpoint")
                .local_id,
            canonical_local_id
        );
        assert_eq!(
            store
                .resolve_provider_reference("imported-provider-id")
                .expect("resolve imported alias")
                .expect("provider via imported alias")
                .local_id,
            canonical_local_id
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn provider_upsert_via_alias_updates_existing_provider_instead_of_forking() {
        let path = temp_store_path("provider-alias-upsert");
        let store = FileStore::new(&path);

        let initial = store
            .upsert_provider(sample_provider("OpenAI", "https://old.example.com/v1"))
            .expect("persist initial provider");
        let original_local_id = initial.local_id.clone();
        let old_base_url = initial.base_url.clone();

        let mut edited = initial.clone();
        edited.local_id = old_base_url.clone();
        edited.display_name = "OpenAI Renamed".to_string();
        edited.base_url = "https://new.example.com/v1".to_string();

        let saved = store
            .upsert_provider(edited)
            .expect("persist alias-based edit");

        assert_eq!(saved.local_id, original_local_id);
        assert_eq!(saved.display_name, "OpenAI Renamed");
        assert_eq!(saved.base_url, "https://new.example.com/v1");
        assert_eq!(store.list_providers().expect("list providers").len(), 1);
        assert_eq!(
            store
                .resolve_provider_reference(&old_base_url)
                .expect("resolve old alias")
                .expect("provider via old alias")
                .local_id,
            original_local_id
        );
        assert_eq!(
            store
                .resolve_provider_reference("https://new.example.com/v1")
                .expect("resolve new base url")
                .expect("provider via new base url")
                .local_id,
            original_local_id
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn provider_crud_round_trip_works_by_local_id() {
        let path = temp_store_path("provider-crud");
        let store = FileStore::new(&path);

        let saved = store
            .upsert_provider(sample_provider(
                "Anthropic",
                "https://anthropic.example.com",
            ))
            .expect("save provider");
        let local_id = saved.local_id.clone();

        let loaded = store
            .get_provider(&local_id)
            .expect("load provider")
            .expect("provider exists");
        assert_eq!(loaded.local_id, local_id);

        let listed = store.list_providers().expect("list providers");
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].local_id, local_id);

        let removed = store
            .delete_provider(&local_id)
            .expect("delete provider")
            .expect("removed provider");
        assert_eq!(removed.local_id, local_id);
        assert!(
            store
                .get_provider(&local_id)
                .expect("load deleted provider")
                .is_none()
        );

        fs::remove_file(path).ok();
    }

    #[test]
    fn pairing_state_round_trips_through_store_sidecar() {
        let path = temp_store_path("pairing-state");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let trusted_device = StoredTrustedDevice {
            trusted_device_id: "trusted-device-1".to_string(),
            namespace: "workspace-alpha".to_string(),
            device_name: "Pixel 9".to_string(),
            device_platform: PairingDevicePlatform::Android,
            device_public_key: "base64-public-key".to_string(),
            paired_at: now,
            last_seen_at: now,
            revoked_at: None,
        };
        let pairing_state = StoredPairingState {
            pairing_sessions: BTreeMap::from([(
                "pairing-session-1".to_string(),
                StoredPairingSession {
                    pairing_session_id: "pairing-session-1".to_string(),
                    namespace: "workspace-alpha".to_string(),
                    bootstrap_token: "bootstrap-secret".to_string(),
                    expires_at: now,
                    status: StoredPairingSessionStatus::Paired,
                    trusted_device_id: Some(trusted_device.trusted_device_id.clone()),
                    completed_at: Some(now),
                },
            )]),
            trusted_devices: BTreeMap::from([(
                trusted_device.trusted_device_id.clone(),
                trusted_device.clone(),
            )]),
            mobile_sessions: BTreeMap::from([(
                "mobile-token-1".to_string(),
                StoredMobileSession {
                    access_token: "mobile-token-1".to_string(),
                    pairing_session_id: "pairing-session-1".to_string(),
                    namespace: "workspace-alpha".to_string(),
                    trusted_device_id: trusted_device.trusted_device_id.clone(),
                    issued_at: now,
                    expires_at: now,
                    revoked_at: None,
                    resume_anchor: "resume-anchor-1".to_string(),
                    resume_anchor_expires_at: now,
                },
            )]),
        };

        store
            .save_pairing_state(&pairing_state)
            .expect("persist pairing state");

        let reloaded = store.load_pairing_state().expect("reload pairing state");
        assert_eq!(reloaded, pairing_state);

        cleanup_store_paths(&path);
    }
}
