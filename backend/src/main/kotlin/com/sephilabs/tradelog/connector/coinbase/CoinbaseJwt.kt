// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.coinbase

import com.sephilabs.tradelog.common.errors.AppException
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.util.encoders.Base64
import java.io.StringReader
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Security
import java.security.Signature
import java.time.Instant

/**
 * Coinbase CDP API uses short-lived JWTs signed with either an Ed25519 or ECDSA P-256 private key.
 * This helper builds the JWT for one request URI.
 */
object CoinbaseJwt {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun build(uri: String, keyName: String, privateKey: String): String {
        val normalized = privateKey.trim()
        val isEs256 = normalized.startsWith("-----") || normalized.contains("EC PRIVATE KEY")
        val header = if (isEs256) {
            "{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"$keyName\"}"
        } else {
            "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"$keyName\"}"
        }
        val now = Instant.now().epochSecond
        val payload = "{\"iss\":\"coinbase-cloud\",\"nbf\":${now - 5},\"exp\":${now + 120},\"sub\":\"$keyName\",\"uri\":\"$uri\"}"
        val signingInput = "${base64Url(header)}.${base64Url(payload)}"
        val signature = if (isEs256) signEs256(signingInput, normalized) else signEd25519(signingInput, normalized)
        return "$signingInput.${base64Url(signature)}"
    }

    private fun signEd25519(input: String, privateKey: String): ByteArray {
        val keyBytes = try {
            Base64.decode(privateKey.replace("\n", "").replace("\r", ""))
        } catch (_: Exception) {
            throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "Coinbase Ed25519 private key is not valid base64")
        }
        if (keyBytes.size != 32) {
            throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "Coinbase Ed25519 private key must be 32 bytes")
        }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(keyBytes, 0))
        signer.update(input.toByteArray(StandardCharsets.UTF_8), 0, input.length)
        return signer.generateSignature()
    }

    private fun signEs256(input: String, pem: String): ByteArray {
        val keyPair = parseEcPem(pem)
        val signer = Signature.getInstance("SHA256withECDSA", "BC")
        signer.initSign(keyPair.private)
        signer.update(input.toByteArray(StandardCharsets.UTF_8))
        val der = signer.sign()
        return derToJws(der)
    }

    private fun parseEcPem(pem: String): KeyPair {
        val reader = StringReader(pem)
        val lines = reader.readLines()
        val base64 = lines.filter { !it.startsWith("-----") }.joinToString("")
        val encoded = Base64.decode(base64)
        val seq = ASN1Sequence.getInstance(encoded)
        val privateKeyBytes = ASN1OctetString.getInstance(seq.getObjectAt(1)).octets
        val privateKeyInt = BigInteger(1, privateKeyBytes)
        val keyFactory = KeyFactory.getInstance("EC", "BC")
        // Coinbase ES256 keys are always P-256; parsing the curve from the PEM adds little here.
        val params = ECNamedCurveTable.getParameterSpec("prime256v1")
        val privateKey = keyFactory.generatePrivate(ECPrivateKeySpec(privateKeyInt, params))
        val publicPoint = params.g.multiply(privateKeyInt)
        val publicKey = keyFactory.generatePublic(ECPublicKeySpec(publicPoint, params))
        return KeyPair(publicKey, privateKey)
    }

    private fun derToJws(der: ByteArray): ByteArray {
        val seq = ASN1Sequence.getInstance(der)
        val r = (seq.getObjectAt(0) as ASN1Integer).value
        val s = (seq.getObjectAt(1) as ASN1Integer).value
        return r.toBytesPadded(32) + s.toBytesPadded(32)
    }

    private fun base64Url(input: String): String =
        Base64.toBase64String(input.toByteArray(StandardCharsets.UTF_8))
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")

    private fun base64Url(bytes: ByteArray): String =
        Base64.toBase64String(bytes)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")

    private fun BigInteger.toBytesPadded(length: Int): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size < length -> ByteArray(length - bytes.size) + bytes
            else -> bytes.copyOfRange(bytes.size - length, bytes.size)
        }
    }
}
