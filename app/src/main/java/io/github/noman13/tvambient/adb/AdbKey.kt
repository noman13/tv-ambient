package io.github.noman13.tvambient.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The app's own ADB key (TECHNICAL.md §3): RSA 2048, private key stored as PKCS#8 in `files/adbkey` (app-private,
 * never leaves the TV). The first connection shows "Allow USB debugging?" for it on the TV.
 */
class AdbKey private constructor(private val key: RSAPrivateCrtKey) {

    /** Signs an AUTH token the way adbd checks it: RSA PKCS#1 v1.5 over the token as a SHA-1 digest. */
    fun sign(token: ByteArray): ByteArray {
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(key as PrivateKey)
        s.update(SHA1_DIGEST_INFO)
        s.update(token)
        return s.sign()
    }

    /** The public key in Android's format (`RSAPublicKey` struct, base64) plus a name, NUL-terminated. */
    fun publicKeyPayload(name: String): ByteArray {
        val words = 64 // 2048 bits / 32
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong()
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
        val buf = java.nio.ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        putLittleEndianWords(buf, n, words)
        putLittleEndianWords(buf, rr, words)
        buf.putInt(key.publicExponent.toInt())
        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return ("$b64 $name\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun putLittleEndianWords(buf: java.nio.ByteBuffer, v: BigInteger, words: Int) {
        var x = v
        val mask = BigInteger.valueOf(0xffffffffL)
        repeat(words) {
            buf.putInt(x.and(mask).toInt())
            x = x.shiftRight(32)
        }
    }

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /**
         * Loads `dir/adbkey`, or makes and stores a new key. [dir] is the app's no-backup folder, so the key is never
         * copied off the TV; a key from an older build in `files/` is moved there.
         */
        fun loadOrCreate(dir: File, legacyDir: File? = null): AdbKey {
            val f = File(dir, "adbkey")
            val old = legacyDir?.let { File(it, "adbkey") }
            if (!f.isFile && old != null && old.isFile) runCatching { old.copyTo(f); old.delete() }
            if (f.isFile) {
                runCatching {
                    val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
                    return AdbKey(k as RSAPrivateCrtKey)
                }
            }
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val pair = g.generateKeyPair()
            val tmp = File(dir, "adbkey.tmp")
            tmp.writeBytes(pair.private.encoded)
            tmp.renameTo(f)
            return AdbKey(pair.private as RSAPrivateCrtKey)
        }
    }
}
