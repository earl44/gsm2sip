package com.callagent.gateway.rtp

import android.util.Log
import android.system.Os
import com.callagent.gateway.RootShell
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Caller capture straight from an ALSA PCM over root, for HALs whose
 * AudioRecord route to the downlink is silent.
 *
 * On the Pixel 7 AudioPolicyManager always opens VOICE_DOWNLINK on the
 * "incall capture" mixPort, whose AoC stream reads exact zeros, while the
 * telephony-rx stream next to it carries the caller cleanly — but only the
 * framework's own call-audio patch can reach that one through the policy.
 * tinycap reaches it directly.
 *
 * Runs `tinycap --` under su and reads its raw stdout (S16LE mono at
 * [pcmRate]), low-passes and decimates it to [outRate], and hands out frames
 * through [read] under the [CaptureSource] contract.
 */
class RootPcmCapture(
    private val card: Int,
    private val device: Int,
    private val pcmRate: Int,
    private val outRate: Int,
    private val periodSize: Int,
    private val periodCount: Int,
    private val setupCmd: String,
    private val restoreCmd: String,
) : CaptureSource {

    init {
        require(outRate > 0 && (pcmRate % outRate == 0 || outRate % pcmRate == 0)) {
            "PCM rate $pcmRate and capture rate $outRate are not integer multiples"
        }
    }

    private val downFactor = if (pcmRate >= outRate) pcmRate / outRate else 1
    private val upFactor = if (outRate > pcmRate) outRate / pcmRate else 1
    private val taps = lowPassTaps(downFactor)

    // Input samples as floats, with the previous frame's last taps.size-1
    // samples kept in front so the filter can run straight across the frame
    // boundary.  Sized on the first read, when the frame size is known.
    private var window = FloatArray(0)
    private var raw = ByteArray(0)

    @Volatile private var proc: Process? = null
    private var input: DataInputStream? = null
    private var directProcess = false
    private var nextFrameNs = 0L

    override val name = "PCM$card:$device@${pcmRate / 1000}k"

    /**
     * Launch tinycap and publish its pipe immediately.
     *
     * The Broadcom Hawaii driver must open its 48 kHz HQ capture path before
     * telephony becomes active.  While the incoming GSM leg is still ringing
     * the earpiece source has no samples, so waiting for the first bytes here
     * would deadlock the sequence which answers that leg.  The capture loop
     * may block on the pipe; once GSM becomes active the already-open path
     * starts delivering the digital downlink.  Idempotent once running.
     */
    override fun start(): Boolean {
        if (proc != null) return true
        // exec, so destroying su's child leaves no tinycap behind holding the
        // PCM.  Nothing but tinycap may write to stdout: tinymix echoes the
        // control it set ("Incall Capture Stream0: DL", 27 bytes), and an odd
        // byte count ahead of the audio splits every sample across two —
        // heard as loud, heavily distorted capture.
        val cmd = buildString {
            if (setupCmd.isNotEmpty()) append("{ ").append(setupCmd).append("; } >/dev/null 2>&1; ")
            append("exec $TINYCAP -- -D $card -d $device -c 1 -r $pcmRate -b 16")
            if (periodSize > 0) append(" -p $periodSize")
            if (periodCount > 0) append(" -n $periodCount")
            append(" 2>/dev/null")
        }
        try {
            // GT-S7580 runs an ARMv7 musl tinycap from /data/local/tmp.  Its
            // adb daemon is root but the ROM deliberately denies `su` to app
            // processes.  With no mixer setup required, opening the PCM
            // directly is sufficient and avoids making call audio depend on
            // the ROM's optional root manager.
            val canRunDirect = setupCmd.isEmpty() &&
                java.io.File(MUSL_LOADER).canExecute() &&
                java.io.File(TINYCAP).canExecute()
            val p = if (canRunDirect) {
                directProcess = true
                val args = mutableListOf(
                    MUSL_LOADER, "--library-path", TOOL_DIR,
                    TINYCAP, "--", "-D", card.toString(), "-d", device.toString(),
                    "-c", "1", "-r", pcmRate.toString(), "-b", "16"
                )
                if (periodSize > 0) args.addAll(listOf("-p", periodSize.toString()))
                if (periodCount > 0) args.addAll(listOf("-n", periodCount.toString()))
                ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            } else {
                directProcess = false
                ProcessBuilder("su", "-c", cmd).start()
            }
            p.outputStream.close()
            // Broadcom CAPH does not block reads on its hardware clock: it can
            // fill the default 64 KiB process pipe with ~4 seconds of 8 kHz
            // mono audio almost instantly.  Our RTP side is correctly paced,
            // so that backlog otherwise grows into a 20+ second delay.  Linux
            // clamps this request to one page (normally 4096 bytes ~= 256 ms),
            // which provides back-pressure close to the capture point.
            val pipeBytes = shrinkPipe(p.inputStream)
            if (pipeBytes != null) Log.i(TAG, "$name stdout pipe limited to ${pipeBytes}B")
            // Do not add BufferedInputStream here: read-ahead is media latency.
            proc = p
            input = DataInputStream(p.inputStream)
            nextFrameNs = System.nanoTime()
            val mode = if (directProcess) "direct" else "su"
            val conversion = when {
                upFactor > 1 -> "upsample x$upFactor"
                downFactor > 1 -> "decimate x$downFactor, ${taps.size} taps"
                else -> "native rate"
            }
            Log.i(TAG, "Started $name -> ${outRate}Hz ($conversion, $mode)")
            return true
        } catch (e: Exception) {
            val why = if (e is EOFException) "exited before producing audio (tinycap missing, or su denied?)" else e.message
            Log.w(TAG, "Could not start $name: $why")
            return false
        }
    }

    override fun read(buf: ByteArray): Int {
        val inp = input ?: return -1
        val outSamples = buf.size / 2
        val inSamples = if (upFactor > 1) outSamples / upFactor else outSamples * downFactor

        // Narrow-band cellular PCM feeding a G.722 leg.  Duplicate each 8 kHz
        // sample at 16 kHz; this preserves every speech sample and produces a
        // valid wideband clock without inventing high-frequency content.
        if (upFactor > 1) {
            if (raw.size != inSamples * 2) raw = ByteArray(inSamples * 2)
            try {
                inp.readFully(raw)
            } catch (_: EOFException) {
                return -1
            } catch (e: Exception) {
                if (proc != null) Log.w(TAG, "$name read failed: ${e.message}")
                return -1
            }
            var o = 0
            var i = 0
            while (i < raw.size) {
                repeat(upFactor) {
                    buf[o++] = raw[i]
                    buf[o++] = raw[i + 1]
                }
                i += 2
            }
            pace(outSamples)
            return o
        }

        val keep = taps.size - 1
        if (raw.size != inSamples * 2) {
            raw = ByteArray(inSamples * 2)
            window = FloatArray(keep + inSamples)
        }
        try {
            inp.readFully(raw)
        } catch (_: EOFException) {
            return -1
        } catch (e: Exception) {
            if (proc != null) Log.w(TAG, "$name read failed: ${e.message}")
            return -1
        }

        // Carry the tail of the last frame, append this one as floats.
        System.arraycopy(window, inSamples, window, 0, keep)
        var w = keep
        var i = 0
        while (i < raw.size) {
            window[w++] = ((raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xFF)).toFloat()
            i += 2
        }

        // One FIR evaluation per output sample, over a contiguous run of the
        // window: no ring buffer, no modulo, nothing data-dependent.
        var o = 0
        for (k in 0 until outSamples) {
            val base = k * downFactor
            var acc = 0f
            for (t in taps.indices) acc += taps[t] * window[base + t]
            val s = acc.toInt().coerceIn(-32768, 32767)
            buf[o++] = (s and 0xFF).toByte()
            buf[o++] = ((s shr 8) and 0xFF).toByte()
        }
        pace(outSamples)
        return o
    }

    /** Some legacy CAPH PCM drivers return a full period immediately instead
     *  of blocking until the hardware clock reaches it.  AudioRecord normally
     *  provides that pacing for the RTP loop; direct PCM therefore has to do
     *  it explicitly or it floods Asterisk and plays speech too fast. */
    private fun pace(samples: Int) {
        val frameNs = samples.toLong() * 1_000_000_000L / outRate
        val now = System.nanoTime()
        if (nextFrameNs == 0L || now - nextFrameNs > 1_000_000_000L) nextFrameNs = now
        nextFrameNs += frameNs
        val waitNs = nextFrameNs - System.nanoTime()
        if (waitNs > 0) {
            try {
                Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Find the FileDescriptor hidden inside Android's process pipe stream and
     *  apply Linux F_SETPIPE_SZ.  Android 7 has no public Process pipe sizing
     *  API, but reflection is unrestricted on this release. */
    private fun shrinkPipe(stream: InputStream): Int? {
        return try {
            val fd = findFileDescriptor(stream) ?: return null
            Os.fcntlInt(fd, F_SETPIPE_SZ, MIN_PIPE_BYTES)
            Os.fcntlInt(fd, F_GETPIPE_SZ, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not limit $name pipe: ${e.message}")
            null
        }
    }

    private fun findFileDescriptor(root: Any): FileDescriptor? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?, depth: Int): FileDescriptor? {
            if (value == null || depth > 6 || !seen.add(value)) return null
            if (value is FileDescriptor) return value
            if (value is FileInputStream) return value.fd
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    if (!FileDescriptor::class.java.isAssignableFrom(field.type) &&
                        !InputStream::class.java.isAssignableFrom(field.type)) continue
                    try {
                        field.isAccessible = true
                        val found = visit(field.get(value), depth + 1)
                        if (found != null) return found
                    } catch (_: Exception) {}
                }
                type = type.superclass
            }
            return null
        }
        return visit(root, 0)
    }

    /** Kill tinycap.  Also unblocks a [read] in progress. */
    override fun close() {
        val p = proc ?: return
        proc = null
        try { p.destroy() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        input = null
        nextFrameNs = 0L
    }

    /** Undo [setupCmd] and make sure no tinycap is left on the PCM. */
    override fun release() {
        close()
        if (directProcess && restoreCmd.isEmpty()) {
            directProcess = false
            return
        }
        // Anchored, so the pattern does not match the shell that is running
        // this very command (its own command line contains the pattern).
        val cmd = buildString {
            if (restoreCmd.isNotEmpty()) append(restoreCmd).append("; ")
            append("pkill -f '^$TINYCAP -- -D $card -d $device ' 2>/dev/null; true")
        }
        RootShell.exec(cmd)
    }

    companion object {
        private const val TAG = "RootPcmCapture"
        /** Where the Magisk module's service.sh installs it. */
        const val TOOL_DIR = "/data/local/tmp"
        const val TINYCAP = "$TOOL_DIR/tinycap32"
        const val MUSL_LOADER = "$TOOL_DIR/ld-musl-armhf.so.1"
        private const val F_SETPIPE_SZ = 1031
        private const val F_GETPIPE_SZ = 1032
        private const val MIN_PIPE_BYTES = 512

        /** Hamming-windowed sinc low-pass with its cutoff just under the
         *  output Nyquist, normalised to unity gain at DC. */
        private fun lowPassTaps(factor: Int): FloatArray {
            val n = 12 * factor + 1
            val fc = 0.45 / factor                 // cycles per input sample
            val mid = (n - 1) / 2.0
            val taps = DoubleArray(n) { k ->
                val x = k - mid
                val sinc = if (x == 0.0) 2 * fc else Math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (n - 1)))
            }
            val sum = taps.sum()
            return FloatArray(n) { (taps[it] / sum).toFloat() }
        }
    }
}
