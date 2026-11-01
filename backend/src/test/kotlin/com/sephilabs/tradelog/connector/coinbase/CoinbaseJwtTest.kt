// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.coinbase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoinbaseJwtTest {

    @Test
    fun `Ed25519 JWT has three base64url parts and a kid header`() {
        val jwt = CoinbaseJwt.build(
            uri = "GET api.coinbase.com/api/v3/brokerage/orders/historical/fills",
            keyName = "organizations/org/apiKeys/key",
            privateKey = "GqgizGEd42bKhI4h3fIQhfO0Qp/HSTROpyO/HiuoY0I=",
        )
        val parts = jwt.split(".")
        assertThat(parts).hasSize(3)
        assertThat(parts.all { !it.contains("+") && !it.contains("/") && !it.contains("=") }).isTrue
    }

    @Test
    fun `ES256 JWT accepts a PEM private key`() {
        val pem = """
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEIAWs/5DNsx+lQLKVEk+hF9Fj2JpVKJb7iOKXPoXOuTNaoAoGCCqGSM49
            AwEHoUQDQgAEulx/fJfgNMHhzYE8Bt5s+qebTDcT8wRztrao3KvURXFVw+WLAd17
            CaF+1buDqw9O6SDFDH92EDviquOd/S+i1w==
            -----END EC PRIVATE KEY-----
        """.trimIndent()
        val jwt = CoinbaseJwt.build(
            uri = "POST drb.coinbase.com/api/v2",
            keyName = "key",
            privateKey = pem,
        )
        val header = String(java.util.Base64.getUrlDecoder().decode(jwt.substringBefore(".")))
        assertThat(header).contains("\"alg\":\"ES256\"")
        assertThat(header).contains("\"kid\":\"key\"")
    }
}
