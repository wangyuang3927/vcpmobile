use std::{
    collections::{BTreeMap, BTreeSet, btree_map::Entry},
    fs,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use vcpmobile_domain::{
    AgentConfig, Conversation, ConversationId, GenerationState, NodeId, ProviderAuthConfig,
    ProviderConfig, ProviderModelCatalog,
};
use vcpmobile_protocol::NodeBundle;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredConversation {
    pub conversation: Conversation,
    pub nodes: Vec<NodeBundle>,
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
    pub node_count: usize,
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct StoreData {
    #[serde(default)]
    pub conversations: BTreeMap<String, StoredConversation>,
    #[serde(default, alias = "agents")]
    pub agent_configs: BTreeMap<String, AgentConfig>,
    #[serde(default, alias = "providers")]
    pub provider_configs: BTreeMap<String, ProviderConfig>,
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
    path: PathBuf,
}

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("validation error: {0}")]
    Validation(String),
}

pub type StoreResult<T> = Result<T, StoreError>;

impl FileStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn load(&self) -> StoreResult<StoreData> {
        if !self.path.exists() {
            return Ok(StoreData::default());
        }
        let raw = fs::read_to_string(&self.path)?;
        if raw.trim().is_empty() {
            return Ok(StoreData::default());
        }

        let mut data: StoreData = serde_json::from_str(&raw)?;
        data.normalize_provider_configs();
        Ok(data)
    }

    pub fn save(&self, data: &StoreData) -> StoreResult<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }

        let mut canonical = data.clone();
        canonical.normalize_provider_configs();

        let raw = serde_json::to_string_pretty(&canonical)?;
        fs::write(&self.path, raw)?;
        Ok(())
    }

    pub fn get_conversation(
        &self,
        conversation_id: ConversationId,
    ) -> StoreResult<Option<StoredConversation>> {
        let data = self.load()?;
        Ok(data
            .conversations
            .get(&conversation_id.to_string())
            .cloned())
    }

    pub fn upsert_conversation(&self, record: StoredConversation) -> StoreResult<()> {
        let mut data = self.load()?;
        data.conversations
            .insert(record.conversation.id.to_string(), record);
        self.save(&data)
    }

    pub fn list_conversations(&self) -> StoreResult<Vec<StoredConversation>> {
        let data = self.load()?;
        Ok(data.conversations.into_values().collect())
    }

    pub fn list_conversation_catalog(&self) -> StoreResult<Vec<StoredConversationCatalogItem>> {
        let mut items = self
            .list_conversations()?
            .into_iter()
            .map(|stored| {
                let is_recoverable = cursor_resolves_to_leaf(&stored);
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
        let data = self.load()?;
        Ok(data.agent_configs.get(agent_id).cloned())
    }

    pub fn upsert_agent(&self, mut agent: AgentConfig) -> StoreResult<AgentConfig> {
        agent
            .validate()
            .map_err(|error| StoreError::Validation(error.to_string()))?;

        let mut data = self.load()?;
        let now = chrono::Utc::now();
        if let Some(existing) = data.agent_configs.get(&agent.id.to_string()) {
            agent.created_at = existing.created_at;
        }
        agent.updated_at = now;

        data.agent_configs
            .insert(agent.id.to_string(), agent.clone());
        self.save(&data)?;
        Ok(agent)
    }

    pub fn list_agents(&self) -> StoreResult<Vec<AgentConfig>> {
        let mut agents = self.load()?.agent_configs.into_values().collect::<Vec<_>>();
        agents.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        Ok(agents)
    }

    pub fn delete_agent(&self, agent_id: &str) -> StoreResult<Option<AgentConfig>> {
        let mut data = self.load()?;
        let removed = data.agent_configs.remove(agent_id);
        self.save(&data)?;
        Ok(removed)
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::{env, fs};
    use uuid::Uuid;
    use vcpmobile_domain::{
        AgentConfig, AgentId, MessageNode, MessageRole, MessageVariant, PROVIDER_LOCAL_ID_PREFIX,
        PROVIDER_PRESET_LOCAL_ID_PREFIX, ProviderAdapterKind, ProviderBodyFragment,
        ProviderModelCatalogEntry, ProviderPreset, TopicId, VariantStatus,
    };
    use vcpmobile_protocol::VariantBundle;

    fn temp_store_path(name: &str) -> PathBuf {
        env::temp_dir().join(format!("vcpmobile-store-{name}-{}.json", Uuid::new_v4()))
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

        fs::remove_file(path).ok();
    }

    #[test]
    fn upsert_agent_rejects_invalid_required_fields() {
        let path = temp_store_path("agents-invalid");
        let store = FileStore::new(&path);
        let mut agent = sample_agent("Planner");
        agent.prompt.system_prompt = " ".to_string();

        let error = store.upsert_agent(agent).expect_err("validation failure");
        assert!(matches!(error, StoreError::Validation(_)));

        fs::remove_file(path).ok();
    }

    #[test]
    fn catalog_marks_missing_cursor_conversation_not_recoverable() {
        let path = temp_store_path("missing-cursor");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        store
            .upsert_conversation(StoredConversation {
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
            })
            .expect("write stored conversation");

        let items = store
            .list_conversation_catalog()
            .expect("load catalog projection");

        assert_eq!(items.len(), 1);
        assert!(!items[0].is_recoverable);

        fs::remove_file(path).ok();
    }

    #[test]
    fn catalog_marks_missing_branch_ancestor_not_recoverable() {
        let path = temp_store_path("missing-ancestor");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        store
            .upsert_conversation(StoredConversation {
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
            })
            .expect("write stored conversation");

        let items = store
            .list_conversation_catalog()
            .expect("load catalog projection");

        assert_eq!(items.len(), 1);
        assert!(!items[0].is_recoverable);

        fs::remove_file(path).ok();
    }

    #[test]
    fn catalog_marks_invalid_selected_variant_index_not_recoverable() {
        let path = temp_store_path("invalid-select-index");
        let store = FileStore::new(&path);
        let now = chrono::Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        store
            .upsert_conversation(StoredConversation {
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
            })
            .expect("write stored conversation");

        let items = store
            .list_conversation_catalog()
            .expect("load catalog projection");

        assert_eq!(items.len(), 1);
        assert!(!items[0].is_recoverable);

        fs::remove_file(path).ok();
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
}
