package io.github.imix667.gboardplus

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol shared with the native gamepadd daemon (cpp/gamepadd.c):
 * one fixed 24-byte little-endian frame (v2) per state change over the
 * abstract local socket [GamepadProto.SOCKET_NAME]. Bytes 0..15 are the
 * gamepad state; bytes 16..23 are the mouse section (button bitmask +
 * one-shot relative deltas). The daemon turns frames into input_events on
 * a virtual "Microsoft X-Box 360 pad" and a virtual "Gboard+ Mouse"
 * (/dev/uinput), which Parsec picks up as real hardware.
 */
object GamepadProto {
    const val SOCKET_NAME = "gbp_gamepad"
    const val ASSET_NAME = "gamepadd"
    const val FRAME_SIZE = 24

    // Button bit indices (daemon maps them to BTN_SOUTH/EAST/... in order).
    const val BTN_A = 0
    const val BTN_B = 1
    const val BTN_X = 2
    const val BTN_Y = 3
    const val BTN_LB = 4
    const val BTN_RB = 5
    const val BTN_BACK = 6
    const val BTN_START = 7
    const val BTN_GUIDE = 8
    const val BTN_L3 = 9
    const val BTN_R3 = 10

    // Mouse button bit indices (daemon maps them to BTN_LEFT/RIGHT/MIDDLE).
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2
}

/** Mutable snapshot of the whole pad; serialized by [toFrame]. */
class PadState {
    var buttons: Int = 0          // bitmask of GamepadProto.BTN_*
    var lx: Int = 0               // -32768..32767
    var ly: Int = 0
    var rx: Int = 0
    var ry: Int = 0
    var lt: Int = 0               // 0..255
    var rt: Int = 0               // 0..255
    var hatX: Int = 0             // -1/0/1
    var hatY: Int = 0             // -1/0/1
    // Mouse section (frame v2). Buttons are state; rel/wheel are PENDING
    // one-shot deltas — GamepadClient.send() zeroes them after a successful
    // write so a later frame never re-sends the same movement.
    var mouseBtns: Int = 0        // bitmask of GamepadProto.MOUSE_*
    var relX: Int = 0             // pending relative delta, -32768..32767
    var relY: Int = 0
    var wheel: Int = 0            // pending wheel delta, -128..127

    fun reset() {
        buttons = 0; lx = 0; ly = 0; rx = 0; ry = 0
        lt = 0; rt = 0; hatX = 0; hatY = 0
        mouseBtns = 0; relX = 0; relY = 0; wheel = 0
    }

    fun toFrame(): ByteArray {
        val buf = ByteBuffer.allocate(GamepadProto.FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(buttons)
        buf.putShort(lx.coerceIn(-32768, 32767).toShort())
        buf.putShort(ly.coerceIn(-32768, 32767).toShort())
        buf.putShort(rx.coerceIn(-32768, 32767).toShort())
        buf.putShort(ry.coerceIn(-32768, 32767).toShort())
        buf.put(lt.coerceIn(0, 255).toByte())
        buf.put(rt.coerceIn(0, 255).toByte())
        buf.put(hatX.coerceIn(-1, 1).toByte())
        buf.put(hatY.coerceIn(-1, 1).toByte())
        buf.put(mouseBtns.coerceIn(0, 255).toByte())
        buf.put(0) // pad
        buf.putShort(relX.coerceIn(-32768, 32767).toShort())
        buf.putShort(relY.coerceIn(-32768, 32767).toShort())
        buf.put(wheel.coerceIn(-128, 127).toByte())
        buf.put(0) // pad
        return buf.array()
    }
}

/**
 * Client side of the daemon link, used from the Gboard process (the hook).
 * Connects lazily; writes are best-effort — if the daemon is not running the
 * frame is dropped and the panel shows "daemon not started". On a successful
 * (re)connect the current state is re-sent by the caller's callback wiring.
 */
class GamepadClient {
    @Volatile var connected: Boolean = false
        private set
    @Volatile private var connecting: Boolean = false
    private var socket: LocalSocket? = null
    private val writeLock = Any()

    /** Fired on the connect thread whenever [connected] flips. */
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    fun connectAsync() {
        if (connected || connecting) return
        connecting = true
        Thread {
            var ok = false
            try {
                val s = LocalSocket()
                s.connect(
                    LocalSocketAddress(
                        GamepadProto.SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT
                    )
                )
                s.soTimeout = 0
                synchronized(writeLock) { socket = s }
                ok = true
            } catch (_: Throwable) {
                ok = false
            }
            connecting = false
            setConnected(ok)
        }.start()
    }

