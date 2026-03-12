use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use vcpmobile_domain::{
    AgentId, Conversation, ConversationId, DraftState, GenerationState, MessageNode, MessagePart,
    MessagePartPayload, MessageRole, MessageVariant, NodeId, PartId, TopicId, VariantId,
    VariantStatus,
};

pub const CHAT_EVENT_SCHEMA_FAMILY: &str = "chat_event";
pub const CHAT_EVENT_SCHEMA_MAJOR: u16 = 1;
pub const CHAT_EVENT_SCHEMA_MINOR: u16 = 0;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct EventSchema {
    pub family: String,
    pub major: u16,
    pub minor: u16,
}

impl EventSchema {
    pub fn chat_event_v1() -> Self {
        Self {
            family: CHAT_EVENT_SCHEMA_FAMILY.to_string(),
            major: CHAT_EVENT_SCHEMA_MAJOR,
            minor: CHAT_EVENT_SCHEMA_MINOR,
        }
    }
}

pub trait NamedEvent {
    fn event_name(&self) -> &'static str;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EventEnvelope<T> {
    pub schema: EventSchema,
    pub event_id: Uuid,
    pub event_name: String,
    pub conversation_id: Option<ConversationId>,
    pub emitted_at: DateTime<Utc>,
    pub payload: T,
}

impl<T: NamedEvent> EventEnvelope<T> {
    pub fn new(conversation_id: Option<ConversationId>, payload: T) -> Self {
        Self {
            schema: EventSchema::chat_event_v1(),
            event_id: Uuid::new_v4(),
            event_name: payload.event_name().to_string(),
            conversation_id,
            emitted_at: Utc::now(),
            payload,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "event", content = "data", rename_all = "snake_case")]
pub enum ChatEvent {
    ConversationListInvalidate {
        reason: String,
    },
    /// Full selected-branch snapshot for one conversation.
    ///
    /// Recovery/hydration surfaces and live send flows both use this event to materialize the
    /// complete currently selected branch from root to `Conversation.current_cursor`.
    ConversationSnapshot {
        conversation: SnapshotConversation,
        branch: SnapshotBranch,
    },
    ConversationNodeUpsert {
        node: SnapshotNode,
    },
    /// Selection-only mutation for an existing node.
    ///
    /// Reducers must treat this as a variant switch on the same `MessageNode`, not as a new
    /// branch or a signal to synthesize missing parts on the client. This event is only valid
    /// for recipients that already hold the full variant array for the node; selected-only
    /// payload surfaces must use `ConversationNodeUpsert` or `ConversationSnapshot` instead.
    ConversationNodeSelect {
        node_id: NodeId,
        select_index: usize,
    },
    ConversationMetaUpdate {
        conversation: Conversation,
    },
    GenerationStarted {
        node_id: NodeId,
        variant_id: Uuid,
    },
    GenerationPartDelta {
        node_id: NodeId,
        variant_id: Uuid,
        appended_parts: Vec<MessagePart>,
    },
    GenerationCompleted {
        node_id: NodeId,
        variant_id: Uuid,
    },
    GenerationFailed {
        node_id: NodeId,
        variant_id: Uuid,
        message: String,
    },
    GenerationCancelled {
        node_id: NodeId,
        variant_id: Uuid,
        message: Option<String>,
    },
    DraftUpdated {
        draft: DraftState,
    },
    DraftCleared {
        conversation_id: ConversationId,
    },
    AuthQrPlaceholder {
        session_id: String,
        status: String,
    },
    EngineError {
        message: String,
    },
}

impl NamedEvent for ChatEvent {
    fn event_name(&self) -> &'static str {
        match self {
            Self::ConversationListInvalidate { .. } => "conversation_list_invalidate",
            Self::ConversationSnapshot { .. } => "conversation_snapshot",
            Self::ConversationNodeUpsert { .. } => "conversation_node_upsert",
            Self::ConversationNodeSelect { .. } => "conversation_node_select",
            Self::ConversationMetaUpdate { .. } => "conversation_meta_update",
            Self::GenerationStarted { .. } => "generation_started",
            Self::GenerationPartDelta { .. } => "generation_part_delta",
            Self::GenerationCompleted { .. } => "generation_completed",
            Self::GenerationFailed { .. } => "generation_failed",
            Self::GenerationCancelled { .. } => "generation_cancelled",
            Self::DraftUpdated { .. } => "draft_updated",
            Self::DraftCleared { .. } => "draft_cleared",
            Self::AuthQrPlaceholder { .. } => "auth_qr_placeholder",
            Self::EngineError { .. } => "engine_error",
        }
    }
}

