use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Canonical chat truth is a Rust-owned graph:
/// `Conversation -> MessageNode -> MessageVariant -> ordered MessagePart`.
///
/// The graph shape is the truth source. Android may project it for presentation, but
/// must not invent missing branch, selection, or typed-part semantics.
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
/// Rust-owned conversation truth.
///
/// Invariants:
/// - A conversation owns one message graph; every referenced node/variant/part belongs to this
///   conversation through the node lineage.
/// - `current_cursor` is the leaf node of the active branch in Rust truth, or `None` only when the
///   conversation has not materialized any nodes yet.
/// - Active branch identity comes from Rust via the cursor plus each node's selected variant;
///   Android may project that path but must not invent it.
pub struct Conversation {
    pub id: ConversationId,
    pub topic_id: TopicId,
    pub agent_id: AgentId,
    pub title: String,
    pub summary: Option<String>,
    pub pinned: bool,
    pub generation_state: GenerationState,
    /// Points to the leaf `MessageNode` on the currently selected branch.
    ///
    /// This is always a node identity, never a variant identity. If set, it must resolve
    /// inside the same conversation.
    pub current_cursor: Option<NodeId>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// A stable turn slot in the conversation tree.
///
/// `id`, `conversation_id`, `parent_node_id`, and `role` define where this turn lives in the
/// graph. Selecting a different variant changes the chosen realization for this turn but does
/// not allocate a new node or change ancestry.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
/// Stable structural slot for one logical turn in the conversation graph.
///
/// Invariants:
/// - `conversation_id`, `parent_node_id`, and `role` define the node's position and stay stable
///   once the node is created.
/// - A new turn or a changed parent edge creates a new node ID rather than rewriting an existing
///   node. Editing a persisted user turn therefore branches by creating a new node from the old
///   parent.
/// - `select_index` is the canonical selected-variant pointer for the node and must resolve to an
///   existing variant in full Rust-owned node truth.
pub struct MessageNode {
    pub id: NodeId,
    pub conversation_id: ConversationId,
    /// Root nodes use `None`. Non-root nodes must point to another node in the same
    /// conversation, so the active branch can be reconstructed from parent links.
    pub parent_node_id: Option<NodeId>,
    pub role: MessageRole,
    /// Single source of truth for the selected variant on this node.
    ///
    /// App-facing projections may omit unselected variants, but reducers must treat this
    /// index as authoritative whenever multiple variants exist.
    pub select_index: usize,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// One concrete realization of a `MessageNode`.
///
/// Regenerate/retry flows append new variants on the same node when the logical turn slot
/// stays the same. Once a variant reaches a terminal status, it remains durable history.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
/// One content revision or generation attempt for a node.
///
/// Invariants:
/// - A variant belongs to exactly one node and never changes `node_id`.
/// - Assistant regenerate/retry for the same logical turn appends a new variant and moves
///   selection at the node; previously selected variants remain immutable history.
/// - `Streaming` is the only non-terminal status. Terminal states should record `finished_at`.
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

/// Ordered typed truth for a single `MessageVariant`.
///
/// `order_index` is stable within a variant. Parts may append while a variant is streaming,
/// but later edits/regenerations must not rewrite old parts in place. Typed payloads are the
/// canonical truth; markdown/text are content formats, not catch-all containers for tool,
/// reasoning, or media semantics.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
/// Typed payload atom owned by one variant.
///
/// Invariants:
/// - A part belongs to exactly one variant and is never re-parented.
/// - `order_index` is unique within the variant and reflects append order.
/// - Typed payloads remain Rust truth even if Android later renders a flattened presentation.
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
