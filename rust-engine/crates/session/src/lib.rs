use chrono::Utc;
use thiserror::Error;
use uuid::Uuid;
use vcpmobile_domain::{
    Conversation, ConversationId, GenerationState, MessageNode, MessagePart, MessagePartPayload,
    MessageRole, MessageVariant, NodeId, TopicId, VariantStatus,
};
use vcpmobile_protocol::{ChatEvent, EventEnvelope, NodeBundle, VariantBundle};
use vcpmobile_store::{FileStore, StoreError, StoredConversation, StoredConversationCatalogItem};

#[derive(Debug, Clone)]
pub struct SessionEngine {
    store: FileStore,
    default_topic_id: TopicId,
    default_agent_id: Uuid,
}

#[derive(Debug, Clone)]
pub struct SessionSendRequest {
    pub conversation_id: Option<ConversationId>,
    pub text: String,
}

#[derive(Debug, Error)]
pub enum SessionError {
    #[error("store error: {0}")]
    Store(#[from] StoreError),
    #[error("empty user text")]
    EmptyText,
    #[error("conversation not found: {0}")]
    ConversationNotFound(ConversationId),
}

impl SessionEngine {
    pub fn new(store: FileStore, default_topic_id: TopicId, default_agent_id: Uuid) -> Self {
        Self {
            store,
            default_topic_id,
            default_agent_id,
        }
    }

    pub fn store(&self) -> &FileStore {
        &self.store
    }

    pub fn ensure_demo_conversation(&self) -> Result<StoredConversation, SessionError> {
        let (conversation, node_bundle) =
            demo_conversation(self.default_topic_id, self.default_agent_id);
        let stored = StoredConversation {
            conversation,
            nodes: vec![node_bundle],
        };
        self.store.upsert_conversation(stored.clone())?;
        Ok(stored)
    }

    pub fn snapshot_for(
        &self,
        conversation_id: ConversationId,
    ) -> Result<Option<StoredConversation>, SessionError> {
        Ok(self.store.get_conversation(conversation_id)?)
    }

    pub fn conversation_catalog(&self) -> Result<Vec<StoredConversationCatalogItem>, SessionError> {
        Ok(self.store.list_conversation_catalog()?)
    }

    pub fn send_message(
        &self,
        request: SessionSendRequest,
    ) -> Result<Vec<EventEnvelope<ChatEvent>>, SessionError> {
        let text = request.text.trim();
        if text.is_empty() {
            return Err(SessionError::EmptyText);
        }

        let now = Utc::now();
        let mut stored = match request.conversation_id {
            Some(conversation_id) => self
                .store
                .get_conversation(conversation_id)?
                .ok_or(SessionError::ConversationNotFound(conversation_id))?,
            None => StoredConversation {
                conversation: Conversation {
                    id: ConversationId::new_v4(),
                    topic_id: self.default_topic_id,
                    agent_id: self.default_agent_id,
                    title: truncate_title(text),
                    summary: Some("Rust session store conversation".to_string()),
                    pinned: false,
                    generation_state: GenerationState::Idle,
                    current_cursor: None,
                    created_at: now,
                    updated_at: now,
                },
                nodes: Vec::new(),
            },
        };

        let user_node = build_user_node(
            stored.conversation.id,
            stored.conversation.current_cursor,
            text,
            now,
        );
        let user_node_id = user_node.node.id;
        let user_snapshot_node = selected_variant_snapshot_projection(&user_node);
        stored.nodes.push(user_node);

        let assistant_node =
            build_assistant_node(stored.conversation.id, Some(user_node_id), text, now);
        let assistant_node_id = assistant_node.node.id;
        let assistant_variant_id = assistant_node.variants[0].variant.id;

        stored.conversation.current_cursor = Some(assistant_node_id);
        stored.conversation.generation_state = GenerationState::Idle;
        stored.conversation.updated_at = now;
        stored.nodes.push(assistant_node.clone());
        self.store.upsert_conversation(stored.clone())?;

        // Materialize both persisted nodes so the client never has to keep a synthetic user turn
        // alive without Rust-owned node/variant identity.
        let snapshot_nodes = vec![
            user_snapshot_node,
            selected_variant_snapshot_projection(&assistant_node),
        ];
        let assistant_delta_parts = streaming_delta_parts(&assistant_node);

        Ok(vec![
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::ConversationSnapshot {
                    conversation: stored.conversation.clone(),
                    nodes: snapshot_nodes,
                },
            ),
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::GenerationStarted {
                    node_id: assistant_node_id,
                    variant_id: assistant_variant_id,
                },
            ),
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::GenerationPartDelta {
                    node_id: assistant_node_id,
                    variant_id: assistant_variant_id,
                    appended_parts: assistant_delta_parts,
                },
            ),
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::ConversationNodeUpsert {
                    node: assistant_node,
                },
            ),
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::GenerationCompleted {
                    node_id: assistant_node_id,
                    variant_id: assistant_variant_id,
                },
            ),
        ])
    }
}

