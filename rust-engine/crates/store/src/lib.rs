use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use vcpmobile_domain::{
    Conversation, ConversationId, GenerationState, NodeId, PROVIDER_LOCAL_ID_PREFIX,
    PROVIDER_PRESET_LOCAL_ID_PREFIX, ProviderConfig,
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
    #[serde(default, alias = "providers")]
    pub provider_configs: BTreeMap<String, ProviderConfig>,
}

impl StoreData {
    fn normalize_provider_configs(&mut self) {
        let mut normalized = BTreeMap::new();

        for (key, mut provider) in std::mem::take(&mut self.provider_configs) {
            // Legacy stores may key providers by endpoint; normalize them back to stable local IDs.
            provider.ensure_stable_ids(&key);
            normalized.insert(provider.local_id.clone(), provider);
        }

        self.provider_configs = normalized;
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
            .map(|stored| StoredConversationCatalogItem {
                conversation_id: stored.conversation.id,
                title: stored.conversation.title,
                summary: stored.conversation.summary,
                updated_at: stored.conversation.updated_at,
                generation_state: stored.conversation.generation_state,
                pinned: stored.conversation.pinned,
                current_cursor: stored.conversation.current_cursor,
                is_recoverable: stored.conversation.current_cursor.is_some()
                    && !stored.nodes.is_empty(),
                node_count: stored.nodes.len(),
            })
            .collect::<Vec<_>>();

        items.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        Ok(items)
    }

    pub fn get_provider(&self, local_id: &str) -> StoreResult<Option<ProviderConfig>> {
        let data = self.load()?;
        Ok(data.provider_configs.get(local_id).cloned())
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
        let reference_seed = if provider.local_id.trim().is_empty() {
            provider.base_url.clone()
        } else {
            provider.local_id.clone()
        };
        provider.ensure_stable_ids(&reference_seed);

        let now = chrono::Utc::now();
        if let Some(existing) = data.provider_configs.get(&provider.local_id).cloned() {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::{env, fs};
    use uuid::Uuid;
    use vcpmobile_domain::{
        ProviderAdapterKind, ProviderBodyFragment, ProviderModelCatalogEntry, ProviderPreset,
    };

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
