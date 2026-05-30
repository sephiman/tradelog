// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import java.util.UUID

/**
 * Hook invoked after a successful login to kick off an incremental, rate-limited sync of the
 * user's ACTIVE API data sources. Implemented asynchronously so it never blocks the login
 * response (see the sync module). Kept as an interface so the identity module does not depend
 * on the sync internals.
 */
interface LoginSyncTrigger {
    fun onLogin(userId: UUID)
}
