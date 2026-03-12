use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use vcpmobile_domain::{
    Conversation, ConversationId, DraftState, MessageNode, MessagePart, MessageVariant, NodeId,
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
        conversation: Conversation,
        nodes: Vec<NodeBundle>,
    },
    ConversationNodeUpsert {
        node: NodeBundle,
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
            Self::DraftUpdated { .. } => "draft_updated",
            Self::DraftCleared { .. } => "draft_cleared",
            Self::AuthQrPlaceholder { .. } => "auth_qr_placeholder",
            Self::EngineError { .. } => "engine_error",
        }
    }
}

/// Transport shape for one node plus its variants.
///
/// Canonical Rust/store truth may include every variant and use `node.select_index` against the
/// full `variants` array. Client-facing selected-only projections must include exactly one variant
/// and normalize `node.select_index` to `0` so the bundle stays self-consistent without Android
/// fallback guesses.
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
    use serde_json::Value;
    use uuid::Uuid;

    use super::{ChatEvent, EventEnvelope, EventSchema, NamedEvent};

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
}
