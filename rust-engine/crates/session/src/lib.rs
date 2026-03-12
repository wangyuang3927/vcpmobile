use base64::{Engine as _, engine::general_purpose::STANDARD};
use chrono::Utc;
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::{Cursor, Read},
};
use thiserror::Error;
use uuid::Uuid;
use vcpmobile_domain::{
    Conversation, ConversationId, DOCUMENT_MIME_APPLICATION_PDF, DOCUMENT_MIME_DOCX,
    DOCUMENT_MIME_PPTX, DOCUMENT_MIME_TEXT_MARKDOWN, DOCUMENT_MIME_TEXT_PLAIN,
    DocumentAttachmentInput, DocumentDescriptor, DocumentPromptTransformItem,
    DocumentPromptTransformOutput, DocumentPromptTransformStatus, GenerationSignal,
    GenerationState, MessageNode, MessagePart, MessagePartPayload, MessageRole, MessageVariant,
    NodeId, TopicId, VariantStatus,
};
use vcpmobile_protocol::{
    ChatEvent, EventEnvelope, NodeBundle, SnapshotBranch, SnapshotConversation, SnapshotNode,
    SnapshotPart, SnapshotVariant, VariantBundle,
};
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
    pub attachments: Vec<DocumentAttachmentInput>,
}

#[derive(Debug, Clone)]
pub struct SessionEditRequest {
    pub conversation_id: ConversationId,
    pub node_id: NodeId,
    pub text: String,
    pub attachments: Vec<DocumentAttachmentInput>,
}

#[derive(Debug, Clone)]
struct PreparedDocumentAttachment {
    descriptor: DocumentDescriptor,
    bytes: Vec<u8>,
}

#[derive(Debug, Error)]
pub enum DocumentTransformError {
    #[error("invalid base64 payload for {name}: {reason}")]
    InvalidBase64 { name: String, reason: String },
    #[error("unsupported document type for {name}: {mime}")]
    UnsupportedType { name: String, mime: String },
    #[error("invalid utf-8 content for {name}: {reason}")]
    InvalidUtf8 { name: String, reason: String },
    #[error("pdf extraction failed for {name}: {reason}")]
    PdfExtract { name: String, reason: String },
    #[error("zip parse failed for {name}: {reason}")]
    ZipParse { name: String, reason: String },
    #[error("xml parse failed for {name}: {reason}")]
    XmlParse { name: String, reason: String },
    #[error("document payload missing required path {path} in {name}")]
    MissingArchivePath { name: String, path: String },
}

