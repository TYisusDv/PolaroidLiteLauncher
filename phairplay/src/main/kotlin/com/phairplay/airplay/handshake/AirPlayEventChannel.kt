package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/** Sends recovery commands on the encrypted AirPlay event connection. */
internal class AirPlayEventChannel(
    private val socket: Socket,
    sharedSecret: ByteArray,
) {
    private val writeKey = deriveKey(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key")
    private val writeLock = Any()
    private var writeCounter = 0L
    private val lastKeyframeRequestMs = AtomicLong(0L)

    /** Ask the sender for a fresh H.264 IDR, at most once per second. */
    fun requestKeyframe() {
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastKeyframeRequestMs.get()
            if (now - previous < KEYFRAME_REQUEST_INTERVAL_MS) return
            if (lastKeyframeRequestMs.compareAndSet(previous, now)) break
        }

        val body = PlistCodec.encode(mapOf("type" to "forceKeyFrame"))
        val request = buildString {
            append("POST /command HTTP/1.1\r\n")
            append("Content-Type: application/x-apple-binary-plist\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("CSeq: ").append(now).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII) + body

        try {
            synchronized(writeLock) {
                check(!socket.isClosed && socket.isConnected) { "event socket is not connected" }
                socket.getOutputStream().write(encryptFrame(request))
                socket.getOutputStream().flush()
            }
            Logger.i("AirPlay event: requested a fresh video keyframe")
        } catch (e: Exception) {
            Logger.w("AirPlay event: keyframe request failed: ${e.message}")
        }
    }

    private fun encryptFrame(plain: ByteArray): ByteArray {
        require(plain.size <= MAX_FRAME_SIZE)
        val length = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(plain.size.toShort()).array()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putLong(writeCounter++).array()
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(writeKey), TAG_BITS, nonce, length))
        val encrypted = ByteArray(cipher.getOutputSize(plain.size))
        val written = cipher.processBytes(plain, 0, plain.size, encrypted, 0)
        val total = written + cipher.doFinal(encrypted, written)
        return length + encrypted.copyOf(total)
    }

    private fun deriveKey(secret: ByteArray, salt: String, info: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA512Digest())
        hkdf.init(HKDFParameters(secret, salt.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(KEY_SIZE).also { hkdf.generateBytes(it, 0, it.size) }
    }

    private companion object {
        const val KEY_SIZE = 32
        const val TAG_BITS = 128
        const val MAX_FRAME_SIZE = 1024
        const val KEYFRAME_REQUEST_INTERVAL_MS = 1_000L
    }
}
