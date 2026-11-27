# NeXtep (AOSP Edition)

An LSPosed multitasking workspace module for AOSP / stock-like Android (Android 15+, API 35+) providing one main window and three live side panes.

Based on [NeXtep](https://github.com/lujinxin/NeXtep) by lujinxin under the Apache License 2.0.

## Key Features

- **AOSP & Third-Party Launcher Support**: Adapted for stock-like Android and Niagara Launcher (`bitpit.launcher`), with automatic SystemUI fallback scaling for unhooked launchers.
- **Hardware Key Trigger**: Customize any physical key `keyCode` with **Single Press / Double Press / Long Press** modes and an app blacklist.
- **Window Corner Radius**: Adjustable rounded corners (`0–24 dp`, default `12 dp`) across the main window, side panes, launcher, and swap transitions.
- **External Shell Commands**: Open, close, and query workspace state via `am broadcast` and `settings get global nextep_workspace_active_v1`.

## LSPosed Scopes

Enable the module in LSPosed for:

- `com.android.systemui`
- `android` / `system` (System Framework)
- Your launcher (e.g. `bitpit.launcher`, `com.google.android.apps.nexuslauncher`, or `com.android.launcher3`)

Reboot after enabling or updating the module.

## Build

Requires JDK 21 and Android SDK 36.1 (or push to GitHub and run `.github/workflows/build-apk.yml`):

```bash
./gradlew :app:assembleDebug
```

## License

Apache License 2.0. See `LICENSE`.
