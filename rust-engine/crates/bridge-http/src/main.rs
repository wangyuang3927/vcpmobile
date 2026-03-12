use std::{convert::Infallible, net::SocketAddr, path::PathBuf, sync::Arc, time::Duration};

use anyhow::Context;
use axum::{
    Json, Router,
    extract::{Path, State},
    http::StatusCode,
    response::{
        IntoResponse,
        sse::{Event, KeepAlive, Sse},
    },
    routing::{get, post},
};
use futures_util::stream;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tokio_stream::StreamExt;
use uuid::Uuid;
use vcpmobile_domain::{
    ConversationId, DocumentAttachmentInput, GenerationState, ProviderAuthConfig, ProviderConfig,
};
use vcpmobile_protocol::{
    ChatEvent, EditMessageRequest, EventEnvelope, RegenerateNodeRequest, SelectVariantRequest,
    SnapshotBranch, SnapshotConversation, TransformDocumentPromptRequest,
    TransformDocumentPromptResponse,
};
use vcpmobile_session::{
    SessionEditRequest, SessionEngine, SessionRegenerateRequest, SessionSelectVariantRequest,
    SessionSendRequest, demo_conversation, selected_branch_snapshot_nodes,
};
use vcpmobile_store::FileStore;

#[derive(Clone)]
struct AppState {
    engine: Arc<Mutex<SessionEngine>>,
}

#[derive(Debug, Serialize)]
struct ApiErrorResponse {
    error: ApiErrorBody,
}

#[derive(Debug, Serialize)]
struct ApiErrorBody {
    kind: &'static str,
    code: &'static str,
    message: String,
    retriable: bool,
}

#[derive(Debug)]
struct ApiError {
    status: StatusCode,
    kind: &'static str,
    code: &'static str,
    message: String,
    retriable: bool,
}

impl ApiError {
    fn validation(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            kind: "validation",
            code,
            message: message.into(),
            retriable: false,
        }
    }

    fn not_found(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::NOT_FOUND,
            kind: "validation",
            code,
            message: message.into(),
            retriable: false,
        }
    }

    fn conflict(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::CONFLICT,
            kind: "validation",
            code,
            message: message.into(),
            retriable: false,
        }
    }

    fn internal(message: impl Into<String>) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            kind: "internal",
            code: "store_error",
            message: message.into(),
            retriable: false,
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> axum::response::Response {
        (
            self.status,
            Json(ApiErrorResponse {
                error: ApiErrorBody {
                    kind: self.kind,
                    code: self.code,
                    message: self.message,
                    retriable: self.retriable,
                },
            }),
        )
            .into_response()
    }
}

#[derive(Debug, Deserialize)]
struct BridgeChatMessage {
    role: String,
    content: String,
    #[serde(default)]
    attachments: Vec<DocumentAttachmentInput>,
}

#[derive(Debug, Deserialize)]
struct BridgeChatRequest {
    messages: Vec<BridgeChatMessage>,
    #[serde(default)]
    conversation_id: Option<ConversationId>,
}

#[derive(Debug, Serialize)]
struct ConversationListItem {
    conversation_id: ConversationId,
    title: String,
    updated_at: chrono::DateTime<chrono::Utc>,
    generation_state: GenerationState,
    /// Selected branch leaf in Rust truth.
    ///
    /// This mirrors `Conversation.current_cursor` and is always a node identity.
    current_cursor: Option<uuid::Uuid>,
}

