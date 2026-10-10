# Gboard+ — Software Requirements Specification

- **Product:** Gboard+
- **Version:** 1.0.0
- **Owner:** krom3
- **Status:** Draft → for review
- **Related:** [design.md](design.md) · [database.md](database.md) · [architecture.md](architecture.md) · [test-plan.md](test-plan.md)

Gboard+ is an LSPosed (Xposed) module that adds a toolbar, clipboard vault (text + images),
shortcuts, cursor/trackpad tools and screenshot capture **inside the Gboard process**. It was
extracted from *Auto Expand* (AE) so that Gboard features live in a separate module with an
isolated scope, preferences and storage. Gboard+ and Auto Expand must **not conflict** when both
are installed.

Definitions: **AE** = Auto Expand; **hook** = the code Gboard+ injects into Gboard via LSPosed;
**app** = the Gboard+ settings application; **toolbar** = the extra button row above the keyboard.

---

## Part A — Functional Requirements

### REQ-FUNC-001: Module installation and activation
- **ID:** REQ-FUNC-001
- **Title:** Install and activate as an LSPosed module
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall install as a standalone Android application and register as an
LSPosed/Xposed module so that it can be enabled and scoped from the LSPosed Manager.

**Rationale:** Without a valid module declaration the hook cannot be loaded into any target process.

**Acceptance Criteria:**
- The APK declares `xposedmodule=true`, `xposedminversion=82`, and `assets/xposed_init` pointing at `io.github.kvmy666.gboardplus.GboardPlusHook`.
- The module appears in LSPosed Manager with the name "Gboard+" and its description.
- Enabling the module and rebooting causes the hook to load in `com.google.android.inputmethod.latin`.
- The app launches to its settings screen without crashing when the module is disabled.

**Verification Method:** Demonstration
**More Information:** REQ-FUNC-002, REQ-NFR-004.

### REQ-FUNC-002: Gboard-only scope
- **ID:** REQ-FUNC-002
- **Title:** Restrict the Xposed scope to Gboard only
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall declare exactly one suggested Xposed scope entry,
`com.google.android.inputmethod.latin`, and inject no other process.

**Rationale:** A narrow scope prevents Gboard+ from interfering with SystemUI, system_server, or
other apps, and is the first line of defence against conflicts with Auto Expand.

**Acceptance Criteria:**
- `res/values/arrays.xml` `xposed_scope` contains only `com.google.android.inputmethod.latin`.
- The hook returns immediately for any other `packageName`.
- No `com.android.systemui`, `android`, or `com.oppo.quicksearchbox` hook is installed.

**Verification Method:** Inspection
**More Information:** REQ-FUNC-026.

### REQ-FUNC-003: Toolbar injection (master toggle)
- **ID:** REQ-FUNC-003
- **Title:** Inject a toolbar row below the keyboard
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall, when the "Enable keyboard toolbar" preference is on, inject a
horizontal button row inside Gboard's keyboard content layout every time the input view is created.

**Rationale:** This is the core feature — the surface on which all buttons live.

**Acceptance Criteria:**
- After `setInputView`, exactly one Gboard+ toolbar (tag `gbp_kb_toolbar`) exists in the keyboard view tree.
- Re-creating the input view (app switch) does not duplicate the toolbar.
- When the master toggle is off, no toolbar is injected.

**Verification Method:** Test
**More Information:** REQ-FUNC-004, REQ-FUNC-005, REQ-FUNC-026.

### REQ-FUNC-004: Toolbar height scaling
- **ID:** REQ-FUNC-004
- **Title:** Scale toolbar height
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall size the toolbar height relative to the keyboard key-row height as
configured by the "Toolbar height (×)" preference (default 1.0).

**Rationale:** Devices and keyboard themes differ; users need to fit the bar to their layout.

**Acceptance Criteria:**
- A multiplier of 1.0 produces a bar height equal to half a key row.
- The height applies on the next input-view creation without an app restart.
- A floor prevents the bar from collapsing to zero.

**Verification Method:** Test
**More Information:** REQ-FUNC-003, REQ-FUNC-023.