fn truncate_title(text: &str) -> String {
    let mut title = text.chars().take(18).collect::<String>();
    if text.chars().count() > 18 {
        title.push('…');
    }
    if title.is_empty() {
        "新对话".to_string()
    } else {
        title
    }
}

fn assistant_markdown_reply(user_text: &str) -> String {
    format!(
        "Rust Engine 已接收你的消息：**{}**\n\n- 来源：Rust session engine\n- 形态：markdown_block",
        user_text
    )
}

fn build_user_node(
    conversation_id: ConversationId,
    parent_node_id: Option<NodeId>,
    text: &str,
    now: chrono::DateTime<Utc>,
) -> NodeBundle {
    let node_id = NodeId::new_v4();
    let variant_id = Uuid::new_v4();
    NodeBundle {
        node: MessageNode {
            id: node_id,
            conversation_id,
            parent_node_id,
            role: MessageRole::User,
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
            parts: vec![MessagePart {
                id: Uuid::new_v4(),
                variant_id,
                order_index: 0,
                payload: MessagePartPayload::Text {
                    text: text.to_string(),
                },
            }],
        }],
    }
}

fn build_assistant_node(
    conversation_id: ConversationId,
    parent_node_id: Option<NodeId>,
    text: &str,
    now: chrono::DateTime<Utc>,
) -> NodeBundle {
    let node_id = NodeId::new_v4();
    let variant_id = Uuid::new_v4();
    NodeBundle {
        node: MessageNode {
            id: node_id,
            conversation_id,
            parent_node_id,
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
            parts: vec![
                MessagePart {
                    id: Uuid::new_v4(),
                    variant_id,
                    order_index: 0,
                    payload: MessagePartPayload::Reasoning {
                        text: format!("正在基于用户消息生成回复：{}", truncate_title(text)),
                    },
                },
                MessagePart {
                    id: Uuid::new_v4(),
                    variant_id,
                    order_index: 1,
                    payload: MessagePartPayload::MarkdownBlock {
                        markdown: assistant_markdown_reply(text),
                    },
                },
            ],
        }],
    }
}

fn selected_variant_snapshot_projection(bundle: &NodeBundle) -> NodeBundle {
    let selected_variant = bundle
        .variants
        .get(bundle.node.select_index)
        .or_else(|| bundle.variants.first())
        .cloned();
    let mut node = bundle.node.clone();
    node.select_index = 0;

    NodeBundle {
        node,
        variants: selected_variant.into_iter().collect(),
    }
}

fn streaming_delta_parts(bundle: &NodeBundle) -> Vec<MessagePart> {
    bundle
        .variants
        .get(bundle.node.select_index)
        .or_else(|| bundle.variants.first())
        .map(|variant| {
            variant
                .parts
                .iter()
                .filter(|part| should_emit_in_streaming_delta(&part.payload))
                .cloned()
                .collect()
        })
        .unwrap_or_default()
}

