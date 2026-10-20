/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts.mac

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted link between the Pad and ALXP for Mac.
 *
 * Each side has a long-term X25519 key. A session mixes both keys with fresh
 * nonces into an AES-256-GCM key. The first time a Mac connects, both sides
 * show a six-digit code from that exchange and the user confirms it on the
 * Pad; after that the Mac's key is remembered.
 *
 * Frames: u32 length, then 12-byte nonce + ciphertext + 16-byte tag. The
 * plaintext is a one-byte type and its payload.
 */
class MacLink(private val socket: Socket, private val key: ByteArray) {

    companion object {
        const val MAGIC = "ALXP1"
        const val CLIP_TEXT: Byte = 1
        const val CLIP_PNG: Byte = 2
        const val DISPLAY_START: Byte = 10
        const val VIDEO: Byte = 11
        const val DISPLAY_STOP: Byte = 12
        const val POINTER: Byte = 20
        const val SCROLL: Byte = 21
        const val HELLO: Byte = 30

        private const val PREFIX_LENGTH = 12 // X.509 header before a raw X25519 key
        private const val MAX_FRAME = 16 shl 20

        /** The Pad's long-term key, kept in device storage. */
        fun identity(context: Context): KeyPair {
            val prefs = context.getSharedPreferences("mac_link", Context.MODE_PRIVATE)
            val factory = KeyFactory.getInstance("XDH")
            val stored = prefs.getString("private", null)
            if (stored != null) {
                val private = factory.generatePrivate(PKCS8EncodedKeySpec(decode(stored)))
                val public = factory.generatePublic(X509EncodedKeySpec(decode(prefs.getString("public", "")!!)))
                return KeyPair(public, private)
            }
            val pair = KeyPairGenerator.getInstance("XDH").generateKeyPair()
            prefs.edit()
                .putString("private", encode(pair.private.encoded))
                .putString("public", encode(pair.public.encoded))
                .apply()
            return pair
        }

        fun raw(pair: KeyPair): ByteArray = pair.public.encoded.copyOfRange(PREFIX_LENGTH, PREFIX_LENGTH + 32)

        fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

        /**
         * Server side of the handshake. Returns the session key and the code the
         * user compares, or throws if the Mac does not speak ALXP.
         */
        fun accept(socket: Socket, pair: KeyPair): Triple<ByteArray, ByteArray, String> {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val magic = ByteArray(MAGIC.length).also { input.readFully(it) }
            require(String(magic) == MAGIC) { "Not an ALXP client" }
            val macPublic = ByteArray(32).also { input.readFully(it) }
            val macNonce = ByteArray(16).also { input.readFully(it) }
            val padPublic = raw(pair)
            val padNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            output.write(padPublic)
            output.write(padNonce)
            output.flush()

            val factory = KeyFactory.getInstance("XDH")
            val prefix = pair.public.encoded.copyOfRange(0, PREFIX_LENGTH)
            val macKey = factory.generatePublic(X509EncodedKeySpec(prefix + macPublic))
            val shared = KeyAgreement.getInstance("XDH").run {
                init(pair.private)
                doPhase(macKey, true)
                generateSecret()
            }
            val transcript = macPublic + padPublic + macNonce + padNonce
            val key = sha256("alxp-key".toByteArray(), shared, transcript)
            val digest = sha256("alxp-code".toByteArray(), shared, transcript)
            val number = ByteBuffer.wrap(digest).int.toLong() and 0xFFFFFFFFL
            return Triple(key, macPublic, "%06d".format(number % 1_000_000))
        }

        private fun encode(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        private fun decode(text: String) = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
    }

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val secret = SecretKeySpec(key, "AES")
    private var sendCounter = 0L
    private val random = SecureRandom()

    @Synchronized
    fun send(type: Byte, payload: ByteArray = ByteArray(0)) {
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        ByteBuffer.wrap(nonce).putLong(4, sendCounter++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(byteArrayOf(type) + payload)
        output.writeInt(nonce.size + sealed.size)
        output.write(nonce)
        output.write(sealed)
        output.flush()
    }

    /** Blocks for the next frame: its type and payload. */
    fun receive(): Pair<Byte, ByteArray> {
        val length = input.readInt()
        require(length in 29..MAX_FRAME) { "Bad frame length $length" }
        val frame = ByteArray(length).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, frame, 0, 12))
        val plain = cipher.doFinal(frame, 12, length - 12)
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    fun close() = try { socket.close() } catch (_: Exception) {}
}
