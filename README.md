# TurboMesh Computing Machine

<p align="center">
  <img src="docs/images/hero.svg" alt="TurboMesh hero banner showing Bluetooth mesh messaging, topology, and telemetry" width="100%">
</p>

Bluetooth Mesh Android project focused on BLE discovery, custom mesh messaging, topology visualization, and device/network telemetry.

## What this repo is about

TurboMesh is being built as a richer Bluetooth Mesh application than a basic scanner demo. The planned and in-progress work centers on:

- **BLE discovery and connection management**
- **Custom mesh message routing and channel workflows**
- **Topology, radar, RSSI history, and delivery stats**
- **Android-first UX for observing and tuning the network**

## Quick visual overview

<p align="center">
  <img src="docs/images/app-surfaces.svg" alt="Repository overview showing discovery, messaging, topology, stats, setup, and docs" width="100%">
</p>

## Architecture theme

<p align="center">
  <img src="docs/images/mesh-architecture.svg" alt="Architecture overview showing BLE transport, mesh core, observability, and docs" width="100%">
</p>

## Where active work is happening

The default branch is now a cleaner landing page. The more substantial implementation work currently lives on active branches:

| Branch | Purpose |
| --- | --- |
| `copilot/restart-action` | Most complete Android app branch with BLE, messaging, topology, stats, and settings work |
| `copilot/add-ble-mesh-article-scanner` | Experimental BLE/article scanner branch |
| `copilot/update-readme-and-add-docs` | Supplemental repo documentation branch |

## If you want to use the Android app

1. Start from `copilot/restart-action`.
2. Open the project in Android Studio.
3. Ensure your Android SDK is configured in `local.properties`.
4. Run on a physical Android device with BLE support and grant Bluetooth/location permissions.

## Repo-page assets

The images under `docs/images/` are SVGs so they render well on GitHub, stay lightweight in the repo, and can be edited or replaced with real screenshots later.
