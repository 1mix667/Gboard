# Gboard+ — Architecture

- **Status:** Draft
- **Related:** [requirements.md](requirements.md) · [database.md](database.md)

## 1. Overview

Gboard+ has two execution contexts that never share memory:

1. **Settings app** (`io.github.imix667.gboardplus`, own UID) — Compose UI, owns the
   `SharedPreferences("prefs")`, publishes a flat JSON snapshot, probes liveness, runs opt-in
   root actions.
2. **Hook** (`GboardPlusHook`, running **inside Gboard's process** via LSPosed) — reads the
   snapshot, injects the toolbar, owns the clipboard/image vault (the DB physically lives in
   Gboard's data dir), and answers liveness pings.

They communicate through the filesystem/global-settings/IPC only. All identifiers are isolated
from Auto Expand (`gbp_*`, `io.github.imix667.gboardplus.*`), and the single intentional AE
touch-point is the handoff.

## 2. Component diagram

```mermaid
flowchart LR
    subgraph App["Gboard+ app (own UID)"]
        UI["SettingsScreen (Compose)"]
        P["GbpPrefs\nSharedPreferences 'prefs'"]
        PUB["publish():\nsu cp + Settings.Global"]
        PING["pingHook():\nordered broadcast"]
        PRV["GbpPrefsProvider\n(read-only)"]
        UI --> P --> PUB
        UI --> PING
        P -.-> PRV
    end

    subgraph IPC["Cross-process channels"]
        FILEP["/data/local/tmp/gboardplus_prefs.json"]
        GLOB["Settings.Global\ngbp_prefs_json"]
        PINCH["broadcast\nio.github...gboardplus.PING"]
    end

    subgraph Gboard["Gboard process (com.google.android.inputmethod.latin)"]
        HOOK["GboardPlusHook"]
        TB["Toolbar UI"]
        VLT["Vault\nClipboardDatabase + ClipboardImageStore"]
        SNAP["ClipboardScreenshotWatcher"]
        LIVE["Liveness receiver"]
        AE["AE detect + row suppression"]
    end

    PUB --> FILEP
    PUB --> GLOB
    FILEP --> HOOK
    GLOB --> HOOK
    PING --> PINCH --> LIVE --> PING
    HOOK --> TB
    HOOK --> VLT
    HOOK --> SNAP
    HOOK --> AE

    subgraph AEmod["Auto Expand (separate app+scope)"]
        AEH["AE KeyboardHook"]
        AEP["/data/local/tmp/tweaks_prefs.json"]
    end
    AE -->|reads keyboard_enhancer_enabled| AEP
    AE -.->|removes row tagged 'ae_kb_toolbar'| TB
```

## 3. Pref propagation (sequence)

```mermaid
sequenceDiagram
    participant U as User
    participant UI as SettingsScreen
    participant P as GbpPrefs
    participant FS as /data/local/tmp
    participant G as Settings.Global
    participant H as GboardPlusHook
    U->>UI: toggle "Save copied images"
    UI->>P: prefs.edit().putBoolean(...).apply()
    UI->>P: publish()
    P->>FS: cp gboardplus_prefs.json (su)
    P->>G: settings put global gbp_prefs_json <b64> (su)
    loop every ~2s or next input view
        H->>FS: read gboardplus_prefs.json
        H->>H: refreshPrefs() → cached* fields
    end
```

## 4. Toolbar injection (sequence)

```mermaid
sequenceDiagram
    participant GB as Gboard
    participant H as GboardPlusHook
    participant V as Keyboard content layout
    GB->>H: InputMethodService.setInputView(view) [after hook]
    H->>H: refreshPrefs(ctx); ensureLiveness(ctx)
    alt toolbar enabled
        H->>V: find child tagged 'gbp_kb_toolbar'
        alt not present
            H->>V: addView(toolbar)
            H->>V: suppressAeToolbarIfActive() → remove 'ae_kb_toolbar'
            H->>H: post{} size bar from key-row height
        end
    end
```

## 5. Liveness + reload (sequence)

```mermaid
sequenceDiagram
    participant UI as SettingsScreen
    participant P as GbpPrefs
    participant GB as Gboard (process)
    participant H as Hook receiver
    UI->>P: pingHook()
    P->>GB: sendOrderedBroadcast(PING)
    alt hook loaded
        H-->>P: setResultCode(RESULT_OK)
        P-->>UI: loaded=true → hide card+button
    else not loaded
        P-->>UI: loaded=false → show card
        UI->>P: force-stop
        alt root
            P->>GB: su -c am force-stop com.google.android.inputmethod.latin
            UI->>P: re-probe after 1.2s
        else no root
            UI->>GB: ACTION_APPLICATION_DETAILS_SETTINGS (user taps Force stop)
        end
    end
```

## 6. Auto Expand handoff (sequence)

```mermaid
sequenceDiagram
    participant H as GboardPlusHook
    participant AEF as /data/local/tmp/tweaks_prefs.json
    participant UI as SettingsScreen
    participant R as su
    H->>AEF: read keyboard_enhancer_enabled
    alt == "1"
        H->>H: remove view tagged 'ae_kb_toolbar'
        UI->>UI: show handoff card
        opt user taps "Disable it for me (root)"
            UI->>AEF: write keyboard_enhancer_enabled=0 (via su)
            UI->>R: settings put global ae_prefs_json <b64>
            UI->>H: enable Gboard+ toolbar; re-publish
        end
    end
```

## 7. Module structure

```
io.github.imix667.gboardplus
├─ GboardPlusHook.kt            # IXposedHookLoadPackage; toolbar, buttons, liveness, AE handoff
├─ ClipboardDatabase.kt         # SQLite vault (adopted)
├─ ClipboardImageStore.kt       # webp sidecar store
├─ ClipboardImagePolicy.kt      # pure budget/eviction + sampling math
├─ ClipboardScreenshotWatcher.kt# screenshot detection (MediaStore/File watchers)
├─ ClipboardSearch.kt           # scored, diacritic-insensitive search
├─ GbpPrefsJson.kt              # flat JSON parser (hook + app)
├─ GbpPrefs.kt                  # app-side prefs, publish, ping, root actions, AE handoff
├─ GbpPrefsProvider.kt          # read-only prefs provider
├─ App.kt / MainActivity.kt     # application + Compose settings UI
└─ (Xposed entry via assets/xposed_init → GboardPlusHook)
```

## 8. Trust & failure boundaries

- **Narrowest scope:** only `com.google.android.inputmethod.latin` (REQ-FUNC-002).
- **All hook bodies try/caught**; failures log under `GboardPlus` and return (REQ-NFR-004/005).
- **No network permission**; vault data stays in private app/Gboard dirs (REQ-NFR-003).
- **Isolated identity** from AE except the documented handoff (REQ-NFR-009).

## 9. Deployment

- Build: Gradle (AGP 8.13.2, Kotlin 2.1.0, JDK 17+), `arm64-v8a`, `minSdk 35`/`targetSdk 36`.
- Install: sideload APK → enable in LSPosed → scope Gboard → force-stop Gboard (the app's card
  guides this).
- CI: `.github/workflows/build-release.yml` builds a release APK on version tags.
