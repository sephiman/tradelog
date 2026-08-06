// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitget

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component

/** Bitget on the pre-UTA classic (v2) API. Its keys and [BitgetConnector]'s are not interchangeable. */
@Component
class BitgetClassicConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : BitgetPositionConnector(props.connectors.bitget, mapper, props) {

    override val kind = SourceKind.BITGET_CLASSIC

    override val historyPath = "/api/v2/mix/position/history-position"
    override val productParam = "productType"
    override val cursorParam = "idLessThan"
}