/// Rust/store transport shape for one node plus its variants.
///
/// Canonical Rust/store truth may include every variant and use `node.select_index` against the
/// full `variants` array. App-facing payloads should project this into `SnapshotNode` so clients
/// do not need to infer branch semantics from hidden variants.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeBundle {
    pub node: MessageNode,
    pub variants: Vec<VariantBundle>,
}

/// Transport shape for one variant and its ordered typed parts.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VariantBundle {
    pub variant: MessageVariant,
    pub parts: Vec<MessagePart>,
}

/// Stable conversation header carried by app-facing snapshot events.
///
/// This freezes the public JSON contract independently from the Rust-owned domain structs while
/// still mirroring the same truth.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SnapshotConversation {
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

impl From<&Conversation> for SnapshotConversation {
    fn from(value: &Conversation) -> Self {
        Self {
            id: value.id,
            topic_id: value.topic_id,
            agent_id: value.agent_id,
            title: value.title.clone(),
            summary: value.summary.clone(),
            pinned: value.pinned,
            generation_state: value.generation_state,
            current_cursor: value.current_cursor,
            created_at: value.created_at,
            updated_at: value.updated_at,
        }
    }
}

/// Selected-branch projection for app-facing snapshot payloads.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SnapshotBranch {
    pub cursor_node_id: Option<NodeId>,
    pub nodes: Vec<SnapshotNode>,
}

/// Selected node shape used by snapshot/upsert payloads.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SnapshotNode {
    pub node_id: NodeId,
    pub parent_node_id: Option<NodeId>,
    pub role: MessageRole,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub selected_variant: SnapshotVariant,
}

/// Selected variant truth surfaced to Android.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SnapshotVariant {
    pub variant_id: VariantId,
    pub status: VariantStatus,
    pub model_id: Option<String>,
    pub usage_json: Option<String>,
    pub created_at: DateTime<Utc>,
    pub finished_at: Option<DateTime<Utc>>,
    pub parts: Vec<SnapshotPart>,
}

/// Stable selected-part shape. The containing selected variant already establishes ancestry, so
/// app-facing payloads do not need to infer it from repeated container IDs.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SnapshotPart {
    pub part_id: PartId,
    pub order_index: i32,
    pub payload: MessagePartPayload,
}