#[derive(Debug, Serialize)]
struct ConversationCatalogItem {
    conversation_id: ConversationId,
    title: String,
    summary: Option<String>,
    updated_at: chrono::DateTime<chrono::Utc>,
    generation_state: GenerationState,
    pinned: bool,
    /// Selected branch leaf in Rust truth.
    ///
    /// This mirrors `Conversation.current_cursor` and is always a node identity.
    current_cursor: Option<uuid::Uuid>,
    is_recoverable: bool,
    node_count: usize,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
enum ProviderAuthView {
    None,
    BearerToken {
        has_token: bool,
    },
    ApiKey {
        header_name: String,
        has_value: bool,
    },
    Basic {
        username: String,
        has_password: bool,
    },
}

#[derive(Debug, Clone, Serialize, PartialEq)]
struct ProviderConfigView {
    local_id: String,
    adapter_kind: vcpmobile_domain::ProviderAdapterKind,
    display_name: String,
    avatar_uri: Option<String>,
    base_url: String,
    auth: ProviderAuthView,
    model_catalog: vcpmobile_domain::ProviderModelCatalog,
    custom_headers: Vec<vcpmobile_domain::ProviderHeader>,
    custom_body_fragments: Vec<vcpmobile_domain::ProviderBodyFragment>,
    presets: Vec<vcpmobile_domain::ProviderPreset>,
    default_preset_local_id: Option<String>,
    reference_aliases: Vec<String>,
    created_at: chrono::DateTime<chrono::Utc>,
    updated_at: chrono::DateTime<chrono::Utc>,
}

impl From<ProviderConfig> for ProviderConfigView {
    fn from(provider: ProviderConfig) -> Self {
        Self {
            local_id: provider.local_id,
            adapter_kind: provider.adapter_kind,
            display_name: provider.display_name,
            avatar_uri: provider.avatar_uri,
            base_url: provider.base_url,
            auth: ProviderAuthView::from(provider.auth),
            model_catalog: provider.model_catalog,
            custom_headers: provider.custom_headers,
            custom_body_fragments: provider.custom_body_fragments,
            presets: provider.presets,
            default_preset_local_id: provider.default_preset_local_id,
            reference_aliases: provider.reference_aliases,
            created_at: provider.created_at,
            updated_at: provider.updated_at,
        }
    }
}

impl From<ProviderAuthConfig> for ProviderAuthView {
    fn from(auth: ProviderAuthConfig) -> Self {
        match auth {
            ProviderAuthConfig::None => Self::None,
            ProviderAuthConfig::BearerToken { token } => Self::BearerToken {
                has_token: !token.trim().is_empty(),
            },
            ProviderAuthConfig::ApiKey { header_name, value } => Self::ApiKey {
                header_name,
                has_value: !value.trim().is_empty(),
            },
            ProviderAuthConfig::Basic { username, password } => Self::Basic {
                username,
                has_password: !password.trim().is_empty(),
            },
        }
    }
}

fn app(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/api/chat/demo", get(chat_demo))
        .route("/api/chat/conversations", get(chat_conversations))
        .route("/api/chat/catalog", get(chat_catalog))
        .route("/api/chat", post(chat_send))
        .route("/api/chat/edit", post(chat_edit))
        .route("/api/chat/regenerate", post(chat_regenerate))
        .route("/api/chat/select-variant", post(chat_select_variant))
        .route("/api/chat/document-prompt", post(chat_document_prompt))
        .route("/api/chat/stream/{conversation_id}", get(chat_stream))
        .route("/api/providers", get(provider_list).post(provider_create))
        .route(
            "/api/providers/{provider_local_id}",
            get(provider_get)
                .put(provider_update)
                .delete(provider_delete),
        )
        .with_state(state)
}

async fn health(State(state): State<AppState>) -> impl IntoResponse {
    let engine = state.engine.lock().await;
    Json(serde_json::json!({
        "status": "ok",
        "service": "vcpmobile-bridge-http",
        "store_path": engine.store().path(),
    }))
}

async fn chat_demo() -> impl IntoResponse {
    let topic_id = Uuid::new_v4();
    let agent_id = Uuid::new_v4();
    let (conversation, node_bundle) = demo_conversation(topic_id, agent_id);

    Json(serde_json::json!({
        "conversation": conversation,
        "node": node_bundle,
    }))
}

async fn chat_conversations(
    State(state): State<AppState>,
) -> Result<Json<Vec<ConversationListItem>>, (StatusCode, String)> {
    let engine = state.engine.lock().await;
    let conversations = engine
        .conversation_catalog()
        .map_err(|error| (StatusCode::INTERNAL_SERVER_ERROR, error.to_string()))?
        .into_iter()
        .map(|item| ConversationListItem {
            conversation_id: item.conversation_id,
            title: item.title,
            updated_at: item.updated_at,
            generation_state: item.generation_state,
            current_cursor: item.current_cursor,
        })
        .collect::<Vec<_>>();
    Ok(Json(conversations))
}

async fn chat_catalog(
    State(state): State<AppState>,
) -> Result<Json<Vec<ConversationCatalogItem>>, (StatusCode, String)> {
    let engine = state.engine.lock().await;
    let catalog = engine
        .conversation_catalog()
        .map_err(|error| (StatusCode::INTERNAL_SERVER_ERROR, error.to_string()))?
        .into_iter()
        .map(|item| ConversationCatalogItem {
            conversation_id: item.conversation_id,
            title: item.title,
            summary: item.summary,
            updated_at: item.updated_at,
            generation_state: item.generation_state,
            pinned: item.pinned,
            current_cursor: item.current_cursor,
            is_recoverable: item.is_recoverable,
            node_count: item.node_count,
        })
        .collect::<Vec<_>>();
    Ok(Json(catalog))
}

async fn chat_send(
    State(state): State<AppState>,
    Json(body): Json<BridgeChatRequest>,
) -> Result<Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>, (StatusCode, String)>
{
    let latest_user_message = body
        .messages
        .iter()
        .rev()
        .find(|message| message.role.trim().eq_ignore_ascii_case("user"))
        .ok_or((
            StatusCode::BAD_REQUEST,
            "missing latest user message".to_string(),
        ))?;
    let user_text = latest_user_message.content.trim().to_string();
    if user_text.is_empty() && latest_user_message.attachments.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "latest user message must include text or attachments".to_string(),
        ));
    }

    let engine = state.engine.lock().await;
    let events = engine
        .send_message(SessionSendRequest {
            conversation_id: body.conversation_id,
            text: user_text,
            attachments: latest_user_message.attachments.clone(),
        })
        .map_err(|error| match error {
            vcpmobile_session::SessionError::ConversationNotFound(_) => {
                (StatusCode::NOT_FOUND, error.to_string())
            }
            _ => (StatusCode::INTERNAL_SERVER_ERROR, error.to_string()),
        })?;

    Ok(build_event_stream(events))
}

