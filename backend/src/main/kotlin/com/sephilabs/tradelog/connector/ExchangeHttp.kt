// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Cryptographic helpers shared by the signed-REST exchange connectors. */
object ExchangeSign {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(input: String): String = toHex(
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    )

    fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return toHex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