#[derive(Debug, Error)]
pub enum SessionError {
    #[error("store error: {0}")]
    Store(#[from] StoreError),
    #[error("empty user text")]
    EmptyText,
    #[error("conversation not found: {0}")]
    ConversationNotFound(ConversationId),
    #[error("node not found in conversation {conversation_id}: {node_id}")]
    NodeNotFound {
        conversation_id: ConversationId,
        node_id: NodeId,
    },
    #[error("invalid conversation state for {conversation_id}: {reason}")]
    InvalidConversationState {
        conversation_id: ConversationId,
        reason: String,
    },
    #[error("document transform error: {0}")]
    DocumentTransform(#[from] DocumentTransformError),
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

    pub fn transform_document_prompt(
        &self,
        attachments: Vec<DocumentAttachmentInput>,
    ) -> Result<DocumentPromptTransformOutput, SessionError> {
        transform_document_prompt_output(&attachments).map_err(SessionError::from)
    }

    pub fn send_message(
        &self,
        request: SessionSendRequest,
    ) -> Result<Vec<EventEnvelope<ChatEvent>>, SessionError> {
        let text = request.text.trim();
        if text.is_empty() && request.attachments.is_empty() {
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
                    title: truncate_title(text, &request.attachments),
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

        let parent_cursor = validated_current_cursor(&stored)?;
        let user_node = build_user_node(
            stored.conversation.id,
            parent_cursor,
            text,
            &request.attachments,
            now,
        )?;
        validate_node_bundle_parts(&user_node)?;
        let user_node_id = user_node.node.id;
        let assistant_node = build_assistant_node(
            stored.conversation.id,
            Some(user_node_id),
            text,
            now,
            VariantStatus::Streaming,
            None,
        );
        validate_node_bundle_parts(&assistant_node)?;
        let assistant_node_id = assistant_node.node.id;
        let assistant_variant_id = assistant_node.variants[0].variant.id;
        let completed_assistant_node = finalize_assistant_node(&assistant_node, now);

        stored.conversation.current_cursor = Some(assistant_node_id);
        stored.conversation.generation_state = stored
            .conversation
            .generation_state
            .transition(GenerationSignal::Submit)
            .transition(GenerationSignal::Started);
        stored.conversation.updated_at = now;
        stored.nodes.push(user_node);
        stored.nodes.push(assistant_node.clone());

        let snapshot_nodes = selected_branch_snapshot_nodes(&stored)?;
        let assistant_delta_parts = streaming_delta_parts(&assistant_node)?;
        stored.conversation.generation_state = stored
            .conversation
            .generation_state
            .transition(GenerationSignal::Delta)
            .transition(GenerationSignal::Complete);
        if let Some(last_node) = stored.nodes.last_mut() {
            *last_node = completed_assistant_node.clone();
        }
        self.store.upsert_conversation(stored.clone())?;

        Ok(vec![
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::ConversationSnapshot {
                    conversation: SnapshotConversation::from(&inflight_conversation_projection(
                        &stored.conversation,
                    )),
                    branch: SnapshotBranch {
                        cursor_node_id: stored.conversation.current_cursor,
                        nodes: snapshot_nodes,
                    },
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
                    node: selected_node_projection(&completed_assistant_node)?,
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

    pub fn edit_message(
        &self,
        request: SessionEditRequest,
    ) -> Result<Vec<EventEnvelope<ChatEvent>>, SessionError> {
        let text = request.text.trim();
        if text.is_empty() && request.attachments.is_empty() {
            return Err(SessionError::EmptyText);
        }

        let now = Utc::now();
        let mut stored = self
            .store
            .get_conversation(request.conversation_id)?
            .ok_or(SessionError::ConversationNotFound(request.conversation_id))?;
        let branch_node_ids = selected_branch_node_ids(&stored)?;
        if !branch_node_ids.contains(&request.node_id) {
            return Err(SessionError::InvalidConversationState {
                conversation_id: request.conversation_id,
                reason: format!(
                    "node {} is not on the selected branch for edit",
                    request.node_id
                ),
            });
        }

        let target_index = stored
            .nodes
            .iter()
            .position(|bundle| bundle.node.id == request.node_id)
            .ok_or(SessionError::NodeNotFound {
                conversation_id: request.conversation_id,
                node_id: request.node_id,
            })?;
        let target_bundle = stored.nodes[target_index].clone();
        if target_bundle.node.role != MessageRole::User {
            return Err(SessionError::InvalidConversationState {
                conversation_id: request.conversation_id,
                reason: format!("node {} is not a user node", request.node_id),
            });
        }
        if user_parts_match_request(&target_bundle, text, &request.attachments)? {
            return Ok(Vec::new());
        }

        let edited_user_node = build_user_node(
            stored.conversation.id,
            target_bundle.node.parent_node_id,
            text,
            &request.attachments,
            now,
        )?;
        validate_node_bundle_parts(&edited_user_node)?;
        let edited_user_node_id = edited_user_node.node.id;

        let assistant_node = build_assistant_node(
            stored.conversation.id,
            Some(edited_user_node_id),
            text,
            now,
            VariantStatus::Streaming,
            None,
        );
        validate_node_bundle_parts(&assistant_node)?;
        let assistant_node_id = assistant_node.node.id;
        let assistant_variant_id = assistant_node.variants[0].variant.id;
        let completed_assistant_node = finalize_assistant_node(&assistant_node, now);

        stored.conversation.current_cursor = Some(assistant_node_id);
        stored.conversation.generation_state = stored
            .conversation
            .generation_state
            .transition(GenerationSignal::Submit)
            .transition(GenerationSignal::Started);
        stored.conversation.updated_at = now;
        stored.nodes.push(edited_user_node);
        stored.nodes.push(assistant_node.clone());

        let snapshot_nodes = selected_branch_snapshot_nodes(&stored)?;
        let assistant_delta_parts = streaming_delta_parts(&assistant_node)?;
        stored.conversation.generation_state = stored
            .conversation
            .generation_state
            .transition(GenerationSignal::Delta)
            .transition(GenerationSignal::Complete);
        if let Some(last_node) = stored.nodes.last_mut() {
            *last_node = completed_assistant_node.clone();
        }
        self.store.upsert_conversation(stored.clone())?;

        Ok(vec![
            EventEnvelope::new(
                Some(stored.conversation.id),
                ChatEvent::ConversationSnapshot {
                    conversation: SnapshotConversation::from(&inflight_conversation_projection(
                        &stored.conversation,
                    )),
                    branch: SnapshotBranch {
                        cursor_node_id: stored.conversation.current_cursor,
                        nodes: snapshot_nodes,
                    },
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
                    node: selected_node_projection(&completed_assistant_node)?,
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

fn truncate_title(text: &str, attachments: &[DocumentAttachmentInput]) -> String {
    let source = if text.is_empty() {
        attachments
            .first()
            .map(|attachment| attachment.name.as_str())
            .unwrap_or("文档输入")
    } else {
        text
    };
    let mut title = source.chars().take(18).collect::<String>();
    if source.chars().count() > 18 {
        title.push('…');
    }
    if title.is_empty() {
        "新对话".to_string()
    } else {
        title
    }
}

fn assistant_text_reply(user_text: &str) -> String {
    format!("Rust Engine 已接收你的消息：{user_text}\n\n来源：Rust session engine\n形态：text")
}

fn transform_document_prompt_output(
    attachments: &[DocumentAttachmentInput],
) -> Result<DocumentPromptTransformOutput, DocumentTransformError> {
    let mut items = Vec::with_capacity(attachments.len());

    for attachment in attachments {
        let prepared = match prepare_document_attachment(attachment) {
            Ok(prepared) => prepared,
            Err(error) => {
                let document = fallback_document_descriptor(attachment);
                items.push(DocumentPromptTransformItem {
                    document: document.clone(),
                    status: DocumentPromptTransformStatus::ParseFailed,
                    prompt_text: format_document_prompt_failure(&document, &error.to_string()),
                    extracted_char_count: 0,
                    error: Some(error.to_string()),
                });
                continue;
            }
        };
        let result = match extract_document_text(&prepared) {
            Ok(text) => {
                let prompt_text = format_document_prompt_text(&prepared.descriptor, &text);
                let extracted_char_count = text.chars().count();
                DocumentPromptTransformItem {
                    document: prepared.descriptor,
                    status: DocumentPromptTransformStatus::Ready,
                    prompt_text,
                    extracted_char_count,
                    error: None,
                }
            }
            Err(error) => {
                let prompt_text =
                    format_document_prompt_failure(&prepared.descriptor, &error.to_string());
                DocumentPromptTransformItem {
                    document: prepared.descriptor,
                    status: DocumentPromptTransformStatus::ParseFailed,
                    prompt_text,
                    extracted_char_count: 0,
                    error: Some(error.to_string()),
                }
            }
        };
        items.push(result);
    }

    let combined_prompt_text = items
        .iter()
        .map(|item| item.prompt_text.as_str())
        .collect::<Vec<_>>()
        .join("\n\n");

    Ok(DocumentPromptTransformOutput {
        items,
        combined_prompt_text,
    })
}

fn fallback_document_descriptor(attachment: &DocumentAttachmentInput) -> DocumentDescriptor {
    DocumentDescriptor {
        name: attachment.name.clone(),
        mime: normalized_document_mime(attachment),
        size_bytes: 0,
        sha256: "unavailable".to_string(),
    }
}

fn prepare_document_attachment(
    attachment: &DocumentAttachmentInput,
) -> Result<PreparedDocumentAttachment, DocumentTransformError> {
    let bytes = STANDARD
        .decode(attachment.content_base64.as_bytes())
        .map_err(|error| DocumentTransformError::InvalidBase64 {
            name: attachment.name.clone(),
            reason: error.to_string(),
        })?;
    let mime = normalized_document_mime(attachment);
    let sha256 = format!("{:x}", Sha256::digest(&bytes));

    Ok(PreparedDocumentAttachment {
        descriptor: DocumentDescriptor {
            name: attachment.name.clone(),
            mime,
            size_bytes: bytes.len(),
            sha256,
        },
        bytes,
    })
}

fn normalized_document_mime(attachment: &DocumentAttachmentInput) -> String {
    if let Some(mime) = attachment.mime.as_deref() {
        let trimmed = mime.trim().to_ascii_lowercase();
        if !trimmed.is_empty() {
            return trimmed;
        }
    }

    match attachment
        .name
        .rsplit('.')
        .next()
        .map(|ext| ext.trim().to_ascii_lowercase())
        .as_deref()
    {
        Some("txt") => DOCUMENT_MIME_TEXT_PLAIN.to_string(),
        Some("md") | Some("markdown") => DOCUMENT_MIME_TEXT_MARKDOWN.to_string(),
        Some("pdf") => DOCUMENT_MIME_APPLICATION_PDF.to_string(),
        Some("docx") => DOCUMENT_MIME_DOCX.to_string(),
        Some("pptx") => DOCUMENT_MIME_PPTX.to_string(),
        _ => attachment
            .mime
            .as_deref()
            .map(str::trim)
            .filter(|mime| !mime.is_empty())
            .unwrap_or("application/octet-stream")
            .to_ascii_lowercase(),
    }
}

fn extract_document_text(
    attachment: &PreparedDocumentAttachment,
) -> Result<String, DocumentTransformError> {
    match attachment.descriptor.mime.as_str() {
        DOCUMENT_MIME_TEXT_PLAIN | DOCUMENT_MIME_TEXT_MARKDOWN => {
            String::from_utf8(attachment.bytes.clone()).map_err(|error| {
                DocumentTransformError::InvalidUtf8 {
                    name: attachment.descriptor.name.clone(),
                    reason: error.to_string(),
                }
            })
        }
        DOCUMENT_MIME_APPLICATION_PDF => pdf_extract::extract_text_from_mem(&attachment.bytes)
            .map(normalize_extracted_text)
            .map_err(|error| DocumentTransformError::PdfExtract {
                name: attachment.descriptor.name.clone(),
                reason: error.to_string(),
            }),
        DOCUMENT_MIME_DOCX => extract_docx_text(attachment),
        DOCUMENT_MIME_PPTX => extract_pptx_text(attachment),
        other => Err(DocumentTransformError::UnsupportedType {
            name: attachment.descriptor.name.clone(),
            mime: other.to_string(),
        }),
    }
}

fn extract_docx_text(
    attachment: &PreparedDocumentAttachment,
) -> Result<String, DocumentTransformError> {
    extract_zip_xml_text(
        attachment,
        &["word/document.xml", "word/header1.xml", "word/footer1.xml"],
        &["t"],
    )
}

fn extract_pptx_text(
    attachment: &PreparedDocumentAttachment,
) -> Result<String, DocumentTransformError> {
    let cursor = Cursor::new(&attachment.bytes);
    let mut archive =
        zip::ZipArchive::new(cursor).map_err(|error| DocumentTransformError::ZipParse {
            name: attachment.descriptor.name.clone(),
            reason: error.to_string(),
        })?;
    let mut slide_names = archive
        .file_names()
        .filter(|name| name.starts_with("ppt/slides/slide") && name.ends_with(".xml"))
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();
    slide_names.sort();
    if slide_names.is_empty() {
        return Err(DocumentTransformError::MissingArchivePath {
            name: attachment.descriptor.name.clone(),
            path: "ppt/slides/slide*.xml".to_string(),
        });
    }

    let mut slides = Vec::with_capacity(slide_names.len());
    for slide_name in slide_names {
        let xml = read_zip_entry(
            &mut archive,
            &slide_name,
            attachment.descriptor.name.as_str(),
        )?;
        let text = extract_text_nodes(&xml, &["t"], attachment.descriptor.name.as_str())?;
        let trimmed = text.trim();
        if !trimmed.is_empty() {
            slides.push(trimmed.to_string());
        }
    }

    Ok(slides.join("\n\n"))
}

fn extract_zip_xml_text(
    attachment: &PreparedDocumentAttachment,
    candidate_paths: &[&str],
    tag_suffixes: &[&str],
) -> Result<String, DocumentTransformError> {
    let cursor = Cursor::new(&attachment.bytes);
    let mut archive =
        zip::ZipArchive::new(cursor).map_err(|error| DocumentTransformError::ZipParse {
            name: attachment.descriptor.name.clone(),
            reason: error.to_string(),
        })?;

    let mut sections = Vec::new();
    let mut found_any = false;
    for path in candidate_paths {
        if archive.by_name(path).is_ok() {
            found_any = true;
            let xml = read_zip_entry(&mut archive, path, attachment.descriptor.name.as_str())?;
            let text = extract_text_nodes(&xml, tag_suffixes, attachment.descriptor.name.as_str())?;
            let trimmed = text.trim();
            if !trimmed.is_empty() {
                sections.push(trimmed.to_string());
            }
        }
    }

    if !found_any {
        return Err(DocumentTransformError::MissingArchivePath {
            name: attachment.descriptor.name.clone(),
            path: candidate_paths.join(", "),
        });
    }

    Ok(sections.join("\n\n"))
}

fn read_zip_entry<R: Read + std::io::Seek>(
    archive: &mut zip::ZipArchive<R>,
    path: &str,
    name: &str,
) -> Result<String, DocumentTransformError> {
    let mut file = archive
        .by_name(path)
        .map_err(|error| DocumentTransformError::ZipParse {
            name: name.to_string(),
            reason: error.to_string(),
        })?;
    let mut xml = String::new();
    file.read_to_string(&mut xml)
        .map_err(|error| DocumentTransformError::ZipParse {
            name: name.to_string(),
            reason: error.to_string(),
        })?;
    Ok(xml)
}

fn extract_text_nodes(
    xml: &str,
    tag_suffixes: &[&str],
    name: &str,
) -> Result<String, DocumentTransformError> {
    let document =
        roxmltree::Document::parse(xml).map_err(|error| DocumentTransformError::XmlParse {
            name: name.to_string(),
            reason: error.to_string(),
        })?;
    let mut text = String::new();

    for node in document.descendants().filter(|node| node.is_element()) {
        let tag_name = node.tag_name().name();
        if tag_suffixes.iter().any(|suffix| tag_name.ends_with(suffix)) {
            if let Some(content) = node.text() {
                text.push_str(content);
            }
        } else if tag_name == "p" && !text.ends_with('\n') {
            text.push('\n');
        }
    }

    Ok(normalize_extracted_text(text))
}

fn normalize_extracted_text(text: String) -> String {
    let mut normalized = String::new();
    let mut previous_blank = false;

    for line in text.lines() {
        let trimmed = line.trim_end();
        if trimmed.is_empty() {
            if !previous_blank && !normalized.is_empty() {
                normalized.push('\n');
            }
            previous_blank = true;
            continue;
        }
        if !normalized.is_empty() {
            normalized.push('\n');
        }
        normalized.push_str(trimmed);
        previous_blank = false;
    }

    normalized.trim().to_string()
}

fn format_document_prompt_text(document: &DocumentDescriptor, text: &str) -> String {
    format!(
        "[Document: {name}]\nMIME: {mime}\nSHA256: {sha256}\n\n{text}",
        name = document.name,
        mime = document.mime,
        sha256 = document.sha256,
    )
}

fn format_document_prompt_failure(document: &DocumentDescriptor, error: &str) -> String {
    format!(
        "[Document Parse Failure: {name}]\nMIME: {mime}\nSHA256: {sha256}\nReason: {error}",
        name = document.name,
        mime = document.mime,
        sha256 = document.sha256,
        error = error,
    )
}

fn build_user_node(
    conversation_id: ConversationId,
    parent_node_id: Option<NodeId>,
    text: &str,
    attachments: &[DocumentAttachmentInput],
    now: chrono::DateTime<Utc>,
) -> Result<NodeBundle, SessionError> {
    let node_id = NodeId::new_v4();
    let variant_id = Uuid::new_v4();
    let parts = build_user_parts(variant_id, text, attachments)?;

    Ok(NodeBundle {
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
            parts,
        }],
    })
}

fn build_user_parts(
    variant_id: Uuid,
    text: &str,
    attachments: &[DocumentAttachmentInput],
) -> Result<Vec<MessagePart>, SessionError> {
    let mut parts = Vec::new();

    if !text.is_empty() {
        parts.push(MessagePart {
            id: Uuid::new_v4(),
            variant_id,
            order_index: 0,
            payload: MessagePartPayload::Text {
                text: text.to_string(),
            },
        });
    }

    for attachment in attachments {
        let prepared = prepare_document_attachment(attachment)?;
        parts.push(MessagePart {
            id: Uuid::new_v4(),
            variant_id,
            order_index: parts.len() as i32,
            payload: MessagePartPayload::Document {
                file_name: prepared.descriptor.name.clone(),
                url: attachment_document_url(&prepared.descriptor),
                mime: Some(prepared.descriptor.mime.clone()),
            },
        });
    }

    Ok(parts)
}

fn attachment_document_url(document: &DocumentDescriptor) -> String {
    format!("attachment://sha256/{}", document.sha256)
}

fn build_assistant_node(
    conversation_id: ConversationId,
    parent_node_id: Option<NodeId>,
    text: &str,
    now: chrono::DateTime<Utc>,
    status: VariantStatus,
    finished_at: Option<chrono::DateTime<Utc>>,
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
                status,
                model_id: Some("rust-session-engine".to_string()),
                usage_json: None,
                created_at: now,
                finished_at,
            },
            parts: vec![
                MessagePart {
                    id: Uuid::new_v4(),
                    variant_id,
                    order_index: 0,
                    payload: MessagePartPayload::Reasoning {
                        text: format!("正在基于用户消息生成回复：{}", truncate_title(text, &[])),
                    },
                },
                MessagePart {
                    id: Uuid::new_v4(),
                    variant_id,
                    order_index: 1,
                    payload: MessagePartPayload::Text {
                        text: assistant_text_reply(text),
                    },
                },
            ],
        }],
    }
}

fn finalize_assistant_node(bundle: &NodeBundle, finished_at: chrono::DateTime<Utc>) -> NodeBundle {
    let mut finalized = bundle.clone();
    if let Some(selected_variant) = finalized.variants.get_mut(finalized.node.select_index) {
        selected_variant.variant.status = VariantStatus::Completed;
        selected_variant.variant.finished_at = Some(finished_at);
    }
    finalized
}

fn inflight_conversation_projection(conversation: &Conversation) -> Conversation {
    let mut projection = conversation.clone();
    projection.generation_state = GenerationState::Started;
    projection
}

pub fn selected_branch_snapshot_nodes(
    stored: &StoredConversation,
) -> Result<Vec<SnapshotNode>, SessionError> {
    let Some(mut current_cursor) = validated_current_cursor(stored)? else {
        return Ok(Vec::new());
    };
    let node_index = stored_node_index(stored);
    let mut ordered_branch = Vec::new();
    let mut visited = BTreeSet::new();

    loop {
        if !visited.insert(current_cursor) {
            return Err(SessionError::InvalidConversationState {
                conversation_id: stored.conversation.id,
                reason: format!("cycle detected while resolving branch at node {current_cursor}"),
            });
        }
        let bundle = node_index.get(&current_cursor).ok_or_else(|| {
            SessionError::InvalidConversationState {
                conversation_id: stored.conversation.id,
                reason: format!("node {current_cursor} missing from stored conversation"),
            }
        })?;
        if bundle.node.conversation_id != stored.conversation.id {
            return Err(SessionError::InvalidConversationState {
                conversation_id: stored.conversation.id,
                reason: format!(
                    "node {current_cursor} belongs to conversation {}",
                    bundle.node.conversation_id
                ),
            });
        }
        ordered_branch.push(selected_node_projection(bundle)?);

        match bundle.node.parent_node_id {
            Some(parent_node_id) => current_cursor = parent_node_id,
            None => break,
        }
    }

    ordered_branch.reverse();
    Ok(ordered_branch)
}

fn validated_current_cursor(stored: &StoredConversation) -> Result<Option<NodeId>, SessionError> {
    let Some(current_cursor) = stored.conversation.current_cursor else {
        return Ok(None);
    };
    let node_index = stored_node_index(stored);
    let Some(bundle) = node_index.get(&current_cursor) else {
        return Err(SessionError::InvalidConversationState {
            conversation_id: stored.conversation.id,
            reason: format!("current_cursor {current_cursor} does not resolve to a stored node"),
        });
    };
    if bundle.node.conversation_id != stored.conversation.id {
        return Err(SessionError::InvalidConversationState {
            conversation_id: stored.conversation.id,
            reason: format!(
                "current_cursor {current_cursor} resolves to node in conversation {}",
                bundle.node.conversation_id
            ),
        });
    }
    let has_child = stored.nodes.iter().any(|candidate| {
        candidate.node.conversation_id == stored.conversation.id
            && candidate.node.parent_node_id == Some(current_cursor)
    });
    if has_child {
        return Err(SessionError::InvalidConversationState {
            conversation_id: stored.conversation.id,
            reason: format!("current_cursor {current_cursor} is not a leaf node"),
        });
    }
    Ok(Some(current_cursor))
}

fn selected_branch_node_ids(stored: &StoredConversation) -> Result<BTreeSet<NodeId>, SessionError> {
    let Some(mut current_cursor) = validated_current_cursor(stored)? else {
        return Ok(BTreeSet::new());
    };
    let node_index = stored_node_index(stored);
    let mut ordered_branch = BTreeSet::new();
    let mut visited = BTreeSet::new();

    loop {
        if !visited.insert(current_cursor) {
            return Err(SessionError::InvalidConversationState {
                conversation_id: stored.conversation.id,
                reason: format!("cycle detected while resolving branch at node {current_cursor}"),
            });
        }
        let bundle = node_index.get(&current_cursor).ok_or_else(|| {
            SessionError::InvalidConversationState {
                conversation_id: stored.conversation.id,
                reason: format!("node {current_cursor} missing from stored conversation"),
            }
        })?;
        if bundle.node.conversation_id != stored.conversation.id {
            return Err(SessionError::InvalidConversationState {
                conversation_id: stored.conversation.id,
                reason: format!(
                    "node {current_cursor} belongs to conversation {}",
                    bundle.node.conversation_id
                ),
            });
        }
        ordered_branch.insert(current_cursor);

        match bundle.node.parent_node_id {
            Some(parent_node_id) => current_cursor = parent_node_id,
            None => break,
        }
    }

    Ok(ordered_branch)
}

fn stored_node_index(stored: &StoredConversation) -> BTreeMap<NodeId, &NodeBundle> {
    stored
        .nodes
        .iter()
        .map(|bundle| (bundle.node.id, bundle))
        .collect()
}

fn selected_variant(bundle: &NodeBundle) -> Result<&VariantBundle, SessionError> {
    bundle
        .variants
        .get(bundle.node.select_index)
        .ok_or_else(|| SessionError::InvalidConversationState {
            conversation_id: bundle.node.conversation_id,
            reason: format!(
                "node {} select_index {} out of range for {} variants",
                bundle.node.id,
                bundle.node.select_index,
                bundle.variants.len()
            ),
        })
}

fn selected_node_projection(bundle: &NodeBundle) -> Result<SnapshotNode, SessionError> {
    let selected_variant = selected_variant(bundle)?.clone();
    validate_variant_parts(
        bundle.node.conversation_id,
        bundle.node.id,
        &selected_variant.parts,
    )?;

    Ok(SnapshotNode {
        node_id: bundle.node.id,
        parent_node_id: bundle.node.parent_node_id,
        role: bundle.node.role,
        created_at: bundle.node.created_at,
        updated_at: bundle.node.updated_at,
        selected_variant: SnapshotVariant {
            variant_id: selected_variant.variant.id,
            status: selected_variant.variant.status,
            model_id: selected_variant.variant.model_id.clone(),
            usage_json: selected_variant.variant.usage_json.clone(),
            created_at: selected_variant.variant.created_at,
            finished_at: selected_variant.variant.finished_at,
            parts: selected_variant
                .parts
                .iter()
                .map(SnapshotPart::from)
                .collect(),
        },
    })
}

fn streaming_delta_parts(bundle: &NodeBundle) -> Result<Vec<MessagePart>, SessionError> {
    let selected_variant = selected_variant(bundle)?;
    validate_variant_parts(
        bundle.node.conversation_id,
        bundle.node.id,
        &selected_variant.parts,
    )?;

    Ok(selected_variant.parts.clone())
}

fn validate_node_bundle_parts(bundle: &NodeBundle) -> Result<(), SessionError> {
    for variant in &bundle.variants {
        validate_variant_parts(bundle.node.conversation_id, bundle.node.id, &variant.parts)?;
    }
    Ok(())
}

fn validate_variant_parts(
    conversation_id: ConversationId,
    node_id: NodeId,
    parts: &[MessagePart],
) -> Result<(), SessionError> {
    MessagePart::validate_sequence(parts).map_err(|error| SessionError::InvalidConversationState {
        conversation_id,
        reason: format!("node {node_id} has invalid part sequence: {error}"),
    })
}

fn user_parts_match_request(
    bundle: &NodeBundle,
    text: &str,
    attachments: &[DocumentAttachmentInput],
) -> Result<bool, SessionError> {
    let selected_variant = selected_variant(bundle)?;
    let candidate = build_user_parts(Uuid::nil(), text, attachments)?;

    Ok(selected_variant
        .parts
        .iter()
        .map(message_part_signature)
        .collect::<Vec<_>>()
        == candidate
            .iter()
            .map(message_part_signature)
            .collect::<Vec<_>>())
}

fn message_part_signature(part: &MessagePart) -> (i32, MessagePartPayload) {
    (part.order_index, part.payload.clone())
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
                conversation: SnapshotConversation::from(&conversation),
                branch: SnapshotBranch {
                    cursor_node_id: conversation.current_cursor,
                    nodes: vec![
                        selected_node_projection(&node_bundle).expect("demo node projection"),
                    ],
                },
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
                    payload: MessagePartPayload::Text {
                        text: "你好，这是一条来自 Rust Engine 的 demo 流式消息。".to_string(),
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
    use std::{
        env, fs,
        io::{Cursor, Write},
    };

    fn test_engine() -> SessionEngine {
        let path = env::temp_dir().join(format!("vcpmobile-session-test-{}.json", Uuid::new_v4()));
        let store = FileStore::new(path);
        SessionEngine::new(store, Uuid::new_v4(), Uuid::new_v4())
    }

    fn attachment(name: &str, mime: Option<&str>, bytes: &[u8]) -> DocumentAttachmentInput {
        DocumentAttachmentInput {
            name: name.to_string(),
            mime: mime.map(str::to_string),
            content_base64: STANDARD.encode(bytes),
        }
    }

    fn build_zip(entries: &[(&str, &str)]) -> Vec<u8> {
        let cursor = Cursor::new(Vec::new());
        let mut writer = zip::ZipWriter::new(cursor);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);

        for (path, body) in entries {
            writer.start_file(path, options).expect("start zip file");
            writer
                .write_all(body.as_bytes())
                .expect("write zip file body");
        }

        writer.finish().expect("finish zip").into_inner()
    }

    fn docx_bytes() -> Vec<u8> {
        build_zip(&[(
            "word/document.xml",
            r#"<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Hello DOCX</w:t></w:r></w:p><w:p><w:r><w:t>Second line</w:t></w:r></w:p></w:body></w:document>"#,
        )])
    }

    fn pptx_bytes() -> Vec<u8> {
        build_zip(&[
            (
                "ppt/slides/slide1.xml",
                r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Hello PPTX</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"#,
            ),
            (
                "ppt/slides/slide2.xml",
                r#"<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>Second slide</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"#,
            ),
        ])
    }

    fn pdf_bytes() -> Vec<u8> {
        STANDARD
            .decode("JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2JqCjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCAzMDAgMTQ0XSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4gPj4gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCA0MSA+PgpzdHJlYW0KQlQKL0YxIDI0IFRmCjcyIDEwMCBUZAooSGVsbG8gUERGKSBUagpFVAoKZW5kc3RyZWFtCmVuZG9iago1IDAgb2JqCjw8IC9UeXBlIC9Gb250IC9TdWJ0eXBlIC9UeXBlMSAvQmFzZUZvbnQgL0hlbHZldGljYSA+PgplbmRvYmoKeHJlZgowIDYKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDA5IDAwMDAwIG4gCjAwMDAwMDAwNTggMDAwMDAgbiAKMDAwMDAwMDExNSAwMDAwMCBuIAowMDAwMDAwMjQxIDAwMDAwIG4gCjAwMDAwMDAzMzIgMDAwMDAgbiAKdHJhaWxlcgo8PCAvU2l6ZSA2IC9Sb290IDEgMCBSID4+CnN0YXJ0eHJlZgo0MDIKJSVFT0YK")
            .expect("decode pdf fixture")
    }

    #[test]
    fn transform_document_prompt_extracts_plain_text_with_file_framing() {
        let engine = test_engine();
        let output = engine
            .transform_document_prompt(vec![attachment(
                "notes.txt",
                Some(DOCUMENT_MIME_TEXT_PLAIN),
                b"hello\nworld",
            )])
            .expect("transform plain text");

        assert_eq!(output.items.len(), 1);
        assert_eq!(output.items[0].status, DocumentPromptTransformStatus::Ready);
        assert_eq!(
            output.items[0].extracted_char_count,
            "hello\nworld".chars().count()
        );
        assert!(
            output.items[0]
                .prompt_text
                .contains("[Document: notes.txt]")
        );
        assert!(output.items[0].prompt_text.contains("MIME: text/plain"));
        assert!(output.items[0].prompt_text.contains("hello\nworld"));
        assert_eq!(output.combined_prompt_text, output.items[0].prompt_text);
    }

    #[test]
    fn transform_document_prompt_surfaces_parse_failures_without_dropping_documents() {
        let engine = test_engine();
        let output = engine
            .transform_document_prompt(vec![DocumentAttachmentInput {
                name: "broken.pdf".to_string(),
                mime: Some(DOCUMENT_MIME_APPLICATION_PDF.to_string()),
                content_base64: "%%%".to_string(),
            }])
            .expect("transform should return parse_failed output");

        assert_eq!(output.items.len(), 1);
        assert_eq!(
            output.items[0].status,
            DocumentPromptTransformStatus::ParseFailed
        );
        assert!(
            output.items[0]
                .prompt_text
                .contains("[Document Parse Failure: broken.pdf]")
        );
        assert!(
            output.items[0]
                .error
                .as_deref()
                .expect("parse failure error")
                .contains("invalid base64 payload")
        );
    }

    #[test]
    fn transform_document_prompt_extracts_docx_body_text() {
        let engine = test_engine();
        let output = engine
            .transform_document_prompt(vec![attachment(
                "notes.docx",
                Some(DOCUMENT_MIME_DOCX),
                &docx_bytes(),
            )])
            .expect("transform docx");

        assert_eq!(output.items[0].status, DocumentPromptTransformStatus::Ready);
        assert!(
            output.items[0]
                .prompt_text
                .contains("Hello DOCX\nSecond line")
        );
    }

    #[test]
    fn transform_document_prompt_extracts_pptx_slide_text_in_order() {
        let engine = test_engine();
        let output = engine
            .transform_document_prompt(vec![attachment(
                "slides.pptx",
                Some(DOCUMENT_MIME_PPTX),
                &pptx_bytes(),
            )])
            .expect("transform pptx");

        assert_eq!(output.items[0].status, DocumentPromptTransformStatus::Ready);
        assert!(
            output.items[0]
                .prompt_text
                .contains("Hello PPTX\n\nSecond slide")
        );
    }

    #[test]
    fn transform_document_prompt_extracts_pdf_text() {
        let engine = test_engine();
        let output = engine
            .transform_document_prompt(vec![attachment(
                "paper.pdf",
                Some(DOCUMENT_MIME_APPLICATION_PDF),
                &pdf_bytes(),
            )])
            .expect("transform pdf");

        assert_eq!(output.items[0].status, DocumentPromptTransformStatus::Ready);
        assert!(output.items[0].prompt_text.contains("Hello PDF"));
    }

    #[test]
    fn send_message_without_conversation_id_creates_and_persists_conversation() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
                attachments: Vec::new(),
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
    fn send_message_persists_document_parts_on_user_node() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "see attachment".to_string(),
                attachments: vec![attachment(
                    "notes.md",
                    Some(DOCUMENT_MIME_TEXT_MARKDOWN),
                    b"# Heading\nbody",
                )],
            })
            .expect("send with document attachment");

        let conversation_id = events[0].conversation_id.expect("conversation id");
        let stored = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");
        let user_parts = &stored.nodes[0].variants[0].parts;

        assert_eq!(user_parts.len(), 2);
        assert!(matches!(
            user_parts[0].payload,
            MessagePartPayload::Text { .. }
        ));
        assert!(matches!(
            user_parts[1].payload,
            MessagePartPayload::Document { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_accepts_attachment_only_turns() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: String::new(),
                attachments: vec![attachment(
                    "paper.pdf",
                    Some(DOCUMENT_MIME_APPLICATION_PDF),
                    &pdf_bytes(),
                )],
            })
            .expect("send attachment only turn");

        let conversation_id = events[0].conversation_id.expect("conversation id");
        let stored = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");

        assert_eq!(stored.conversation.title, "paper.pdf");
        assert!(matches!(
            stored.nodes[0].variants[0].parts[0].payload,
            MessagePartPayload::Document { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_with_existing_conversation_id_appends_to_same_conversation() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
                attachments: Vec::new(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");

        engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
                attachments: Vec::new(),
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
                attachments: Vec::new(),
            })
            .expect_err("missing conversation must fail");

        assert!(matches!(error, SessionError::ConversationNotFound(id) if id == missing));
        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn edit_message_creates_new_branch_and_preserves_prior_truth() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
                attachments: Vec::new(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");
        engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
                attachments: Vec::new(),
            })
            .expect("follow-up message");

        let before_edit = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");
        let original_user_node = before_edit.nodes[2].node.clone();
        let original_assistant_node = before_edit.nodes[3].node.clone();
        let original_user_parts = before_edit.nodes[2].variants[0].parts.clone();

        let events = engine
            .edit_message(SessionEditRequest {
                conversation_id,
                node_id: original_user_node.id,
                text: "second edited".to_string(),
                attachments: Vec::new(),
            })
            .expect("edit message");

        let snapshot_nodes = match &events[0].payload {
            ChatEvent::ConversationSnapshot { branch, .. } => &branch.nodes,
            other => panic!("expected conversation snapshot, got {other:?}"),
        };
        assert_eq!(snapshot_nodes.len(), 4);
        assert_eq!(
            snapshot_nodes[2].parent_node_id, original_user_node.parent_node_id,
            "edited user node should branch from the original parent"
        );
        assert_eq!(
            snapshot_nodes[2].selected_variant.parts[0].payload,
            MessagePartPayload::Text {
                text: "second edited".to_string(),
            }
        );
        assert_eq!(
            snapshot_nodes[3].parent_node_id,
            Some(snapshot_nodes[2].node_id),
            "replacement assistant should branch from edited user node"
        );

        let stored = engine
            .snapshot_for(conversation_id)
            .expect("load edited snapshot")
            .expect("stored conversation");
        assert_eq!(
            stored.nodes.len(),
            6,
            "edit should add a user+assistant branch"
        );
        assert_eq!(
            stored.nodes[2].variants[0].parts, original_user_parts,
            "original edited turn must remain unchanged"
        );
        assert_eq!(
            stored.nodes[3].node.parent_node_id,
            Some(original_user_node.id),
            "old assistant branch should remain attached to the old user node"
        );
        assert_eq!(
            stored.nodes[3].node.id, original_assistant_node.id,
            "prior assistant node should be preserved"
        );
        assert_eq!(
            stored.conversation.current_cursor,
            Some(stored.nodes[5].node.id),
            "selected branch should move to the replacement assistant leaf"
        );

        let branch_text = selected_branch_snapshot_nodes(&stored)
            .expect("selected branch")
            .into_iter()
            .flat_map(|node| node.selected_variant.parts.into_iter())
            .filter_map(|part| match part.payload {
                MessagePartPayload::Text { text } => Some(text),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(
            branch_text,
            vec![
                "first".to_string(),
                "Rust Engine 已接收你的消息：first\n\n来源：Rust session engine\n形态：text"
                    .to_string(),
                "second edited".to_string(),
                "Rust Engine 已接收你的消息：second edited\n\n来源：Rust session engine\n形态：text"
                    .to_string(),
            ]
        );

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn edit_message_rejects_nodes_outside_selected_branch() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
                attachments: Vec::new(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");
        engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
                attachments: Vec::new(),
            })
            .expect("follow-up message");

        let before_edit = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");
        let original_user_node_id = before_edit.nodes[2].node.id;

        engine
            .edit_message(SessionEditRequest {
                conversation_id,
                node_id: original_user_node_id,
                text: "second edited".to_string(),
                attachments: Vec::new(),
            })
            .expect("first edit");

        let error = engine
            .edit_message(SessionEditRequest {
                conversation_id,
                node_id: original_user_node_id,
                text: "second edited again".to_string(),
                attachments: Vec::new(),
            })
            .expect_err("old branch node should no longer be editable");

        assert!(matches!(
            error,
            SessionError::InvalidConversationState {
                conversation_id: id,
                reason,
            } if id == conversation_id && reason.contains("is not on the selected branch")
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn edit_message_noop_does_not_create_sibling_branch() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello".to_string(),
                attachments: Vec::new(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");
        let before_edit = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");
        let original_node_count = before_edit.nodes.len();
        let user_node_id = before_edit.nodes[0].node.id;

        let events = engine
            .edit_message(SessionEditRequest {
                conversation_id,
                node_id: user_node_id,
                text: "hello".to_string(),
                attachments: Vec::new(),
            })
            .expect("noop edit should succeed");

        assert!(
            events.is_empty(),
            "noop edit should not emit mutation events"
        );
        let after_edit = engine
            .snapshot_for(conversation_id)
            .expect("load snapshot")
            .expect("stored conversation");
        assert_eq!(after_edit.nodes.len(), original_node_count);

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn selected_node_projection_keeps_selected_variant_full_parts() {
        let now = Utc::now();
        let bundle = build_assistant_node(
            ConversationId::new_v4(),
            None,
            "projection",
            now,
            VariantStatus::Streaming,
            None,
        );

        let projected = selected_node_projection(&bundle).expect("projection");
        let parts = &projected.selected_variant.parts;

        assert_eq!(parts.len(), 2);
        assert!(matches!(
            parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(parts[1].payload, MessagePartPayload::Text { .. }));
    }

    #[test]
    fn selected_node_projection_surfaces_only_the_selected_variant() {
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

        let projected = selected_node_projection(&bundle).expect("projection");

        assert_eq!(projected.node_id, node_id);
        assert_eq!(projected.selected_variant.variant_id, second_variant_id);
        assert_eq!(projected.selected_variant.parts.len(), 1);
        assert_eq!(projected.selected_variant.parts[0].order_index, 0);
    }

    #[test]
    fn streaming_delta_parts_keep_reasoning_and_text_from_selected_variant() {
        let now = Utc::now();
        let bundle = build_assistant_node(
            ConversationId::new_v4(),
            None,
            "projection",
            now,
            VariantStatus::Streaming,
            None,
        );

        let parts = streaming_delta_parts(&bundle).expect("streaming delta");

        assert_eq!(parts.len(), 2);
        assert!(matches!(
            parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(parts[1].payload, MessagePartPayload::Text { .. }));
    }

    #[test]
    fn streaming_delta_parts_keep_tool_parts_typed() {
        let now = Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();
        let bundle = NodeBundle {
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
                    status: VariantStatus::Streaming,
                    model_id: Some("rust-session-engine".to_string()),
                    usage_json: None,
                    created_at: now,
                    finished_at: None,
                },
                parts: vec![
                    MessagePart {
                        id: Uuid::new_v4(),
                        variant_id,
                        order_index: 0,
                        payload: MessagePartPayload::Reasoning {
                            text: "thinking".to_string(),
                        },
                    },
                    MessagePart {
                        id: Uuid::new_v4(),
                        variant_id,
                        order_index: 1,
                        payload: MessagePartPayload::Tool {
                            tool_call_id: Some("tool-call-1".to_string()),
                            tool_name: "search".to_string(),
                            input_json: "{\"query\":\"rust\"}".to_string(),
                            output_json: Some("{\"hits\":1}".to_string()),
                            error_message: None,
                            state: vcpmobile_domain::ToolPartState::Completed,
                        },
                    },
                    MessagePart {
                        id: Uuid::new_v4(),
                        variant_id,
                        order_index: 2,
                        payload: MessagePartPayload::Text {
                            text: "hello".to_string(),
                        },
                    },
                ],
            }],
        };

        let parts = streaming_delta_parts(&bundle).expect("streaming delta");

        assert_eq!(parts.len(), 3);
        assert!(matches!(
            parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(parts[1].payload, MessagePartPayload::Tool { .. }));
        assert!(matches!(parts[2].payload, MessagePartPayload::Text { .. }));
    }

    #[test]
    fn assistant_node_uses_text_for_final_content() {
        let now = Utc::now();
        let bundle = build_assistant_node(
            ConversationId::new_v4(),
            None,
            "text projection",
            now,
            VariantStatus::Streaming,
            None,
        );
        let parts = &bundle.variants[0].parts;

        assert_eq!(parts.len(), 2);
        assert!(matches!(parts[1].payload, MessagePartPayload::Text { .. }));
    }

    #[test]
    fn send_message_streams_reasoning_and_text_delta_for_assistant_content() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
                attachments: Vec::new(),
            })
            .expect("send message");

        let delta_parts = match &events[2].payload {
            ChatEvent::GenerationPartDelta { appended_parts, .. } => appended_parts,
            other => panic!("expected generation_part_delta, got {other:?}"),
        };

        assert_eq!(delta_parts.len(), 2);
        assert!(matches!(
            delta_parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(
            delta_parts[1].payload,
            MessagePartPayload::Text { .. }
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
                attachments: Vec::new(),
            })
            .expect("send message");

        let snapshot_nodes = match &events[0].payload {
            ChatEvent::ConversationSnapshot { branch, .. } => &branch.nodes,
            other => panic!("expected conversation_snapshot, got {other:?}"),
        };

        assert_eq!(
            snapshot_nodes.len(),
            2,
            "send snapshot should materialize both new nodes"
        );
        assert_eq!(snapshot_nodes[0].role, MessageRole::User);
        assert_eq!(snapshot_nodes[0].parent_node_id, None);
        assert!(matches!(
            snapshot_nodes[0].selected_variant.parts[0].payload,
            MessagePartPayload::Text { .. }
        ));
        assert_eq!(snapshot_nodes[1].role, MessageRole::Assistant);
        assert_eq!(
            snapshot_nodes[1].parent_node_id,
            Some(snapshot_nodes[0].node_id),
            "assistant snapshot should preserve the persisted user-node edge"
        );
        assert_eq!(
            snapshot_nodes[1].selected_variant.status,
            VariantStatus::Streaming
        );
        assert_eq!(snapshot_nodes[1].selected_variant.parts.len(), 2);
        assert!(matches!(
            snapshot_nodes[1].selected_variant.parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(
            snapshot_nodes[1].selected_variant.parts[1].payload,
            MessagePartPayload::Text { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_snapshot_for_follow_up_turn_carries_full_selected_branch() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
                attachments: Vec::new(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");

        let second_events = engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
                attachments: Vec::new(),
            })
            .expect("second message");

        let snapshot_nodes = match &second_events[0].payload {
            ChatEvent::ConversationSnapshot { branch, .. } => &branch.nodes,
            other => panic!("expected conversation_snapshot, got {other:?}"),
        };

        assert_eq!(snapshot_nodes.len(), 4);
        assert_eq!(snapshot_nodes[0].role, MessageRole::User);
        assert_eq!(snapshot_nodes[0].parent_node_id, None);
        assert_eq!(snapshot_nodes[1].role, MessageRole::Assistant);
        assert_eq!(
            snapshot_nodes[1].parent_node_id,
            Some(snapshot_nodes[0].node_id)
        );
        assert_eq!(snapshot_nodes[2].role, MessageRole::User);
        assert_eq!(
            snapshot_nodes[2].parent_node_id,
            Some(snapshot_nodes[1].node_id)
        );
        assert_eq!(snapshot_nodes[3].role, MessageRole::Assistant);
        assert_eq!(
            snapshot_nodes[3].parent_node_id,
            Some(snapshot_nodes[2].node_id)
        );

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_emits_node_upsert_with_full_selected_variant() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "hello rust".to_string(),
                attachments: Vec::new(),
            })
            .expect("send message");

        let upserted_parts = match &events[3].payload {
            ChatEvent::ConversationNodeUpsert { node } => &node.selected_variant.parts,
            other => panic!("expected conversation_node_upsert, got {other:?}"),
        };

        assert_eq!(upserted_parts.len(), 2);
        assert!(matches!(
            upserted_parts[0].payload,
            MessagePartPayload::Reasoning { .. }
        ));
        assert!(matches!(
            upserted_parts[1].payload,
            MessagePartPayload::Text { .. }
        ));

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_rejects_broken_current_cursor_state() {
        let engine = test_engine();
        let now = Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();

        engine
            .store()
            .upsert_conversation(StoredConversation {
                conversation: Conversation {
                    id: conversation_id,
                    topic_id: Uuid::new_v4(),
                    agent_id: Uuid::new_v4(),
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
                        parts: vec![MessagePart {
                            id: Uuid::new_v4(),
                            variant_id,
                            order_index: 0,
                            payload: MessagePartPayload::Text {
                                text: "old".to_string(),
                            },
                        }],
                    }],
                }],
            })
            .expect("write broken conversation");

        let error = engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "resume".to_string(),
                attachments: Vec::new(),
            })
            .expect_err("broken cursor must fail");

        assert!(matches!(
            error,
            SessionError::InvalidConversationState { conversation_id: id, .. } if id == conversation_id
        ));
        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn selected_branch_snapshot_nodes_reject_cross_conversation_ancestor() {
        let now = Utc::now();
        let conversation_id = ConversationId::new_v4();
        let foreign_conversation_id = ConversationId::new_v4();
        let parent_node_id = NodeId::new_v4();
        let current_cursor = NodeId::new_v4();
        let parent_variant_id = Uuid::new_v4();
        let child_variant_id = Uuid::new_v4();
        let stored = StoredConversation {
            conversation: Conversation {
                id: conversation_id,
                topic_id: Uuid::new_v4(),
                agent_id: Uuid::new_v4(),
                title: "broken ancestry".to_string(),
                summary: None,
                pinned: false,
                generation_state: GenerationState::Idle,
                current_cursor: Some(current_cursor),
                created_at: now,
                updated_at: now,
            },
            nodes: vec![
                NodeBundle {
                    node: MessageNode {
                        id: parent_node_id,
                        conversation_id: foreign_conversation_id,
                        parent_node_id: None,
                        role: MessageRole::User,
                        select_index: 0,
                        created_at: now,
                        updated_at: now,
                    },
                    variants: vec![VariantBundle {
                        variant: MessageVariant {
                            id: parent_variant_id,
                            node_id: parent_node_id,
                            status: VariantStatus::Completed,
                            model_id: None,
                            usage_json: None,
                            created_at: now,
                            finished_at: Some(now),
                        },
                        parts: vec![MessagePart {
                            id: Uuid::new_v4(),
                            variant_id: parent_variant_id,
                            order_index: 0,
                            payload: MessagePartPayload::Text {
                                text: "foreign".to_string(),
                            },
                        }],
                    }],
                },
                NodeBundle {
                    node: MessageNode {
                        id: current_cursor,
                        conversation_id,
                        parent_node_id: Some(parent_node_id),
                        role: MessageRole::Assistant,
                        select_index: 0,
                        created_at: now,
                        updated_at: now,
                    },
                    variants: vec![VariantBundle {
                        variant: MessageVariant {
                            id: child_variant_id,
                            node_id: current_cursor,
                            status: VariantStatus::Completed,
                            model_id: None,
                            usage_json: None,
                            created_at: now,
                            finished_at: Some(now),
                        },
                        parts: vec![MessagePart {
                            id: Uuid::new_v4(),
                            variant_id: child_variant_id,
                            order_index: 0,
                            payload: MessagePartPayload::Text {
                                text: "child".to_string(),
                            },
                        }],
                    }],
                },
            ],
        };

        let error = selected_branch_snapshot_nodes(&stored)
            .expect_err("cross-conversation ancestry must fail");

        assert!(matches!(
            error,
            SessionError::InvalidConversationState { conversation_id: id, .. } if id == conversation_id
        ));
    }

    #[test]
    fn selected_node_projection_rejects_invalid_select_index() {
        let now = Utc::now();
        let conversation_id = ConversationId::new_v4();
        let node_id = NodeId::new_v4();
        let variant_id = Uuid::new_v4();
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
                        text: "only".to_string(),
                    },
                }],
            }],
        };

        let error = selected_node_projection(&bundle)
            .expect_err("invalid select_index must degrade to invalid state");

        assert!(matches!(
            error,
            SessionError::InvalidConversationState { conversation_id: id, .. } if id == conversation_id
        ));
    }

    #[test]
    fn selected_branch_snapshot_nodes_reject_invalid_select_index_on_selected_branch() {
        let now = Utc::now();
        let conversation_id = ConversationId::new_v4();
        let current_cursor = NodeId::new_v4();
        let variant_id = Uuid::new_v4();
        let stored = StoredConversation {
            conversation: Conversation {
                id: conversation_id,
                topic_id: Uuid::new_v4(),
                agent_id: Uuid::new_v4(),
                title: "broken select index".to_string(),
                summary: None,
                pinned: false,
                generation_state: GenerationState::Idle,
                current_cursor: Some(current_cursor),
                created_at: now,
                updated_at: now,
            },
            nodes: vec![NodeBundle {
                node: MessageNode {
                    id: current_cursor,
                    conversation_id,
                    parent_node_id: None,
                    role: MessageRole::Assistant,
                    select_index: 1,
                    created_at: now,
                    updated_at: now,
                },
                variants: vec![VariantBundle {
                    variant: MessageVariant {
                        id: variant_id,
                        node_id: current_cursor,
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
                            text: "only".to_string(),
                        },
                    }],
                }],
            }],
        };