### REQ-FUNC-005: Button size scaling
- **ID:** REQ-FUNC-005
- **Title:** Scale toolbar button size independently
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall scale button width and glyph size by the "Button size (×)"
preference, independent of the toolbar height.

**Rationale:** Decoupling button and bar scaling lets users keep a short bar with readable icons.

**Acceptance Criteria:**
- A multiplier of 1.0 renders the default button size.
- Glyph size never exceeds the bar height.
- Changes apply without an app restart.

**Verification Method:** Test
**More Information:** REQ-FUNC-004.

### REQ-FUNC-006: Clipboard button
- **ID:** REQ-FUNC-006
- **Title:** Clipboard history button
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a Clipboard toolbar button that opens the clipboard-history
popup when tapped, when `btn_clipboard_enabled` is on.

**Rationale:** Fast access to clipboard history is a primary reason users install the module.

**Acceptance Criteria:**
- Tapping the button opens the vault popup listing stored entries, newest first.
- The popup supports pin, favourite, delete and search interactions.
- Hiding the button disables the entry point but keeps stored entries.

**Verification Method:** Test
**More Information:** REQ-FUNC-013, REQ-FUNC-014.

### REQ-FUNC-007: Paste button
- **ID:** REQ-FUNC-007
- **Title:** Paste button
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a Paste button that inserts the current clipboard content,
when `btn_paste_enabled` is on.

**Rationale:** One-tap paste is a common keyboard ergonomic request.

**Acceptance Criteria:**
- Tapping pastes the text at the caret.
- Non-text clips are handled without crashing (no crash, no partial corruption).

**Verification Method:** Test
**More Information:** REQ-FUNC-006.

### REQ-FUNC-008: Select-all button
- **ID:** REQ-FUNC-008
- **Title:** Select-all / select-word button
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a button where tap selects the last word and long-press
selects all text, when `btn_selectall_enabled` is on.

**Rationale:** Reduces friction for selection without the select handle.

**Acceptance Criteria:**
- Tap selects the word adjacent to the caret.
- Long-press selects the entire field.
- Works in a standard editable field.

**Verification Method:** Test
**More Information:** REQ-FUNC-009, REQ-FUNC-011.

### REQ-FUNC-009: Cursor navigation button
- **ID:** REQ-FUNC-009
- **Title:** Cursor start/end navigation
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a Cursor-navigation button that jumps the caret to the start
or end of the field, when `btn_cursor_enabled` is on.

**Rationale:** Useful in long fields; off by default to keep the bar compact.

**Acceptance Criteria:**
- Both start and end targets are reachable from the button interaction.
- The caret moves without altering text.

**Verification Method:** Test
**More Information:** REQ-FUNC-010.

### REQ-FUNC-010: Trackpad stick
- **ID:** REQ-FUNC-010
- **Title:** Trackpad cursor stick
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a trackpad stick for fine cursor movement with grab and
steering haptics, when `btn_trackpad_enabled` is on.

**Rationale:** Precise caret placement is otherwise tedious on touch keyboards.

**Acceptance Criteria:**
- Dragging the stick moves the caret relative to the drag.
- Haptics fire on grab and on steering ticks when `trackpad_haptics_enabled` is on.
- Sensitivity responds to `trackpad_sensitivity`.

**Verification Method:** Test
**More Information:** REQ-FUNC-011, REQ-FUNC-022.

### REQ-FUNC-011: Select-mode toggle
- **ID:** REQ-FUNC-011
- **Title:** Select mode toggle
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a Select-mode toggle button; while on, drags on the trackpad
stick extend a text selection instead of moving the caret.

**Rationale:** Gives a keyboard-only path to arbitrary selection.

**Acceptance Criteria:**
- Toggling on changes stick drags to selection; toggling off restores caret movement.
- If either Select-mode or Trackpad is hidden, Select-mode is forced off.

**Verification Method:** Test
**More Information:** REQ-FUNC-010.

### REQ-FUNC-012: Text shortcut button
- **ID:** REQ-FUNC-012
- **Title:** Text-shortcut insertion
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide a shortcut button where tap inserts `shortcut_text_1` and
long-press inserts `shortcut_text_2`, when `btn_shortcut_enabled` is on; an empty long-press value
disables the long-press action.

