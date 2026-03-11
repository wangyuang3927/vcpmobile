# vcpmobile

Rust-first Android AI client baseline for `rust-vcpmobile`.

## Repository posture

- Rust owns conversation truth, protocol, store, session, and local bridge.
- Android Compose is a thin native client.
- Product planning and technical baseline live under `docs/product` and `docs/architecture`.

## Primary directories

- `rust-engine/`: Rust chat engine and HTTP/SSE bridge
- `android-compose/`: Android Compose client
- `docs/architecture/`: technical specs and capability matrix
- `docs/product/`: PRD and Linear breakdown
- `scripts/`: verification scripts

## Local validation

### Rust

```bash
cd rust-engine
cargo check
cargo test
cargo run -p vcpmobile-bridge-http
```

### Android

```bash
cd android-compose
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Notes

This branch is a clean local Symphony baseline derived from the active Rust-first rewrite, not from the legacy Vue/Capacitor app currently on `main`.