impl From<&MessagePart> for SnapshotPart {
    fn from(value: &MessagePart) -> Self {
        Self {
            part_id: value.id,
            order_index: value.order_index,
            payload: value.payload.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateConversationRequest {
    pub topic_id: Uuid,
    pub agent_id: Uuid,
    pub title: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SendMessageRequest {
    pub conversation_id: ConversationId,
    pub text: String,
}

#[cfg(test)]
mod tests {
    use chrono::Utc;
    use serde_json::Value;
    use uuid::Uuid;
    use vcpmobile_domain::{GenerationState, MessagePartPayload, MessageRole, VariantStatus};

    use super::{
        ChatEvent, EventEnvelope, EventSchema, NamedEvent, SnapshotBranch, SnapshotConversation,
        SnapshotNode, SnapshotPart, SnapshotVariant,
    };

    #[test]
    fn chat_event_names_are_canonical_snake_case() {
        let cases = vec![
            (
                ChatEvent::ConversationListInvalidate {
                    reason: "refresh".to_string(),
                },
                "conversation_list_invalidate",
            ),
            (
                ChatEvent::ConversationNodeSelect {
                    node_id: Uuid::nil(),
                    select_index: 0,
                },
                "conversation_node_select",
            ),
            (
                ChatEvent::GenerationPartDelta {
                    node_id: Uuid::nil(),
                    variant_id: Uuid::nil(),
                    appended_parts: Vec::new(),
                },
                "generation_part_delta",
            ),
            (
                ChatEvent::DraftCleared {
                    conversation_id: Uuid::nil(),
                },
                "draft_cleared",
            ),
            (
                ChatEvent::GenerationCancelled {
                    node_id: Uuid::nil(),
                    variant_id: Uuid::nil(),
                    message: None,
                },
                "generation_cancelled",
            ),
            (
                ChatEvent::EngineError {
                    message: "boom".to_string(),
                },
                "engine_error",
            ),
        ];

        for (event, expected_name) in cases {
            assert_eq!(event.event_name(), expected_name);
        }
    }

    #[test]
    fn event_envelope_uses_stable_outer_metadata_and_matches_payload_name() {
        let envelope = EventEnvelope::new(
            Some(Uuid::nil()),
            ChatEvent::GenerationCompleted {
                node_id: Uuid::nil(),
                variant_id: Uuid::nil(),
            },
        );

        assert_eq!(envelope.schema, EventSchema::chat_event_v1());
        assert_eq!(envelope.event_name, "generation_completed");

        let serialized: Value = serde_json::to_value(&envelope).expect("serialize envelope");
        assert_eq!(serialized["schema"]["family"], "chat_event");
        assert_eq!(serialized["schema"]["major"], 1);
        assert_eq!(serialized["schema"]["minor"], 0);
        assert_eq!(
            serialized["event_name"], serialized["payload"]["event"],
            "outer event_name must stay aligned with the tagged payload event"
        );
        assert!(
            serialized["event_id"].as_str().is_some(),
            "outer envelope must carry a stable event identity"
        );
    }

    #[test]
    fn conversation_snapshot_payload_uses_stable_selected_branch_shape() {
        let now = Utc::now();
        let envelope = EventEnvelope::new(
            Some(Uuid::nil()),
            ChatEvent::ConversationSnapshot {
                conversation: SnapshotConversation {
                    id: Uuid::nil(),
                    topic_id: Uuid::nil(),
                    agent_id: Uuid::nil(),
                    title: "demo".to_string(),
                    summary: None,
                    pinned: false,
                    generation_state: GenerationState::Idle,
                    current_cursor: Some(Uuid::nil()),
                    created_at: now,
                    updated_at: now,
                },
                branch: SnapshotBranch {
                    cursor_node_id: Some(Uuid::nil()),
                    nodes: vec![SnapshotNode {
                        node_id: Uuid::nil(),
                        parent_node_id: None,
                        role: MessageRole::Assistant,
                        created_at: now,
                        updated_at: now,
                        selected_variant: SnapshotVariant {
                            variant_id: Uuid::nil(),
                            status: VariantStatus::Completed,
                            model_id: Some("demo-model".to_string()),
                            usage_json: None,
                            created_at: now,
                            finished_at: Some(now),
                            parts: vec![SnapshotPart {
                                part_id: Uuid::nil(),
                                order_index: 0,
                                payload: MessagePartPayload::Text {
                                    text: "hello".to_string(),
                                },
                            }],
                        },
                    }],
                },
            },
        );

        let serialized: Value = serde_json::to_value(&envelope).expect("serialize envelope");

        assert!(serialized["payload"]["data"]["nodes"].is_null());
        assert_eq!(
            serialized["payload"]["data"]["branch"]["cursor_node_id"],
            Value::String(Uuid::nil().to_string())
        );
        assert_eq!(
            serialized["payload"]["data"]["branch"]["nodes"][0]["node_id"],
            Value::String(Uuid::nil().to_string())
        );
        assert_eq!(
            serialized["payload"]["data"]["branch"]["nodes"][0]["selected_variant"]["variant_id"],
            Value::String(Uuid::nil().to_string())
        );
        assert_eq!(
            serialized["payload"]["data"]["branch"]["nodes"][0]["selected_variant"]["parts"][0]["part_id"],
            Value::String(Uuid::nil().to_string())
        );
    }
}
