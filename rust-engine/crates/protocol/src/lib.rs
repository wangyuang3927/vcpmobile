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
    ConversationSnapshot {
        conversation: Conversation,
        nodes: Vec<NodeBundle>,
    },
    ConversationNodeUpsert {
        node: NodeBundle,
    },
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeBundle {
    pub node: MessageNode,
    pub variants: Vec<VariantBundle>,
}

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
