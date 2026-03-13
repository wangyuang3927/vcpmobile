# rust-vcpmobile Capability Matrix v1

## 1. Purpose

本矩阵用于回答三件事：

- `rib / hapi / vcpchat / vcptoolbox` 分别提供了什么高价值能力
- 当前 `vcpmobile` 已经实现了什么
- `rust-vcpmobile` 在技术 spec 中应继承、重写还是新增什么

## 2. Matrix

| Capability | rib | hapi | vcpchat | vcptoolbox | current vcpmobile | rust-vcpmobile disposition |
| --- | --- | --- | --- | --- | --- | --- |
| Chat-first IA | chat-first route + drawer workspace | not applicable | chat app shell exists but not Android-native baseline | not frontend baseline | Compose chat screen + catalog workbench | inherit rib IA principles, adapt to VCP identity |
| Conversation truth | Conversation -> MessageNode -> MessageVariant -> ordered MessagePart | session metadata only | file-backed topic/history, weaker truth model | upstream request pipeline, not client truth | Rust conversation/node/variant/part already exists with `current_cursor` + `select_index` truth anchors | keep current Rust truth, extend toward rib richness |
| Rich typed parts | text/image/video/audio/document/reasoning/tool | no | mixed attachments in group chat history | media translation, sticker/image pipeline | text/reasoning/markdown/code only | extend current Rust/Android to rib-level typed parts |
| Streaming semantics | part-aware merge, reasoning/tool aware | SSE events for app state | streamed group messages with agent metadata | stream + tool loop + interrupt | SSE chat events + node upsert | keep SSE for phase 1, upgrade event richness |
| Provider/API config | strong per-provider config + QR import/export | no | not main reference | upstream target endpoints | minimal/no real provider config | inherit rib-style provider config, Rust-owned |
| Agent config | full assistant surfaces | no | prompt modes + config persistence | alias/prompt/config.env/admin APIs | almost none | create full Rust-owned agent config system, informed by rib + vcptoolbox |
| Placeholder system | template/injection support | no | prompt modes and `{{AgentName}}` | staged `{{}}` pipeline, plugin placeholders, sticker placeholders | none | preserve staged placeholder semantics in Rust with frozen order: `agent -> generic -> plugin -> static`, then separate `sticker_media` expansion |
| QR onboarding | provider QR import/export | QR bootstrap + long/short token split | no | target backend | placeholder only | use hapi-style minimal bootstrap for VCPToolBox pairing; optionally keep rib-style provider QR for API import |
| Group chat | none native | no | strongest reference: @agent, naturerandom, invite_only, topic/history | upstream can service it | none | new Rust-native agent group chat |
| Forum | none | no | client reference | backend/forum/plugin truth | none | P1: normalized thread model, do not copy markdown-floor parsing as truth |
| Notes | backup/export only, not same module | no | local+network note tree, share-into-notes | dailynotes backend routes | none | P1: local-first note tree + backend compatibility |
| Local-first persistence | Room + DataStore + backup | SQLite sessions/state | files + config | files/config/plugin state | JSON store + recovery store + scripts | strengthen current local-first model; move from JSON store to SQLite |
| Rich-content parity floor | mature typed parts, markdown/code, reasoning, tool cards, branch chat | no | partial attachment/group rendering | media/tool upstream semantics only | markdown/code/reasoning subset | P0 must mirror rib chat richness on a Rust truth model |
| Group-turn policy | no | no | direct mention, tags, `@所有人`, sequential/naturerandom/invite_only | can host upstream agents | none | preserve deterministic parts of vcpchat policy, remove renderer randomness |
| Auth/adapter boundary | per-provider app auth only | pairing bootstrap auth | app-side configs only | bearer/basic-cookie/path-key mixed domains | none | normalize auth domains in adapter; never leak vcptoolbox quirks into client truth |
| Verification | mature app but not imported | not relevant | not target | not target | strongest local verification scripts | preserve and formalize current verification path |

## 3. Key Dispositions

### 3.1 Inherit As Product Baseline

- `rib` chat-first IA
- `rib` typed part richness
- `rib` per-provider configuration depth
- `rib` assistant/agent editing surface decomposition
- `rib` conversation tree + branch selection model
- `rib` rich markdown/code/reasoning/tool rendering floor

### 3.2 Reuse As Protocol / System Pattern

- `hapi` QR bootstrap minimalism
- `hapi` long-lived bootstrap secret -> short-lived mobile token split
- `hapi` namespace-scoped isolation
- `hapi` REST/SSE mobile transport preference

### 3.3 Adapt, Not Copy

- `vcpchat` group chat speaker policy and `@agent` control
- `vcpchat` forum and notes as module references
- `vcptoolbox` placeholder pipeline
- `vcptoolbox` sticker/image pack contract
- `vcptoolbox` separate auth domains, but only after Rust adapter normalization

### 3.4 Already Built, Continue Forward

- current Rust conversation/node/variant/part truth skeleton
- current HTTP + SSE bridge
- current recovery/catalog split
- current Android reducer/repository/recovery separation
- current verification scripts and release gate

### 3.5 Must Be Replaced Or Retired

- legacy `android/` app path in current repo
- current stubbed Rust assistant echo behavior
- current JSON file store as final persistence story
- current partial Android rich-part support

## 4. Immediate Implications For Tech Spec

### 4.1 Markdown, Code, And Document-Ingestion Boundary

- Markdown parity is a bounded readability floor for chat content, not a promise to reproduce every renderer or plugin from `rib` or `vcpchat`.
- P0 markdown includes: paragraphs, headings, emphasis, ordered/unordered lists, read-only task lists, block quotes, links, tables, math, inline code, and fenced code.
- P0 code rendering means: preserve whitespace and line breaks, render in monospace, keep optional language labels, and avoid truncation. Syntax highlighting, copy buttons, line numbers, and executable code affordances are optional.
- P0 document-as-prompt scope is limited to `txt`, `md`, `pdf`, `docx`, and `pptx` attachments converted into prompt text while the original part remains typed as `document`.
- Out of scope for this parity floor: raw HTML execution, Mermaid/diagram renderers, embedded web views, spreadsheet ingestion, OCR guarantees for scanned files, and general document-viewer parity.
- "Match rib" here means matching the bounded user-visible floor above on a Rust-owned truth model, not inheriting its internal APIs or every adjacent capability.

1. `rust-vcpmobile` should not invent a new truth model; it should deepen the current Rust model toward `rib` richness.
2. Group chat is a real P0 system, not a later embellishment.
3. QR onboarding is its own bounded subsystem, separate from general provider QR import/export.
4. `vcptoolbox` compatibility belongs in adapter layers and migration contracts, not in UI truth.
5. P0 rich-content parity means at least: typed `text/image/document/reasoning/tool` parts, branch conversation, bounded markdown/code/document-as-prompt parity, and inline tool/reasoning surfaces.
6. Group chat policy must preserve direct mention first, then tags, then `@所有人`, with `invite_only` disabling automatic replies.
7. `vcptoolbox` quirks to avoid inheriting directly include special-model routing before bearer auth, raw model mismatch before redirect normalization, odd image route shape, unauthenticated plugin callback, and admin cookie assumptions.