**Rationale:** Snippets (e-mail, address) are a frequent text-entry need.

**Acceptance Criteria:**
- Tap inserts shortcut 1; long-press inserts shortcut 2 when non-empty.
- Empty shortcut leaves the field unchanged.

**Verification Method:** Test
**More Information:** REQ-FUNC-023.

### REQ-FUNC-013: Clipboard vault operations
- **ID:** REQ-FUNC-013
- **Title:** Clipboard vault with pin, favourite and search
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall store clipboard entries in a persistent vault supporting pinning,
favouriting, deletion and text search.

**Rationale:** Persistence and organisation are what make the vault useful across sessions.

**Acceptance Criteria:**
- Copied text is captured and displayed newest-first in the vault.
- Pin and favourite are persisted and reflected in row styling and sort orders.
- Search filters entries case-insensitively; clearing the query restores the full list.
- Deleting an entry removes it (soft-delete honoured by the store) and it does not reappear.

**Verification Method:** Test
**More Information:** REQ-FUNC-014, REQ-FUNC-028, [database.md](database.md).

### REQ-FUNC-014: Clipboard capacity pruning
- **ID:** REQ-FUNC-014
- **Title:** Prune oldest clipboard entries at the limit
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall prune the oldest non-pinned entries when the vault reaches
`clipboard_max_entries`.

**Rationale:** Bounds storage and memory; pinned entries are exempt by design.

**Acceptance Criteria:**
- When the limit is reached, insertion prunes oldest non-pinned entries first.
- Pinned entries are never pruned by this rule.
- Lowering the limit takes effect for subsequent inserts.

**Verification Method:** Test
**More Information:** REQ-FUNC-013.

### REQ-FUNC-015: Clipboard row display mode
- **ID:** REQ-FUNC-015
- **Title:** Full-text vs first-line rows
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall show either the full text or only the first line of each clipboard
row according to `clip_full_text_enabled` (default full text).

**Rationale:** Density preference.

**Acceptance Criteria:**
- With the option on, multi-line entries show all lines subject to the row height.
- With it off, only the first line is shown.

**Verification Method:** Test
**More Information:** REQ-FUNC-013.

### REQ-FUNC-016: Image vault capture
- **ID:** REQ-FUNC-016
- **Title:** Save copied images to the vault
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall, when `clip_images_enabled` is on, capture images copied to the
system clipboard into the vault (thumbnail + full-size derivatives) with hash-based de-duplication.

**Rationale:** Image clipboard history is a distinguishing feature.

**Acceptance Criteria:**
- Copied images are stored and shown as image rows with a thumbnail.
- Identical images are de-duplicated by content hash (refcount), not duplicated.
- When the option is off, no capture code path runs.

**Verification Method:** Test
**More Information:** REQ-FUNC-017, REQ-FUNC-028.

### REQ-FUNC-017: Image vault limits
- **ID:** REQ-FUNC-017
- **Title:** Bound image vault by entry count and bytes
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall evict image entries per `clip_img_max_entries` and
`clip_img_max_mb` without evicting pinned entries.

**Rationale:** Images dominate storage; explicit budgets keep the vault predictable.

**Acceptance Criteria:**
- Exceeding either budget evicts oldest non-pinned image entries until within budget.
- Pinned images are exempt.
- Eviction frees the associated on-disk files.

**Verification Method:** Test
**More Information:** REQ-FUNC-016.

### REQ-FUNC-018: Screenshot auto-capture
- **ID:** REQ-FUNC-018
- **Title:** Capture system screenshots into the vault
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall, when `shot_capture_enabled` is on, detect screenshots taken by the
system and ingest them into the image vault, tagged as screenshots.

**Rationale:** Screenshot history is a common need and the vault already lives in Gboard's process.

**Acceptance Criteria:**
- A screenshot taken while Gboard is running appears in the vault as a screenshot entry.
- Screenshots taken while Gboard is not running are ingested on next keyboard start (catch-up).
- Duplicate ingestion of the same file identity is prevented.

