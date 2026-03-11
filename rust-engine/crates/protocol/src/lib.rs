use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use vcpmobile_domain::{
    Conversation, ConversationId, DraftState, MessageNode, MessagePart, MessageVariant, NodeId,
};

pub const SCHEMA_VERSION: &str = "0.1.0";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EventEnvelope<T> {
    pub schema_version: String,
    pub conversation_id: Option<ConversationId>,
    pub emitted_at: DateTime<Utc>,
    pub payload: T,
}

impl<T> EventEnvelope<T> {
    pub fn new(conversation_id: Option<ConversationId>, payload: T) -> Self {
        Self {
            schema_version: SCHEMA_VERSION.to_string(),
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