async fn chat_document_prompt(
    State(state): State<AppState>,
    Json(body): Json<TransformDocumentPromptRequest>,
) -> Result<Json<TransformDocumentPromptResponse>, (StatusCode, String)> {
    let engine = state.engine.lock().await;
    let output = engine
        .transform_document_prompt(body.attachments)
        .map_err(|error| (StatusCode::BAD_REQUEST, error.to_string()))?;

    Ok(Json(TransformDocumentPromptResponse { output }))
}

async fn chat_edit(
    State(state): State<AppState>,
    Json(body): Json<EditMessageRequest>,
) -> Result<Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>, (StatusCode, String)>
{
    let user_text = body.text.trim().to_string();
    if user_text.is_empty() && body.attachments.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "edited message must include text or attachments".to_string(),
        ));
    }

    let engine = state.engine.lock().await;
    let events = engine
        .edit_message(SessionEditRequest {
            conversation_id: body.conversation_id,
            node_id: body.node_id,
            text: user_text,
            attachments: body.attachments,
        })
        .map_err(|error| match error {
            vcpmobile_session::SessionError::ConversationNotFound(_)
            | vcpmobile_session::SessionError::NodeNotFound { .. } => {
                (StatusCode::NOT_FOUND, error.to_string())
            }
            vcpmobile_session::SessionError::EmptyText => {
                (StatusCode::BAD_REQUEST, error.to_string())
            }
            _ => (StatusCode::BAD_REQUEST, error.to_string()),
        })?;

    Ok(build_event_stream(events))
}

async fn chat_regenerate(
    State(state): State<AppState>,
    Json(body): Json<RegenerateNodeRequest>,
) -> Result<
    Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>,
    (axum::http::StatusCode, String),
> {
    let engine = state.engine.lock().await;
    let events = engine
        .regenerate_message(SessionRegenerateRequest {
            conversation_id: body.conversation_id,
            node_id: body.node_id,
        })
        .map_err(|error| match error {
            vcpmobile_session::SessionError::ConversationNotFound(_)
            | vcpmobile_session::SessionError::NodeNotFound { .. } => {
                (axum::http::StatusCode::NOT_FOUND, error.to_string())
            }
            _ => (axum::http::StatusCode::BAD_REQUEST, error.to_string()),
        })?;

    Ok(build_event_stream(events))
}

async fn chat_select_variant(
    State(state): State<AppState>,
    Json(body): Json<SelectVariantRequest>,
) -> Result<
    Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>,
    (axum::http::StatusCode, String),
