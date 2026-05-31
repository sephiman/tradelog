// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.journal

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.connector.FileImportPreviewDto
import com.sephilabs.tradelog.connector.FileImportService
import com.sephilabs.tradelog.identity.auth.CurrentUser
import com.sephilabs.tradelog.identity.auth.ImportRateLimiter
import com.sephilabs.tradelog.sync.SyncRunDto
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/profiles/{profileId}/data-sources/{dataSourceId}/journal-csv")
class JournalCsvImportController(
    private val service: FileImportService,
    private val currentUser: CurrentUser,
    private val importRateLimiter: ImportRateLimiter,
) {

    @PostMapping("/preview")
    fun preview(
        @PathVariable profileId: UUID,
        @PathVariable dataSourceId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): FileImportPreviewDto {
        if (file.isEmpty) throw AppException.badRequest("IMPORT_PARSE_FAILED")
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
