# EVO-Studio — Launch Guide

| Launch target | How | Notes |
|---|---|---|
| **Development** | Eclipse → `.launches/EVOStudio_RCP_Application.launch` | PDE Runtime Workbench |
| **Debug** | Eclipse Debug Configurations (duplicate of RCP Application) | |
| **Headless server** | `scripts/run-headless.sh` | |
| **Tests** | `./mvnw verify` | PDE JUnit (Tycho Surefire) |
| **Product (built)** | `product/eu.kalafatic.dstudio.product/target/products/eu.kalafatic.dstudio.product.id/linux/gtk/x86_64/evostudio` | needs display |

UPM detects the built product as `Product: evostudio` (kind `eclipse-product`). GUI launches were not verified headlessly; the product launcher's existence/executability was verified after build.
