// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.quantfury

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.auth.ImportRateLimiter
import com.sephilabs.tradelog.identity.auth.CurrentUser
import com.sephilabs.tradelog.sync.SyncRunDto
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/profiles/{profileId}/data-sources/{dataSourceId}/quantfury")
class QuantfuryImportController(
    private val service: QuantfuryImportService,
    private val currentUser: CurrentUser,
    private val importRateLimiter: ImportRateLimiter,
) {

    @PostMapping("/preview")
    fun preview(
        @PathVariable profileId: UUID,
        @PathVariable dataSourceId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): QuantfuryPreviewDto {
        require(!file.isEmpty)
        return service.preview(profileId, dataSourceId, file.inputStream)
    }

    @PostMapping("/execute")
    fun execute(
        @PathVariable profileId: UUID,
        @PathVariable dataSourceId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): SyncRunDto {
        if (file.isEmpty) throw AppException.badRequest("IMPORT_PARSE_FAILED")
        if (!importRateLimiter.tryAcquire("import:${currentUser.requireUser().id}")) {
            throw AppException.tooManyRequests()
        }
        return service.execute(profileId, dataSourceId, file.inputStream)
    }
}
