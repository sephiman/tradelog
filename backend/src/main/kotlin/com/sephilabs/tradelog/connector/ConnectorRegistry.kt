// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component

/** Indexes the available [Connector] beans by [SourceKind] (factory lookup). */
@Component
class ConnectorRegistry(connectors: List<Connector>) {

    private val byKind: Map<SourceKind, Connector> = connectors.associateBy { it.kind }

    fun get(kind: SourceKind): Connector =
        byKind[kind] ?: throw AppException.badRequest("CONNECTOR_NOT_AVAILABLE", kind.name)

    fun api(kind: SourceKind): ApiConnector =
        get(kind) as? ApiConnector ?: throw AppException.badRequest("CONNECTOR_NOT_API", kind.name)

    fun file(kind: SourceKind): FileImportConnector =
        get(kind) as? FileImportConnector ?: throw AppException.badRequest("CONNECTOR_NOT_FILE", kind.name)
}