**Verification Method:** Test
**More Information:** REQ-FUNC-019, REQ-FUNC-016.

### REQ-FUNC-019: Screenshot to clipboard
- **ID:** REQ-FUNC-019
- **Title:** Also push captured screenshots to the system clipboard
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall, when `shot_to_clipboard_enabled` is on, additionally place each
captured screenshot on the system clipboard.

**Rationale:** Enables immediate paste; off by default because it overwrites the user's clip.

**Acceptance Criteria:**
- With the option on, a captured screenshot becomes the current clipboard item.
- With the option off, the system clipboard is not modified.

**Verification Method:** Test
**More Information:** REQ-FUNC-018.

### REQ-FUNC-020: Undo
- **ID:** REQ-FUNC-020
- **Title:** Undo last text edit
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall support undoing the last edit via a shake gesture
(`shake_undo_enabled`) and/or an Undo button (`undo_button_enabled`).

**Rationale:** Recovers from accidental input, especially with one-tap paste and shortcuts.

**Acceptance Criteria:**
- After a qualifying edit, a shake restores the previous text state.
- The Undo button performs the same action when enabled.
- `shake_sensitivity` affects the trigger threshold.

**Verification Method:** Test
**More Information:** REQ-FUNC-021.

### REQ-FUNC-021: Haptics and vibration strength
- **ID:** REQ-FUNC-021
- **Title:** Haptic feedback and global vibration strength
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide haptic feedback for grab/tick interactions and shall honour a
global `vibration_strength` scaling.

**Rationale:** Consistency with the keyboard's tactile feel.

**Acceptance Criteria:**
- Enabling `/` disabling trackpad haptics takes effect without reboot.
- `vibration_strength` scales the produced haptic amplitude where the platform supports it.

**Verification Method:** Test
**More Information:** REQ-FUNC-010, REQ-FUNC-022.

### REQ-FUNC-022: Trackpad sensitivity and root DPAD fallback
- **ID:** REQ-FUNC-022
- **Title:** Sensitivity and optional privileged DPAD delivery
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall apply `trackpad_sensitivity` to movement scaling and shall, when
`trackpad_root_injection_enabled` is on, optionally deliver cursor key events via a privileged
(`su`) command for fields that swallow IME DPAD events.

**Rationale:** Some apps ignore IME-delivered navigation; a root fallback keeps the trackpad usable.

**Acceptance Criteria:**
- Higher sensitivity moves the caret further per drag unit.
- With root injection on and root available, navigation still works in a DPAD-swallowing field.
- With root unavailable, the fallback is skipped and the app does not crash.

**Verification Method:** Test
**More Information:** REQ-FUNC-027.

### REQ-FUNC-023: Preference persistence and publication to the hook
- **ID:** REQ-FUNC-023
- **Title:** Persist settings and publish them to the hook
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall persist settings in the app's private `SharedPreferences` and publish
a flat JSON snapshot to `/data/local/tmp/gboardplus_prefs.json` (and mirror it to
`Settings.Global gbp_prefs_json`) so the hook can read it.

**Rationale:** The hook lives in Gboard's process and cannot read the app's private prefs directly.

**Acceptance Criteria:**
- Changing any toggle updates the published snapshot within the hook's refresh interval.
- First launch writes defaults so the snapshot always exists.
- The published channel uses only Gboard+ keys (never `ae_*`).

**Verification Method:** Test
**More Information:** REQ-NFR-009, REQ-FUNC-024.

### REQ-FUNC-024: Hook liveness card
- **ID:** REQ-FUNC-024
- **Title:** Show whether Gboard+ is loaded in Gboard
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall determine whether the hook is loaded in Gboard by sending an ordered
broadcast (`io.github.kvmy666.gboardplus.PING`) to Gboard and reading the hook's reply, and shall
display a card stating the result.

**Rationale:** After enabling the module, users need to know whether a Gboard restart is still
required, which is otherwise invisible.

**Acceptance Criteria:**
- When the hook is loaded, the card reads "loaded" and offers no force-stop button.
- When not loaded, the card explains a restart is required and shows the reload control.
- The card updates automatically after a reload and on re-check.