    private fun setConnected(v: Boolean) {
        if (connected == v) return
        connected = v
        try { onConnectionChanged?.invoke(v) } catch (_: Throwable) {}
    }

    /** Send one state frame. Drops silently when not connected. A successful
     *  write consumes the pending mouse deltas in [state] (they are one-shot
     *  events, not state), so no later frame can re-send them. */
    fun send(state: PadState) {
        if (!connected) return
        val frame = state.toFrame()
        synchronized(writeLock) {
            try {
                val out = socket?.outputStream ?: throw IllegalStateException("no socket")
                out.write(frame)
                out.flush()
                state.relX = 0
                state.relY = 0
                state.wheel = 0
            } catch (_: Throwable) {
                try { socket?.close() } catch (_: Throwable) {}
                socket = null
                setConnected(false)
            }
        }
    }

    // ── Mouse API for the panels ──────────────────────────────────────
    // Each call mutates [state] and pushes a frame immediately. Callers
    // hold the same lock they use for the pad setters (padLock in the hook).

    /** Press/release one mouse button (GamepadProto.MOUSE_*). The button
     *  bit lives in [state] even while disconnected, so it re-syncs on the
     *  next connect exactly like the pad buttons do. */
    fun setMouseButton(state: PadState, bit: Int, pressed: Boolean) {
        state.mouseBtns = if (pressed) state.mouseBtns or (1 shl bit)
        else state.mouseBtns and (1 shl bit).inv()
        send(state)
    }

    /** Queue a relative pointer move and send it now. Deltas are dropped
     *  while disconnected — replaying stale movement after a reconnect
     *  would make the host cursor jump. */
    fun moveMouse(state: PadState, dx: Int, dy: Int) {
        if (!connected) return
        if (dx == 0 && dy == 0) return
        state.relX = (state.relX + dx).coerceIn(-32768, 32767)
        state.relY = (state.relY + dy).coerceIn(-32768, 32767)
        send(state)
    }

    /** Queue a wheel step (sign = direction) and send it now. */
    fun wheelMouse(state: PadState, n: Int) {
        if (!connected) return
        if (n == 0) return
        state.wheel = (state.wheel + n).coerceIn(-128, 127)
        send(state)
    }

    fun close() {
        synchronized(writeLock) {
            try { socket?.close() } catch (_: Throwable) {}
            socket = null
        }
        setConnected(false)
    }
}

/**
 * App-side daemon management (MainActivity): extract the bundled daemon
 * binary from assets into filesDir and start it as root.
 *
 * The binary lands under a VERSIONED name (gamepadd-<versionCode>): an
 * already-running daemon survives app updates, so a fixed file name could
 * leave the app executing a stale binary while a stale PROCESS (started by
 * an older build, without newer flags like --log) keeps holding the
 * socket. Versioned names + a verified kill of both names make a start a
 * clean slate.
 *
 * "Daemon is up" is judged from the root side — process found by pidof AND
 * the abstract socket present in /proc/net/unix — because that is what the
 * su start script itself can attest. Whether THIS app process can connect
 * is reported as a separate line; it must not drag the verdict down.
 */
object GamepadDaemon {

    /** Legacy (pre-versioning) binary/process name. */
    private const val LEGACY_NAME = GamepadProto.ASSET_NAME  // "gamepadd"

    private fun versionedName(): String =
        "${GamepadProto.ASSET_NAME}-${BuildConfig.VERSION_CODE}"

    /** Every process name a daemon of any build may run under. */
    private fun daemonNames(): List<String> = listOf(LEGACY_NAME, versionedName())

    fun binaryFile(ctx: Context): File = File(ctx.filesDir, versionedName())

    /** Copy assets/gamepadd -> filesDir/<versioned name> (refreshing on
     *  size drift) and remove the legacy unversioned copy, so nothing can
     *  ever exec a stale binary again. */
    fun extract(ctx: Context): File? = try {
        val dst = binaryFile(ctx)
        val assetSize = try {
            ctx.assets.openFd(GamepadProto.ASSET_NAME).use { it.length }
        } catch (_: Throwable) { -1L }
        if (!dst.exists() || (assetSize > 0 && dst.length() != assetSize)) {
            ctx.assets.open(GamepadProto.ASSET_NAME).use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
        }
        val legacy = File(ctx.filesDir, LEGACY_NAME)
        if (legacy.absolutePath != dst.absolutePath) {
            try { legacy.delete() } catch (_: Throwable) {}
        }
        dst.setExecutable(true, false)
        dst.setReadable(true, false)
        dst
    } catch (_: Throwable) {
        null
    }

