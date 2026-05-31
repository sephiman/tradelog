// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.backup

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.auth.CurrentUser
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Whole-account backup for the authenticated user. Export streams a portable JSON snapshot (no
 * secrets); import REPLACES everything the user owns with the file's contents and is guarded by an
 * explicit `confirm=true` so a stray POST can't wipe data.
 */
@RestController
@RequestMapping("/api/backup")
class BackupController(
    private val currentUser: CurrentUser,
    private val exportService: ExportService,
    private val importService: ImportService,
) {

    @GetMapping("/export")
    fun export(): ResponseEntity<BackupEnvelope> {
        val envelope = exportService.export(currentUser.requireUser())
        val filename = "tradelog-export-${Instant.now().toString().take(10)}.json"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(envelope)
    }

    @PostMapping("/import")
    fun import(
        @RequestParam(name = "confirm", defaultValue = "false") confirm: Boolean,
        @RequestBody envelope: BackupEnvelope,
    ): ImportSummary {
        if (!confirm) throw AppException.badRequest("BACKUP_CONFIRM_REQUIRED")
        return importService.replaceAll(currentUser.requireUser(), envelope)
    }
}