**Verification Method:** Test
**More Information:** REQ-FUNC-025.

### REQ-FUNC-025: Reload Gboard (force-stop)
- **ID:** REQ-FUNC-025
- **Title:** Force-stop Gboard to load the hook
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall provide, when the hook is not loaded, a control that force-stops
Gboard so the hook loads on the next keyboard start: with root via `am force-stop`, and without root
by deep-linking to Gboard's App Info page where the user taps the platform's Force stop.

**Rationale:** Hooks load at process start; Gboard is long-lived, so a restart is mandatory and must
be one tap for non-technical users.

**Acceptance Criteria:**
- With root, the button runs `su -c am force-stop com.google.android.inputmethod.latin` and then re-probes.
- Without root, the button opens `ACTION_APPLICATION_DETAILS_SETTINGS` for Gboard and instructs the user.
- After the hook becomes reachable, the card and button disappear.

**Verification Method:** Test
**More Information:** REQ-FUNC-024, REQ-FUNC-027.

### REQ-FUNC-026: Auto Expand detection and handoff
- **ID:** REQ-FUNC-026
- **Title:** Detect Auto Expand's Gboard tweak and take over
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall detect when Auto Expand's Gboard toolbar is still enabled, remove
Auto Expand's injected toolbar row in the Gboard process so the user sees a single toolbar, surface
a handoff card, and — when root is available — offer a one-tap action that disables Auto Expand's
Gboard toggle and enables Gboard+.

**Rationale:** The user previously embedded the Gboard tweak inside Auto Expand. Both modules must
not show two toolbars; Gboard+ must be able to take over without the user editing AE by hand.

**Acceptance Criteria:**
- When AE's `keyboard_enhancer_enabled` is "1", Gboard+ removes the view tagged `ae_kb_toolbar` from the keyboard layout (re-checked after injection).
- The settings app shows the handoff card whenever AE's toggle is detected as on.
- The root action rewrites AE's published channels to `keyboard_enhancer_enabled=0`, enables Gboard+, and re-checks.
- When AE's toggle is off, no AE row exists and the card is hidden.

**Verification Method:** Test
**More Information:** REQ-FUNC-002, REQ-FUNC-027, [design.md](design.md).

### REQ-FUNC-027: No-root graceful degradation
- **ID:** REQ-FUNC-027
- **Title:** Operate with full core function without root
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall deliver the toolbar and all non-privileged features without root, and
shall replace every root-only action with a documented manual equivalent.

**Rationale:** Most target users are unrooted or unwilling to grant root; the core value must not
depend on it.

**Acceptance Criteria:**
- Toolbar, buttons, vault, images, screenshots and settings work with no root.
- Root-only actions are hidden or shown as manual steps (App Info deep link) when root is absent.
- No ANR, crash or infinite prompt when `su` is missing.

**Verification Method:** Demonstration
**More Information:** REQ-FUNC-022, REQ-FUNC-025, REQ-FUNC-026.

### REQ-FUNC-028: Clipboard database adoption
- **ID:** REQ-FUNC-028
- **Title:** Adopt the existing clipboard database without data loss
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall open the existing clipboard database
(`.../com.google.android.inputmethod.latin/databases/clipboard.db`) as Gboard+ becomes its owner,
preserving existing entries and columns, and shall mark ownership without dropping data.

**Rationale:** Users who used the AE Gboard tweak already have a vault; the extraction must not lose it.

**Acceptance Criteria:**
- Opening the existing database shows all prior entries.
- First open under Gboard+ writes an ownership marker to the meta table (idempotent).
- No `DROP TABLE` or destructive migration runs on adoption.
- Concurrent access by an older AE build does not corrupt the database.

**Verification Method:** Test
**More Information:** REQ-FUNC-013, [database.md](database.md).

### REQ-FUNC-029: Module status indicator
- **ID:** REQ-FUNC-029
- **Title:** Show module active state
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall display whether the module is active in the settings app.

**Rationale:** Confirms successful activation after enabling and rebooting.

