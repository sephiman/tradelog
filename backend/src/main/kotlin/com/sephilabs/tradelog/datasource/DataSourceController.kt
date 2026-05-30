// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/profiles/{profileId}/data-sources")
class DataSourceController(private val service: DataSourceService) {

    @GetMapping
    fun list(@PathVariable profileId: UUID): List<DataSourceDto> = service.list(profileId)

    @PostMapping
    fun create(
        @PathVariable profileId: UUID,
        @Valid @RequestBody body: CreateDataSourceRequest,
    ): ResponseEntity<DataSourceDto> =
        ResponseEntity.status(201).body(service.create(profileId, body))

    @GetMapping("/{dataSourceId}")
    fun get(@PathVariable profileId: UUID, @PathVariable dataSourceId: UUID): DataSourceDto =
        service.get(profileId, dataSourceId)

    @PutMapping("/{dataSourceId}")
    fun update(
        @PathVariable profileId: UUID,
        @PathVariable dataSourceId: UUID,
        @Valid @RequestBody body: UpdateDataSourceRequest,
    ): DataSourceDto = service.update(profileId, dataSourceId, body)

    @DeleteMapping("/{dataSourceId}")
    fun delete(@PathVariable profileId: UUID, @PathVariable dataSourceId: UUID): ResponseEntity<Void> {
        service.delete(profileId, dataSourceId)
        return ResponseEntity.noContent().build()
    }
}
