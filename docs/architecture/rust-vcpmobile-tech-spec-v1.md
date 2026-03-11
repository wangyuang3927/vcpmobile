# rust-vcpmobile Technical Spec v1

## 1. Scope

本 spec 承接 [rust-vcpmobile-prd-v1.md](/home/eric/vcpmobile开发/vcpmobile/docs/product/rust-vcpmobile-prd-v1.md)，定义 `rust-vcpmobile` 的技术基线。

它解决的问题不是“从零设计一个客户端”，而是：

- 在当前 `vcpmobile` Rust-first 代码现实上继续推进
- 把 `rib` 的成熟聊天能力、`hapi` 的 onboarding 模式、`vcpchat` 的 agent 群聊、`vcptoolbox` 的后端能力统一到一个可实现的 Rust 主真相体系里

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

### 7.3 Streaming Semantics

Streaming must be part-aware:

- deltas append to ordered parts
- reasoning may stream independently of final visible answer
- tool calls/results must remain typed, not flattened
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

### 8.3 Prompt Resolution

Prompt handling should preserve `vcptoolbox` staged semantics, but the active resolved prompt should be produced in Rust and surfaced to clients as resolved truth + provenance metadata.

The mobile agent editor must expose at least:

- role/avatar/name
- system prompt
- placeholder variables and preview of resolved output
- provider/model override
- request-level overrides
- local tool permissions
- group participation settings

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

## 12. VCPToolBox Compatibility Layer

### 12.1 Placeholder Compatibility

Rust should preserve the staged placeholder concept:

- agent placeholders first
- generic prompt placeholders next
- plugin/static placeholders next
- sticker/media placeholders separately

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
