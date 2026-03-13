# rust-vcpmobile Technical Spec v1

## 1. Scope

本 spec 承接 `docs/product/rust-vcpmobile-prd-v1.md`，定义 `rust-vcpmobile` 的技术基线。

它解决的问题不是“从零设计一个客户端”，而是：

- 在当前 `vcpmobile` Rust-first 代码现实上继续推进
- 把 `rib` 的成熟聊天能力、`hapi` 的 onboarding 模式、`vcpchat` 的 agent 群聊、`vcptoolbox` 的后端能力统一到一个可实现的 Rust 主真相体系里

外部参考的仓库内收敛入口是：

- `docs/reference/external-baselines.md`

## 2. Technical Position

### 2.1 Core Position

- 默认 `Rust maximalism`
- Android 保留为尽可能薄的原生展示与交互壳
- 只有在体验明显变差或工程复杂度明显失控时，才允许把部分逻辑留在 Android

### 2.2 Truth Boundary

- Rust owns:
  - conversation truth
  - message node/variant/part truth
  - agent/group/topic/draft truth
  - provider config truth
  - onboarding/session truth
  - forum/note compatibility adapters
- Android owns:
  - rendering
  - gestures
  - layout and navigation shell
  - local ephemeral view state
  - presentation grouping such as current/recent/older

## 3. Reference Synthesis

### 3.1 rib

Carry forward:

- chat-first IA
- ordered typed parts
- part-aware streaming merge
- conversation tree with per-node branch selection
- document attachment -> prompt ingestion
- markdown/code/reasoning/tool rendering as first-class chat surfaces
- per-provider config depth
- assistant config split into multiple focused surfaces
- local-first persistence + backup posture

Do not copy directly:

- Kotlin-specific data/store architecture
- embedded local web API shape as-is
- inferred features that are not code-backed as first-class truth, such as structured quote/reply

### 3.2 hapi

Carry forward:

- minimal QR payload
- bootstrap secret -> short-lived mobile token exchange
- namespace isolation
- session resume anchors
- REST/SSE for mobile state transport

Do not copy directly:

- raw token lingering in URL/localStorage
- terminal/session-centric machine control assumptions

### 3.3 vcpchat

Carry forward:

- `@agent` as first-class control primitive
- group/topic/history truth shape
- explicit invite/manual dispatch mode
- agent avatar metadata in runtime messages
- notes share-in path
- deterministic dispatch order: direct mention -> tags -> `@所有人` -> fallback
- `sequential` / `naturerandom` / `invite_only` as explicit turn-policy modes

Do not copy directly:

- renderer-era random policy as truth
- markdown-floor parsing forum model
- fuzzy username/avatar matching as system truth
- `Math.random()` nondeterminism
- UI-gated invite loopholes
- broken topic update event wiring

### 3.4 vcptoolbox

Carry forward:

- staged placeholder pipeline
- emoji/sticker pack filesystem contract
- agent alias/prompt/config surfaces
- chat tool loop semantics
- forum/note backend seams
- separate auth domains as adapter inputs

Do not copy directly:

- current auth/order quirks
- plugin-first filesystem truth as mobile product truth
- special-model routing before bearer auth
- raw model mismatch before redirect normalization
- odd image route shape and unauthenticated plugin callback
- admin cookie quirks as mobile auth truth

## 4. Current Implementation Baseline

### 4.1 Already Built

- Rust workspace with `domain / protocol / store / session / bridge-http`
- typed conversation/node/variant/part model skeleton
- HTTP + SSE local bridge
- catalog facade and recovery facts
- Android transport/repository/reducer/recovery separation
- snapshot-based conversation recovery
- deterministic local verification scripts

### 4.2 Current Gaps

- real upstream orchestration is absent
- persistence is still JSON-backed, not SQLite-backed
- draft truth is not end-to-end
- Android only supports a subset of rich parts
- release config is still dev-local oriented
- legacy `android/` path still exists and muddies scope

## 5. Target System Shape

```text
Android App
  - shell / navigation / rendering / native input / media pickers
  - minimal local UI state
  - bridge client

Rust Core
  - domain
  - protocol
  - store
  - session
  - provider
  - onboarding
  - forum adapter
  - notes adapter
  - bridge-http

External Systems
  - VCPToolBox
  - OpenAI-compatible / Google / Anthropic-compatible APIs
  - local QR bootstrap service
```

## 6. Rust Core Modules

### 6.1 `domain`

Owns the main product truth:

- `Conversation`
- `MessageNode`
- `MessageVariant`
- `MessagePart`
- `AgentProfile`
- `AgentGroup`
- `GroupTopic`
- `DraftState`
- `ProviderConfig`
- `PairingSession`
- `ForumThread`
- `ForumReply`
- `NoteEntry`

### 6.2 `protocol`

Owns typed app-facing contracts:

- request DTOs
- response DTOs
- typed event envelopes
- schema versioning

#### App-facing event envelope v1

All app-facing chat events must share one stable outer shape:

```json
{
  "schema": {
    "family": "chat_event",
    "major": 1,
    "minor": 0
  },
  "event_id": "uuid",
  "event_name": "conversation_snapshot",
  "conversation_id": "uuid|null",
  "emitted_at": "RFC3339 timestamp",
  "payload": {
    "event": "conversation_snapshot",
    "data": {}
  }
}
```

Rules:

- `event_name` is the canonical snake_case event identifier consumed by app reducers.
- `payload` carries the typed event body. During the v1 transition it may still repeat
  the same event tag internally, but reducers should treat the outer shape as the durable
  compatibility boundary.
- `schema.family` names the event family. It changes only when the stream is no longer a
  chat-event stream.
- `schema.major` increments for breaking changes: envelope field rename/removal, event rename,
  required payload field removal, or semantic reinterpretation of an existing event.
- `schema.minor` increments for backward-compatible additions: new optional fields, new event
  types, or additive payload data that old clients may ignore safely.

#### `conversation_snapshot` payload shape

`conversation_snapshot` freezes the selected-branch projection rather than exposing raw
store/domain node bundles:

```json
{
  "event": "conversation_snapshot",
  "data": {
    "conversation": {
      "id": "uuid",
      "topic_id": "uuid",
      "agent_id": "uuid",
      "title": "string",
      "summary": "string|null",
      "pinned": false,
      "generation_state": "idle|requesting|started|streaming|completed|failed|cancelled",
      "current_cursor": "node-uuid|null",
      "created_at": "RFC3339 timestamp",
      "updated_at": "RFC3339 timestamp"
    },
    "branch": {
      "cursor_node_id": "node-uuid|null",
      "nodes": [
        {
          "node_id": "node-uuid",
          "parent_node_id": "node-uuid|null",
          "role": "user|assistant|system|tool",
          "created_at": "RFC3339 timestamp",
          "updated_at": "RFC3339 timestamp",
          "selected_variant": {
            "variant_id": "variant-uuid",
            "status": "streaming|completed|failed|cancelled",
            "model_id": "string|null",
            "usage_json": "string|null",
            "created_at": "RFC3339 timestamp",
            "finished_at": "RFC3339 timestamp|null",
            "parts": [
              {
                "part_id": "part-uuid",
                "order_index": 0,
                "payload": {}
              }
            ]
          }
        }
      ]
    }
  }
}
```

#### `conversation_node_upsert` payload shape

`conversation_node_upsert` carries the same explicit branch anchor as
`conversation_snapshot`, but narrows the payload to one selected node so Android can replace that
node truth directly without inferring whether branch selection moved:

```json
{
  "event": "conversation_node_upsert",
  "data": {
    "branch": {
      "cursor_node_id": "node-uuid|null",
      "node": {
        "node_id": "node-uuid",
        "parent_node_id": "node-uuid|null",
        "role": "user|assistant|system|tool",
        "created_at": "RFC3339 timestamp",
        "updated_at": "RFC3339 timestamp",
        "selected_variant": {
          "variant_id": "variant-uuid",
          "status": "streaming|completed|failed|cancelled",
          "model_id": "string|null",
          "usage_json": "string|null",
          "created_at": "RFC3339 timestamp",
          "finished_at": "RFC3339 timestamp|null",
          "parts": [
            {
              "part_id": "part-uuid",
              "order_index": 0,
              "payload": {}
            }
          ]
        }
      }
    }
  }
}
```

Rules:

- Snapshot and upsert payloads are selected-only projections. They do not expose a `variants` array
  or `select_index`.
- `branch.cursor_node_id` duplicates `conversation.current_cursor` on purpose on both snapshot and
  upsert payloads so branch selection stays explicit at the protocol boundary.
- `parent_node_id` is always a node identity; variants never encode branch ancestry.
- `parts` are ordered by `order_index` and carried inside the selected variant, so Android never
  needs to infer which branch/variant owns them.

#### Generation and tool event set v1

Generation lifecycle stays explicit and node/variant-scoped:

- `generation_started`
  - means upstream acknowledged one assistant generation attempt for `node_id + variant_id`
  - advances reducer/runtime state only from `requesting -> started`
- `generation_part_delta`
  - carries `appended_parts[]` only; clients must append in-order and must not infer hidden parts
  - is the only event that advances `requesting|started -> streaming`
- `generation_completed`
  - clean terminal for the active generation attempt
  - means the selected variant truth should already be recoverable via snapshot/upsert
- `generation_failed`
  - terminal failure for the active generation attempt
  - carries a typed `error` object instead of a free-form top-level message
- `generation_cancelled`
  - terminal user- or system-driven cancellation for the active generation attempt
  - may include an optional human-readable `message`, but cancellation semantics come from the event name itself

