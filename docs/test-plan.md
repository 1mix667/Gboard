# Gboard+ — Test Plan

- **Status:** Draft
- **Related:** [requirements.md](requirements.md) · [architecture.md](architecture.md)

## 1. Strategy

| Layer | Scope | Tooling | When |
|-------|-------|---------|------|
| Unit | Pure logic: DB, image policy/store, watcher, prefs parser, search | JUnit4 + Robolectric (real SQLite/org.json) | Every build (CI) |
| Integration (on-device) | Hook load, toolbar, buttons, vault, screenshots, liveness, handoff | Manual scripted matrix on reference device | Before release |
| Compliance | Privacy/no-network, scope | Static inspection | Before release |

**Entry criteria:** module compiles; unit suite green. **Exit criteria:** all TC pass on the device
matrix, no crash in `adb logcat -s GboardPlus` and no Gboard ANR.

## 2. Unit test suite (automated — currently green)

Run: `./gradlew testDebugUnitTest` → **67 tests, 5 suites, 0 failures.**

| Suite | Covers | Notes |
|-------|--------|-------|
| `ClipboardDatabaseTest` | Migration from a real 3.2.x dataset, text/image delete isolation, refcount, soft-delete/undo/commit state machine | Robolectric, real SQLite |
| `ClipboardImagePolicyTest` | Entry-count and byte budgets, eviction order, sampling math | Pure JVM |
| `ClipboardImageStoreTest` | Sidecar write/read/de-dup, eviction file cleanup | Robolectric |
| `ClipboardScreenshotWatcherTest` | Screenshot detection, dedup identity, catch-up | Robolectric |
| `GbpPrefsJsonTest` | Flat JSON parse, empty values, malformed input | Robolectric (org.json) |

### 2.1 Required new/added unit coverage (targets)
| Test | Requirement | Assertion |
|------|-------------|-----------|
| `DbOwnershipTest` | REQ-FUNC-028 | First open writes `owner` meta row once; reopening does not duplicate; no `DROP TABLE` |
| `PrefsIsolationTest` | REQ-NFR-009 | `GbpPrefs` never reads/writes any `ae_*` key except the handoff path |
| `AeHandoffTest` | REQ-FUNC-026 | Given an AE blob with `keyboard_enhancer_enabled=1`, the rewritten blob sets it to `0` and preserves other keys |
| `ToolbarGeometryTest` | REQ-FUNC-004/005 | Height = 0.5 × row × multiplier with floor; glyph ≤ bar height |

## 3. Integration test cases (on-device)

Format: **TC-ID — title** · Preconditions · Steps · Expected · Trace.

### Installation & activation
- **TC-001 — Install and enable** · Pre: signed APK · Steps: install, open LSPosed, enable Gboard+, scope Gboard, reboot · Expected: module listed; home/settings shows status · Traces: REQ-FUNC-001, REQ-FUNC-029
- **TC-002 — Scope is Gboard-only** · Steps: inspect LSPosed scope · Expected: only `com.google.android.inputmethod.latin` · Traces: REQ-FUNC-002, REQ-NFR-005

### Liveness & reload
- **TC-003 — Not-loaded card** · Pre: module enabled, Gboard not restarted · Steps: open app · Expected: "not loaded" card + reload control · Traces: REQ-FUNC-024
- **TC-004 — Root force-stop reload** · Pre: root · Steps: tap "Force stop Gboard", open keyboard · Expected: card clears to "loaded", button gone · Traces: REQ-FUNC-025
- **TC-005 — No-root reload** · Pre: no root · Steps: tap "Open Gboard app info", tap platform Force stop, re-open keyboard · Expected: instruction shown; card clears after hook replies · Traces: REQ-FUNC-025, REQ-NFR-010

### Toolbar
- **TC-006 — Toolbar appears** · Steps: focus a text field · Expected: one toolbar row above keyboard · Traces: REQ-FUNC-003
- **TC-007 — No duplicate on app switch** · Steps: switch between two apps with text fields · Expected: no stacked toolbars · Traces: REQ-FUNC-003
- **TC-008 — Height/button scaling** · Steps: change multipliers, reopen keyboard · Expected: bar/button sizes change; glyph never overflows · Traces: REQ-FUNC-004, REQ-FUNC-005
- **TC-009 — Master off** · Steps: disable toolbar · Expected: no toolbar · Traces: REQ-FUNC-003

### Buttons
- **TC-010 — Clipboard** · Expected: popup with newest-first entries; pin/favourite/delete/search work · Traces: REQ-FUNC-006, REQ-FUNC-013
- **TC-011 — Paste** · Expected: current clip pasted at caret · Traces: REQ-FUNC-007
- **TC-012 — Select-all** · Expected: tap selects word, long-press selects field · Traces: REQ-FUNC-008
- **TC-013 — Cursor nav** · Expected: caret jumps to start/end · Traces: REQ-FUNC-009
- **TC-014 — Trackpad** · Expected: drag moves caret; haptics on grab/tick; sensitivity respected · Traces: REQ-FUNC-010, REQ-FUNC-021, REQ-FUNC-022
- **TC-015 — Select mode** · Expected: stick drags select text; hiding trackpad/select-mode forces off · Traces: REQ-FUNC-011
- **TC-016 — Shortcut** · Expected: tap inserts #1; long-press inserts #2; empty #2 no-ops · Traces: REQ-FUNC-012

