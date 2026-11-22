package com.callagent.gateway.rtp

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor

/**
 * Full-duplex cellular PCM bridge through the GT-S7580 Broadcom DSP VOIF tap.
 *
 * The companion kernel module publishes untouched S16LE narrow-band frames
 * at 8 kHz.  The descriptor is non-blocking so stopping a call can always
 * close it immediately; RTP's existing encoder performs the 8 -> 16 kHz
 * expansion when the SIP leg negotiated G.722.
 */
class VoifCapture(
    private val path: String = DEVICE,
) : CaptureSource {
    @Volatile private var fd: FileDescriptor? = null

    override val name = "VOIF-DL@8k"

    override fun start(): Boolean {
        if (fd != null) return true
        return try {
            fd = Os.open(path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            Log.i(TAG, "Opened $name from $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open $path: ${e.message}")
            false
        }
    }

    override fun read(buf: ByteArray): Int {
        while (true) {
            val current = fd ?: return -1
            try {
                return Os.read(current, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EAGAIN) {
                    if (fd != null) Log.w(TAG, "$name read failed: ${e.message}")
                    return -1
                }
            } catch (e: Exception) {
                if (fd != null) Log.w(TAG, "$name read failed: ${e.message}")
                return -1
            }
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
    }

    /** Write one decoded SIP frame into the modem uplink.
     * VOIF consumes 8 kHz mono S16LE; G.722 playback arrives at 16 kHz. */
    fun writeUplink(pcm: ByteArray, sampleRate: Int): Int {
        val current = fd ?: return -1
        val out = if (sampleRate == 8000) pcm else downsample16To8(pcm)
        return try {
            Os.write(current, out, 0, out.size)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EAGAIN && fd != null)
                Log.w(TAG, "VOIF uplink write failed: ${e.message}")
            0
        } catch (e: Exception) {
            if (fd != null) Log.w(TAG, "VOIF uplink write failed: ${e.message}")
            -1
        }
    }

    private fun downsample16To8(input: ByteArray): ByteArray {
        val samples = input.size / 2
        val out = ByteArray((samples / 2) * 2)
        var src = 0
        var dst = 0
        while (src + 3 < input.size) {
            // Average adjacent 16 kHz samples instead of simply discarding one.
            val a = ((input[src + 1].toInt() shl 8) or (input[src].toInt() and 0xff)).toShort().toInt()
            val b = ((input[src + 3].toInt() shl 8) or (input[src + 2].toInt() and 0xff)).toShort().toInt()
            val value = (a + b) / 2
            out[dst] = (value and 0xff).toByte()
            out[dst + 1] = ((value shr 8) and 0xff).toByte()
            src += 4
            dst += 2
        }
        return out
    }

    override fun close() {
        val current = fd ?: return
        fd = null
        try { Os.close(current) } catch (_: Exception) {}
    }

    override fun release() = close()

    companion object {
        private const val TAG = "VoifCapture"
        const val DEVICE = "/dev/voif_tap"
    }
}