> {
    let engine = state.engine.lock().await;
    let events = engine
        .select_variant(SessionSelectVariantRequest {
            conversation_id: body.conversation_id,
            node_id: body.node_id,
            variant_id: body.variant_id,
        })
        .map_err(|error| match error {
            vcpmobile_session::SessionError::ConversationNotFound(_)
            | vcpmobile_session::SessionError::NodeNotFound { .. }
            | vcpmobile_session::SessionError::VariantNotFound { .. } => {
                (axum::http::StatusCode::NOT_FOUND, error.to_string())
            }
            _ => (axum::http::StatusCode::BAD_REQUEST, error.to_string()),
        })?;

    Ok(build_event_stream(events))
}

async fn chat_stream(
    State(state): State<AppState>,
    Path(conversation_id): Path<String>,
) -> Result<Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>, (StatusCode, String)>
{
    let conversation_id = Uuid::parse_str(&conversation_id)
        .map(ConversationId::from)
        .map_err(|error| (StatusCode::BAD_REQUEST, error.to_string()))?;
    let engine = state.engine.lock().await;
    let stored = engine
        .snapshot_for(conversation_id)
        .map_err(|error| (StatusCode::INTERNAL_SERVER_ERROR, error.to_string()))?
        .ok_or((StatusCode::NOT_FOUND, "conversation not found".to_string()))?;

    let events = vec![EventEnvelope::new(
        Some(stored.conversation.id),
        ChatEvent::ConversationSnapshot {
            conversation: SnapshotConversation::from(&stored.conversation),
            branch: SnapshotBranch {
                cursor_node_id: stored.conversation.current_cursor,
                nodes: selected_branch_snapshot_nodes(&stored)
                    .map_err(|error| (StatusCode::INTERNAL_SERVER_ERROR, error.to_string()))?,
            },
        },
    )];

    Ok(build_event_stream(events))
}

async fn provider_list(
    State(state): State<AppState>,
) -> Result<Json<Vec<ProviderConfigView>>, ApiError> {
    let engine = state.engine.lock().await;
    let providers = engine
        .store()
        .list_providers()
        .map_err(|error| ApiError::internal(error.to_string()))?
        .into_iter()
        .map(ProviderConfigView::from)
        .collect::<Vec<_>>();
    Ok(Json(providers))
}

async fn provider_get(
    State(state): State<AppState>,
    Path(provider_local_id): Path<String>,
) -> Result<Json<ProviderConfigView>, ApiError> {
    let engine = state.engine.lock().await;
    let provider = engine
        .store()
        .get_provider(&provider_local_id)
        .map_err(|error| ApiError::internal(error.to_string()))?
        .ok_or_else(|| ApiError::not_found("provider_not_found", "provider not found"))?;
    Ok(Json(ProviderConfigView::from(provider)))
}

async fn provider_create(
    State(state): State<AppState>,
    Json(provider): Json<ProviderConfig>,
) -> Result<(StatusCode, Json<ProviderConfigView>), ApiError> {
    validate_provider_config(&provider)?;
    let engine = state.engine.lock().await;
    reject_duplicate_provider_create(engine.store(), &provider)?;
    let saved = engine
        .store()
        .upsert_provider(provider)
        .map_err(|error| ApiError::internal(error.to_string()))?;
    Ok((StatusCode::CREATED, Json(ProviderConfigView::from(saved))))
}

async fn provider_update(
    State(state): State<AppState>,
    Path(provider_local_id): Path<String>,
    Json(mut provider): Json<ProviderConfig>,
) -> Result<Json<ProviderConfigView>, ApiError> {
    validate_provider_config(&provider)?;
    let engine = state.engine.lock().await;
    let existing = engine
        .store()
        .get_provider(&provider_local_id)
        .map_err(|error| ApiError::internal(error.to_string()))?
        .ok_or_else(|| ApiError::not_found("provider_not_found", "provider not found"))?;
    provider.local_id = existing.local_id;
    let saved = engine
        .store()
        .upsert_provider(provider)
        .map_err(|error| ApiError::internal(error.to_string()))?;
    Ok(Json(ProviderConfigView::from(saved)))
}

async fn provider_delete(
    State(state): State<AppState>,
    Path(provider_local_id): Path<String>,
) -> Result<Json<ProviderConfigView>, ApiError> {
    let engine = state.engine.lock().await;
    let removed = engine
        .store()
        .delete_provider(&provider_local_id)
        .map_err(|error| ApiError::internal(error.to_string()))?
        .ok_or_else(|| ApiError::not_found("provider_not_found", "provider not found"))?;
    Ok(Json(ProviderConfigView::from(removed)))
}