### Vault
- **TC-017 — Capacity prune** · Steps: copy > limit entries · Expected: oldest non-pinned pruned; pinned kept · Traces: REQ-FUNC-014, REQ-NFR-008
- **TC-018 — Row display mode** · Expected: full-text vs first-line toggles · Traces: REQ-FUNC-015
- **TC-019 — Image capture + dedup** · Steps: copy same image twice · Expected: one entry (hash de-dup) · Traces: REQ-FUNC-016
- **TC-020 — Image бюджеты** · Steps: exceed entries/MB budget · Expected: oldest non-pinned images evicted; files freed · Traces: REQ-FUNC-017, REQ-NFR-008
- **TC-021 — Screenshot capture** · Steps: take a screenshot with Gboard running · Expected: appears as screenshot entry · Traces: REQ-FUNC-018
- **TC-022 — Screenshot catch-up** · Steps: kill Gboard, screenshot, open keyboard · Expected: ingested on start · Traces: REQ-FUNC-018, REQ-NFR-012
- **TC-023 — Screenshot to clipboard** · Expected: with option on, screenshot becomes the clip; off → unchanged · Traces: REQ-FUNC-019

### Undo / haptics
- **TC-024 — Shake undo** · Expected: previous text restored; sensitivity affects threshold · Traces: REQ-FUNC-020
- **TC-025 — Vibration strength** · Expected: haptic amplitude scales where supported · Traces: REQ-FUNC-021

### Reliability / compatibility
- **TC-026 — Fault isolation** · Steps: inject fault in one button handler (test build) · Expected: other buttons still work; no crash reaches Gboard · Traces: REQ-NFR-004
- **TC-027 — Missing target tolerance** · Steps: run against a Gboard build with a renamed target · Expected: logged warning, no toolbar, no crash · Traces: REQ-NFR-006

## 4. Conflict test matrix (AE × Gboard+)

| # | AE installed | AE Gboard tweak | Gboard+ | Root | Expected |
|---|--------------|-----------------|---------|------|----------|
| C1 | yes | ON | enabled+scoped | yes | Single toolbar; handoff card; root action flips AE off and Gboard+ on; vault intact |
| C2 | yes | ON | enabled+scoped | no | Single toolbar (AE row removed in-process); card shows manual "Open Auto Expand" |
| C3 | yes | OFF | enabled+scoped | any | Single toolbar (Gboard+); no handoff card |
| C4 | yes | ON | disabled | any | AE toolbar only; Gboard+ adds nothing |
| C5 | no | — | enabled+scoped | any | Gboard+ toolbar; no AE artifacts |
| C6 | yes | ON | enabled+scoped | any | **Vault integrity:** both modules never corrupt `clipboard.db`; entries readable before/after |
| C7 | yes | ON→OFF (handoff) | enabled | yes | After handoff, reopening keyboard shows exactly one toolbar; AE barrel removed |

## 5. Test flow (happy path)

```
install APK
  → open app (defaults published)
  → enable in LSPosed, scope Gboard
  → reboot
  → open app: card "not loaded"
  → Force stop Gboard (or App Info)
  → open any text field  → hook loads, toolbar appears
  → app re-probe: card "loaded", button gone
  → exercise buttons → vault → images → screenshots → undo
  → (if AE present & ON) handoff → single toolbar confirmed
  → toggle prefs → verify live application
```

## 6. Device / build matrix

| Device | OS | ABI | Gboard | Status |
|--------|----|-----|--------|--------|
| OnePlus CPH2747 | Android 16 (OxygenOS) | arm64-v8a | 18.4.1.985164140 | Primary |
| Xiaomi (TBD) | Android 15/16 | arm64-v8a | current | Secondary |

## 7. Non-functional verification

| Requirement | Method |
|-------------|--------|
| REQ-NFR-001/002 | Frame timing / stopwatch (p95 targets) |
| REQ-NFR-003 | Static inspection: no `INTERNET`; no sockets |
| REQ-NFR-004/005 | Fault injection + boot test |
| REQ-NFR-006/007 | Run on matrix above |
| REQ-NFR-008 | Budget tests (unit) + on-device large-source test |
| REQ-NFR-009 | Static grep for `ae_`/authorities/actions |
| REQ-NFR-010 | No-root walkthrough with a non-technical tester |
| REQ-NFR-011 | `adb logcat -s GboardPlus` review |
| REQ-NFR-012 | Kill/update Gboard and confirm re-appearance |