Tool lifecycle is separate from generation deltas so reducers do not need to infer tool state from
parts or timing:

- `tool_call_started`
  - starts one opaque `tool_call_id` under the active `node_id + variant_id`
  - includes `tool_name` and `arguments_json`
- `tool_call_completed`
  - marks that `tool_call_id` as completed
  - typed `tool_result` parts still carry the durable content truth
- `tool_call_failed`
  - marks that `tool_call_id` as failed
  - carries the same typed `error` object used by generation and engine failures
- `tool_call_cancelled`
  - marks that `tool_call_id` as cancelled
  - may include an optional human-readable `message`

Error semantics:

- Every failure payload uses `error.kind + error.code? + error.message + error.retriable`.
- `error.kind` is one of `provider|tool|transport|validation|internal`.
- `error.code` is a stable machine-oriented classifier when the upstream/source exposes one.
- `error.message` is for user/log presentation and must not be parsed as the primary programmatic signal.
- `error.retriable` tells reducers whether retry affordances should stay available without reinterpreting provider-specific text.

### 6.3 `store`

Moves from JSON file store to SQLite-backed durable truth.

Should cover:

- conversations
- nodes / variants / parts
- agents
- agent groups / topics
- drafts
- provider configs
- pairing sessions / trusted devices
- forum adapter metadata
- notes metadata and local cache

### 6.4 `session`

Owns runtime orchestration:

- single chat send/stream lifecycle
- group chat turn orchestration
- resume/recovery anchors
- interrupt/cancel state
- current generation state

Single-chat send lifecycle must use named Rust states instead of timing guesses:

- `idle`: no active generation for the selected branch
- `requesting`: user send accepted locally, upstream start not confirmed yet
- `started`: upstream generation acknowledged, no visible delta applied yet
- `streaming`: at least one assistant delta has been applied
- `completed`: generation finished cleanly and selected variant truth is finalized
- `failed`: generation terminated with an error
- `cancelled`: generation terminated by explicit interrupt/cancel

Transition contract:

- `idle|completed|failed|cancelled -> requesting` on new send
- `requesting -> started` on `generation_started`
- `requesting|started|streaming -> streaming` on first/subsequent delta
- `requesting|started|streaming -> completed|failed|cancelled` only through explicit terminal events
- `tool_call_*` events do not by themselves terminalize generation; they annotate the in-flight generation attempt for the same `node_id + variant_id`
- recovery/resume eligibility is anchored to `requesting|started|streaming`, not inferred from elapsed time

### 6.5 `provider`

Owns upstream adaptation:

- OpenAI-compatible providers
- Google-compatible providers
- Anthropic-compatible providers
- `VCPToolBox` adapter

It must absorb host/provider quirks the same way `rib` does, but in Rust-owned config and request shaping.

### 6.6 `onboarding`

Owns QR bootstrap and trust exchange:

- QR payload encoding/decoding
- bootstrap credential validation
- short-lived mobile auth/session issuance
- namespace/device binding
- resume anchor registration

### 6.7 `bridge-http`

Phase 1 transport:

- HTTP requests
- SSE event streams
- auth handoff
- snapshot fetch
- catalog and config endpoints

Phase 2 may evaluate non-HTTP transport, but Phase 1 remains canonical for now.

## 7. Conversation Truth Model

### 7.1 Core Shape

Conversation truth follows:

`Conversation -> MessageNode -> MessageVariant -> ordered MessagePart`

This keeps current Rust direction and aligns with `rib`.

### 7.2 Required Part Types

P0 should support at least:

- `text`
- `reasoning`
- `tool`
- `image`
- `document`
- `error`

P0 parity floor from `rib` means:

- typed `text / image / document / reasoning / tool` truth
- markdown text rendering with headings, lists, checkboxes, block quotes, links, tables, math, and fenced code
- branch/regenerate/edit flows against message nodes rather than a flat transcript
- document-as-prompt ingestion path
- inline tool approval/result rendering

`audio`, `video`, and a first-class `quote/reply` primitive are not required for launch. They can remain later extensions unless implementation becomes cheap and stable.

Android may render subsets differently, but Rust must own the full typed truth.

### 7.2.1 Markdown Rendering Boundary

Markdown parity is a bounded readability floor for chat content. It applies to typed text content and to text extracted from supported document attachments. It must not become a lossy container for `reasoning`, `tool`, `image`, or `document` truth.

P0 markdown includes:

- paragraphs and hard line breaks
- headings (`#` through `######`)
- emphasis / strong / strikethrough
- ordered and unordered lists
- read-only task lists / checkboxes
- block quotes
- inline links
- inline code
- fenced code blocks with optional language labels
- tables
- inline and block math

P0 markdown explicitly excludes:

