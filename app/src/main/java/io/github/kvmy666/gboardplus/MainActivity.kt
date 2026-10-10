package io.github.kvmy666.gboardplus

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val Accent = Color(0xFFF03971)
private val LocalUseIcons = compositionLocalOf { false }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GbpPrefs.ensureDefaults(this)
        GbpPrefs.publish(this)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme(primary = Accent, secondary = Accent, tertiary = Accent)
                else -> lightColorScheme(primary = Accent, secondary = Accent, tertiary = Accent)
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen()
                }
            }
        }
    }
}

private class PrefState(private val ctx: android.content.Context) {
    private val prefs = GbpPrefs.prefs(ctx)
    // Observable backing so the switches/rows recompose when a value changes.
    val map = mutableStateMapOf<String, String>()

    fun load(): PrefState { for ((k, v) in prefs.all) map[k] = v.toString(); return this }
    fun str(key: String, def: String) = map[key] ?: def
    fun bool(key: String, def: Boolean) = (map[key] ?: def.toString()).toBoolean()
    fun f(key: String, def: Float) = map[key]?.toFloatOrNull() ?: def
    fun i(key: String, def: Int) = map[key]?.toIntOrNull() ?: def

    fun setBool(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply(); map[key] = v.toString(); publishAsync() }
    fun setStr(key: String, v: String) { prefs.edit().putString(key, v).apply(); map[key] = v; publishAsync() }

