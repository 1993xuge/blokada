package core

import com.github.salomonbrys.kodein.instance
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
        private val ktx: AndroidKontext,
        private val s: Tunnel = ktx.di().instance()
) {

    private var latest: BlockaConfig = BlockaConfig()

    init {
        ktx.on(BLOCKA_CONFIG) {
            latest = it
            when {
                !it.adblocking && !it.blockaVpn -> s.enabled %= false
                !it.adblocking && it.blockaVpn && !it.hasGateway() -> {
                    ktx.emit(BLOCKA_CONFIG, it.copy(blockaVpn = false))
                    s.enabled %= false
                }
                it.adblocking && it.blockaVpn && !it.hasGateway() -> {
                    ktx.emit(BLOCKA_CONFIG, it.copy(blockaVpn = false))
                }
                !s.enabled() -> s.enabled %= true
            }
        }

        s.enabled.doWhenChanged(withInit = true).then {

        }
    }

    fun turnAdblocking(on: Boolean): Boolean {
        ktx.emit(BLOCKA_CONFIG, latest.copy(adblocking = true))
        return true
    }

    fun turnVpn(on: Boolean): Boolean {
        return when {
            !on -> {
                ktx.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = false))
                true
            }
            latest.activeUntil.before(Date()) -> {
                showSnack(R.string.menu_vpn_activate_account.res())
                ktx.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = false))
                false
            }
            !latest.hasGateway() -> {
                showSnack(R.string.menu_vpn_select_gateway.res())
                ktx.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = false))
                false
            }
            else -> {
                ktx.emit(BLOCKA_CONFIG, latest.copy(blockaVpn = true))
                true
            }
        }
    }
}