fn should_emit_in_streaming_delta(payload: &MessagePartPayload) -> bool {
    !matches!(payload, MessagePartPayload::Reasoning { .. })
}

pub fn demo_conversation(topic_id: TopicId, agent_id: Uuid) -> (Conversation, NodeBundle) {
    let conversation_id = ConversationId::new_v4();
    let node_id = NodeId::new_v4();
    let variant_id = Uuid::new_v4();
    let now = Utc::now();

    let conversation = Conversation {
        id: conversation_id,
        topic_id,
        agent_id,
        title: "新对话".to_string(),
        summary: Some("Rust engine demo session".to_string()),
        pinned: false,
        generation_state: GenerationState::Streaming,
        current_cursor: Some(node_id),
        created_at: now,
        updated_at: now,
    };

    let node = MessageNode {
        id: node_id,
        conversation_id,
        parent_node_id: None,
        role: MessageRole::Assistant,
        select_index: 0,
        created_at: now,
        updated_at: now,
    };

    let variant = MessageVariant {
        id: variant_id,
        node_id,
        status: VariantStatus::Streaming,
        model_id: Some("demo-model".to_string()),
        usage_json: None,
        created_at: now,
        finished_at: None,
    };

    let part = MessagePart {
        id: Uuid::new_v4(),
        variant_id,
        order_index: 0,
        payload: MessagePartPayload::Reasoning {
            text: "正在整理回答结构…".to_string(),
        },
    };

    let bundle = NodeBundle {
        node,
        variants: vec![VariantBundle {
            variant,
            parts: vec![part],
        }],
    };

    (conversation, bundle)
}

