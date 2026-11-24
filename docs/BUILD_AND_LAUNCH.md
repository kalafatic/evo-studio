# EVO-Studio Build and Launch Guide

## Verified toolchain

- JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- Maven 3.9.12 via the included `./mvnw`
- Linux GTK x86_64

EVO-Studio uses Tycho 4.0.8. The machine-wide Maven 3.8.7 cannot resolve
`tycho-extras:4.0.8`, so a Maven wrapper pinned to 3.9.12 is included.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build

```bash
./mvnw -B -DskipTests clean verify
```

Verified result: `BUILD SUCCESS` for all 13 modules.

`scripts/build.sh` performs the same build with JDK 21 and prefers the included wrapper.
Override the Maven executable with `MVN_CMD` if needed.

## Product

| Product | Directory | Launcher |
|---|---|---|
| EVO-Studio RCP | `product/eu.kalafatic.dstudio.product/target/products/eu.kalafatic.dstudio.product.id/linux/gtk/x86_64` | `evostudio` |

Product identities:

- Product definition id / registered product: `eu.kalafatic.dstudio.application.product`
- Product file uid: `eu.kalafatic.dstudio.product.id`
- UI application: `eu.kalafatic.dstudio.application.application`
- Headless/server application: `eu.kalafatic.dstudio.application.server`

EVO-Studio does not export a separate headless product; the RCP product also contains the
server application and can be started headlessly with `-application`.

## Launch configurations

Launch files are in `.launches/` and are visible when the repository root project is
imported.
The launches explicitly select this repository's bundles, so they stay isolated when
other projects or repository variants are imported into the same workspace.

| Launch | Purpose |
|---|---|
| `EVOStudio_RCP_Application.launch` | Full EVO-Studio RCP product (PDE Runtime Workbench) |

Headless server and tests are launched with `scripts/run-headless.sh` and
`./mvnw verify` respectively rather than dedicated launch files.

## Headless runtime

```bash
scripts/build.sh
scripts/run-headless.sh
```

Or directly:

```bash
cd product/eu.kalafatic.dstudio.product/target/products/eu.kalafatic.dstudio.product.id/linux/gtk/x86_64
./evostudio -nosplash -consoleLog -application eu.kalafatic.dstudio.application.server
```

Verified: the server reports `[EVO-Studio] Server listening on 127.0.0.1:48080` and
`[EVO-Studio] Headless runtime ready` using `~/.dstudio` as the configuration directory. The
process runs until stopped.

## Test status

```bash
./mvnw -B verify
```

Verified result: 224 tests, 0 failures, 0 errors, 0 skipped.

The suite includes integration tests for the TEIP server and live Aider/OpenCode harness
adapters; those require the corresponding external tools to be installed.
