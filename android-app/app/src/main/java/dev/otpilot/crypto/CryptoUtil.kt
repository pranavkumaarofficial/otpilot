package dev.otpilot.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption compatible with the Web Crypto API used in the PWA.
 *
 * Modern Android processors (Snapdragon, MediaTek) have hardware AES
 * instruction sets — encryption takes <1ms with near-zero power draw.
 */
object CryptoUtil {

    data class EncryptedPayload(val iv: String, val ciphertext: String)

    /**
     * Encrypt plaintext with a base64-encoded 256-bit key.
     * Returns base64-encoded IV and ciphertext, compatible with
     * Web Crypto API's AES-GCM on the PWA receiver side.
     */
    fun encrypt(plaintext: String, keyBase64: String): EncryptedPayload? {
        return try {
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val key = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)

            // GCM generates a random 12-byte IV internally
            val iv = cipher.iv
            val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            EncryptedPayload(
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertextWithTag, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt a payload encrypted by this class or the Web Crypto API.
     */
    fun decrypt(ivBase64: String, ciphertextBase64: String, keyBase64: String): String? {
        return try {
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val key = SecretKeySpec(keyBytes, "AES")
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