**Acceptance Criteria:**
- When the hook is reachable, the status reads active; otherwise inactive with guidance.
- The status refreshes on app resume.

**Verification Method:** Test
**More Information:** REQ-FUNC-024.

---

## Part B — Non-Functional Requirements

### REQ-NFR-001: Toolbar injection performance
- **ID:** REQ-NFR-001
- **Title:** Toolbar injection latency
- **Category:** Performance
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall add the toolbar to the keyboard view within 100 ms of input-view
creation, measured on the reference device.

**Rationale:** The keyboard must feel instant; a lagging toolbar degrades every text field.

**Acceptance Criteria:**
- Injection completes in ≤ 100 ms (p95) over 50 input-view creations on CPH2747.
- No frame drop exceeding 2 frames is attributable to injection.

**Verification Method:** Test
**More Information:** REQ-FUNC-003.

### REQ-NFR-002: Clipboard open latency
- **ID:** REQ-NFR-002
- **Title:** Clipboard popup open latency
- **Category:** Performance
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall open the clipboard popup within 200 ms (p95) with up to the default
maximum number of text entries.

**Rationale:** Perceived responsiveness of the most-used surface.

**Acceptance Criteria:**
- Popup shows content within 200 ms with 500 text entries present.
- Scrolling the vault stays smooth (no dropped frames over 5 s).

**Verification Method:** Test
**More Information:** REQ-FUNC-006, REQ-FUNC-014.

### REQ-NFR-003: Privacy — local only
- **ID:** REQ-NFR-003
- **Title:** No network access and no telemetry
- **Category:** Security
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall not request the INTERNET permission, shall not transmit clipboard or
image data off-device, and shall contain no analytics or remote endpoints.

**Rationale:** Clipboard contents are among the most sensitive user data; local-only is a trust
requirement.

**Acceptance Criteria:**
- `AndroidManifest.xml` declares no `android.permission.INTERNET`.
- Static inspection finds no outbound HTTP/socket usage in module code.
- Vault and image files remain in the app's or Gboard's private data directories.

**Verification Method:** Inspection
**More Information:** REQ-FUNC-016, REQ-FUNC-028.

### REQ-NFR-004: Reliability — never break Gboard
- **ID:** REQ-NFR-004
- **Title:** Fail-safe hook installation
- **Category:** Reliability
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall isolate every hook and callback in guarded execution so that an
exception in Gboard+ code can never propagate into Gboard or the system and cause a crash or
bootloop.

**Rationale:** A crashing IME degrades the whole device; correctness of the host is paramount.

**Acceptance Criteria:**
- Every hook entry point and callback body is wrapped in try/catch that logs and returns.
- A fault injected into one button handler does not prevent other buttons from working.
- No uncaught exception reaches the Gboard process under the test fault matrix.

**Verification Method:** Test
**More Information:** REQ-NFR-006.

### REQ-NFR-005: Boot safety
- **ID:** REQ-NFR-005
- **Title:** No bootloop on misconfiguration
- **Category:** Reliability
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall not depend on any system-server or SystemUI hook, and shall complete
module load without blocking, so a misconfiguration cannot prevent boot.

**Rationale:** Gboard+ must be safe to leave enabled; boot stability is non-negotiable.

**Acceptance Criteria:**
- The module scope contains no system_server/SystemUI entry.
- Loading the module performs no long blocking work at class-load time.
- The device boots normally with the module enabled and Gboard selected.

**Verification Method:** Demonstration
**More Information:** REQ-FUNC-002.

### REQ-NFR-006: Compatibility — Gboard versions and Android
- **ID:** REQ-NFR-006
- **Title:** Gboard and OS compatibility
- **Category:** Compatibility
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall operate on Android 15+ (verified on Android 16 / OxygenOS) against
current Gboard releases (verified against 18.4.1), degrading safely when an expected class or method
is absent.

**Rationale:** Gboard obfuscates and changes internals; robustness across versions is required.

**Acceptance Criteria:**
- All hooks use `findAndHookMethod`/`hookAllMethods` with caught failures so a missing target is non-fatal.
- Function verified on Gboard 18.4.1 on CPH2747 (Android 16).
- A missing target yields a logged warning and no toolbar, not a crash.

