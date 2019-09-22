package blocka

import tunnel.RestModel
import java.util.*

internal class BlockaVpnManager(
        internal var enabled: Boolean,
        private val accountManager: AccountManager,
        private val leaseManager: LeaseManager,
        private val scheduleAccountCheck: () -> Any
) {

    fun restoreAccount(newId: AccountId) {
        accountManager.restoreAccount(newId)
    }

    fun sync() {
        try {
            if (!enabled) return
            accountManager.ensureKeypair()
            accountManager.sync()
            leaseManager.sync(accountManager.state)
            enabled = accountManager.state.accountOk && leaseManager.state.leaseOk
            if (enabled) scheduleAccountCheck()
        } catch (ex: Exception) {
            enabled = false
            when {
                ex is BoringTunLoadException -> throw ex
                ex is RestModel.TooManyDevicesException -> throw BlockaTooManyDevices()
                ex is BlockaGatewayNotSelected -> throw ex
                accountManager.state.id.isBlank() -> throw BlockaAccountEmpty()
                accountManager.state.activeUntil.expired() -> throw BlockaAccountExpired()
                !accountManager.state.accountOk -> throw BlockaAccountNotOk()
                !leaseManager.state.leaseOk -> throw BlockaLeaseNotOk()
            }

            throw Exception("failed syncing blocka vpn", ex)
        }
    }

    fun shouldSync() = !accountManager.state.id.isEmpty() || leaseManager.state.leaseActiveUntil.before(Date())
}

class BlockaAccountExpired: Exception()
class BlockaAccountEmpty: Exception()
class BlockaAccountNotOk: Exception()
class BlockaLeaseNotOk: Exception()
class BlockaGatewayNotSelected: Exception()
class BlockaTooManyDevices: Exception()
