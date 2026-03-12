# Symphony Issue Authoring

This repository is used as the visible source tree for Symphony workspaces.

To make issue references usable by agents, every issue must point only to files
that exist inside this repository checkout.

## Hard Rules

- Use repository-relative paths only.
- Do not use WSL paths such as `/home/eric/...`.
- Do not use Windows paths such as `\\wsl.localhost\...`.
- Do not refer to files that exist only on your desktop or in another local
  folder.
- If external notes are required, copy the stable parts into `docs/` or
  `references/` first, then reference the copied file.

## Preferred Reference Areas

- Product intent:
  - `docs/product/rust-vcpmobile-prd-v1.md`
  - `docs/product/rust-vcpmobile-linear-breakdown-v1.md`
- Architecture and contracts:
  - `docs/architecture/rust-vcpmobile-tech-spec-v1.md`
  - `docs/architecture/rust-chat-blueprint-v1.md`
  - `docs/architecture/vcpmobile-linear-spec-v1.md`
  - `docs/architecture/rust-vcpmobile-capability-matrix-v1.md`
- Delivery process:
  - `docs/process/gh-delivery.md`
  - `docs/process/symphony-issue-authoring.md`
- External baselines:
  - `docs/reference/external-baselines.md`
  - `references/rib/README.md`

## Good Issue Pattern

Each implementation issue should contain:

- Objective
- In scope
- Done
- Spec references
- Code references
- Validation

## Example

```md
## Objective

Persist selected variant in Rust store and expose it through the bridge.

## In scope

- store schema change
- selected variant read/write
- bridge payload update

## Done

- selected variant survives restart
- Android consumes Rust truth directly

## Spec references

- `docs/product/rust-vcpmobile-prd-v1.md`
- `docs/architecture/rust-vcpmobile-tech-spec-v1.md`
- `docs/architecture/rust-chat-blueprint-v1.md`

## Code references

- `rust-engine/crates/store/`
- `rust-engine/crates/domain/`
- `rust-engine/crates/bridge-http/`
- `android-compose/app/src/main/java/com/vcp/mobile/ui/chat/`

## Validation

- `cd rust-engine && cargo test`
- `cd android-compose && ./gradlew :app:testDebugUnitTest`
```

## Adaptation Rule

If an issue would otherwise reference a file outside this repository:

1. Move or copy the stable material into this repository first.
2. Commit that material on `symphony-local-base`.
3. Rewrite the issue so every reference is repository-relative.

That is the only reliable way for Symphony to see the same files that the issue
is talking about.
