package io.github.imix667.gboardplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONObject

/**
 * Answers the hook's ordered broadcast request for the current preferences.
 *
 * The hook is injected into Gboard's process, which (Android 11+ package visibility) cannot
 * read this app's prefs via PackageManager or a ContentProvider. A manifest receiver for a
 * custom action is not subject to that filtering, so the hook sends
 * [GbpPrefs.ACTION_REQ] and this receiver returns the prefs as base64 JSON via the ordered
 * broadcast result (`setResultData`) — no root, no visibility, no permission.
 */
class GbpPrefsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val all = GbpPrefs.prefs(context).all
            android.util.Log.d("GboardPlus", "prefs receiver size=${all.size} action=${intent?.action}")
            val json = JSONObject()
            for ((k, v) in all) {
                @Suppress("UNCHECKED_CAST")
                json.put(k, when (v) {
                    is Boolean -> if (v) "1" else "0"
                    is Set<*>  -> (v as Set<String>).joinToString("\n")
                    else -> v.toString()
                })
            }
            setResultData(
                Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            // Push the same blob into Gboard's hooked process (app -> Gboard works even
            // though Gboard cannot see this app).
            GbpPrefs.pushToGboard(context, json)
        } catch (t: Throwable) {
            setResultData(null)
        }
    }
}
