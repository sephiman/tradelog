// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.config.AppProperties
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Venue unreachable — not an AppException, which a backfill walk would read as the retention edge. */
class VenueUnreachableException(venue: String, cause: Throwable) :
    RuntimeException("$venue unreachable: ${cause.message}", cause)

/** Shared HTTP plumbing for the exchange connectors. */
object ExchangeHttp {

    /**
     * A [RestClient] with the endpoint's configured connect/read timeout applied. Sync runs on the
     * bounded login executor and the single scheduler thread, so a request that never times out
     * would block those threads indefinitely.
     */
    fun restClient(endpoint: AppProperties.ExchangeEndpoint): RestClient {
        val timeout = Duration.ofMillis(endpoint.timeoutMs)
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
        factory.setReadTimeout(timeout)
        return RestClient.builder().baseUrl(endpoint.baseUrl).requestFactory(factory).build()
    }
}

/** Cryptographic helpers shared by the signed-REST exchange connectors. */
object ExchangeSign {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    fun sha256Hex(input: String): String = toHex(sha256(input))

    fun sha512Hex(input: String): String =
        toHex(MessageDigest.getInstance("SHA-512").digest(input.toByteArray(Charsets.UTF_8)))

    fun hmacSha256(secret: String, message: String): ByteArray =
        hmac("HmacSHA256", secret.toByteArray(Charsets.UTF_8), message.toByteArray(Charsets.UTF_8))

    fun hmacSha256Hex(secret: String, message: String): String = toHex(hmacSha256(secret, message))

    fun hmacSha256Base64(secret: String, message: String): String =
        Base64.getEncoder().encodeToString(hmacSha256(secret, message))

    fun hmacSha512Hex(secret: String, message: String): String =
        toHex(hmac("HmacSHA512", secret.toByteArray(Charsets.UTF_8), message.toByteArray(Charsets.UTF_8)))

    /** HMAC-SHA512 over raw bytes, for schemes whose secret is base64-encoded (Kraken Futures). */
    fun hmacSha512(secret: ByteArray, message: ByteArray): ByteArray = hmac("HmacSHA512", secret, message)

    private fun hmac(algorithm: String, secret: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret, algorithm))
        return mac.doFinal(message)
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