        let error = selected_branch_snapshot_nodes(&stored)
            .expect_err("invalid select_index on selected branch must fail cleanly");

        assert!(matches!(
            error,
            SessionError::InvalidConversationState { conversation_id: id, .. } if id == conversation_id
        ));
    }

    #[test]
    fn conversation_catalog_tracks_latest_turn_for_same_conversation() {
        let engine = test_engine();

        let first_events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "first".to_string(),
                attachments: Vec::new(),
            })
            .expect("first message");
        let conversation_id = first_events[0].conversation_id.expect("conversation id");

        engine
            .send_message(SessionSendRequest {
                conversation_id: Some(conversation_id),
                text: "second".to_string(),
                attachments: Vec::new(),
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
        assert_eq!(item.generation_state, GenerationState::Completed);

        fs::remove_file(engine.store().path()).ok();
    }

    #[test]
    fn send_message_upsert_finalizes_assistant_variant_status() {
        let engine = test_engine();

        let events = engine
            .send_message(SessionSendRequest {
                conversation_id: None,
                text: "finalize".to_string(),
                attachments: Vec::new(),
            })
            .expect("send message");

        let upsert = match &events[3].payload {
            ChatEvent::ConversationNodeUpsert { node } => node,
            other => panic!("expected conversation_node_upsert, got {other:?}"),
        };

        assert_eq!(upsert.selected_variant.status, VariantStatus::Completed);
        assert!(upsert.selected_variant.finished_at.is_some());

        fs::remove_file(engine.store().path()).ok();
    }
}