- raw HTML execution or arbitrary embedded web content
- Mermaid / diagram / notebook / canvas-style renderer plugins
- editable checkbox state
- generated table-of-contents / footnotes / citation systems
- copy / run / execute affordances that require a richer code widget

Validation implication:

- included constructs should round-trip into AST/render payloads or degrade into safe readable text without data loss
- excluded constructs should render as inert readable content, never as executable or plugin-defined behavior

### 7.2.2 Code Rendering Floor

P0 code rendering is a readability contract, not an IDE contract.

Required floor:

- preserve whitespace, indentation, and line breaks
- distinguish inline code from fenced code blocks
- show the declared language when it is available
- render fenced blocks in monospace with overflow handling that avoids truncation

Not required for launch:

- syntax highlighting
- line numbers
- copy buttons
- code folding
- execution or preview integrations

### 7.2.3 Document-As-Prompt Ingestion Boundary

Document-as-prompt is an input transform for supported attachments. It is not a general document-management or document-viewer parity effort.

P0 supported document ingestion types:

- `text/plain`
- `text/markdown`
- `application/pdf`
- `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
- `application/vnd.openxmlformats-officedocument.presentationml.presentation`

Required behavior:

- keep the original message part typed as `document`
- extract readable text from the attachment into prompt input in a deterministic, retry-safe way
- include file identity framing so provider-bound prompt text is attributable to the source document
- surface explicit parse failure output instead of silently dropping document content

P0 explicitly excludes:

- spreadsheet ingestion
- archive traversal
- OCR guarantees for scanned PDFs or image-only documents
- embedded media extraction
- preserving original layout fidelity in prompt text
- interactive document preview parity

### 7.3 Core Invariants

- `Conversation`
  - is the only owner of conversation-level metadata and the active branch cursor
  - uses `current_cursor: NodeId?`, never `VariantId`, as the selected branch anchor
  - must keep every referenced node/variant/part inside the same Rust-owned conversation truth
- `MessageNode`
  - is a stable turn slot in the conversation tree, not a transient UI row
  - uses `parent_node_id` to encode ancestry; root nodes use `null`, non-root nodes point to a node in the same conversation
  - keeps `role` fixed for the life of the node
  - uses `select_index` as the single source of truth for the selected variant
- `MessageVariant`
  - is one concrete realization of a node, not a branch identity of its own
  - may accumulate ordered parts while streaming
  - becomes durable history once it reaches a terminal status
- `MessagePart`
  - belongs to exactly one variant and is ordered by stable `order_index`
  - carries typed payload truth; markdown/text must not be used as a lossy fallback for tool, reasoning, or media semantics

### 7.4 Selected Variant And Branch Identity

- Branch identity is Rust-owned: reconstruct the active branch from `current_cursor` plus `parent_node_id` ancestry, then resolve each node's selected realization through `select_index`.
- Variant selection is per-node truth: switching variants changes the chosen realization for one node but does not mint a new `NodeId`.
- Child relationships attach to `NodeId`, not `VariantId`; variants never rewrite ancestry links.
- Store/protocol projections may omit unselected variants for lightweight app-facing payloads, but Rust must retain enough truth to restore the selected variant without Android inferring it. Selected-only payloads should normalize `select_index = 0` so the emitted bundle remains self-consistent.
- `conversation.node.select` is a selection-only mutation. It never rewrites parts, parent links, or node identity.

### 7.5 Edit And Regenerate Mutation Rules

- Streaming is the only phase allowed to append parts to an existing variant in place.
- Persisted historical parts are immutable; later mutations must preserve old variants/parts as durable history.
- Assistant regenerate/retry stays on the same `MessageNode`: create a new `MessageVariant`, mark it selected, and keep prior variants addressable.
- Editing a persisted user turn creates a new `MessageNode` branch from the edited node's parent rather than mutating the old node's selected parts in place.
- Any downstream assistant response after a user edit belongs to the new branch and must be regenerated from that new node lineage.

#### 7.5.1 Stable User-Facing Flow

Branch navigation is not one generic control. The UI must expose two different branch surfaces because
Rust truth distinguishes two different mutation families:

- assistant regenerate branch
  - one `MessageNode`
  - many `MessageVariant`
  - selector stays on the assistant row itself
- user edit branch
  - one shared parent node
  - many sibling `MessageNode`
  - selector stays on the first divergent user row

The timeline always shows one active branch projection at a time.

- Android renders only the currently selected branch that Rust projects.
- Android must not synthesize hidden branches, sibling counts, or inferred variant positions from a
  flat local transcript.
- A branch selector is shown only when Rust explicitly reports alternatives for the visible row.
- Selector label uses human-facing `current/total` formatting such as `2/3`.
- Selector buttons are disabled at the first/last alternative instead of wrapping.
- During an active generation attempt for the conversation, branch selector, edit, and regenerate
  entry points stay disabled so branch truth and streaming truth do not race.

#### 7.5.2 Branch Selector Modes

Two selector modes are required:

1. `variant` mode for assistant regenerate/retry
   - Applies only to assistant nodes with more than one stored variant.
   - Selecting another position keeps the same `node_id`.
   - Only the selected assistant realization changes; descendants stay attached to the same node
     lineage.

2. `branch` mode for edited user turns
   - Applies only to user nodes that have sibling edited nodes from the same parent.
   - Selecting another position changes the active branch root at that user turn.
   - The selected user turn and every downstream row from that point onward are replaced by the
     newly selected branch projection.

For both modes, Android should keep scroll/focus anchored on the same logical row while the
selection mutation is in flight. It may show a lightweight loading affordance on the selector, but
it must wait for Rust events before rewriting visible message content.

#### 7.5.3 Required Projection Metadata

Selected-branch payloads must carry enough metadata for Android to render selectors without access
to hidden variants or hidden sibling nodes.

Each visible node projection should therefore expose a `branch_navigation` object with:

- `kind`: `none | variant | branch`
- `current_position`: 1-based position of the currently selected alternative
- `total_count`: total number of alternatives addressable from this row
- `can_go_prev`
- `can_go_next`
- `prev_variant_id` / `next_variant_id` when `kind = variant`
- `prev_cursor_node_id` / `next_cursor_node_id` when `kind = branch`

Rules:

- `kind = none` means the selector is hidden.
- Android must treat these navigation handles as authoritative and must not derive alternate target
  identities from local message ordering.
- `cursor_node_id` targets always identify the leaf cursor of the destination branch, not merely
  the sibling user node, so one mutation can switch the whole visible suffix deterministically.

#### 7.5.4 Interaction Entry Contract

The three branch-related entry points map to Rust mutations as follows.

| Entry point | Visible on | Preconditions | Rust mutation | Rust truth change | Expected app-facing events |
| --- | --- | --- | --- | --- | --- |
| Branch selector in `variant` mode | assistant rows with alternate variants | conversation generation is not active | `conversation.node.select { conversation_id, node_id, variant_id }` | switch selected variant on the same node | `conversation_node_upsert` for that node; `conversation_snapshot` only if the selected-branch projection outside that row also changed |
| Branch selector in `branch` mode | first divergent user row of an edited branch family | conversation generation is not active | `conversation.branch.select { conversation_id, cursor_node_id }` | switch `Conversation.current_cursor` to the destination branch leaf | `conversation_snapshot` with the newly selected branch projection |
| Edit entry | persisted user rows on the selected branch | conversation generation is not active | draft entry first, then `conversation.node.edit { conversation_id, node_id, parts[] }` on submit | create a new user node from the original parent and move branch selection to the new lineage | immediate `conversation_snapshot` rooted at the new edited user node lineage, followed by generation lifecycle events for the replacement assistant turn |
| Regenerate entry | assistant rows on the selected branch | conversation generation is not active | `conversation.node.regenerate { conversation_id, node_id }` | append a new variant to the same assistant node and mark it selected | `conversation_node_upsert` for the new selected variant, then `generation_started` / `generation_part_delta` / terminal generation event for the new `node_id + variant_id` |

#### 7.5.5 Edit Entry Behavior

Edit is a draft-first flow.

- Entering edit mode copies the currently selected user row content into the composer.
- Entering edit mode does not change Rust conversation truth yet.
- The composer must surface that the user is editing a historical turn rather than composing a new
  leaf message.
- Cancelling edit restores normal compose mode without mutating branch truth.
- Submitting an edit with no effective content change should be treated as a no-op rather than
  creating a useless sibling branch.
- Once Rust accepts the edit mutation, the old user node remains immutable history and the new user
  node becomes the visible branch root for that divergence point.
- Any assistant rows that formerly followed the old user node must disappear from the visible branch
  until replacement responses arrive from the new lineage.

#### 7.5.6 Regenerate Entry Behavior

Regenerate is a direct node mutation rather than a draft flow.

- The entry point is shown on assistant rows, not user rows.
- Triggering regenerate immediately requests a new assistant realization for the same `MessageNode`.
- Rust must allocate a fresh `variant_id`, mark it selected, and preserve older variants as stable
  history.
- Android may show the regenerated row as pending/streaming once Rust emits the new selected
  variant, but it must not fabricate the replacement content ahead of Rust events.
- If regenerate fails, the newly selected variant should carry the terminal failed/cancelled state
  explicitly so the selector and retry affordances remain grounded in Rust truth.

#### 7.5.7 Failure And Consistency Rules

- Android performs no optimistic transcript rewriting for branch/edit/regenerate beyond lightweight
  pending affordances on the tapped control or composer state.
- If Rust rejects a mutation, the currently visible branch remains unchanged and the failure must
  surface through typed error/reporting channels.
- `conversation_snapshot` is the only event that may replace a visible branch suffix wholesale.
- `conversation_node_upsert` is the only event that may replace the selected realization for one
  already-visible node in place.

### 7.6 Streaming Semantics

Streaming must be part-aware:

- deltas append to ordered parts
- reasoning may stream independently of final visible answer
- tool calls/results must remain typed, not flattened
- tool lifecycle state comes from `tool_call_*` events, not from guessing around part arrival timing
- stream completion must finalize the selected variant truth
- `<think>`-style reasoning fallback should normalize into `reasoning` parts inside Rust
- media payload finalization should land on typed local-file-backed parts, not raw inline blobs

## 8. Agent System

### 8.1 Product Requirement

Phone-side full creation and editing is P0.

### 8.2 Technical Requirement

Rust-owned agent truth should cover:

- id
- name
- avatar
- system prompt
- prompt mode
- placeholder variables / bindings
- model/provider override
- request overrides
- memory flags
- local tool toggles
- group participation metadata

The first mobile editor contract should be frozen as one Rust-owned `AgentConfig`
truth document plus one app-facing `AgentEditorSchema.mobile_v1` layout contract.

Frozen field groups for `mobile_v1`:

1. `identity`
   - required persisted fields: `identity.name`
   - optional persisted fields: `identity.avatar_uri`, `identity.description`
2. `prompt`
   - required persisted fields: `prompt.system_prompt`
   - optional persisted fields: `prompt.prompt_mode`, `prompt.message_template`, `prompt.placeholders`
   - derived read-only fields: `prompt_preview` (resolved prompt preview + provenance records)
3. `model`
   - optional persisted fields: `model.provider_local_id`, `model.preset_local_id`, `model.model_id`
4. `request`
   - optional persisted fields: `request.temperature`, `request.top_p`, `request.max_output_tokens`, `request.reasoning_effort`
5. `memory`
   - optional persisted fields: `memory.use_conversation_memory`, `memory.pin_top_level_facts`
6. `tools`
   - optional persisted fields: `tools.enable_local_tools`, `tools.overrides`
7. `group`
   - optional persisted fields: `group.role_label`, `group.aliases`, `group.mention_tags`, `group.respond_to_mentions`, `group.allow_auto_relay`

Validation rules for `mobile_v1`:

- `identity.name` must be non-empty
- `prompt.system_prompt` must be non-empty
- `prompt.placeholders[*].key` must be non-empty
- `tools.overrides[*].tool_id` must be non-empty
- `group.aliases[*]` and `group.mention_tags[*]` must not contain blank items
- `request.temperature` range is `0.0..=2.0`
- `request.top_p` range is `0.0..=1.0`

### 8.3 Prompt Resolution

Prompt handling should preserve `vcptoolbox` staged semantics, but the active resolved prompt should be produced in Rust and surfaced to clients as resolved truth + provenance metadata.

Rust must expose one preview-friendly prompt resolution contract:

- `raw_prompt`: the saved prompt before substitution
- `resolved_prompt`: the text after prompt-stage placeholder resolution
- ordered resolution records with:
  - placeholder key
  - resolved value
  - category
  - source/provenance
  - status: `applied | shadowed | deferred`

Preview rule:

- preview applies only the text stages of the pipeline
- sticker/media placeholders stay deferred because they resolve into typed media, not plain prompt text
- if multiple sources claim the same placeholder key, the highest-priority category wins; within the same category, source declaration order wins

The mobile agent editor must expose at least:

- role/avatar/name
- system prompt
- placeholder variables and preview of resolved output
- provider/model override
- request-level overrides
- local tool permissions
- group participation settings

### 8.4 Local Persistence Boundary

Local-first persistence for the agent editor should be explicit:

- Rust store persists the full editable `AgentConfig` document
- Android may keep unsaved form state, focus state, expansion state, and draft preview UI locally, but those are not truth
- resolved prompt preview and ordered placeholder provenance are derived in Rust and are not stored as editable agent truth
- provider catalogs, tool catalogs, and runtime environment placeholders are referenced by stable IDs and loaded separately from the agent document

This keeps CRUD simple:

- create/update/delete acts on one persisted `AgentConfig`
- UI section order comes from the frozen `AgentEditorSchema.mobile_v1` groups
- any client-side preview must consume Rust-produced derived fields rather than recomputing prompt resolution rules

## 9. Group Chat System

### 9.1 Core Position

Group chat is P0 and agent-centric, not human-social.

### 9.2 Required Behaviors

- `@agent` direct targeting
- multi-agent shared conversation
- invite/manual dispatch mode
- optional continuation / relay speaking mode
- agent identity attached to runtime messages
- topic rename / redo / interrupt hooks in the truth model, even if some UI surfaces arrive later

### 9.3 Dispatch Policy

Phase 1 policy should be deterministic:

- direct `@agent` mention has highest priority
- then agent tags/aliases configured for matching
- then `@所有人`
- then mode-specific fallback

Mode semantics:

- `invite_only`: no automatic replies
- `sequential`: deterministic ordered turn selection
- `naturerandom`: probabilistic feel is allowed, but selection must be reproducible from Rust policy inputs, not renderer randomness

Anti-self-trigger logic is required for natural mode.

### 9.4 Group Truth

Rust should own:

- group definition
- membership
- topic list
- topic history
- turn policy
- invite/manual dispatch state
- active speaking / generation state

`vcpchat` is the interaction reference, not the storage or truth model.

## 10. Provider And API System

### 10.1 Provider Truth

Per-provider configuration must be structured and Rust-owned, similar in depth to `rib`.

Should include:

- base URL
- auth material
- model catalog
- custom headers
- custom body fragments
- host-specific quirks
- optional QR import/export compatibility

Provider truth should also support:

- named presets
- per-provider avatar/display metadata when useful
- stable local IDs so chat history survives endpoint edits

P0 local schema should treat provider identity as:

- `local_id`: opaque Rust-owned stable ID, not derived from endpoint fields
- `reference_aliases`: compatibility references for old endpoint-based or imported identities
- `presets[*].local_id`: stable preset identity independent from preset display name
- `default_preset_local_id`: explicit default preset pointer

Migration compatibility boundary:

- legacy stores may still be keyed by endpoint/base URL
- Rust store normalization must migrate those keys into stable `local_id` keys
- the old endpoint should remain resolvable through `reference_aliases`
- editing `base_url` must never rewrite historical references to the provider
- legacy `default_preset_local_id` values must be remapped onto the normalized preset `local_id` when preset identities are migrated

### 10.2 Two P0 Paths

- general API providers
- `VCPToolBox` backend path

These are parallel P0 paths, not primary/secondary.

### 10.3 Adapter Boundary

`VCPToolBox` compatibility must be normalized behind a Rust adapter:

- `/v1/*` bearer auth is distinct from `/admin_api` basic/cookie auth
- `/pw=.../images|files` path-key routes are distinct from chat/session auth
- mobile client should not need to understand special-model routing or redirect quirks
- auth failures must be normalized into explicit Rust-side errors before they reach Android

## 11. QR Onboarding System

### 11.1 System Shape

QR onboarding consists of:

- local QR bootstrap service
- mobile client
- target backend/service context

### 11.2 Carry-Forward From hapi

- minimal QR payload
- long-lived bootstrap credential
- short-lived mobile auth token
- namespace isolation
- resume anchor registration

### 11.3 rust-vcpmobile Adjustment

Unlike `hapi`, bootstrap credentials should be exchanged quickly for revocable mobile credentials rather than lingering in URLs/storage.

### 11.4 Pairing Exchange Contract

Phase 1 freezes a single pairing exchange endpoint:

- `POST /api/pairing/exchange`
- request/response payloads are JSON
- success returns `200 OK`
- contract or validation failures return an explicit JSON failure payload

Request body:

```json
{
  "pairing_session_id": "pairing-session-1",
  "namespace": "workspace-alpha",
  "bootstrap_token": "bootstrap-secret",
  "device_name": "Pixel 9",
  "device_platform": "android",
  "device_public_key": "base64-public-key"
}
```

Field intent:

- `pairing_session_id`: QR bootstrap session identity from the desktop bridge contract
- `namespace`: backend or workspace isolation key carried through pairing and later revocation
- `bootstrap_token`: one-time or short-lived secret scanned from QR
- `device_name`: user-visible trusted-device label
- `device_platform`: current phase-1 mobile platform, with `android` as the initial shipped value
- `device_public_key`: device-side public key material used to register a revocable trusted device

Success body:

```json
{
  "pairing_session_id": "pairing-session-1",
  "namespace": "workspace-alpha",
  "status": "paired",
  "mobile_token": {
    "access_token": "mobile-token",
    "token_type": "bearer",
    "expires_at": "2026-03-13T12:00:00Z"
  },
  "trusted_device": {
    "trusted_device_id": "trusted-device-1",
    "device_name": "Pixel 9",
    "device_platform": "android"
  },
  "resume_anchor": {
    "anchor": "resume-anchor-1",
    "expires_at": "2026-03-20T12:00:00Z"
  }
}
```

Failure body:

```json
{
  "pairing_session_id": "pairing-session-1",
  "namespace": "workspace-alpha",
  "status": "rejected",
  "error": {
    "code": "bootstrap_token_expired",
    "message": "bootstrap token expired",
    "retriable": false
  }
}
```

Initial failure-code floor:

- `pairing_session_id_required`
- `pairing_namespace_required`
- `pairing_bootstrap_token_required`
- `pairing_device_name_required`
- `pairing_device_public_key_required`

Implementation staging rule:

- before token issuance lands, bridge implementations may return `pairing_exchange_not_ready`
- later work must preserve the same request keys and top-level success/failure structure

## 12. VCPToolBox Compatibility Layer

### 12.1 Placeholder Compatibility

Rust should preserve the staged placeholder concept, but the categories and order must be frozen instead of inferred from backend convention.

Frozen categories:

- `agent`: agent-owned prompt bindings such as role/name/alias/invite target and other agent-specific variables
- `generic`: runtime/environment values such as time/date/user/model/conversation-scoped context
- `plugin`: adapter-supplied dynamic placeholders backed by plugin or backend capability calls
- `static`: filesystem or registry-backed constants that do not depend on per-request runtime state
- `sticker_media`: placeholders that resolve into typed sticker/media payloads rather than prompt text

Frozen resolution order:

1. `agent`
2. `generic`
3. `plugin`
4. `static`
5. `sticker_media` as a separate media expansion phase after text resolution

Conflict rule:

- higher-priority categories shadow lower-priority categories for the same placeholder key
- ties inside the same category resolve by declaration order from Rust-owned inputs, not hash-map iteration or renderer timing

Preview rule:

- Android previews the Rust-produced `resolved_prompt` plus ordered resolution records
- Android does not run final placeholder replacement on its own
- `sticker_media` entries appear as deferred provenance in preview instead of being flattened into text

### 12.2 Sticker / Emoji Compatibility

P0 must preserve the ability to use filesystem-backed sticker packs from `vcptoolbox`.

However, mobile-side rendering should treat stickers as typed media items rather than raw HTML fragments.

### 12.3 Chat Loop Compatibility

`VCPToolBox` adapter should preserve:

- preprocessors
- placeholder/media expansion
- interruptable stream/non-stream behavior
- tool loop semantics

### 12.4 Adapter Rules To Avoid Drift

The adapter must not promote the following into mobile product truth:

- auth ordering quirks
- filesystem-first plugin assumptions
- legacy route irregularities
- backend-side incidental HTML/media render shapes

## 13. Forum And Notes Adapters

### 13.1 Forum

Forum is P1.

Technical stance:

- preserve backend compatibility with `vcptoolbox` / `vcpchat`
- do not treat markdown-floor parsing as final thread truth
- normalize into `Thread / Reply / Board / Metadata` model in Rust

### 13.2 Notes

Notes are also P1.

Technical stance:

- local-first file/tree semantics are acceptable
- support chat-to-note ingress
- allow both local-only and backend-backed sources
- keep a normalized metadata layer in Rust even if content files remain plain text/markdown

## 14. Android Client Responsibilities

Android should keep:

- navigation shell
- drawers/panels/adaptive layout
- message rendering
- media picking / permissions
- ephemeral input state
- local presentation grouping

Android should not own:

- core conversation truth
- group orchestration
- provider truth
- resolved prompt truth
- onboarding truth

## 15. Migration / Retirement Boundaries

### 15.1 Current Compose Path

Continue from the current Compose app and Rust bridge.

### 15.2 Legacy Android Path

The old `android/` tree must be formally retired or clearly marked non-source-of-truth in the next phase. Leaving it ambiguous will corrupt task routing.

### 15.3 Persistence Upgrade

Current JSON store is a transition artifact. SQLite-backed Rust store is the forward path.

## 16. Verification Strategy

### 16.1 Keep

- Rust check/test/resume smoke
- bridge endpoint smoke
- Android deterministic compile/test
- release assemble/install smoke

### 16.2 Add

- capability-level verification tied to P0 features:
  - single chat
  - group chat `@agent`
  - QR bootstrap
  - provider config
  - agent config persistence
  - rich part rendering

## 17. Risks

- Rust maximalism may over-compress UI-owned concerns into core if boundaries are not defended
- copying `vcpchat` group behavior too literally will import renderer-era randomness into product truth
- copying `vcptoolbox` filesystem contracts blindly will freeze plugin-era incidental structure into the mobile core
- under-specifying onboarding/auth will turn QR pairing into a fragile demo feature

## 18. Readiness Verdict

### Verdict

`ready-with-gaps`

### Why

- enough evidence exists to define architecture, boundaries, and module responsibilities
- current implementation reality is known
- reference systems have been reduced into concrete carry-forward rules

### Visible Gaps

- page-level IA and screen decomposition still need a UI/system mapping pass
- `vcptoolbox` auth and adapter boundary still need a dedicated security hardening pass
- forum and notes still need a deeper data migration pass

## 19. Recommended Next Move

1. turn this spec into a first-pass Linear epic/story split
2. do a UI/page-level IA decomposition pass for Android shell and editor surfaces
3. then begin implementation from the current Rust/Compose path rather than restarting from scratch
