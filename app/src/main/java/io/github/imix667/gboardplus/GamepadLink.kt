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
 * one fixed 16-byte little-endian frame per state change over the abstract
 * local socket [GamepadProto.SOCKET_NAME]. The daemon turns frames into
 * input_events on a virtual "Microsoft X-Box 360 pad" (/dev/uinput), which
 * Parsec picks up as a real controller.
 */
object GamepadProto {
    const val SOCKET_NAME = "gbp_gamepad"
    const val ASSET_NAME = "gamepadd"
    const val FRAME_SIZE = 16

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

    fun reset() {
        buttons = 0; lx = 0; ly = 0; rx = 0; ry = 0
        lt = 0; rt = 0; hatX = 0; hatY = 0
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

    /** Send one state frame. Drops silently when not connected. */
    fun send(state: PadState) {
        if (!connected) return
        val frame = state.toFrame()
        synchronized(writeLock) {
            try {
                val out = socket?.outputStream ?: throw IllegalStateException("no socket")
                out.write(frame)
                out.flush()
            } catch (_: Throwable) {
                try { socket?.close() } catch (_: Throwable) {}
                socket = null
                setConnected(false)
            }
        }
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
 * binary from assets into filesDir and start it as root. Status checks use
 * the same abstract socket the hook connects to.
 */
object GamepadDaemon {

    fun binaryFile(ctx: Context): File = File(ctx.filesDir, GamepadProto.ASSET_NAME)

    /** Copy assets/gamepadd -> filesDir (refreshing on size drift), chmod 755. */
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

    /**
     * Extract + (re)start the daemon as root. Runs blocking shell work —
     * call from a background thread. Returns (success, human-readable detail).
     */
    fun start(ctx: Context): Pair<Boolean, String> {
        val bin = extract(ctx) ?: return false to "Не удалось распаковать бинарь демона из APK"
        if (!GbpPrefs.isRootAvailable()) return false to "Нет root (su не отвечает)"
        val path = bin.absolutePath
        // Kill a stale instance (its socket bind would fail anyway), then
        // start detached; the daemon daemonizes itself on --daemonize.
        val cmd = "pkill -x ${GamepadProto.ASSET_NAME} 2>/dev/null; sleep 0.3; " +
            "chmod 755 $path; $path --daemonize"
        val rc = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            // The su shell exits once the daemon is detached.
            if (p.waitFor() == 0) 0 else p.exitValue()
        } catch (t: Throwable) {
            return false to "su запуск упал: ${t.message}"
        }
        // Give it a moment to create the uinput device and bind the socket.
        var up = false
        for (i in 0 until 10) {
            Thread.sleep(200)
            if (isRunning()) { up = true; break }
        }
        return if (up) true to "Демон запущен, виртуальный Xbox 360 pad создан"
        else false to "Демон не поднялся (su rc=$rc). Проверь, что root выдан приложению"
    }
}
