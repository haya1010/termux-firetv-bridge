# Deployment status

Updated: 2026-09-13 (Asia/Tokyo)

- Android sender APK: built and installed on `192.168.0.10:5555` (SH-01L)
- Fire TV receiver APK: built and installed on `192.168.0.42:5555` (AFTSS / sheldon)
- Termux Node.js LTS: installed
- Termux bridge server: deployed and running on TCP 8080
- LAN health check: passed
- Fire TV WebSocket receiver connection: connected
- Android sender WebSocket and VP8 hardware encoder: connected and running
- MediaProjection foreground service: running
- Android AccessibilityService: still shown as OFF in the system settings; remote-control verification is waiting for this toggle

The copied Chihiro source assets are documented in `firetv-receiver/ASSET_PROVENANCE.md`. The source directory under `Desktop/claude` was not modified.
