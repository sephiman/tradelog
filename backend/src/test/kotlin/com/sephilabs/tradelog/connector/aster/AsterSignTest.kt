// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.aster

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AsterSignTest {

    @Test
    fun `signs a query string as a 130-character hex signature`() {
        val privateKey = "5603e11886a6805faa5fb9d3264b89131498220d79a08b53d4384ea0cbf9a076"
        val query = "user=0x1234567890123456789012345678901234567890&signer=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd&nonce=1234567890000&symbol=BTCUSDT"

        val signature = AsterSign.sign(query, privateKey)

        assertThat(signature).hasSize(130)
        assertThat(signature).matches(Regex("^[0-9a-f]+\$").pattern)
    }
}
