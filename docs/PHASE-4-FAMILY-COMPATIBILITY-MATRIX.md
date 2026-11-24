# PHASE-4-FAMILY-COMPATIBILITY-MATRIX.md

**EVO-Studio Phase 4 — WP-4.10 deliverable**
**Date:** 2026-11-16

Legend: ✅ implemented + tested · ⚙️ implemented, partially tested · 📄 documented only ·
🚫 blocked/not applicable · ⏳ deferred by design

## 1. Products × EVO-Studio Harness Platform Capabilities

| Capability | EVO-Studio provides | EVO uses | TRYST uses | EVO-Code (planned) |
|---|---|---|---|---|
| Harness discovery (capabilities endpoint) | ✅ | ✅ | ✅ | 📄 |
| OpenCode execution via EVO-Studio | ✅ (real CLI E2E) | ✅ (live E2E) | ✅ (live E2E) | 📄 |
| Aider execution via EVO-Studio | ✅ (real CLI E2E) | ⚙️ (configurable, not live-tested) | ⚙️ (configurable, not live-tested) | 📄 |
| Task submission (202 + idempotency) | ✅ | ✅ | ✅ | 📄 |
| Status polling | ✅ | ✅ | ✅ | 📄 |
| Event streaming (cursor replay) | ✅ | ✅ | ✅ | 📄 |
| Cancellation | ✅ | ✅ (stub-tested; live cancel = EVO-side trigger) | ✅ (stub-tested) | 📄 |
| Result (patch + modifiedFiles) | ✅ | ✅ (files verified on disk) | ✅ (modifiedFiles list verified) | 📄 |
| Auth (Bearer / dev-mode loopback) | ✅ | ✅ (token masking; dev-mode) | ✅ (token masking) | 📄 |
| Error envelope + version mismatch | ✅ | ✅ (client surfaces HTTP errors) | ✅ | 📄 |

## 2. EVO-Studio Modes × Family

| Mode | Desktop UI | Headless TEIP | Notes |
|---|---|---|---|
| EVO Developer Mode → EVO-Studio | 🚫 (EVO is server-side workflow; GUI is EVO's own) | ✅ live | EVO keeps its UI |
| TRYST task → EVO-Studio | 🚫 | ✅ live | TRYST keeps its UI |
| EVO-Studio desktop → harness | ⚙️ (implemented; GUI runtime unverified on this host) | — | same core services |
| External TEIP client → EVO-Studio | — | ✅ live | any 1.1-compliant client |

## 3. Protocol Compatibility (TEIP 1.1)

| Requirement (spec §) | EVO-Studio provider | EVO client | TRYST client |
|---|---|---|---|
| §4.1 health (status UP/DEGRADED/DOWN) | ✅ | ✅ (health check) | ✅ |
| §4.2 capabilities (canonical arrays) | ✅ | ✅ | ✅ |
| §4.3 create (202/200, links) | ✅ | ✅ | ✅ |
| §4.4 status (+progress) | ✅ | ✅ | ✅ |
| §4.5 events (nextSequence cursor, ≤500) | ✅ | ✅ | ✅ |
| §4.6 result (409 until terminal) | ✅ | ✅ | ✅ |
| §4.7 cancel (idempotent, 404) | ✅ | ✅ | ✅ |
| §5 error envelope + codes | ✅ | ✅ (surfaces) | ✅ |
| §6 auth (mandatory / explicit opt-out) | ✅ | ✅ | ✅ |
| §7 idempotency persisted + restart reconcile | ✅ | ✅ | ✅ |
| §2 open-world receivers | ✅ | ✅ | ✅ |

## 4. Version Matrix (verified runtimes)

| Product | Version | Build | Test state 2026-11-16 |
|---|---|---|---|
| EVO-Studio | 2.6.11.qualifier | Tycho 4.0.8, Eclipse 2025-12, JDK 21 — BUILD SUCCESS | 74/74 |
| EVO | 2.6.11-SNAPSHOT | Tycho 4.0.5 — BUILD SUCCESS (controller chain) | harness suites 10/10 + 7/7 + live E2E 1/1 |
| TRYST | 2.6.11-SNAPSHOT | Tycho 4.0.8, offline — BUILD SUCCESS | targeted 6/6 + 7/7 + 7/7 + live E2E 1/1; full suite: see E2E report |
| OpenCode | 1.19.5 | — | live real executions ✅ |
| Aider | 0.86.2 (auto-upgraded from 0.86.1 during probe) | — | live real execution ✅ |
| Ollama | 0.40.16 | — | models verified |
| EVO-Code | — | no runtime | 📄 contract only |

## 5. Ownership Boundaries (verified in code)

- No EVO-Studio bundle imports `eu.kalafatic.evolution.*` or `eu.kalafatic.tryst.*`
  (core manifests contain no such references).
- EVO change surface: 2 new classes in the existing harness package + 1 registration line.
- TRYST change surface: 1 new adapter class in the existing integration bundle + 1
  registration line.
- No shared binaries, no duplicated harness implementations on the EVO-Studio path
  (local adapters remain only as documented transitional fallbacks).
