package io.github.kvmy666.gboardplus

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * App-side preferences + IPC for Gboard+.
 *
 * Gboard+ runs its toolbar inside Gboard's process. The hook cannot read this app's
 * SharedPreferences, so every change is mirrored to two channels the hook can read:
 *   1. `/data/local/tmp/gboardplus_prefs.json` (a flat String→String JSON), and
 *   2. `Settings.Global gbp_prefs_json` (base64 of the same JSON).
 * Both are written with root (`su`), matching the model Auto Expand already used.
 *
 * Everything here is isolated from Auto Expand: distinct file names, distinct global
 * keys, distinct broadcast actions. The only AE interaction is the explicit handoff in
 * [aeKeyboardTweakActive] / [disableAeKeyboardTweakRoot].
 */
object GbpPrefs {

    const val ACTION_PING = "io.github.kvmy666.gboardplus.PING"
    const val ACTION_REQ = "io.github.kvmy666.gboardplus.PREFS_REQ"
    const val GBOARD_PKG = "com.google.android.inputmethod.latin"
    const val AE_PKG = "io.github.kvmy666.autoexpand"

    private const val TAG = "GboardPlus"
    private const val GBP_PREFS_FILE = "/data/local/tmp/gboardplus_prefs.json"
    private const val AE_PREFS_FILE = "/data/local/tmp/tweaks_prefs.json"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Push the prefs blob to the hooked Gboard process (app -> Gboard broadcast). */
    fun pushToGboard(ctx: Context, json: JSONObject) {
        try {
            val b64 = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            ctx.sendBroadcast(
                Intent("io.github.kvmy666.gboardplus.PREFS_PUSH")
                    .setPackage(GBOARD_PKG)
                    .putExtra("json", b64)
            )
        } catch (t: Throwable) { Log.d(TAG, "pushToGboard failed: $t") }
    }

    /** Write first-run defaults so the published blob always exists for the hook. */
    fun ensureDefaults(ctx: Context) {
        val prefs = prefs(ctx)
        if (prefs.contains("keyboard_enhancer_enabled")) return
        prefs.edit()
            .putBoolean("keyboard_enhancer_enabled", true)
            .putString("toolbar_height_multiplier", "1.0")
            .putString("toolbar_button_multiplier", "1.0")
            .putBoolean("toolbar_use_icons", false)
            .putString("shortcut_text_1", "")
            .putString("shortcut_text_2", "")
            .putString("clipboard_max_entries", "500")
            .putBoolean("btn_clipboard_enabled", true)
            .putBoolean("btn_paste_enabled", true)
            .putBoolean("btn_selectall_enabled", true)
            .putBoolean("btn_cursor_enabled", false)
            .putBoolean("btn_shortcut_enabled", true)
            .putBoolean("btn_trackpad_enabled", true)
            .putBoolean("btn_selectmode_enabled", true)
            .putBoolean("trackpad_haptics_enabled", true)
            .putString("trackpad_sensitivity", "1.0")
            .putBoolean("trackpad_root_injection_enabled", false)
            .putBoolean("clip_full_text_enabled", true)
            .putBoolean("clip_images_enabled", false)
            .putString("clip_img_max_entries", "50")
            .putString("clip_img_max_mb", "100")
            .putBoolean("shot_capture_enabled", false)
            .putBoolean("shot_to_clipboard_enabled", false)
            .putBoolean("undo_enabled", true)
            .putBoolean("undo_button_enabled", true)
            .putBoolean("shake_undo_enabled", true)
            .putString("shake_sensitivity", "1.0")
            .putString("vibration_strength", "100")
            .apply()
    }

    private fun toJson(ctx: Context): JSONObject {
        val json = JSONObject()
        for ((key, value) in prefs(ctx).all) {
            @Suppress("UNCHECKED_CAST")
            json.put(key, when (value) {
                is Boolean -> if (value) "1" else "0"
                is Set<*>  -> (value as Set<String>).joinToString("\n")
                else -> value.toString()
            })
        }
        return json
    }

    /** Mirror the current prefs to the file + Settings.Global channels the hook reads. */
    fun publish(ctx: Context) {
        try {
            val json = toJson(ctx)
            val tmp = File(ctx.cacheDir, "gboardplus_prefs_tmp.json")
            tmp.writeText(json.toString())
            val b64 = Base64.encodeToString(
                json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            val cmd = "cp ${tmp.absolutePath} $GBP_PREFS_FILE; " +
                      "chmod 644 $GBP_PREFS_FILE; " +
                      "settings put global gbp_prefs_json $b64"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            proc.waitFor(); proc.destroy()
        } catch (t: Throwable) {
            Log.d(TAG, "publish failed (no root?): $t")
        }
        // Live push to the hooked Gboard process, independent of root.
        try { pushToGboard(ctx, toJson(ctx)) } catch (_: Throwable) {}
    }

    /** Root session available? */
    fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); p.destroy()
        out.contains("uid=0")
    } catch (_: Throwable) { false }

    /**
     * Ordered broadcast to Gboard to learn whether the hook is loaded. The hook answers
     * with RESULT_OK; no receiver means the module has not loaded in Gboard yet.
     */
    fun pingHook(ctx: Context, onResult: (Boolean) -> Unit) {
        try {
            val intent = Intent(ACTION_PING).setPackage(GBOARD_PKG)
            ctx.sendOrderedBroadcast(
                intent, null,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        onResult(resultCode == Activity.RESULT_OK)
                    }
                }, null, Activity.RESULT_CANCELED, null, null
            )
        } catch (t: Throwable) {
            Log.d(TAG, "ping failed: $t")
            onResult(false)
        }
    }

    /** Force-stop Gboard with root, if available. Returns true when the command ran. */
    fun forceStopGboardRoot(ctx: Context): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $GBOARD_PKG"))
        p.waitFor(); p.destroy(); true
    } catch (_: Throwable) { false }

    /** Deep-link to any package's App Info page (root-free "Force stop" lives there). */
    fun openAppInfo(ctx: Context, pkg: String) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.d(TAG, "openAppInfo failed: $t")
        }
    }

    // ── Auto Expand handoff ─────────────────────────────────────────────
    // Read AE's published blob and report/flip its keyboard toolbar toggle.

    private fun readAePrefs(): JSONObject? = try {
        val f = File(AE_PREFS_FILE)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (_: Throwable) { null }

    fun aeKeyboardTweakActive(ctx: Context): Boolean = try {
        readAePrefs()?.optString("keyboard_enhancer_enabled") == "1"
    } catch (_: Throwable) { false }

    /**
     * Turn Auto Expand's Gboard toolbar off by rewriting AE's channels, then enable
     * Gboard+'s own toolbar. Requires root (AE's provider is read-only). Returns success.
     */
    fun disableAeKeyboardTweakRoot(ctx: Context): Boolean = try {
        val ae = readAePrefs() ?: JSONObject()
        ae.put("keyboard_enhancer_enabled", "0")
        val tmp = File(ctx.cacheDir, "ae_prefs_tmp.json")
        tmp.writeText(ae.toString())
        val b64 = Base64.encodeToString(ae.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "cp ${tmp.absolutePath} $AE_PREFS_FILE; " +
                  "chmod 644 $AE_PREFS_FILE; " +
                  "settings put global ae_prefs_json $b64"
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        proc.waitFor(); proc.destroy()
        // Gboard+ takes over.
        prefs(ctx).edit().putBoolean("keyboard_enhancer_enabled", true).apply()
        publish(ctx)
        true
    } catch (t: Throwable) {
        Log.d(TAG, "disableAeKeyboardTweakRoot failed: $t")
        false
    }
}
