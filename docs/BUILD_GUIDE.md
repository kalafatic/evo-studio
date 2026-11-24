# EVO-Studio — Build Guide

Authoritative parent: `/home/petr/git/EVO-Studio-121126` (root `pom.xml`).

Prerequisites: JDK 21, `./mvnw` (Maven 3.9.12), network for Eclipse 2025-12 and 2024-03 (JUnit) p2 repos.

| Operation | Command (from repo root) | Output | Exit |
|---|---|---|---|
| Full Build | `./mvnw -B -DskipTests clean verify` | 10 modules; product in `product/eu.kalafatic.dstudio.product/target/products/` | 0 |
| Clean Build | same | — | 0 |
| Build Selected Module | `./mvnw -B -DskipTests clean verify -pl <module> -am` | module + deps | 0 |
| Test | `./mvnw -B verify` | 105 tests | non-0 on failures |
| Validate | `./mvnw -B validate` | fast | 0 |
| Package | `./mvnw -B -DskipTests package` | bundles + product | 0 |

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

## Verified results (2026-11-19)
- Full reactor compile/package: **SUCCESS**.
- UPM `validate`: SUCCESS.
- Tests: 105 run — 2 failures + 1 error, all in live-execution E2E tests (OpenCode/Aider/Teip real-agent timeouts) — environment-dependent, not dependency failures.