**Verification Method:** Test
**More Information:** REQ-NFR-004.

### REQ-NFR-007: Architecture support
- **ID:** REQ-NFR-007
- **Title:** arm64-v8a support
- **Category:** Portability
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall run on arm64-v8a Android devices.

**Rationale:** The reference device and target audience are arm64.

**Acceptance Criteria:**
- `abiFilters` includes `arm64-v8a`.
- The APK installs and runs on the reference device.

**Verification Method:** Demonstration
**More Information:** REQ-NFR-006.

### REQ-NFR-008: Scalability of the vault
- **ID:** REQ-NFR-008
- **Title:** Bounded vault growth
- **Category:** Scalability
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall bound text and image vault growth to configured limits and keep
runtime memory usage independent of total historical entries.

**Rationale:** Prevents unbounded storage and Gboard heap growth.

**Acceptance Criteria:**
- Steady-state vault size respects `clipboard_max_entries`, `clip_img_max_entries`, `clip_img_max_mb`.
- Images are loaded/decoded with sampling so a large source is never fully decoded in memory.

**Verification Method:** Test
**More Information:** REQ-FUNC-014, REQ-FUNC-017.

### REQ-NFR-009: Maintainability — isolation
- **ID:** REQ-NFR-009
- **Title:** Isolated identity from Auto Expand
- **Category:** Maintainability
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall use its own applicationId, authorities, preference keys, IPC file
names, broadcast actions and log tags, distinct from Auto Expand, so the two modules cannot
cross-talk except through the explicit handoff.

**Rationale:** Clean separation is what makes coexistence safe and each module independently
maintainable.

**Acceptance Criteria:**
- No `ae_*` key, authority, file name or action is read or written except in the documented AE handoff.
- Log tag is `GboardPlus`.
- The package name is `io.github.kvmy666.gboardplus`.

**Verification Method:** Inspection
**More Information:** REQ-FUNC-023, REQ-FUNC-026.

### REQ-NFR-010: Usability — one-tap recovery
- **ID:** REQ-NFR-010
- **Title:** Recovery without technical steps
- **Category:** Usability
- **Priority:** High
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall let a non-technical user reach a working state without root, using at
most one guided tap to restart Gboard.

**Rationale:** The audience does not know how to grant root or read ADB.

**Acceptance Criteria:**
- The "not loaded" card provides either a root button or an App Info deep link plus instructions.
- No terminal, ADB or root knowledge is required to load the module without root.
- The card disappears automatically once the hook answers.

**Verification Method:** Demonstration
**More Information:** REQ-FUNC-024, REQ-FUNC-025, REQ-FUNC-027.

### REQ-NFR-011: Observability — diagnostics
- **ID:** REQ-NFR-011
- **Title:** Structured, taggable logging
- **Category:** Observability
- **Priority:** Low
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall log lifecycle and error events under a stable tag so issues can be
diagnosed with `adb logcat -s GboardPlus`.

**Rationale:** Field debugging of an injection module needs a clean, filterable signal.

**Acceptance Criteria:**
- Injection, liveness registration and handoff actions emit log lines under `GboardPlus`.
- Errors are logged with their exception type, never silently swallowed without a trace.

**Verification Method:** Inspection
**More Information:** REQ-NFR-004.

### REQ-NFR-012: Activation resilience
- **ID:** REQ-NFR-012
- **Title:** Survive Gboard process restarts and updates
- **Category:** Availability
- **Priority:** Medium
- **Status:** Approved
- **Owner:** krom3

**Statement:** The system shall re-establish the toolbar, listeners and liveness after Gboard is
force-stopped, updated or killed by the system, without user intervention.

**Rationale:** Gboard is restarted frequently; features must re-appear automatically.

**Acceptance Criteria:**
- After a Gboard restart, the toolbar reappears on next keyboard show with no app action.
- After a Gboard app update, the module still loads (LSPosed re-injects on process start).

**Verification Method:** Test
**More Information:** REQ-FUNC-024, REQ-FUNC-025.
