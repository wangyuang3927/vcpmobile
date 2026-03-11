use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use vcpmobile_domain::{Conversation, ConversationId, GenerationState, NodeId};
use vcpmobile_protocol::NodeBundle;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredConversation {
    /// Full Rust-owned conversation truth persisted for recovery.
    ///
    /// `nodes` keeps `NodeBundle` truth, including the selected-variant pointer on each node.
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
    /// Active branch anchor for recovery/catalog projection.
    ///
    /// This is the selected leaf `NodeId`, never a variant identity or a UI row index.
    pub current_cursor: Option<NodeId>,
    pub is_recoverable: bool,
    pub node_count: usize,
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct StoreData {
    pub conversations: BTreeMap<String, StoredConversation>,
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
        Ok(serde_json::from_str(&raw)?)
    }

    pub fn save(&self, data: &StoreData) -> StoreResult<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let raw = serde_json::to_string_pretty(data)?;
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
                is_recoverable: cursor_resolves_to_leaf(&stored),
                node_count: stored.nodes.len(),
            })
            .collect::<Vec<_>>();

        items.sort_by(|left, right| right.updated_at.cmp(&left.updated_at));
        Ok(items)
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
        AgentId, MessageNode, MessageRole, MessageVariant, TopicId, VariantStatus,
    };
    use vcpmobile_protocol::VariantBundle;

    fn temp_store_path(name: &str) -> PathBuf {
        env::temp_dir().join(format!("vcpmobile-store-{name}-{}.json", Uuid::new_v4()))
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
}