pub fn demo_stream(
    conversation: Conversation,
    node_bundle: NodeBundle,
) -> Vec<EventEnvelope<ChatEvent>> {
    let node_id = node_bundle.node.id;
    let variant_id = node_bundle.variants[0].variant.id;

    vec![
        EventEnvelope::new(
            Some(conversation.id),
            ChatEvent::ConversationSnapshot {
                conversation: conversation.clone(),
                nodes: vec![node_bundle.clone()],
            },
        ),
        EventEnvelope::new(
            Some(conversation.id),
            ChatEvent::GenerationStarted {
                node_id,
                variant_id,
            },
        ),
        EventEnvelope::new(
            Some(conversation.id),
            ChatEvent::GenerationPartDelta {
                node_id,
                variant_id,
                appended_parts: vec![MessagePart {
                    id: Uuid::new_v4(),
                    variant_id,
                    order_index: 1,
                    payload: MessagePartPayload::MarkdownBlock {
                        markdown: "你好，这是一条来自 Rust Engine 的 **demo** 流式消息。"
                            .to_string(),
                    },
                }],
            },
        ),
        EventEnvelope::new(
            Some(conversation.id),
            ChatEvent::GenerationCompleted {
                node_id,
                variant_id,
            },
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{env, fs};

    fn test_engine() -> SessionEngine {
        let path = env::temp_dir().join(format!("vcpmobile-session-test-{}.json", Uuid::new_v4()));
        let store = FileStore::new(path);
        SessionEngine::new(store, Uuid::new_v4(), Uuid::new_v4())
    }

    #[test]
    fn send_message_without_conversation_id_creates_and_persists_conversation() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
            })
            .expect("create conversation");

        let conversation_id = events[0]
            .conversation_id
            .expect("conversation id in envelope");
        let stored = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");

        assert_eq!(stored.conversation.id, conversation_id);
        assert_eq!(
            stored.nodes.len(),
            2,
            "user + assistant nodes should persist"
        );
        assert_eq!(stored.nodes[0].node.parent_node_id, None);
        assert_eq!(
            stored.nodes[1].node.parent_node_id,
            Some(stored.nodes[0].node.id),
            "assistant node should branch from the new user node"
        );
        assert_eq!(
            stored.conversation.current_cursor,
            Some(stored.nodes[1].node.id),
            "conversation cursor should track the assistant leaf"
        );
        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_with_existing_conversation_id_appends_to_same_conversation() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");

        engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
            })
            .expect("resume existing conversation");

        let stored = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");

        assert_eq!(stored.conversation.id, conversation_id);
        assert_eq!(stored.nodes.len(), 4, "two turns should yield four nodes");
        let first_assistant_id = stored.nodes[1].node.id;
        let second_user = &stored.nodes[2].node;
        let second_assistant = &stored.nodes[3].node;
        assert_eq!(
            second_user.parent_node_id,
            Some(first_assistant_id),
            "follow-up user turn should branch from the previous cursor"
        );
        assert_eq!(
            second_assistant.parent_node_id,
            Some(second_user.id),
            "assistant reply should branch from the new user node"
        );
        assert_eq!(
            stored.conversation.current_cursor,
            Some(second_assistant.id),
            "cursor should advance to the latest assistant leaf"
        );
        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_with_missing_conversation_id_fails_strictly() {
        let engine = test_engine();
        let missing = ConversationId::new_v4();

        let error = engine
            .send_message(SessionSendRequest {
                conversation_id: Some(missing),
                text: "resume".to_string(),
            })
            .expect_err("missing conversation must fail");

        assert!(matches!(error, SessionError::ConversationNotFound(id) if id == missing));
        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn selected_variant_snapshot_projection_keeps_selected_variant_full_parts() {
        let now = Utc::now();
        let bundle = build_assistant_node(ConversationId::new_v4(), None, "projection", now);

        let projected = selected_variant_snapshot_projection(&bundle);
        let parts = &projected.variants[0].parts;

        assert_eq!(projected.variants.len(), 1);
        assert_eq!(parts.len(), 2);
        assert!(matches!(
            parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(
            parts[1].payload,
            MessagePartPayload::MarkdownBlock { .. }
        ));
    }

    #[test]
    fn selected_variant_snapshot_projection_rebases_selected_variant_to_index_zero() {
        let now = Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let first_variant_id = Uuid::new_v4();
        let second_variant_id = Uuid::new_v4();
        let bundle = NodeBundle {
            node: MessageNode {
                id: node_id,
                conversation_id,
                parent_node_id: None,
                role: MessageRole::Assistant,
                select_index: 1,
                created_at: now,
                updated_at: now,
            },
            variants: vec![
                VariantBundle {
                    variant: MessageVariant {
                        id: first_variant_id,
                        node_id,
                        status: VariantStatus::Completed,
                        model_id: Some("rust-session-engine".to_string()),
                        usage_json: None,
                        created_at: now,
                        finished_at: Some(now),
                    },
                    parts: vec![MessagePart {
                        id: Uuid::new_v4(),
                        variant_id: first_variant_id,
                        order_index: 0,
                        payload: MessagePartPayload::Text {
                            text: "old".to_string(),
                        },
                    }],
                },
                VariantBundle {
                    variant: MessageVariant {
                        id: second_variant_id,
                        node_id,
                        status: VariantStatus::Completed,
                        model_id: Some("rust-session-engine".to_string()),
                        usage_json: None,
                        created_at: now,
                        finished_at: Some(now),
                    },
                    parts: vec![MessagePart {
                        id: Uuid::new_v4(),
                        variant_id: second_variant_id,
                        order_index: 0,
                        payload: MessagePartPayload::Text {
                            text: "selected".to_string(),
                        },
                    }],
                },
            ],
        };

        let projected = selected_variant_snapshot_projection(&bundle);

        assert_eq!(projected.node.select_index, 0);
        assert_eq!(projected.variants.len(), 1);
        assert_eq!(projected.variants[0].variant.id, second_variant_id);
    }

    #[test]
    fn streaming_delta_parts_emit_markdown_block_from_selected_variant() {
        let now = Utc::now();
        let bundle = build_assistant_node(ConversationId::new_v4(), None, "projection", now);

        let parts = streaming_delta_parts(&bundle);

        assert_eq!(parts.len(), 1);
        assert!(matches!(
            parts[0].payload,
            MessagePartPayload::MarkdownBlock { .. }
        ));
    }

    #[test]
    fn assistant_node_uses_markdown_block_for_final_content() {
        let now = Utc::now();
        let bundle =
            build_assistant_node(ConversationId::new_v4(), None, "markdown projection", now);
        let parts = &bundle.variants[0].parts;

        assert_eq!(parts.len(), 2);
        assert!(matches!(
            parts[1].payload,
            MessagePartPayload::MarkdownBlock { .. }
        ));
    }

    #[test]
    fn send_message_streams_markdown_block_delta_for_assistant_content() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
            })
            .expect("send message");

        let delta_parts = match &events[2].payload {
            ChatEvent::GenerationPartDelta { appended_parts, .. } => appended_parts,
            other => panic!("expected generation_part_delta, got {other:?}"),
        };

        assert_eq!(delta_parts.len(), 1);
        assert!(matches!(
            delta_parts[0].payload,
            MessagePartPayload::MarkdownBlock { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_snapshot_carries_new_user_and_assistant_nodes() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
            })
            .expect("send message");

        let snapshot_nodes = match &events[0].payload {
            ChatEvent::ConversationSnapshot { nodes, .. } => nodes,
            other => panic!("expected conversation_snapshot, got {other:?}"),
        };

        assert_eq!(
            snapshot_nodes.len(),
            2,
            "send snapshot should materialize both new nodes"
        );
        assert_eq!(snapshot_nodes[0].node.role, MessageRole::User);
        assert_eq!(snapshot_nodes[0].node.parent_node_id, None);
        assert_eq!(snapshot_nodes[0].node.select_index, 0);
        assert_eq!(snapshot_nodes[0].variants.len(), 1);
        assert!(matches!(
            snapshot_nodes[0].variants[0].parts[0].payload,
            MessagePartPayload::Text { .. }
        ));
        assert_eq!(snapshot_nodes[1].node.role, MessageRole::Assistant);
        assert_eq!(
            snapshot_nodes[1].node.parent_node_id,
            Some(snapshot_nodes[0].node.id),
            "assistant snapshot should preserve the persisted user-node edge"
        );
        assert_eq!(snapshot_nodes[1].node.select_index, 0);
        assert_eq!(snapshot_nodes[1].variants.len(), 1);
        assert_eq!(snapshot_nodes[1].variants[0].parts.len(), 2);
        assert!(matches!(
            snapshot_nodes[1].variants[0].parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(
            snapshot_nodes[1].variants[0].parts[1].payload,
            MessagePartPayload::MarkdownBlock { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_emits_node_upsert_with_full_selected_variant() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
            })
            .expect("send message");

        let upserted_parts = match &events[3].payload {
            ChatEvent::ConversationNodeUpsert { node } => &node.variants[0].parts,
            other => panic!("expected conversation_node_upsert, got {other:?}"),
        };

        assert_eq!(upserted_parts.len(), 2);
        assert!(matches!(
            upserted_parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(
            upserted_parts[1].payload,
            MessagePartPayload::MarkdownBlock { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn conversation_catalog_tracks_latest_turn_for_same_conversation() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");

        engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
            })
            .expect("second message");

        let catalog = engine.conversation_catalog().expect("catalog");
        let stored = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");

        let item = catalog
            .iter()
            .find(|item| item.conversation_id == conversation_id)
            .expect("catalog item for conversation");

        assert_eq!(catalog.len(), 1, "same conversation should project once");
        assert_eq!(item.updated_at, stored.conversation.updated_at);
        assert_eq!(item.current_cursor, stored.conversation.current_cursor);
        assert_eq!(item.generation_state, stored.conversation.generation_state);

        fs::remove_file(engine.store().path()).ok();
    }
}