    // Publishing shells out to `su`, so never block the main thread on it.
    private fun publishAsync() { Thread { GbpPrefs.publish(ctx) }.start() }
}

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val st = remember { PrefState(ctx).load() }
    var loaded by remember { mutableStateOf<Boolean?>(null) }
    var aeActive by remember { mutableStateOf(GbpPrefs.aeKeyboardTweakActive(ctx)) }
    var busy by remember { mutableStateOf(false) }
    val root = remember { GbpPrefs.isRootAvailable() }

    fun probe() { loaded = null; GbpPrefs.pingHook(ctx) { loaded = it } }
    LaunchedEffect(Unit) { probe() }

    var showRestart by remember { mutableStateOf(false) }
    if (showRestart) {
        AlertDialog(
            onDismissRequest = { showRestart = false },
            title = { Text("Restart Gboard to apply") },
            text = { Text("The toolbar is already built. Force-stop Gboard, then reopen the keyboard.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestart = false
                    if (root) { busy = true; Thread { GbpPrefs.forceStopGboardRoot(ctx); Thread.sleep(1000); busy = false }.start() }
                }) { Text(if (root) "Force stop Gboard" else "OK") }
            },
            dismissButton = {
                TextButton(onClick = { showRestart = false; GbpPrefs.openAppInfo(ctx, GbpPrefs.GBOARD_PKG) }) { Text("App info") }
            }
        )
    }

    CompositionLocalProvider(LocalUseIcons provides st.bool("toolbar_use_icons", false)) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Gboard+", style = MaterialTheme.typography.titleLarge, color = Accent, fontWeight = FontWeight.Bold)
            Text("Clipboard vault, shortcuts and cursor tools inside Gboard.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            InfoCard(tint = if (loaded == true) Accent.copy(alpha = 0.12f) else Color(0x22FF9800)) {
                Text(
                    when (loaded) {
                        true -> "Gboard+ is loaded in Gboard"
                        false -> "Gboard+ is not loaded in Gboard yet"
                        null -> "Checking Gboard…"
                    },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
                )
                if (loaded == false) {
                    Text("Gboard must restart to load the hook.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (root) {
                            CompactButton(if (busy) "Working…" else "Force stop Gboard", !busy) {
                                busy = true
                                Thread { GbpPrefs.forceStopGboardRoot(ctx); Thread.sleep(1200); busy = false; probe() }.start()
                            }
                        } else {
                            CompactOutline("Open Gboard app info") { GbpPrefs.openAppInfo(ctx, GbpPrefs.GBOARD_PKG) }
                        }
                        CompactText("Re-check") { probe() }
                    }
                }
            }
        }

        if (aeActive) {
            item {
                InfoCard(tint = Color(0x22FF9800)) {
                    Text("Auto Expand's Gboard tweak is active",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Turn AE's keyboard toolbar off so Gboard+ can take over.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (root) {
                            CompactButton(if (busy) "Working…" else "Disable it for me", !busy) {
                                busy = true
                                Thread {
                                    GbpPrefs.disableAeKeyboardTweakRoot(ctx); Thread.sleep(400)
                                    aeActive = GbpPrefs.aeKeyboardTweakActive(ctx); busy = false
                                }.start()
                            }
                        }
                        CompactOutline("Open Auto Expand") { GbpPrefs.openAppInfo(ctx, GbpPrefs.AE_PKG) }
                    }
                }
            }
        }

        item { SectionTitle("Toolbar") }
        item { Toggle(R.drawable.ic_toolbar, "Keyboard toolbar", "Inject the button row below Gboard", st.bool("keyboard_enhancer_enabled", true)) { st.setBool("keyboard_enhancer_enabled", it) } }
        item { Toggle(R.drawable.ic_icons, "Icons or emojis", "Toolbar glyph style — needs a Gboard restart to apply", st.bool("toolbar_use_icons", false)) { st.setBool("toolbar_use_icons", it); showRestart = true } }
        item { Stepper(R.drawable.ic_height, "Toolbar height", st.f("toolbar_height_multiplier", 1.0f)) { st.setStr("toolbar_height_multiplier", it.toString()) } }
        item { Stepper(R.drawable.ic_button_size, "Button size", st.f("toolbar_button_multiplier", 1.0f)) { st.setStr("toolbar_button_multiplier", it.toString()) } }

        item { SectionTitle("Buttons") }
        item { Toggle(R.drawable.ic_clipboard, "Clipboard", "Clipboard history popup", st.bool("btn_clipboard_enabled", true)) { st.setBool("btn_clipboard_enabled", it) } }
        item { Toggle(R.drawable.ic_paste, "Paste", "Paste the last clipboard item", st.bool("btn_paste_enabled", true)) { st.setBool("btn_paste_enabled", it) } }
        item { Toggle(R.drawable.ic_select_all, "Select all", "Tap = word · long-press = all", st.bool("btn_selectall_enabled", true)) { st.setBool("btn_selectall_enabled", it) } }
        item { Toggle(R.drawable.ic_cursor, "Cursor navigation", "Jump caret to start / end", st.bool("btn_cursor_enabled", false)) { st.setBool("btn_cursor_enabled", it) } }
        item { Toggle(R.drawable.ic_trackpad, "Trackpad stick", "Drag to move the caret", st.bool("btn_trackpad_enabled", true)) { st.setBool("btn_trackpad_enabled", it) } }
        if (st.bool("btn_trackpad_enabled", true)) {
            item { Toggle(R.drawable.ic_vibration, "Stick haptics", "Grab pop + steering ticks", st.bool("trackpad_haptics_enabled", true)) { st.setBool("trackpad_haptics_enabled", it) } }
            item { Stepper(R.drawable.ic_sensitivity, "Trackpad sensitivity", st.f("trackpad_sensitivity", 1.0f)) { st.setStr("trackpad_sensitivity", it.toString()) } }
            item { Toggle(R.drawable.ic_select_mode, "Select mode", "Stick selects text instead of moving", st.bool("btn_selectmode_enabled", true)) { st.setBool("btn_selectmode_enabled", it) } }
        }
        item { Toggle(R.drawable.ic_shortcut, "Text shortcut", "Tap / long-press inserts text", st.bool("btn_shortcut_enabled", true)) { st.setBool("btn_shortcut_enabled", it) } }
        if (st.bool("btn_shortcut_enabled", true)) {
            item { TextField("Shortcut 1 — tap", st.str("shortcut_text_1", "")) { st.setStr("shortcut_text_1", it) } }
            item { TextField("Shortcut 2 — long press", st.str("shortcut_text_2", "")) { st.setStr("shortcut_text_2", it) } }
        }

        item { SectionTitle("Clipboard vault") }
        if (st.bool("btn_clipboard_enabled", true)) {
            item { NumberField("Max entries", st.str("clipboard_max_entries", "500")) { st.setStr("clipboard_max_entries", it) } }
        }
        item { Toggle(R.drawable.ic_full_text, "Show full text", "Off shows only the first line", st.bool("clip_full_text_enabled", true)) { st.setBool("clip_full_text_enabled", it) } }

        item { SectionTitle("Images") }
        item { Toggle(R.drawable.ic_images, "Save copied images", "Keep copied images in the vault", st.bool("clip_images_enabled", false)) { st.setBool("clip_images_enabled", it) } }
        if (st.bool("clip_images_enabled", false)) {
            item { Choice("Max images kept", listOf("20", "50", "100"), st.str("clip_img_max_entries", "50")) { st.setStr("clip_img_max_entries", it) } }
            item { Choice("Max image storage (MB)", listOf("25", "50", "100", "200"), st.str("clip_img_max_mb", "100")) { st.setStr("clip_img_max_mb", it) } }
        }
        item { Toggle(R.drawable.ic_screenshots, "Auto-save screenshots", "Add system screenshots to the vault", st.bool("shot_capture_enabled", false)) { st.setBool("shot_capture_enabled", it) } }
        if (st.bool("shot_capture_enabled", false)) {
            item { Toggle(R.drawable.ic_copy, "Also copy screenshot", "Replaces the current clip with the shot", st.bool("shot_to_clipboard_enabled", false)) { st.setBool("shot_to_clipboard_enabled", it) } }
        }

        item { SectionTitle("Haptics") }
        item { IntSlider(R.drawable.ic_vibration, "Vibration strength", st.i("vibration_strength", 100), 0..100, 19) { st.setStr("vibration_strength", it.toString()) } }

        item { SectionTitle("Undo") }
        item { Toggle(R.drawable.ic_undo, "Undo", "Restore deleted text", st.bool("undo_enabled", true)) { st.setBool("undo_enabled", it) } }
        if (st.bool("undo_enabled", true)) {
            item { Toggle(R.drawable.ic_undo, "Undo button", "Show a toolbar button that restores text", st.bool("undo_button_enabled", false)) { st.setBool("undo_button_enabled", it) } }
            item { Toggle(R.drawable.ic_shake, "Shake to undo", "Shake the phone to restore text", st.bool("shake_undo_enabled", true)) { st.setBool("shake_undo_enabled", it) } }
            if (st.bool("shake_undo_enabled", true)) {
                item { FloatSlider(R.drawable.ic_speed, "Shake sensitivity", st.f("shake_sensitivity", 1.0f), 0.1f..2.0f, 18) { st.setStr("shake_sensitivity", it.toString()) } }
            }
        }

        item {
            Text(
                if (root) "Root available — full features enabled."
                else "No root — root-only actions fall back to manual steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    }
}

@Composable
private fun InfoCard(tint: Color, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tint)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Accent, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun Lead(@DrawableRes icon: Int) {
    // The row glyph mirrors the toolbar choice: icon when icons are on, emoji when off.
    val emoji = when (icon) {
        R.drawable.ic_toolbar -> "⌨️"
        R.drawable.ic_icons -> "🎨"
        R.drawable.ic_height -> "📏"
        R.drawable.ic_button_size -> "🔘"
        R.drawable.ic_clipboard -> "📋"
        R.drawable.ic_paste -> "📥"
        R.drawable.ic_select_all -> "⬛"
        R.drawable.ic_cursor -> "↔️"
        R.drawable.ic_trackpad -> "🕹️"
        R.drawable.ic_vibration -> "📳"
        R.drawable.ic_sensitivity -> "🎚️"
        R.drawable.ic_select_mode -> "🖍️"
        R.drawable.ic_root -> "🛡️"
        R.drawable.ic_shortcut -> "⭐"
        R.drawable.ic_full_text -> "📝"
        R.drawable.ic_images -> "🖼️"
        R.drawable.ic_screenshots -> "📸"
        R.drawable.ic_copy -> "📎"
        R.drawable.ic_undo -> "↩️"
        R.drawable.ic_shake -> "📱"
        R.drawable.ic_speed -> "🌀"
        else -> "•"
    }
    Box(Modifier.padding(end = 12.dp).size(24.dp), contentAlignment = Alignment.Center) {
        if (LocalUseIcons.current) {
            Icon(painterResource(icon), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        } else {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Toggle(@DrawableRes icon: Int, title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Lead(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun Stepper(@DrawableRes icon: Int, title: String, multiplier: Float, onChange: (Float) -> Unit) {
    var v by remember { mutableStateOf(multiplier) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Lead(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text("${"%.2f".format(v)}×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { v = (v / 1.12f).coerceAtLeast(0.35f); onChange(v) }) { Text("–") }
            Text("  ")
            OutlinedButton(onClick = { v = (v * 1.12f).coerceAtMost(3.0f); onChange(v) }) { Text("+") }
        }
    }
}

@Composable
private fun IntSlider(@DrawableRes icon: Int, title: String, value: Int, range: IntRange, steps: Int, onChange: (Int) -> Unit) {
    var v by remember { mutableStateOf(value.toFloat()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lead(icon); Text("$title: ${v.toInt()}", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(value = v, onValueChange = { v = it }, valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = steps, onValueChangeFinished = { onChange(v.toInt()) })
        }
    }
}

@Composable
private fun FloatSlider(@DrawableRes icon: Int, title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lead(icon); Text("$title: ${"%.1f".format(v)}×", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(value = v, onValueChange = { v = it }, valueRange = range, steps = steps,
                onValueChangeFinished = { onChange((Math.round(v * 10f) / 10f)) })
        }
    }
}

@Composable
private fun TextField(label: String, value: String, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Card(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = v, onValueChange = { v = it; onChange(it) },
            label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(8.dp), maxLines = 3
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Card(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = v, onValueChange = { v = it.filter { c -> c.isDigit() }; onChange(v) },
            label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(8.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun Choice(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                options.forEach { opt ->
                    if (opt == selected) CompactButton(opt, true) { onSelect(opt) }
                    else CompactText(opt) { onSelect(opt) }
                }
            }
        }
    }
}

@Composable
private fun CompactButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun CompactOutline(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun CompactText(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.labelMedium) }
}