    /** True when something accepts connections on the daemon socket. */
    fun isRunning(): Boolean = try {
        val s = LocalSocket()
        s.connect(
            LocalSocketAddress(GamepadProto.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            400
        )
        val up = s.isConnected
        s.close()
        up
    } catch (_: Throwable) {
        false
    }

    /** The daemon's own init log (written via gamepadd --log). The daemon
     *  runs as root, but the file lands in our filesDir, so we can read it
     *  back and show the user exactly where startup died. */
    fun logFile(ctx: Context): File = File(ctx.filesDir, "gamepadd.log")

    /** Last [n] non-empty lines of the daemon log. */
    fun logTail(ctx: Context, n: Int = 10): String = try {
        val f = logFile(ctx)
        when {
            !f.exists() -> "(лог демона не создан — демон ни разу не стартовал с записью лога)"
            else -> {
                val lines = f.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) "(лог демона пуст)"
                else lines.takeLast(n).joinToString("\n")
            }
        }
    } catch (t: Throwable) {
        "(лог не читается: ${t.message})"
    }

    // ── Root-side probing ────────────────────────────────────────────
    // One su round-trip answers both questions the verdict needs: is a
    // daemon process alive (pidof over every known name), and is the
    // abstract socket listening (abstract sockets show up in
    // /proc/net/unix as @gbp_gamepad). The script prints machine-readable
    // lines (DAEMON_PIDS=…, SOCKET_LISTEN=yes/no) that we parse back; the
    // raw output is also shown verbatim on the card.

    private data class Probe(
        val pids: List<String>,
        val socketListen: Boolean,
        val rc: Int,
        val raw: String
    )

    /** Shell fragment printing DAEMON_PIDS/SOCKET_LISTEN for all names. */
    private fun probeShell(): String {
        val sb = StringBuilder()
        sb.append("P=\"\"; ")
        for (n in daemonNames()) {
            sb.append("Q=\$(pidof $n 2>/dev/null); ")
            sb.append("if [ -n \"\$Q\" ]; then P=\"\$P \$Q\"; fi; ")
        }
        sb.append("echo \"DAEMON_PIDS=\${P# }\"; ")
        sb.append(
            "if grep -aq ${GamepadProto.SOCKET_NAME} /proc/net/unix 2>/dev/null; " +
                "then echo \"SOCKET_LISTEN=yes\"; else echo \"SOCKET_LISTEN=no\"; fi"
        )
        return sb.toString()
    }

    /** Run one `su -c` script, capturing merged stdout+stderr. */
    private fun suRun(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor() to text
    } catch (t: Throwable) {
        -1 to "su error: ${t.message}"
    }

