use std::{convert::Infallible, net::SocketAddr, path::PathBuf, sync::Arc, time::Duration};

use anyhow::Context;
use axum::{
    Json, Router,
    extract::{Path, State},
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
use vcpmobile_domain::{ConversationId, DocumentAttachmentInput, GenerationState};
use vcpmobile_protocol::{
    ChatEvent, EventEnvelope, SnapshotBranch, SnapshotConversation, TransformDocumentPromptRequest,
    TransformDocumentPromptResponse,
};
use vcpmobile_session::{
    SessionEngine, SessionSendRequest, demo_conversation, selected_branch_snapshot_nodes,
};
use vcpmobile_store::FileStore;

#[derive(Clone)]
struct AppState {
    engine: Arc<Mutex<SessionEngine>>,
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

fn app(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/api/chat/demo", get(chat_demo))
        .route("/api/chat/conversations", get(chat_conversations))
        .route("/api/chat/catalog", get(chat_catalog))
        .route("/api/chat", post(chat_send))
        .route("/api/chat/document-prompt", post(chat_document_prompt))
        .route("/api/chat/stream/{conversation_id}", get(chat_stream))
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
) -> Result<Json<Vec<ConversationListItem>>, (axum::http::StatusCode, String)> {
    let engine = state.engine.lock().await;
    let conversations = engine
        .conversation_catalog()
        .map_err(|error| {
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                error.to_string(),
            )
        })?
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
) -> Result<Json<Vec<ConversationCatalogItem>>, (axum::http::StatusCode, String)> {
    let engine = state.engine.lock().await;
    let catalog = engine
        .conversation_catalog()
        .map_err(|error| {
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                error.to_string(),
            )
        })?
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
) -> Result<
    Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>,
    (axum::http::StatusCode, String),
> {
    let latest_user_message = body
        .messages
        .iter()
        .rev()
        .find(|message| message.role.trim().eq_ignore_ascii_case("user"))
        .ok_or((
            axum::http::StatusCode::BAD_REQUEST,
            "missing latest user message".to_string(),
        ))?;
    let user_text = latest_user_message.content.trim().to_string();
    if user_text.is_empty() && latest_user_message.attachments.is_empty() {
        return Err((
            axum::http::StatusCode::BAD_REQUEST,
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
                (axum::http::StatusCode::NOT_FOUND, error.to_string())
            }
            _ => (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                error.to_string(),
            ),
        })?;

    Ok(build_event_stream(events))
}

async fn chat_document_prompt(
    State(state): State<AppState>,
    Json(body): Json<TransformDocumentPromptRequest>,
) -> Result<Json<TransformDocumentPromptResponse>, (axum::http::StatusCode, String)> {
    let engine = state.engine.lock().await;
    let output = engine
        .transform_document_prompt(body.attachments)
        .map_err(|error| (axum::http::StatusCode::BAD_REQUEST, error.to_string()))?;

    Ok(Json(TransformDocumentPromptResponse { output }))
}

async fn chat_stream(
    State(state): State<AppState>,
    Path(conversation_id): Path<String>,
) -> Result<
    Sse<impl futures_util::Stream<Item = Result<Event, Infallible>>>,
    (axum::http::StatusCode, String),
> {
    let conversation_id = Uuid::parse_str(&conversation_id)
        .map(ConversationId::from)
        .map_err(|error| (axum::http::StatusCode::BAD_REQUEST, error.to_string()))?;
    let engine = state.engine.lock().await;
    let stored = engine
        .snapshot_for(conversation_id)
        .map_err(|error| {
            (
                axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                error.to_string(),
            )
        })?
        .ok_or((
            axum::http::StatusCode::NOT_FOUND,
            "conversation not found".to_string(),
        ))?;

    let events = vec![EventEnvelope::new(
        Some(stored.conversation.id),
        ChatEvent::ConversationSnapshot {
            conversation: SnapshotConversation::from(&stored.conversation),
            branch: SnapshotBranch {
                cursor_node_id: stored.conversation.current_cursor,
                nodes: selected_branch_snapshot_nodes(&stored).map_err(|error| {
                    (
                        axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                        error.to_string(),
                    )
                })?,
            },
        },
    )];

    Ok(build_event_stream(events))
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
        .unwrap_or_else(|_| PathBuf::from("data/store.json"))
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
