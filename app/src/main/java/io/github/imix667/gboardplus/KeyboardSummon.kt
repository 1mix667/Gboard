package io.github.imix667.gboardplus

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.quicksettings.TileService
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * "Keyboard over anything" summon path for the Quick Settings tiles.
 *
 * Parsec (and games in general) has no text field, so the system keyboard
 * can never be raised there the normal way. The tiles therefore launch
 * [ShowKeyboardActivity]: a transparent activity whose window is an ~8dp
 * strip pinned to the top of the screen. Its single EditText takes focus,
 * the system raises Gboard for it, and because the window is only a strip,
 * the app underneath stays visible and keeps receiving touches everywhere
 * else. The keyboard that appears is the real Gboard — with this module's
 * toolbar and game panels inside.
 *
 * Panel hand-off: before the IME shows, the requested mode is stamped into
 * the shared prefs as `pending_panel_mode` (+ timestamp) through
 * [GbpPrefs.setPendingPanelMode]. The hook consumes the stamp at
 * onStartInputView and opens the matching panel (see GboardPlusHook).
 *
 * Tasking: the activity lives in its OWN task (manifest taskAffinity
 * ".summon" + launchMode singleInstance, intents carry NEW_TASK). In the
 * app's default task it would drag MainActivity to the front with it and
 * the strip would sit over the settings UI instead of over Parsec.
 *
 * Everything here is plain framework API (no Compose), so this file also
 * compiles standalone in the local kotlinc check.
 */
class ShowKeyboardActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "summon_mode"
        const val MODE_PLAIN = "plain"
        const val MODE_PAD = "pad"
        const val MODE_WIN = "win"

        fun intent(ctx: Context, mode: String): Intent =
            Intent(ctx, ShowKeyboardActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var editText: EditText? = null

    /** Stamp of the pending-mode write from the latest [applyMode]. */
    private var modeTs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // One invisible EditText: it exists only to hold input focus so the
        // system raises the IME. All colours transparent — nothing of this
        // activity may ever be visible on screen.
        val et = EditText(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            isFocusable = true
            isFocusableInTouchMode = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        setContentView(
            et,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Shrink the window to a thin strip at the top. The rest of the
        // screen belongs to the app underneath: it stays visible and its
        // touches never reach us.
        val stripPx = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val attrs = window.attributes
        attrs.width = WindowManager.LayoutParams.MATCH_PARENT
        attrs.height = stripPx
        attrs.gravity = Gravity.TOP
        window.attributes = attrs

        editText = et
        applyMode(intent)
        et.post { showKeyboard() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMode(intent)
        editText?.post { showKeyboard() }
    }

    override fun onResume() {
        super.onResume()
        // Covers re-summon while this activity is still the (invisible)
        // foreground strip after the IME was dismissed with Back.
        editText?.post { showKeyboard() }
    }

    // Back is intentionally NOT overridden: the system first hides the IME
    // (which also ends the module's panel mode via onFinishInputView), and a
    // further Back finishes this activity by default.

    /**
     * Stamp the requested panel mode into the shared prefs BEFORE the IME
     * shows — the hook reads it when input starts on the EditText above.
     * Plain mode clears the key so a stale stamp can never fire.
     */
    private fun applyMode(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_PAD -> GbpPrefs.setPendingPanelMode(this, "pad")
            MODE_WIN -> GbpPrefs.setPendingPanelMode(this, "win")
            else -> GbpPrefs.setPendingPanelMode(this, null)
        }
        modeTs = GbpPrefs.pendingPanelModeTs(this)
        if (modeTs > 0L) {
            // Hygiene net: the hook consumes the stamp within a second of
            // the keyboard showing. If it somehow never does (IME switched
            // away from Gboard, hook not loaded), drop the key a few
            // seconds later so it cannot hijack a later, unrelated show.
            // Guarded by the stamp — a newer summon always wins.
            val ts = modeTs
            val appCtx = applicationContext
            editText?.postDelayed({ GbpPrefs.clearPendingPanelModeIfTs(appCtx, ts) }, 5_000)
        }
    }

    private fun showKeyboard() {
        val et = editText ?: return
        if (!et.hasFocus()) et.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }
}

/**
 * Shared tap behaviour of the three QS tiles: stamp the panel mode into
 * the shared prefs, then summon the keyboard through
 * [ShowKeyboardActivity]. The shade collapses as the activity launches
 * (startActivityAndCollapse with a PendingIntent — the API 34+ path; the
 * project targets minSdk 35, so no legacy Intent variant is needed).
 */
abstract class SummonTileService : TileService() {

    /** One of [ShowKeyboardActivity.MODE_PLAIN] / MODE_PAD / MODE_WIN. */
    protected abstract val summonMode: String

    /** Distinct per tile so the three PendingIntents never alias. */
    protected abstract val requestCode: Int

    override fun onClick() {
        super.onClick()
        // The activity re-stamps the mode when it starts; writing here too
        // puts the key in the prefs blob as early as physically possible.
        when (summonMode) {
            ShowKeyboardActivity.MODE_PAD -> GbpPrefs.setPendingPanelMode(this, "pad")
            ShowKeyboardActivity.MODE_WIN -> GbpPrefs.setPendingPanelMode(this, "win")
            else -> GbpPrefs.setPendingPanelMode(this, null)
        }
        val pi = PendingIntent.getActivity(
            this,
            requestCode,
            ShowKeyboardActivity.intent(this, summonMode),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pi)
    }
}

/** «Клавиатура» — plain Gboard, no panel. */
class KeyboardTileService : SummonTileService() {
    override val summonMode: String = ShowKeyboardActivity.MODE_PLAIN
    override val requestCode: Int = 0x6B31
}

/** «🎮 Геймпад» — Gboard opening straight into the gamepad panel. */
class GamepadTileService : SummonTileService() {
    override val summonMode: String = ShowKeyboardActivity.MODE_PAD
    override val requestCode: Int = 0x6B32
}

/** «Win» — Gboard opening straight into the Windows-keyboard panel. */
class WinTileService : SummonTileService() {
    override val summonMode: String = ShowKeyboardActivity.MODE_WIN
    override val requestCode: Int = 0x6B33
}
