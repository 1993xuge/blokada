package core

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.blokada.R
import tunnel.BLOCKA_CONFIG
import tunnel.BlockaConfig
import tunnel.showSnack
import java.util.*

/**
 * Automatically decides on the state of Tunnel.enabled flag based on the
 * state of adblocking, vpn, and DNS.
 */

class TunnelStateManager(
        private val ktx: AndroidKontext
) {

    private var latest: BlockaConfig = BlockaConfig()
        @Synchronized get
        @Synchronized set

    init {
        core.on(BLOCKA_CONFIG) {
            latest = it
            check(it)
        }

        dnsManager.enabled.doWhenChanged(withInit = true).then {
            check(latest)
        }

       tunnelState.enabled.doWhenChanged(withInit = true).then {
            when {
                !tunnelState.enabled() -> {
                    // Save state before pausing
                    runBlocking {
                        TunnelPause(
                                vpn = latest.blockaVpn,
                                adblocking = latest.adblocking,
                                dns = dnsManager.enabled()
                        ).saveToPersistence()
                    }

                    v("pausing features.")
                    core.emit(BLOCKA_CONFIG, latest.copy(adblocking = false, blockaVpn = false))
                    dnsManager.enabled %= false
                }
                else -> {
                    // Restore the state
                    val pause = runBlocking { TunnelPause().loadFromPersistence() }

                    val vpn = pause.vpn && latest.hasGateway() && latest.leaseActiveUntil.after(Date())
                    var adblocking = pause.adblocking && Product.current(ktx.ctx) != Product.DNS
                    var dns = pause.dns

                    v("restoring features, is (vpn, adblocking, dns): $vpn $adblocking $dns")

                    if (!adblocking && !vpn && !dns) {
                        if (Product.current(ktx.ctx) != Product.DNS) {
                            v("all features disabled, activating adblocking")
                            adblocking = true
                        } else {
                            v("all features disabled, activating dns")
                            dns = true
                        }
                    }

                    dnsManager.enabled %= dns
                    core.emit(BLOCKA_CONFIG, latest.copy(adblocking = adblocking, blockaVpn = vpn))
                }
            }
        }
    }

    private fun check(it: BlockaConfig) {
        when {
            !tunnelState.enabled() -> Unit
            !it.adblocking && !it.blockaVpn && !dnsManager.enabled() -> {
                v("turning off because no features enabled")
               tunnelState.enabled %= false
            }
            !it.adblocking && it.blockaVpn && !it.hasGateway() -> {
                v("turning off everything because no gateway selected")
                core.emit(BLOCKA_CONFIG, it.copy(blockaVpn = false))
               tunnelState.enabled %= false
            }
            (it.adblocking || dnsManager.enabled()) && it.blockaVpn && !it.hasGateway() -> {
                v("turning off vpn because no gateway selected")
                core.emit(BLOCKA_CONFIG, it.copy(blockaVpn = false))
            }
        }
    }

    fun turnAdblocking(on: Boolean): Boolean {
        core.emit(BLOCKA_CONFIG, latest.copy(adblocking = on))
        return true
    }

    fun turnVpn(on: Boolean): Boolean {
        return when {
            !on -> {
                core.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = false))
                true
            }
            latest.activeUntil.before(Date()) -> {
                GlobalScope.launch { showSnack(R.string.menu_vpn_activate_account.res()) }
                core.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = false))
                false
            }
            !latest.hasGateway() -> {
                GlobalScope.launch { showSnack(R.string.menu_vpn_select_gateway.res()) }
                core.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = false))
                false
            }
            else -> {
                core.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = true))
                true
            }
        }
    }
}

data class TunnelPause(
        val vpn: Boolean = false,
        val adblocking: Boolean = false,
        val dns: Boolean = false
): Persistable {
    override fun key() = "tunnel:pause"
}

