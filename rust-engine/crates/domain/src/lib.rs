use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub type AgentId = Uuid;
pub type TopicId = Uuid;
pub type ConversationId = Uuid;
pub type NodeId = Uuid;
pub type VariantId = Uuid;
pub type PartId = Uuid;

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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Conversation {
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MessageNode {
    pub id: NodeId,
    pub conversation_id: ConversationId,
    pub parent_node_id: Option<NodeId>,
    pub role: MessageRole,
    pub select_index: usize,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

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