    private fun suProbe(): Probe {
        val (rc, out) = suRun(probeShell())
        var pids = emptyList<String>()
        var listen = false
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("DAEMON_PIDS=")) {
                pids = t.removePrefix("DAEMON_PIDS=").split(' ').filter { it.isNotEmpty() }
            }
            if (t == "SOCKET_LISTEN=yes") listen = true
        }
        return Probe(pids, listen, rc, out)
    }

    /** True when su actually refused us (bad rc or a denial in the output)
     *  — the ONLY case where the "no root access" hint is honest. */
    private fun rootDenied(rc: Int, out: String): Boolean =
        rc != 0 || out.contains("denied", ignoreCase = true)

    private const val ROOT_HINT =
        "Похоже, нет root-доступа: выдай приложению Gboard+ права суперпользователя " +
            "в менеджере KernelSU и нажми запуск ещё раз"

    /** True when a daemon process exists (root-side pidof, any build). */
    fun isProcessAlive(): Boolean = try {
        suProbe().pids.isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /**
     * Live state for the settings card: (daemonUp, detail). daemonUp comes
     * from the ROOT-side checks (process + listening socket); the app-side
     * connect attempt is a separate informational line. The detail ends
     * with the daemon log tail, so a screenshot of the card is a complete
     * bug report.
     */
    fun diagnose(ctx: Context): Pair<Boolean, String> {
        val probe = suProbe()
        val appLink = isRunning()
        val up = probe.pids.isNotEmpty() && probe.socketListen
        val sb = StringBuilder()
        sb.append("Процесс (su): ")
            .append(if (probe.pids.isNotEmpty()) "жив, pid ${probe.pids.joinToString(", ")}" else "не найден")
        sb.append("\nСокет в /proc/net/unix: ").append(if (probe.socketListen) "слушает" else "нет")
        sb.append("\nСвязь из приложения: ").append(if (appLink) "есть" else "нет")
        if (rootDenied(probe.rc, probe.raw)) sb.append('\n').append(ROOT_HINT)
        val raw = probe.raw.trim()
        if (raw.isNotEmpty()) sb.append("\n— вывод su —\n").append(raw)
        sb.append("\n— лог демона —\n").append(logTail(ctx))
        return up to sb.toString()
    }

    /**
     * Shell fragment: HARD-kill every daemon of any build (legacy and
     * versioned names) and prove they are gone — up to ~1 s of pidof
     * retries; prints KILL_FAILED=<pids> if something refuses to die, in
     * which case its socket bind would sabotage the fresh start.
     */
    private fun killShell(): String {
        val sb = StringBuilder()
        for (n in daemonNames()) {
            sb.append("for p in \$(pidof $n 2>/dev/null); do kill -9 \$p 2>/dev/null; done; ")
        }
        sb.append("i=0; R=\"x\"; while [ \$i -lt 10 ]; do R=\"\"; ")
        for (n in daemonNames()) {
            sb.append("Q=\$(pidof $n 2>/dev/null); ")
            sb.append("if [ -n \"\$Q\" ]; then R=\"\$R \$Q\"; fi; ")
        }
        sb.append("if [ -z \"\$R\" ]; then break; fi; sleep 0.1; i=\$((i+1)); done; ")
        sb.append("if [ -n \"\$R\" ]; then echo \"KILL_FAILED=\${R# }\"; fi; ")
        return sb.toString()
    }

    /**
     * Extract + HARD-restart the daemon as root, all in ONE su script:
     * verified kill of every stale daemon → start the versioned binary
     * with its init log at filesDir/gamepadd.log (deleted first, so the
     * log always belongs to THIS launch) → the script itself verifies the
     * result (pidof + socket in /proc/net/unix) and prints it as
     * DAEMON_PID_NEW/DAEMON_PIDS/SOCKET_LISTEN lines, shown on the card
     * verbatim. Runs blocking shell work — call from a background thread.
     * Returns (success, human-readable detail with diagnostics).
     */
    fun start(ctx: Context): Pair<Boolean, String> {
        val bin = extract(ctx) ?: return false to "Не удалось распаковать бинарь демона из APK"
        if (!GbpPrefs.isRootAvailable()) {
            // This probe is a real `su -c id` uid=0 check, so the hint is
            // accurate here — unlike a generic post-launch failure.
            return false to "$ROOT_HINT\n— лог демона —\n" + logTail(ctx)
        }
        val log = logFile(ctx)
        try { log.delete() } catch (_: Throwable) {}  // fresh log for this launch
        val cmd = buildString {
            append(killShell())
            append("chmod 755 ${bin.absolutePath}; ")
            append("${bin.absolutePath} --daemonize --log ${log.absolutePath}; ")
            append("sleep 1; ")
            append("NV=\$(pidof ${versionedName()} 2>/dev/null); echo \"DAEMON_PID_NEW=\$NV\"; ")
            append(probeShell())
        }
        val (rc, out) = suRun(cmd)
        val killFailed = out.lineSequence().any { it.trim().startsWith("KILL_FAILED=") }
        var newPid = ""
        var pids = emptyList<String>()
        var listen = false
        for (line in out.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("DAEMON_PID_NEW=") ->
                    newPid = t.removePrefix("DAEMON_PID_NEW=").trim()
                t.startsWith("DAEMON_PIDS=") ->
                    pids = t.removePrefix("DAEMON_PIDS=").split(' ').filter { it.isNotEmpty() }
                t == "SOCKET_LISTEN=yes" -> listen = true
            }
        }
        val appLink = isRunning()
        // Success is attested by the root side: the NEW versioned process
        // is alive AND the socket listens AND no stale daemon survived the
        // kill (a survivor could be the one holding that socket).
        val up = listen && newPid.isNotEmpty() && !killFailed
        val sb = StringBuilder()
        if (up) {
            sb.append("Демон работает: процесс жив (pid $newPid), сокет слушает")
        } else {
            sb.append("Демон не поднялся (su rc=$rc)")
            if (killFailed) sb.append("\nСтарый процесс демона не убился — см. KILL_FAILED в выводе su")
            if (rootDenied(rc, out)) sb.append('\n').append(ROOT_HINT)
        }
        sb.append("\nПроцесс (su): ")
            .append(if (pids.isNotEmpty()) "жив, pid ${pids.joinToString(", ")}" else "не найден")
        sb.append("\nСокет в /proc/net/unix: ").append(if (listen) "слушает" else "нет")
        sb.append("\nСвязь из приложения: ").append(if (appLink) "есть" else "нет")
        val raw = out.trim()
        if (raw.isNotEmpty()) sb.append("\n— вывод su —\n").append(raw)
        sb.append("\n— лог демона —\n").append(logTail(ctx))
        return up to sb.toString()
    }
}
