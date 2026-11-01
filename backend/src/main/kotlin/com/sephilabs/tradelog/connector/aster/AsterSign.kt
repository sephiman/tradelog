// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.aster

import com.sephilabs.tradelog.common.errors.AppException
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECAlgorithms
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * EIP-712 signing for Aster's V3 Pro API Agent keys. The signed message is the final URL-encoded
 * query string (excluding `signature`), and the signature is appended as `signature=v,r,s`.
 */
object AsterSign {

    private const val MAINNET_CHAIN_ID = 1666L

    private val DOMAIN_TYPE_HASH = keccak256("EIP712Domain(string name,string version,uint256 chainId)")
    private val MESSAGE_TYPE_HASH = keccak256("Message(string msg)")

    /** Sign [queryString] with the agent private key (hex, with or without 0x). */
    fun sign(queryString: String, privateKeyHex: String): String {
        val key = privateKeyHex.removePrefix("0x")
        val privateKey = try {
            BigInteger(key, 16)
        } catch (_: NumberFormatException) {
            throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "Aster V3 agent private key is not valid hex")
        }
        val digest = eip712Digest(queryString, MAINNET_CHAIN_ID)
        val (r, s, recId) = signDigest(digest, privateKey)
        return "${r.toString(16).padStart(64, '0')}${s.toString(16).padStart(64, '0')}${(recId + 27).toString(16)}"
    }

    private fun eip712Digest(queryString: String, chainId: Long): ByteArray {
        val domainSeparator = domainSeparator(chainId)
        val messageHash = messageHash(queryString)
        val digest = KeccakDigest(256)
        val prefix = byteArrayOf(0x19, 0x01)
        digest.update(prefix, 0, prefix.size)
        digest.update(domainSeparator, 0, domainSeparator.size)
        digest.update(messageHash, 0, messageHash.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun domainSeparator(chainId: Long): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(DOMAIN_TYPE_HASH, 0, DOMAIN_TYPE_HASH.size)
        digest.update(keccak256("AsterSignTransaction"), 0, 32)
        digest.update(keccak256("1"), 0, 32)
        digest.update(chainId.toBigInteger().toBytesPadded(32), 0, 32)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun messageHash(queryString: String): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(MESSAGE_TYPE_HASH, 0, MESSAGE_TYPE_HASH.size)
        digest.update(keccak256(queryString), 0, 32)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun keccak256(input: String): ByteArray {
        val digest = KeccakDigest(256)
        val bytes = input.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes, 0, bytes.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun keccak256(bytes: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(bytes, 0, bytes.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun signDigest(digest: ByteArray, privateKey: BigInteger): Triple<BigInteger, BigInteger, Int> {
        val params = ECNamedCurveTable.getParameterSpec("secp256k1")
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
        val signer = ECDSASigner(HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey, domain))
        val sig = signer.generateSignature(digest)
        val r = sig[0]
        val s = sig[1]
        val canonicalS = if (s > params.n.shiftRight(1)) params.n.subtract(s) else s
        val recId = recoverId(digest, r, canonicalS, domain, privateKey)
            ?: throw AppException.badRequest("SYNC_FAILED", detail = "Aster V3 signature recovery id could not be determined")
        return Triple(r, canonicalS, recId)
    }

    private fun recoverId(
        digest: ByteArray,
        r: BigInteger,
        s: BigInteger,
        domain: ECDomainParameters,
        privateKey: BigInteger,
    ): Int? {
        val e = BigInteger(1, digest)
        val n = domain.n
        val expectedPub = domain.g.multiply(privateKey).getEncoded(false)
        val rInv = r.modInverse(n)
        // For secp256k1 the valid x is r itself; recId encodes the y parity (0 = even, 1 = odd).
        for (recId in 0..1) {
            val compressed = byteArrayOf((if (recId == 0) 0x02 else 0x03).toByte()) + r.toBytesPadded(32)
            val rPoint = try { domain.curve.decodePoint(compressed) } catch (_: IllegalArgumentException) { continue }
            val q = ECAlgorithms.sumOfTwoMultiplies(
                rPoint,
                s.multiply(rInv).mod(n),
                domain.g,
                e.negate().multiply(rInv).mod(n),
            )
            if (q.getEncoded(false).contentEquals(expectedPub)) return recId
        }
        return null
    }

    private fun BigInteger.toBytesPadded(length: Int): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size < length -> ByteArray(length - bytes.size) + bytes
            else -> bytes.copyOfRange(bytes.size - length, bytes.size)
        }
    }
}