fn validate_provider_config(provider: &ProviderConfig) -> Result<(), ApiError> {
    if provider.display_name.trim().is_empty() {
        return Err(ApiError::validation(
            "provider_display_name_required",
            "provider display_name must not be empty",
        ));
    }
    let base_url = provider.base_url.trim();
    if !base_url.starts_with("http://") && !base_url.starts_with("https://") {
        return Err(ApiError::validation(
            "provider_base_url_invalid",
            "provider base_url must start with http:// or https://",
        ));
    }

    match &provider.auth {
        ProviderAuthConfig::None => {}
        ProviderAuthConfig::BearerToken { token } => {
            if token.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_auth_token_required",
                    "bearer token auth requires a non-empty token",
                ));
            }
        }
        ProviderAuthConfig::ApiKey { header_name, value } => {
            if header_name.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_auth_header_required",
                    "api key auth requires a non-empty header_name",
                ));
            }
            if value.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_auth_value_required",
                    "api key auth requires a non-empty value",
                ));
            }
        }
        ProviderAuthConfig::Basic { username, password } => {
            if username.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_auth_username_required",
                    "basic auth requires a non-empty username",
                ));
            }
            if password.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_auth_password_required",
                    "basic auth requires a non-empty password",
                ));
            }
        }
    }

    for (index, header) in provider.custom_headers.iter().enumerate() {
        if header.name.trim().is_empty() {
            return Err(ApiError::validation(
                "provider_header_name_required",
                format!("custom header at index {index} must have a name"),
            ));
        }
    }

    for (index, fragment) in provider.custom_body_fragments.iter().enumerate() {
        if fragment.pointer.trim().is_empty() {
            return Err(ApiError::validation(
                "provider_body_pointer_required",
                format!("custom body fragment at index {index} must have a pointer"),
            ));
        }
    }

    for (index, model) in provider.model_catalog.entries.iter().enumerate() {
        if model.model_id.trim().is_empty() {
            return Err(ApiError::validation(
                "provider_model_id_required",
                format!("model catalog entry at index {index} must have a model_id"),
            ));
        }
    }

    if provider
        .model_catalog
        .default_model
        .as_ref()
        .is_some_and(|default_model| default_model.trim().is_empty())
    {
        return Err(ApiError::validation(
            "provider_default_model_invalid",
            "provider default_model must not be empty when provided",
        ));
    }

    for (index, preset) in provider.presets.iter().enumerate() {
        if preset.name.trim().is_empty() {
            return Err(ApiError::validation(
                "provider_preset_name_required",
                format!("preset at index {index} must have a name"),
            ));
        }
        for header in &preset.headers {
            if header.name.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_preset_header_name_required",
                    format!("preset at index {index} contains a header without a name"),
                ));
            }
        }
        for fragment in &preset.body_fragments {
            if fragment.pointer.trim().is_empty() {
                return Err(ApiError::validation(
                    "provider_preset_body_pointer_required",
                    format!("preset at index {index} contains a body fragment without a pointer"),
                ));
            }
        }
    }

    if let Some(default_preset_local_id) = provider.default_preset_local_id.as_ref() {
        let default_preset_local_id = default_preset_local_id.trim();
        if default_preset_local_id.is_empty() {
            return Err(ApiError::validation(
                "provider_default_preset_invalid",
                "provider default_preset_local_id must not be empty when provided",
            ));
        }
        let matches_known_preset = provider.presets.iter().any(|preset| {
            preset.local_id.trim() == default_preset_local_id
                || preset.name.trim() == default_preset_local_id
        });
        if !matches_known_preset {
            return Err(ApiError::validation(
                "provider_default_preset_unknown",
                "provider default_preset_local_id must reference an existing preset local_id or preset name",
            ));
        }
    }

    Ok(())
}

fn reject_duplicate_provider_create(
    store: &FileStore,
    provider: &ProviderConfig,
) -> Result<(), ApiError> {
    let mut references = std::collections::BTreeSet::new();
    for reference in std::iter::once(provider.local_id.as_str())
        .chain(std::iter::once(provider.base_url.as_str()))
        .chain(provider.reference_aliases.iter().map(String::as_str))
    {
        let reference = reference.trim();
        if reference.is_empty() {
            continue;
        }
        references.insert(reference.to_string());
    }

    for reference in references {
        let existing = store
            .resolve_provider_reference(&reference)
            .map_err(|error| ApiError::internal(error.to_string()))?;
        if existing.is_some() {
            return Err(ApiError::conflict(
                "provider_already_exists",
                format!("provider already exists for reference `{reference}`"),
            ));
        }
    }

    Ok(())
}

