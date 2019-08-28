package update

import core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blokada.BuildConfig
import tunnel.TunnelState
import tunnel.tunnelState
import ui.displayNotificationForUpdate

val updateManager by lazy {
    UpdateImpl()
}

class UpdateImpl {

    val lastSeenUpdateMillis = newPersistedProperty2("lastSeenUpdate", { 0L })
}

val updateCoordinator = UpdateCoordinator(downloader = AUpdateDownloader())
suspend fun initUpdate() = withContext(Dispatchers.Main.immediate) {
    val ctx = getApplicationContext()!!

    // Check for update periodically
    tunnelState.tunnelState.doWhen { tunnelState.tunnelState(TunnelState.ACTIVE) }.then {
        // This "pokes" the cache and refreshes if needed
        repo.content.refresh()
    }

    // Display an info message when update is available
    repo.content.doOnUiWhenSet().then {
        if (isUpdate(repo.content().newestVersionCode)) {
            updateManager.lastSeenUpdateMillis.refresh(force = true)
        }
    }

    // Display notifications for updates
    updateManager.lastSeenUpdateMillis.doOnUiWhenSet().then {
        val content = repo.content()
        val last = updateManager.lastSeenUpdateMillis()
        val cooldown = 86400 * 1000L

        if (isUpdate(content.newestVersionCode) && canShowNotification(last, cooldown)) {
            displayNotificationForUpdate(ctx, content.newestVersionName)
            updateManager.lastSeenUpdateMillis %= time.now()
        }
    }

}

internal fun canShowNotification(last: Long, cooldownMillis: Long): Boolean {
    return last + cooldownMillis < time.now()
}

fun isUpdate(code: Int): Boolean {
    return code > BuildConfig.VERSION_CODE
}
