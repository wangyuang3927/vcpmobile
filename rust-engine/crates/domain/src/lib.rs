use std::{collections::BTreeSet, error::Error, fmt};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

// Canonical chat truth is a Rust-owned graph:
// `Conversation -> MessageNode -> MessageVariant -> ordered MessagePart`.
//
// The graph shape is the truth source. Android may project it for presentation, but
// must not invent missing branch, selection, or typed-part semantics.
pub type AgentId = Uuid;
pub type TopicId = Uuid;
pub type ConversationId = Uuid;
pub type NodeId = Uuid;
pub type VariantId = Uuid;
pub type PartId = Uuid;
pub type ProviderLocalId = String;
pub type ProviderPresetLocalId = String;

pub const PROVIDER_LOCAL_ID_PREFIX: &str = "provider_local_";
pub const PROVIDER_PRESET_LOCAL_ID_PREFIX: &str = "provider_preset_local_";

pub const DOCUMENT_MIME_TEXT_PLAIN: &str = "text/plain";
pub const DOCUMENT_MIME_TEXT_MARKDOWN: &str = "text/markdown";
pub const DOCUMENT_MIME_APPLICATION_PDF: &str = "application/pdf";
pub const DOCUMENT_MIME_DOCX: &str =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
pub const DOCUMENT_MIME_PPTX: &str =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation";

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
    Requesting,
    Started,
    Streaming,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum GenerationSignal {
    Submit,
    Started,
    Delta,
    Complete,
    Fail,
    Cancel,
    Reset,
}

impl GenerationState {
    pub fn transition(self, signal: GenerationSignal) -> Self {
        match signal {
            GenerationSignal::Submit => {
                if self.is_active() {
                    self
                } else {
                    Self::Requesting
                }
            }
            GenerationSignal::Started => match self {
                Self::Requesting => Self::Started,
                _ => self,
            },
            GenerationSignal::Delta => match self {
                Self::Requesting | Self::Started | Self::Streaming => Self::Streaming,
                _ => self,
            },
            GenerationSignal::Complete => match self {
                Self::Requesting | Self::Started | Self::Streaming => Self::Completed,
                _ => self,
            },
            GenerationSignal::Fail => match self {
                Self::Requesting | Self::Started | Self::Streaming => Self::Failed,
                _ => self,
            },
            GenerationSignal::Cancel => match self {
                Self::Requesting | Self::Started | Self::Streaming => Self::Cancelled,
                _ => self,
            },
            GenerationSignal::Reset => Self::Idle,
        }
    }