fn build_event_stream(
    events: Vec<EventEnvelope<ChatEvent>>,
) -> Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>> {
    let stream = stream::iter(events)
        .throttle(Duration::from_millis(250))
        .map(|envelope| {
            let payload = serde_json::to_string(&envelope).expect("serialize event");
            Ok(Event::default().event("chat_event").data(payload))
        });

    Sse::new(stream).keep_alive(
        KeepAlive::new()
            .interval(Duration::from_secs(10))
            .text("keep-alive"),
    )
}

fn default_store_path() -> PathBuf {
    std::env::var("VCPMOBILE_STORE_PATH")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("data/store.sqlite3"))
}

fn server_host() -> String {
    std::env::var("HOST")
        .ok()
        .map(|host| host.trim().to_string())
        .filter(|host| !host.is_empty())
        .unwrap_or_else(|| "127.0.0.1".to_string())
}

fn server_port() -> u16 {
    std::env::var("PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .filter(|port| *port > 0)
        .unwrap_or(4001)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let store = FileStore::new(default_store_path());
    let topic_id = Uuid::new_v4();
    let agent_id = Uuid::new_v4();
    let engine = SessionEngine::new(store, topic_id, agent_id);

    engine
        .ensure_demo_conversation()
        .context("seed demo conversation")?;

    let state = AppState {
        engine: Arc::new(Mutex::new(engine)),
    };
    let app = app(state);
    let addr = format!("{}:{}", server_host(), server_port())
        .parse::<SocketAddr>()
        .context("parse host/port")?;
    println!("vcpmobile-bridge-http listening on http://{}", addr);
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .with_context(|| format!("bind {}", addr))?;
    axum::serve(listener, app).await.context("serve axum")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::{Body, to_bytes},
        http::{Request, StatusCode},
    };
    use serde_json::{Value, json};
    use std::{env, fs};
    use tower::util::ServiceExt;
    use vcpmobile_domain::TopicId;

    fn test_store_path(name: &str) -> PathBuf {
        env::temp_dir().join(format!(
            "vcpmobile-bridge-http-{name}-{}.json",
            Uuid::new_v4()
        ))
    }

    fn test_app(name: &str) -> (Router, PathBuf) {
        let path = test_store_path(name);
        let engine = SessionEngine::new(FileStore::new(&path), TopicId::new_v4(), Uuid::new_v4());
        let app = app(AppState {
            engine: Arc::new(Mutex::new(engine)),
        });
        (app, path)
    }

    fn sample_provider_payload() -> Value {
        json!({
            "adapter_kind": "openai_compatible",
            "display_name": "OpenAI",
            "base_url": "https://api.example.com/v1",
            "auth": {
                "type": "bearer_token",
                "token": "secret-token"
            },
            "model_catalog": {
                "default_model": "gpt-4.1-mini",
                "entries": [
                    {
                        "model_id": "gpt-4.1-mini",
                        "display_name": "GPT-4.1 mini",
                        "enabled": true
                    }
                ]
            },
            "custom_headers": [
                {
                    "name": "X-Tenant",
                    "value": "mobile"
                }
            ],
            "custom_body_fragments": [
                {
                    "pointer": "/temperature",
                    "value": 0.2
                }
            ],
            "presets": [
                {
                    "name": "balanced",
                    "model_id": "gpt-4.1-mini",
                    "headers": [],
                    "body_fragments": []
                }
            ],
            "default_preset_local_id": "balanced"
        })
    }

    async fn json_request(
        app: Router,
        method: &str,
        uri: &str,
        payload: Option<Value>,
    ) -> (StatusCode, Value) {
        let request = Request::builder()
            .method(method)
            .uri(uri)
            .header("content-type", "application/json")
            .body(match payload {
                Some(payload) => Body::from(payload.to_string()),
                None => Body::empty(),
            })
            .expect("build request");

        let response = app.oneshot(request).await.expect("send request");
        let status = response.status();
        let bytes = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("read body");
        let body = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).expect("parse json body")
        };
        (status, body)
    }

    #[tokio::test]
    async fn provider_crud_routes_persist_and_redact_auth() {
        let (app, path) = test_app("provider-crud");

        let (create_status, created) = json_request(
            app.clone(),
            "POST",
            "/api/providers",
            Some(sample_provider_payload()),
        )
        .await;
        assert_eq!(create_status, StatusCode::CREATED);

        let local_id = created["local_id"]
            .as_str()
            .expect("provider local_id")
            .to_string();
        assert!(local_id.starts_with("provider_local_"));
        assert_eq!(created["auth"]["type"], "bearer_token");
        assert_eq!(created["auth"]["has_token"], true);
        assert!(created["auth"].get("token").is_none());

        let (list_status, listed) = json_request(app.clone(), "GET", "/api/providers", None).await;
        assert_eq!(list_status, StatusCode::OK);
        assert_eq!(listed.as_array().expect("provider list").len(), 1);

        let (get_status, loaded) = json_request(
            app.clone(),
            "GET",
            &format!("/api/providers/{local_id}"),
            None,
        )
        .await;
        assert_eq!(get_status, StatusCode::OK);
        assert_eq!(loaded["local_id"], local_id);
        assert_eq!(loaded["auth"]["type"], "bearer_token");
        assert_eq!(loaded["auth"]["has_token"], true);

        let mut updated_payload = sample_provider_payload();
        updated_payload["display_name"] = Value::String("OpenAI Mirror".to_string());
        updated_payload["base_url"] = Value::String("https://mirror.example.com/v1".to_string());
        updated_payload["auth"] = json!({
            "type": "api_key",
            "header_name": "X-API-Key",
            "value": "updated-secret"
        });

        let (update_status, updated) = json_request(
            app.clone(),
            "PUT",
            &format!("/api/providers/{local_id}"),
            Some(updated_payload),
        )
        .await;
        assert_eq!(update_status, StatusCode::OK);
        assert_eq!(updated["local_id"], local_id);
        assert_eq!(updated["display_name"], "OpenAI Mirror");
        assert_eq!(updated["auth"]["type"], "api_key");
        assert_eq!(updated["auth"]["header_name"], "X-API-Key");
        assert_eq!(updated["auth"]["has_value"], true);
        assert!(updated["auth"].get("value").is_none());

        let stored: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(&path).expect("read store file"))
                .expect("parse store json");
        let stored_provider = &stored["provider_configs"][&local_id];
        assert_eq!(
            stored_provider["auth"]["value"].as_str(),
            Some("updated-secret")
        );

        let (delete_status, deleted) = json_request(
            app.clone(),
            "DELETE",
            &format!("/api/providers/{local_id}"),
            None,
        )
        .await;
        assert_eq!(delete_status, StatusCode::OK);
        assert_eq!(deleted["local_id"], local_id);

        let (missing_status, missing) =
            json_request(app, "GET", &format!("/api/providers/{local_id}"), None).await;
        assert_eq!(missing_status, StatusCode::NOT_FOUND);
        assert_eq!(missing["error"]["code"], "provider_not_found");

        fs::remove_file(path).ok();
    }

    #[tokio::test]
    async fn provider_create_rejects_invalid_payloads() {
        let (app, path) = test_app("provider-validation");
        let invalid = json!({
            "adapter_kind": "openai_compatible",
            "display_name": "",
            "base_url": "ftp://invalid.example.com",
            "auth": {
                "type": "bearer_token",
                "token": ""
            }
        });

        let (status, body) = json_request(app, "POST", "/api/providers", Some(invalid)).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(body["error"]["code"], "provider_display_name_required");

        fs::remove_file(path).ok();
    }

    #[tokio::test]
    async fn provider_create_rejects_duplicate_references() {
        let (app, path) = test_app("provider-conflict");

        let (status, _) = json_request(
            app.clone(),
            "POST",
            "/api/providers",
            Some(sample_provider_payload()),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);

        let (duplicate_status, duplicate_body) = json_request(
            app,
            "POST",
            "/api/providers",
            Some(sample_provider_payload()),
        )
        .await;
        assert_eq!(duplicate_status, StatusCode::CONFLICT);
        assert_eq!(duplicate_body["error"]["code"], "provider_already_exists");

        fs::remove_file(path).ok();
    }
}
