// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.common.crypto

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class CredentialCryptoTest {

    private fun crypto(keyBytes: Int?): CredentialCrypto {
        val key = keyBytes?.let { Base64.getEncoder().encodeToString(ByteArray(it) { i -> i.toByte() }) } ?: ""
        return CredentialCrypto(AppProperties(crypto = AppProperties.Crypto(key = key)))
    }

    @Test
    fun `round trips plaintext`() {
        val c = crypto(32)
        val secret = """{"apiKey":"abc","apiSecret":"def"}"""
        val enc = c.encrypt(secret)
        assertNotEquals(secret, enc)
        assertEquals(secret, c.decrypt(enc))
    }

    @Test
    fun `distinct ciphertexts for same plaintext (random iv)`() {
        val c = crypto(32)
        assertNotEquals(c.encrypt("same"), c.encrypt("same"))
    }

    @Test
    fun `missing key throws`() {
        val ex = assertThrows(AppException::class.java) { crypto(null).encrypt("x") }
        assertEquals("CRYPTO_KEY_MISSING", ex.code)
    }

    @Test
    fun `tampered ciphertext fails to decrypt`() {
        val c = crypto(32)
        assertThrows(Exception::class.java) { c.decrypt("not-valid-base64-or-too-short") }
    }
}
