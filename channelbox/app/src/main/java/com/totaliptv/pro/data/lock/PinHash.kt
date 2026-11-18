package com.totaliptv.pro.data.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN storage: salted PBKDF2-HMAC-SHA256. Only "v1:<iterations>:<saltHex>:<hashHex>" is stored;
 * the PIN itself is never written anywhere.
 */
object PinHash {
    const val PIN_LENGTH = 4
    private const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun create(pin: String, salt: ByteArray = randomSalt()): String {
        require(isValidPin(pin)) { "PIN must be $PIN_LENGTH digits" }
        val hash = derive(pin, salt, ITERATIONS)
        return "v1:$ITERATIONS:${hex(salt)}:${hex(hash)}"
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank() || !isValidPin(pin)) return false
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != "v1") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return false
        val salt = unhex(parts[2]) ?: return false
        val expected = unhex(parts[3]) ?: return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        return runCatching { ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}
