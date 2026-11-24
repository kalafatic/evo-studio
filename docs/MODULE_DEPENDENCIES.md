# EVO-Studio — Module Dependencies

```
targetplatform/eu.kalafatic.dstudio.target
plugins/eu.kalafatic.dstudio.core
plugins/eu.kalafatic.dstudio.infrastructure  → core; embeds + EXPORTS com.fasterxml.jackson.* (2.20), jgit packages via Import-Package
plugins/eu.kalafatic.dstudio.harness         → core, infrastructure
plugins/eu.kalafatic.dstudio.teip            → core, infrastructure
plugins/eu.kalafatic.dstudio.application     → core, infrastructure, harness, teip; platform UI
plugins/eu.kalafatic.dstudio.ui              → core (+ platform UI, compare, jface.text)
features/eu.kalafatic.dstudio.feature        → all plugins
product/eu.kalafatic.dstudio.product         → feature
tests/eu.kalafatic.dstudio.tests             → all plugins; junit-jupiter [5.10.2,5.10.3), org.eclipse.jgit.api [7.0.0,8.0.0)
```

External: Eclipse 2025-12 SimRel; JUnit 5.10.2 resolved from the 2024-03 location in the target definition.
