# EVO-Studio — Project Structure

Authoritative parent/aggregator: **`pom.xml` (repository root)** — `eu.kalafatic.dstudio.parent` 2.6.11-SNAPSHOT

| Layer | Location | Notes |
|---|---|---|
| Repository root | `/home/petr/git/EVO-Studio-121126` | Git root; parent POM at root |
| Build system | Maven + Tycho 4.0.8, `./mvnw` | Java 21 |
| Target platform | Eclipse 2025-12 SimRel (+ JUnit from 2024-03) | `targetplatform/eu.kalafatic.dstudio.target/eu.kalafatic.dstudio.target.target` |
| Feature | `features/eu.kalafatic.dstudio.feature/feature.xml` | |
| Product | `product/eu.kalafatic.dstudio.product/dstudio.product` | |
| Launch configs | `.launches/` (4 files) | DStudio_RCP_Application, DStudio_Headless_Server, DStudio_Tests, DStudio_Maven_Build |

## Reactor modules (10)

| Module | Role |
|---|---|
| targetplatform/eu.kalafatic.dstudio.target | Target definition |
| plugins/eu.kalafatic.dstudio.core | Core services |
| plugins/eu.kalafatic.dstudio.infrastructure | Infrastructure; embeds + exports jackson 2.20 |
| plugins/eu.kalafatic.dstudio.harness | OpenCode/Aider/EVO-Code/ENR harness adapters |
| plugins/eu.kalafatic.dstudio.teip | TEIP provider (MCP client + HTTP handlers) |
| plugins/eu.kalafatic.dstudio.application | RCP application |
| plugins/eu.kalafatic.dstudio.ui | SWT UI |
| features/eu.kalafatic.dstudio.feature | Feature |
| product/eu.kalafatic.dstudio.product | Product assembly |
| tests/eu.kalafatic.dstudio.tests | Test bundle (JUnit 5) |

Scripts: `scripts/build.sh`, `scripts/run-headless.sh`.