    pub fn is_active(self) -> bool {
        matches!(self, Self::Requesting | Self::Started | Self::Streaming)
    }

    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Failed | Self::Cancelled)
    }

    pub fn can_resume(self) -> bool {
        matches!(self, Self::Requesting | Self::Started | Self::Streaming)
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ToolPartState {
    Pending,
    Running,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ProviderAdapterKind {
    #[serde(rename = "openai_compatible")]
    OpenAiCompatible,
    #[serde(rename = "google_compatible")]
    GoogleCompatible,
    #[serde(rename = "anthropic_compatible")]
    AnthropicCompatible,
    #[serde(rename = "vcptoolbox")]
    VcpToolBox,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentProfile {
    pub id: AgentId,
    pub name: String,
    #[serde(default)]
    pub avatar_uri: Option<String>,
    pub description: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentIdentityConfig {
    pub name: String,
    #[serde(default)]
    pub avatar_uri: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AgentPromptMode {
    #[default]
    SystemOnly,
    SystemAndMessageTemplate,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentPromptVariable {
    pub key: String,
    #[serde(default)]
    pub label: Option<String>,
    pub value: String,
    #[serde(default)]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentPromptConfig {
    pub system_prompt: String,
    #[serde(default)]
    pub prompt_mode: AgentPromptMode,
    #[serde(default)]
    pub message_template: Option<String>,
    #[serde(default)]
    pub placeholders: Vec<AgentPromptVariable>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentModelConfig {
    #[serde(default)]
    pub provider_local_id: Option<ProviderLocalId>,
    #[serde(default)]
    pub preset_local_id: Option<ProviderPresetLocalId>,
    #[serde(default)]
    pub model_id: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AgentReasoningEffort {
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct AgentRequestConfig {
    #[serde(default)]
    pub temperature: Option<f32>,
    #[serde(default)]
    pub top_p: Option<f32>,
    #[serde(default)]
    pub max_output_tokens: Option<u32>,
    #[serde(default)]
    pub reasoning_effort: Option<AgentReasoningEffort>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentMemoryConfig {
    #[serde(default = "default_true")]
    pub use_conversation_memory: bool,
    #[serde(default)]
    pub pin_top_level_facts: bool,
}

impl Default for AgentMemoryConfig {
    fn default() -> Self {
        Self {
            use_conversation_memory: default_true(),
            pin_top_level_facts: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentToolPermission {
    pub tool_id: String,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentToolConfig {
    #[serde(default = "default_true")]
    pub enable_local_tools: bool,
    #[serde(default)]
    pub overrides: Vec<AgentToolPermission>,
}

impl Default for AgentToolConfig {
    fn default() -> Self {
        Self {
            enable_local_tools: default_true(),
            overrides: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AgentGroupConfig {
    #[serde(default)]
    pub role_label: Option<String>,
    #[serde(default)]
    pub aliases: Vec<String>,
    #[serde(default)]
    pub mention_tags: Vec<String>,
    #[serde(default = "default_true")]
    pub respond_to_mentions: bool,
    #[serde(default)]
    pub allow_auto_relay: bool,
}

impl Default for AgentGroupConfig {
    fn default() -> Self {
        Self {
            role_label: None,
            aliases: Vec::new(),
            mention_tags: Vec::new(),
            respond_to_mentions: default_true(),
            allow_auto_relay: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AgentConfig {
    pub id: AgentId,
    pub identity: AgentIdentityConfig,
    #[serde(default)]
    pub prompt: AgentPromptConfig,
    #[serde(default)]
    pub model: AgentModelConfig,
    #[serde(default)]
    pub request: AgentRequestConfig,
    #[serde(default)]
    pub memory: AgentMemoryConfig,
    #[serde(default)]
    pub tools: AgentToolConfig,
    #[serde(default)]
    pub group: AgentGroupConfig,
    #[serde(default = "current_timestamp")]
    pub created_at: DateTime<Utc>,
    #[serde(default = "current_timestamp")]
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AgentConfigValidationError {
    EmptyField {
        section: &'static str,
        field: &'static str,
    },
    EmptyListValue {
        section: &'static str,
        field: &'static str,
        index: usize,
    },
    InvalidRange {
        section: &'static str,
        field: &'static str,
        min: f32,
        max: f32,
        actual: f32,
    },
}

impl fmt::Display for AgentConfigValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyField { section, field } => {
                write!(f, "{section}.{field} must be non-empty")
            }
            Self::EmptyListValue {
                section,
                field,
                index,
            } => write!(f, "{section}.{field}[{index}] must be non-empty"),
            Self::InvalidRange {
                section,
                field,
                min,
                max,
                actual,
            } => write!(
                f,
                "{section}.{field} must be between {min} and {max}, got {actual}"
            ),
        }
    }
}

impl Error for AgentConfigValidationError {}

impl AgentConfig {
    pub fn new(name: impl Into<String>, system_prompt: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id: AgentId::new_v4(),
            identity: AgentIdentityConfig {
                name: name.into(),
                avatar_uri: None,
                description: None,
            },
            prompt: AgentPromptConfig {
                system_prompt: system_prompt.into(),
                ..AgentPromptConfig::default()
            },
            model: AgentModelConfig::default(),
            request: AgentRequestConfig::default(),
            memory: AgentMemoryConfig::default(),
            tools: AgentToolConfig::default(),
            group: AgentGroupConfig::default(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn profile(&self) -> AgentProfile {
        AgentProfile {
            id: self.id,
            name: self.identity.name.clone(),
            avatar_uri: self.identity.avatar_uri.clone(),
            description: self.identity.description.clone().unwrap_or_default(),
        }
    }

    pub fn validate(&self) -> Result<(), AgentConfigValidationError> {
        require_non_empty_agent("identity", "name", &self.identity.name)?;
        require_non_empty_agent("prompt", "system_prompt", &self.prompt.system_prompt)?;

        if let Some(message_template) = &self.prompt.message_template {
            require_non_empty_agent("prompt", "message_template", message_template)?;
        }

        if let Some(provider_local_id) = &self.model.provider_local_id {
            require_non_empty_agent("model", "provider_local_id", provider_local_id)?;
        }
        if let Some(preset_local_id) = &self.model.preset_local_id {
            require_non_empty_agent("model", "preset_local_id", preset_local_id)?;
        }
        if let Some(model_id) = &self.model.model_id {
            require_non_empty_agent("model", "model_id", model_id)?;
        }

        for (index, placeholder) in self.prompt.placeholders.iter().enumerate() {
            if placeholder.key.trim().is_empty() {
                return Err(AgentConfigValidationError::EmptyListValue {
                    section: "prompt",
                    field: "placeholders.key",
                    index,
                });
            }
        }

        for (index, tool_override) in self.tools.overrides.iter().enumerate() {
            if tool_override.tool_id.trim().is_empty() {
                return Err(AgentConfigValidationError::EmptyListValue {
                    section: "tools",
                    field: "overrides.tool_id",
                    index,
                });
            }
        }

        for (index, alias) in self.group.aliases.iter().enumerate() {
            if alias.trim().is_empty() {
                return Err(AgentConfigValidationError::EmptyListValue {
                    section: "group",
                    field: "aliases",
                    index,
                });
            }
        }

        for (index, tag) in self.group.mention_tags.iter().enumerate() {
            if tag.trim().is_empty() {
                return Err(AgentConfigValidationError::EmptyListValue {
                    section: "group",
                    field: "mention_tags",
                    index,
                });
            }
        }

        if let Some(temperature) = self.request.temperature {
            require_range("request", "temperature", temperature, 0.0, 2.0)?;
        }
        if let Some(top_p) = self.request.top_p {
            require_range("request", "top_p", top_p, 0.0, 1.0)?;
        }

        Ok(())
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum PlaceholderCategory {
    Agent,
    Generic,
    Plugin,
    Static,
    StickerMedia,
}

impl PlaceholderCategory {
    pub fn resolution_rank(self) -> u8 {
        match self {
            Self::Agent => 0,
            Self::Generic => 1,
            Self::Plugin => 2,
            Self::Static => 3,
            Self::StickerMedia => 4,
        }
    }

    pub fn participates_in_prompt_preview(self) -> bool {
        !matches!(self, Self::StickerMedia)
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PlaceholderSource {
    AgentProfile,
    AgentBinding,
    Conversation,
    Runtime,
    ProviderPreset,
    Plugin,
    StaticRegistry,
    StickerPack,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PromptPlaceholderValue {
    pub key: String,
    pub value: String,
    pub category: PlaceholderCategory,
    pub source: PlaceholderSource,
}

impl PromptPlaceholderValue {
    pub fn new(
        key: impl Into<String>,
        value: impl Into<String>,
        category: PlaceholderCategory,
        source: PlaceholderSource,
    ) -> Self {
        Self {
            key: key.into(),
            value: value.into(),
            category,
            source,
        }
    }

    pub fn canonical_token(&self) -> String {
        format!("{{{{{}}}}}", self.key)
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PromptResolutionStatus {
    Applied,
    Deferred,
    Shadowed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PromptResolutionRecord {
    pub key: String,
    pub value: String,
    pub category: PlaceholderCategory,
    pub source: PlaceholderSource,
    pub status: PromptResolutionStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PromptResolutionPreview {
    pub raw_prompt: String,
    pub resolved_prompt: String,
    pub records: Vec<PromptResolutionRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Topic {
    pub id: TopicId,
    pub agent_id: AgentId,
    pub title: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// Rust-owned conversation truth.
///
/// Invariants:
/// - A conversation owns one message graph; every referenced node/variant/part belongs to this
///   conversation through the node lineage.
/// - `current_cursor` is the leaf node of the active branch in Rust truth, or `None` only when the
///   conversation has not materialized any nodes yet.
/// - Active branch identity comes from Rust via the cursor plus each node's selected variant;
///   Android may project that path but must not invent it.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
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
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
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

/// One content revision or generation attempt for a node.
///
/// Invariants:
/// - A variant belongs to exactly one node and never changes `node_id`.
/// - Assistant regenerate/retry for the same logical turn appends a new variant and moves
///   selection at the node; previously selected variants remain immutable history.
/// - `Streaming` is the only non-terminal status. Terminal states should record `finished_at`.
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
        #[serde(default)]
        mime: Option<String>,
        alt: Option<String>,
    },
    Document {
        #[serde(alias = "name")]
        file_name: String,
        url: String,
        #[serde(default)]
        mime: Option<String>,
    },
    Tool {
        #[serde(default)]
        tool_call_id: Option<String>,
        tool_name: String,
        state: ToolPartState,
        #[serde(default, alias = "arguments_json")]
        input_json: String,
        #[serde(default, alias = "result_json")]
        output_json: Option<String>,
        #[serde(default)]
        error_message: Option<String>,
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DocumentDescriptor {
    pub name: String,
    pub mime: String,
    pub size_bytes: usize,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DocumentAttachmentInput {
    pub name: String,
    #[serde(default)]
    pub mime: Option<String>,
    pub content_base64: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DocumentPromptTransformStatus {
    Ready,
    ParseFailed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DocumentPromptTransformItem {
    pub document: DocumentDescriptor,
    pub status: DocumentPromptTransformStatus,
    pub prompt_text: String,
    pub extracted_char_count: usize,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DocumentPromptTransformOutput {
    pub items: Vec<DocumentPromptTransformItem>,
    pub combined_prompt_text: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MessagePartValidationError {
    NegativeOrderIndex {
        order_index: i32,
    },
    DuplicateOrderIndex {
        order_index: i32,
    },
    NonIncreasingOrderIndex {
        previous_order_index: i32,
        current_order_index: i32,
    },
    EmptyField {
        part_type: &'static str,
        field: &'static str,
    },
    MissingField {
        part_type: &'static str,
        field: &'static str,
    },
}

impl fmt::Display for MessagePartValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NegativeOrderIndex { order_index } => {
                write!(
                    f,
                    "message part order_index must be >= 0, got {order_index}"
                )
            }
            Self::DuplicateOrderIndex { order_index } => {
                write!(f, "message part order_index {order_index} is duplicated")
            }
            Self::NonIncreasingOrderIndex {
                previous_order_index,
                current_order_index,
            } => write!(
                f,
                "message part order_index must increase strictly, got {previous_order_index} then {current_order_index}"
            ),
            Self::EmptyField { part_type, field } => {
                write!(f, "{part_type} part requires non-empty {field}")
            }
            Self::MissingField { part_type, field } => {
                write!(f, "{part_type} part requires {field}")
            }
        }
    }
}

impl Error for MessagePartValidationError {}

/// Typed payload atom owned by one variant.
///
/// Invariants:
/// - A part belongs to exactly one variant and is never re-parented.
/// - `order_index` is unique within the variant and reflects append order.
/// - Typed payloads remain Rust truth even if Android later renders a flattened presentation.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MessagePart {
    pub id: PartId,
    pub variant_id: VariantId,
    pub order_index: i32,
    pub payload: MessagePartPayload,
}

impl MessagePartPayload {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Text { .. } => "text",
            Self::Reasoning { .. } => "reasoning",
            Self::Image { .. } => "image",
            Self::Document { .. } => "document",
            Self::Tool { .. } => "tool",
            Self::File { .. } => "file",
            Self::Quote { .. } => "quote",
            Self::CodeBlock { .. } => "code_block",
            Self::MarkdownBlock { .. } => "markdown_block",
            Self::Error { .. } => "error",
            Self::ToolCall { .. } => "tool_call",
            Self::ToolResult { .. } => "tool_result",
        }
    }

    pub fn validate(&self) -> Result<(), MessagePartValidationError> {
        match self {
            Self::Text { text } => require_non_empty("text", "text", text),
            Self::Reasoning { text } => require_non_empty("reasoning", "text", text),
            Self::Image { url, .. } => require_non_empty("image", "url", url),
            Self::Document { file_name, url, .. } => {
                require_non_empty("document", "file_name", file_name)?;
                require_non_empty("document", "url", url)
            }
            Self::Tool {
                tool_call_id,
                tool_name,
                input_json,
                output_json,
                error_message,
                state,
            } => {
                if let Some(tool_call_id) = tool_call_id {
                    require_non_empty("tool", "tool_call_id", tool_call_id)?;
                }
                require_non_empty("tool", "tool_name", tool_name)?;
                require_non_empty("tool", "input_json", input_json)?;
                if let Some(output_json) = output_json {
                    require_non_empty("tool", "output_json", output_json)?;
                }
                if let Some(error_message) = error_message {
                    require_non_empty("tool", "error_message", error_message)?;
                }
                if matches!(state, ToolPartState::Failed)
                    && error_message
                        .as_ref()
                        .is_none_or(|value| value.trim().is_empty())
                {
                    return Err(MessagePartValidationError::MissingField {
                        part_type: "tool",
                        field: "error_message",
                    });
                }
                Ok(())
            }
            Self::File { name, url, .. } => {
                require_non_empty("file", "name", name)?;
                require_non_empty("file", "url", url)
            }
            Self::Quote { text, .. } => require_non_empty("quote", "text", text),
            Self::CodeBlock { code, .. } => require_non_empty("code_block", "code", code),
            Self::MarkdownBlock { markdown } => {
                require_non_empty("markdown_block", "markdown", markdown)
            }
            Self::Error { message } => require_non_empty("error", "message", message),
            Self::ToolCall {
                tool_name,
                arguments_json,
            } => {
                require_non_empty("tool_call", "tool_name", tool_name)?;
                require_non_empty("tool_call", "arguments_json", arguments_json)
            }
            Self::ToolResult {
                tool_name,
                result_json,
            } => {
                require_non_empty("tool_result", "tool_name", tool_name)?;
                require_non_empty("tool_result", "result_json", result_json)
            }
        }
    }
}

impl MessagePart {
    pub fn validate(&self) -> Result<(), MessagePartValidationError> {
        if self.order_index < 0 {
            return Err(MessagePartValidationError::NegativeOrderIndex {
                order_index: self.order_index,
            });
        }
        self.payload.validate()
    }

    pub fn validate_sequence(parts: &[Self]) -> Result<(), MessagePartValidationError> {
        let mut seen = BTreeSet::new();
        let mut previous_order_index = None;

        for part in parts {
            part.validate()?;

            if !seen.insert(part.order_index) {
                return Err(MessagePartValidationError::DuplicateOrderIndex {
                    order_index: part.order_index,
                });
            }

            if let Some(previous_order_index) = previous_order_index {
                if part.order_index <= previous_order_index {
                    return Err(MessagePartValidationError::NonIncreasingOrderIndex {
                        previous_order_index,
                        current_order_index: part.order_index,
                    });
                }
            }

            previous_order_index = Some(part.order_index);
        }

        Ok(())
    }
}

fn require_non_empty(
    part_type: &'static str,
    field: &'static str,
    value: &str,
) -> Result<(), MessagePartValidationError> {
    if value.trim().is_empty() {
        Err(MessagePartValidationError::EmptyField { part_type, field })
    } else {
        Ok(())
    }
}

fn require_non_empty_agent(
    section: &'static str,
    field: &'static str,
    value: &str,
) -> Result<(), AgentConfigValidationError> {
    if value.trim().is_empty() {
        Err(AgentConfigValidationError::EmptyField { section, field })
    } else {
        Ok(())
    }
}

fn require_range(
    section: &'static str,
    field: &'static str,
    value: f32,
    min: f32,
    max: f32,
) -> Result<(), AgentConfigValidationError> {
    if !(min..=max).contains(&value) {
        Err(AgentConfigValidationError::InvalidRange {
            section,
            field,
            min,
            max,
            actual: value,
        })
    } else {
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DraftState {
    pub conversation_id: ConversationId,
    pub text: String,
    pub attachment_ids: Vec<String>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderBodyFragment {
    pub pointer: String,
    pub value: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderModelCatalogEntry {
    pub model_id: String,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProviderModelCatalog {
    #[serde(default)]
    pub default_model: Option<String>,
    #[serde(default)]
    pub entries: Vec<ProviderModelCatalogEntry>,
}

#[cfg(test)]
mod generation_state_tests {
    use super::{GenerationSignal, GenerationState};

    #[test]
    fn generation_state_machine_tracks_send_stream_terminal_states() {
        let requesting = GenerationState::Idle.transition(GenerationSignal::Submit);
        let started = requesting.transition(GenerationSignal::Started);
        let streaming = started.transition(GenerationSignal::Delta);
        let completed = streaming.transition(GenerationSignal::Complete);

        assert_eq!(requesting, GenerationState::Requesting);
        assert_eq!(started, GenerationState::Started);
        assert_eq!(streaming, GenerationState::Streaming);
        assert_eq!(completed, GenerationState::Completed);
        assert!(completed.is_terminal());
        assert!(!completed.is_active());
    }

    #[test]
    fn generation_state_machine_marks_failed_and_cancelled_as_terminal() {
        let failed = GenerationState::Requesting.transition(GenerationSignal::Fail);
        let cancelled = GenerationState::Started.transition(GenerationSignal::Cancel);

        assert_eq!(failed, GenerationState::Failed);
        assert_eq!(cancelled, GenerationState::Cancelled);
        assert!(failed.is_terminal());
        assert!(cancelled.is_terminal());
        assert!(!failed.can_resume());
        assert!(!cancelled.can_resume());
    }

    #[test]
    fn generation_state_machine_anchors_resume_to_named_inflight_states() {
        assert!(GenerationState::Requesting.can_resume());
        assert!(GenerationState::Started.can_resume());
        assert!(GenerationState::Streaming.can_resume());

        assert!(!GenerationState::Idle.can_resume());
        assert!(!GenerationState::Completed.can_resume());
        assert!(!GenerationState::Failed.can_resume());
        assert!(!GenerationState::Cancelled.can_resume());
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ProviderAuthConfig {
    #[default]
    None,
    BearerToken {
        token: String,
    },
    ApiKey {
        header_name: String,
        value: String,
    },
    Basic {
        username: String,
        password: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderPreset {
    #[serde(default)]
    pub local_id: ProviderPresetLocalId,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub model_id: Option<String>,
    #[serde(default)]
    pub headers: Vec<ProviderHeader>,
    #[serde(default)]
    pub body_fragments: Vec<ProviderBodyFragment>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProviderConfig {
    #[serde(default)]
    pub local_id: ProviderLocalId,
    pub adapter_kind: ProviderAdapterKind,
    #[serde(alias = "name")]
    pub display_name: String,
    #[serde(default)]
    pub avatar_uri: Option<String>,
    pub base_url: String,
    #[serde(default)]
    pub auth: ProviderAuthConfig,
    #[serde(default)]
    pub model_catalog: ProviderModelCatalog,
    #[serde(default)]
    pub custom_headers: Vec<ProviderHeader>,
    #[serde(default)]
    pub custom_body_fragments: Vec<ProviderBodyFragment>,
    #[serde(default)]
    pub presets: Vec<ProviderPreset>,
    #[serde(default)]
    pub default_preset_local_id: Option<ProviderPresetLocalId>,
    #[serde(default, alias = "legacy_keys")]
    pub reference_aliases: Vec<String>,
    #[serde(default = "current_timestamp")]
    pub created_at: DateTime<Utc>,
    #[serde(default = "current_timestamp")]
    pub updated_at: DateTime<Utc>,
}

impl ProviderPreset {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            local_id: new_provider_preset_local_id(),
            name: name.into(),
            description: None,
            model_id: None,
            headers: Vec::new(),
            body_fragments: Vec::new(),
        }
    }

    pub fn ensure_local_id(&mut self, provider_local_id: &str, ordinal: usize) {
        let local_id = self.local_id.trim().to_string();
        if local_id.is_empty() || !is_stable_local_id(&local_id, PROVIDER_PRESET_LOCAL_ID_PREFIX) {
            let name_seed = if !local_id.is_empty() {
                local_id
            } else {
                self.name.trim().to_string()
            };
            let seed = if name_seed.is_empty() {
                format!("{provider_local_id}:{ordinal}")
            } else {
                format!("{provider_local_id}:{name_seed}:{ordinal}")
            };
            self.local_id = migrated_provider_preset_local_id(&seed);
        }
    }
}

impl ProviderConfig {
    pub fn new(
        adapter_kind: ProviderAdapterKind,
        display_name: impl Into<String>,
        base_url: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        let base_url = base_url.into();
        let mut provider = Self {
            local_id: new_provider_local_id(),
            adapter_kind,
            display_name: display_name.into(),
            avatar_uri: None,
            base_url,
            auth: ProviderAuthConfig::default(),
            model_catalog: ProviderModelCatalog::default(),
            custom_headers: Vec::new(),
            custom_body_fragments: Vec::new(),
            presets: Vec::new(),
            default_preset_local_id: None,
            reference_aliases: Vec::new(),
            created_at: now,
            updated_at: now,
        };
        let base_url_alias = provider.base_url.clone();
        provider.register_reference_alias(base_url_alias);
        provider
    }

    pub fn ensure_stable_ids(&mut self, reference_seed: &str) {
        let seed = if reference_seed.trim().is_empty() {
            self.base_url.trim().to_string()
        } else {
            reference_seed.trim().to_string()
        };
        let base_url_alias = self.base_url.trim().to_string();
        let existing_local_id = self.local_id.trim().to_string();
        let requested_default_preset_id = self
            .default_preset_local_id
            .as_ref()
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());

        if existing_local_id.is_empty()
            || !is_stable_local_id(&existing_local_id, PROVIDER_LOCAL_ID_PREFIX)
        {
            self.local_id = if is_stable_local_id(&seed, PROVIDER_LOCAL_ID_PREFIX) {
                seed.clone()
            } else {
                migrated_provider_local_id(&seed)
            };
        }

        self.register_reference_alias(existing_local_id);
        self.register_reference_alias(seed);
        self.register_reference_alias(base_url_alias);

        let mut migrated_default_preset_id = None;
        for (index, preset) in self.presets.iter_mut().enumerate() {
            let previous_local_id = preset.local_id.trim().to_string();
            let previous_name = preset.name.trim().to_string();
            preset.ensure_local_id(&self.local_id, index);

            if migrated_default_preset_id.is_none()
                && requested_default_preset_id
                    .as_ref()
                    .is_some_and(|default_id| {
                        default_id == &preset.local_id
                            || default_id == &previous_local_id
                            || default_id == &previous_name
                    })
            {
                migrated_default_preset_id = Some(preset.local_id.clone());
            }
        }

        self.default_preset_local_id = requested_default_preset_id.and_then(|default_id| {
            self.presets
                .iter()
                .any(|preset| preset.local_id == default_id)
                .then_some(default_id)
                .or(migrated_default_preset_id)
        });
    }

    pub fn matches_reference(&self, reference: &str) -> bool {
        let reference = reference.trim();
        !reference.is_empty()
            && (self.local_id == reference
                || self
                    .reference_aliases
                    .iter()
                    .any(|alias| alias.as_str() == reference))
    }

    pub fn register_reference_alias(&mut self, alias: impl AsRef<str>) {
        let alias = alias.as_ref().trim();
        if alias.is_empty() || alias == self.local_id {
            return;
        }

        let mut aliases = self
            .reference_aliases
            .iter()
            .cloned()
            .collect::<BTreeSet<_>>();
        aliases.insert(alias.to_string());
        self.reference_aliases = aliases.into_iter().collect();
    }
}

pub fn new_provider_local_id() -> ProviderLocalId {
    prefixed_local_id(PROVIDER_LOCAL_ID_PREFIX, Uuid::new_v4())
}

pub fn new_provider_preset_local_id() -> ProviderPresetLocalId {
    prefixed_local_id(PROVIDER_PRESET_LOCAL_ID_PREFIX, Uuid::new_v4())
}

pub fn migrated_provider_local_id(seed: &str) -> ProviderLocalId {
    seeded_local_id(PROVIDER_LOCAL_ID_PREFIX, &format!("provider:{seed}"))
}

pub fn migrated_provider_preset_local_id(seed: &str) -> ProviderPresetLocalId {
    seeded_local_id(
        PROVIDER_PRESET_LOCAL_ID_PREFIX,
        &format!("provider_preset:{seed}"),
    )
}

fn prefixed_local_id(prefix: &str, id: Uuid) -> String {
    format!("{prefix}{}", id.simple())
}

fn seeded_local_id(prefix: &str, seed: &str) -> String {
    format!("{prefix}{}", stable_seed_hex(seed))
}

fn current_timestamp() -> DateTime<Utc> {
    Utc::now()
}

fn default_enabled() -> bool {
    true
}

fn default_true() -> bool {
    true
}

fn is_stable_local_id(value: &str, prefix: &str) -> bool {
    value.starts_with(prefix) && value.len() > prefix.len()
}

fn stable_seed_hex(seed: &str) -> String {
    format!(
        "{:016x}{:016x}",
        fnv1a_64(seed.as_bytes(), 0xcbf29ce484222325),
        fnv1a_64(seed.as_bytes(), 0x9ae16a3b2f90404f)
    )
}

fn fnv1a_64(bytes: &[u8], offset_basis: u64) -> u64 {
    const FNV_PRIME: u64 = 0x100000001b3;

    let mut hash = offset_basis;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sample_provider(base_url: &str) -> ProviderConfig {
        ProviderConfig::new(
            ProviderAdapterKind::OpenAiCompatible,
            "Sample Provider",
            base_url,
        )
    }

    #[test]
    fn agent_config_new_sets_required_identity_and_prompt_fields() {
        let agent = AgentConfig::new("VCP Planner", "You are a planner.");

        assert_eq!(agent.identity.name, "VCP Planner");
        assert_eq!(agent.prompt.system_prompt, "You are a planner.");
        assert!(agent.memory.use_conversation_memory);
        assert!(agent.tools.enable_local_tools);
        assert!(agent.group.respond_to_mentions);
    }

    #[test]
    fn agent_config_validation_rejects_blank_required_or_structural_fields() {
        let mut agent = AgentConfig::new("VCP Planner", "You are a planner.");
        agent.identity.name = "  ".to_string();
        assert_eq!(
            agent.validate(),
            Err(AgentConfigValidationError::EmptyField {
                section: "identity",
                field: "name",
            })
        );

        let mut agent = AgentConfig::new("VCP Planner", "You are a planner.");
        agent.prompt.placeholders.push(AgentPromptVariable {
            key: " ".to_string(),
            label: None,
            value: "Alice".to_string(),
            description: None,
        });
        assert_eq!(
            agent.validate(),
            Err(AgentConfigValidationError::EmptyListValue {
                section: "prompt",
                field: "placeholders.key",
                index: 0,
            })
        );
    }

    #[test]
    fn agent_config_validation_rejects_out_of_range_request_overrides() {
        let mut agent = AgentConfig::new("VCP Planner", "You are a planner.");
        agent.request.temperature = Some(2.5);

        assert_eq!(
            agent.validate(),
            Err(AgentConfigValidationError::InvalidRange {
                section: "request",
                field: "temperature",
                min: 0.0,
                max: 2.0,
                actual: 2.5,
            })
        );
    }

    #[test]
    fn ensure_stable_ids_keeps_stable_provider_id_across_base_url_edits() {
        let mut provider = sample_provider("https://old.example.com/v1");
        let local_id = provider.local_id.clone();
        let old_base_url = provider.base_url.clone();

        provider.base_url = "https://new.example.com/v1".to_string();
        provider.ensure_stable_ids(&local_id);

        assert_eq!(provider.local_id, local_id);
        assert!(provider.matches_reference(&provider.local_id));
        assert!(provider.matches_reference(&old_base_url));
        assert!(provider.matches_reference("https://new.example.com/v1"));
    }

    #[test]
    fn ensure_stable_ids_remaps_default_preset_reference_from_legacy_name() {
        let mut provider = sample_provider("https://preset-name.example.com/v1");
        let provider_local_id = provider.local_id.clone();

        let mut balanced = ProviderPreset::new("balanced");
        balanced.local_id.clear();
        let mut fast = ProviderPreset::new("fast");
        fast.local_id.clear();

        provider.presets = vec![balanced, fast];
        provider.default_preset_local_id = Some("balanced".to_string());

        provider.ensure_stable_ids(&provider_local_id);

        assert_eq!(
            provider.default_preset_local_id,
            Some(provider.presets[0].local_id.clone())
        );
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
    }

    #[test]
    fn ensure_stable_ids_remaps_default_preset_reference_from_nonstable_preset_id() {
        let mut provider = sample_provider("https://preset-id.example.com/v1");
        let provider_local_id = provider.local_id.clone();

        let mut balanced = ProviderPreset::new("balanced");
        balanced.local_id = "balanced-v1".to_string();
        let mut fast = ProviderPreset::new("fast");
        fast.local_id = "fast-v1".to_string();

        provider.presets = vec![balanced, fast];
        provider.default_preset_local_id = Some("balanced-v1".to_string());

        provider.ensure_stable_ids(&provider_local_id);

        assert_eq!(
            provider.default_preset_local_id,
            Some(provider.presets[0].local_id.clone())
        );
        assert_ne!(provider.presets[0].local_id, "balanced-v1");
        assert!(
            provider.presets[0]
                .local_id
                .starts_with(PROVIDER_PRESET_LOCAL_ID_PREFIX)
        );
    }

    #[test]
    fn placeholder_category_resolution_rank_is_frozen() {
        assert_eq!(PlaceholderCategory::Agent.resolution_rank(), 0);
        assert_eq!(PlaceholderCategory::Generic.resolution_rank(), 1);
        assert_eq!(PlaceholderCategory::Plugin.resolution_rank(), 2);
        assert_eq!(PlaceholderCategory::Static.resolution_rank(), 3);
        assert_eq!(PlaceholderCategory::StickerMedia.resolution_rank(), 4);
    }

    #[test]
    fn sticker_media_placeholders_are_excluded_from_prompt_preview() {
        assert!(PlaceholderCategory::Agent.participates_in_prompt_preview());
        assert!(PlaceholderCategory::Generic.participates_in_prompt_preview());
        assert!(PlaceholderCategory::Plugin.participates_in_prompt_preview());
        assert!(PlaceholderCategory::Static.participates_in_prompt_preview());
        assert!(!PlaceholderCategory::StickerMedia.participates_in_prompt_preview());
    }

    #[test]
    fn canonical_document_and_tool_parts_serialize_with_core_type_tags() {
        let document = MessagePartPayload::Document {
            file_name: "notes.pdf".to_string(),
            url: "file:///notes.pdf".to_string(),
            mime: Some("application/pdf".to_string()),
        };
        let tool = MessagePartPayload::Tool {
            tool_call_id: Some("call-1".to_string()),
            tool_name: "search_web".to_string(),
            state: ToolPartState::Completed,
            input_json: "{\"query\":\"rust\"}".to_string(),
            output_json: Some("{\"items\":1}".to_string()),
            error_message: None,
        };

        assert_eq!(
            serde_json::to_value(document).expect("serialize document"),
            json!({
                "type": "document",
                "file_name": "notes.pdf",
                "url": "file:///notes.pdf",
                "mime": "application/pdf"
            })
        );
        assert_eq!(
            serde_json::to_value(tool).expect("serialize tool"),
            json!({
                "type": "tool",
                "tool_call_id": "call-1",
                "tool_name": "search_web",
                "state": "completed",
                "input_json": "{\"query\":\"rust\"}",
                "output_json": "{\"items\":1}",
                "error_message": null
            })
        );
    }

    #[test]
    fn core_image_and_error_parts_keep_explicit_type_tags() {
        let image = MessagePartPayload::Image {
            url: "https://cdn.example.com/cat.png".to_string(),
            mime: Some("image/png".to_string()),
            alt: Some("cat preview".to_string()),
        };
        let error = MessagePartPayload::Error {
            message: "upstream exploded".to_string(),
        };

        assert_eq!(
            serde_json::to_value(image).expect("serialize image"),
            json!({
                "type": "image",
                "url": "https://cdn.example.com/cat.png",
                "mime": "image/png",
                "alt": "cat preview"
            })
        );
        assert_eq!(
            serde_json::to_value(error).expect("serialize error"),
            json!({
                "type": "error",
                "message": "upstream exploded"
            })
        );
    }

    #[test]
    fn validate_sequence_accepts_core_p0_part_progression() {
        let variant_id = VariantId::new_v4();
        let parts = vec![
            MessagePart {
                id: PartId::new_v4(),
                variant_id,
                order_index: 0,
                payload: MessagePartPayload::Reasoning {
                    text: "thinking".to_string(),
                },
            },
            MessagePart {
                id: PartId::new_v4(),
                variant_id,
                order_index: 1,
                payload: MessagePartPayload::Tool {
                    tool_call_id: Some("call-1".to_string()),
                    tool_name: "search_web".to_string(),
                    state: ToolPartState::Completed,
                    input_json: "{\"query\":\"rust\"}".to_string(),
                    output_json: Some("{\"items\":1}".to_string()),
                    error_message: None,
                },
            },
            MessagePart {
                id: PartId::new_v4(),
                variant_id,
                order_index: 2,
                payload: MessagePartPayload::Text {
                    text: "final answer".to_string(),
                },
            },
        ];

        MessagePart::validate_sequence(&parts).expect("valid P0 part sequence");
    }

    #[test]
    fn validate_sequence_rejects_duplicate_order_indexes() {
        let variant_id = VariantId::new_v4();
        let parts = vec![
            MessagePart {
                id: PartId::new_v4(),
                variant_id,
                order_index: 0,
                payload: MessagePartPayload::Text {
                    text: "first".to_string(),
                },
            },
            MessagePart {
                id: PartId::new_v4(),
                variant_id,
                order_index: 0,
                payload: MessagePartPayload::Text {
                    text: "second".to_string(),
                },
            },
        ];

        let error = MessagePart::validate_sequence(&parts).expect_err("duplicate order index");

        assert_eq!(
            error,
            MessagePartValidationError::DuplicateOrderIndex { order_index: 0 }
        );
    }

    #[test]
    fn tool_validation_requires_error_message_for_failed_state() {
        let error = MessagePartPayload::Tool {
            tool_call_id: Some("call-1".to_string()),
            tool_name: "search_web".to_string(),
            state: ToolPartState::Failed,
            input_json: "{\"query\":\"rust\"}".to_string(),
            output_json: None,
            error_message: None,
        }
        .validate()
        .expect_err("failed tools must carry an error message");

        assert_eq!(
            error,
            MessagePartValidationError::MissingField {
                part_type: "tool",
                field: "error_message",
            }
        );
    }

    #[test]
    fn document_validation_requires_file_name_and_url() {
        let error = MessagePartPayload::Document {
            file_name: " ".to_string(),
            url: "".to_string(),
            mime: None,
        }
        .validate()
        .expect_err("blank document metadata must fail");

        assert_eq!(
            error,
            MessagePartValidationError::EmptyField {
                part_type: "document",
                field: "file_name",
            }
        );
    }

    #[test]
    fn image_validation_requires_url() {
        let error = MessagePartPayload::Image {
            url: " ".to_string(),
            mime: Some("image/png".to_string()),
            alt: Some("cat preview".to_string()),
        }
        .validate()
        .expect_err("blank image url must fail");

        assert_eq!(
            error,
            MessagePartValidationError::EmptyField {
                part_type: "image",
                field: "url",
            }
        );
    }

    #[test]
    fn error_validation_requires_message() {
        let error = MessagePartPayload::Error {
            message: "".to_string(),
        }
        .validate()
        .expect_err("blank error message must fail");

        assert_eq!(
            error,
            MessagePartValidationError::EmptyField {
                part_type: "error",
                field: "message",
            }
        );
    }
}
