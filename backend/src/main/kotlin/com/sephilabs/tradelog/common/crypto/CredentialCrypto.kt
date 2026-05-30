// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.common.crypto

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encryption for exchange API credentials at rest.
 *
 * The key comes from `app.crypto.key` (env `TRADELOG_CRYPTO_KEY`) as a base64-encoded
 * 16/24/32-byte AES key. Output format is base64( iv(12 bytes) || ciphertext+tag ).
 *
 * The key is only required once a data source with API credentials is saved or read; the app
 * boots fine without it and only fails (with a clear error) when an API credential is actually
 * stored or decrypted while the key is missing/malformed. Encryption is done explicitly here
 * (not via a JPA AttributeConverter) so the secret never leaks into logs, DTOs, or any response —
 * it is decrypted only inside the sync worker.
 */
@Component
class CredentialCrypto(private val props: AppProperties) {

    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val key = requireKey()
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(stored: String): String {
        val key = requireKey()
        val bytes = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            throw AppException.badRequest("CREDENTIALS_CORRUPT")
        }
        if (bytes.size <= IV_LENGTH) throw AppException.badRequest("CREDENTIALS_CORRUPT")
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun requireKey(): SecretKeySpec {
        val raw = props.crypto.key
        if (raw.isBlank()) throw AppException.badRequest("CRYPTO_KEY_MISSING")
        val keyBytes = try {
            Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            throw AppException.badRequest("CRYPTO_KEY_INVALID")
        }
        if (keyBytes.size !in intArrayOf(16, 24, 32)) {
            throw AppException.badRequest("CRYPTO_KEY_INVALID")
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
