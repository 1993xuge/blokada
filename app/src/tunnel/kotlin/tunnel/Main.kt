package tunnel

import android.net.VpnService
import com.github.michaelbull.result.onFailure
import com.github.salomonbrys.kodein.instance
import core.*
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newSingleThreadContext
import kotlinx.coroutines.experimental.runBlocking
import java.io.FileDescriptor
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*

object Events {
    val RULESET_BUILDING = "RULESET_BUILDING".newEventOf<Unit>()
    val RULESET_BUILT = "RULESET_BUILT".newEventOf<Pair<Int, Int>>()
    val FILTERS_CHANGING = "FILTERS_CHANGING".newEvent()
    val FILTERS_CHANGED = "FILTERS_CHANGED".newEventOf<Collection<Filter>>()
    val REQUEST = "REQUEST".newEventOf<Request>()
    val TUNNEL_POWER_SAVING = "TUNNEL_POWER_SAVING".newEvent()
    val MEMORY_CAPACITY = "MEMORY_CAPACITY".newEventOf<Int>()
    val TUNNEL_RESTART = "TUNNEL_RESTART".newEvent()
}

class Main(
        private val onVpnClose: (Kontext) -> Unit,
        private val onVpnConfigure: (Kontext, VpnService.Builder) -> Unit,
        private val onRequest: Callback<Request>,
        private val doResolveFilterSource: (Filter) -> IFilterSource,
        private val doProcessFetchedFilters: (Set<Filter>) -> Set<Filter>
) {

    private val forwarder = Forwarder()
    private val loopback = LinkedList<Triple<ByteArray, Int, Int>>()
    private val blockade = Blockade()
    private var currentServers = emptyList<InetSocketAddress>()
    private var filters = FilterManager(blockade = blockade, doResolveFilterSource =
    doResolveFilterSource, doProcessFetchedFilters = doProcessFetchedFilters)
    private var socketCreator = {
        w("using not protected socket")
        DatagramSocket()
    }
    private var config = TunnelConfig()
    private var blockaConfig = BlockaConfig()
    private var proxy = createProxy()
    private var tunnel = createTunnel()
    private var connector = ServiceConnector(
            onClose = onVpnClose,
            onConfigure = { ktx, tunnel -> 0L }
    )
    private var currentUrl: String = ""

    private var tunnelThread: Thread? = null
    private var fd: FileDescriptor? = null
    private var binder: ServiceBinder? = null
    private var enabled: Boolean = false

    private val CTRL = newSingleThreadContext("tunnel-ctrl")
    private var threadCounter = 0
    private var usePausedConfigurator = false

    private fun createProxy() = if (blockaConfig.blockaVpn) null
            else DnsProxy(currentServers, blockade, forwarder, loopback, doCreateSocket = socketCreator)

    private fun createTunnel() = if (blockaConfig.blockaVpn) BlockaTunnel(currentServers, config,
            blockaConfig, socketCreator, blockade)
            else DnsTunnel(proxy!!, config, forwarder, loopback)

    private fun createConfigurator(ktx: AndroidKontext) = when {
        usePausedConfigurator -> PausedVpnConfigurator(currentServers, filters)
        blockaConfig.blockaVpn -> BlockaVpnConfigurator(currentServers, filters, blockaConfig, ktx.ctx.packageName)
        else -> DnsVpnConfigurator(currentServers, filters, ktx.ctx.packageName)
    }

    fun setup(ktx: AndroidKontext, servers: List<InetSocketAddress>, config: BlockaConfig? = null, start: Boolean = false) = async(CTRL) {
        val cfg = config ?: blockaConfig
        val processedServers = processServers(ktx, servers, cfg, ktx.di().instance() )
        v("setup tunnel, start = $start, enabled = $enabled", processedServers, config ?: "no blocka config")
        enabled = start or enabled
        when {
            processedServers.isEmpty() -> {
                v("empty dns servers, will disable tunnel")
                currentServers = emptyList()
                maybeStopVpn(ktx)
                maybeStopTunnelThread(ktx)
                if (start) enabled = true
            }
            isVpnOn() && currentServers == processedServers && (config == null ||
                    blockaConfig.blockaVpn == config.blockaVpn
                    && blockaConfig.gatewayId == config.gatewayId
                    && blockaConfig.adblocking == config.adblocking) -> {
                v("no changes in configuration, ignoring")
            }
            else -> {
                currentServers = processedServers
                config?.run { blockaConfig = this }
                socketCreator = {
                    val socket = DatagramSocket()
                    val protected = binder?.service?.protect(socket) ?: false
                    if (!protected) e("could not protect")
                    socket
                }
                val configurator = createConfigurator(ktx)

                connector = ServiceConnector(onVpnClose, onConfigure = { ktx, vpn ->
                    configurator.configure(ktx, vpn)
                    onVpnConfigure(ktx, vpn)
                    5000L
                })

                v("will sync filters")
                if (filters.sync(ktx)) {
                    filters.save(ktx)

                    v("will restart vpn and tunnel")
                    maybeStopTunnelThread(ktx)
                    maybeStopVpn(ktx)
                    v("done stopping vpn and tunnel")

                    if (enabled) {
                        v("will start vpn")
                        startVpn(ktx)
                        startTunnelThread(ktx)
                    }
                }
            }
        }
        Unit
    }

    private fun processServers(ktx: Kontext, servers: List<InetSocketAddress>, config: BlockaConfig?, dns: Dns) = when {
        // Dont do anything other than it used to be, in non-vpn mode
        config?.blockaVpn != true -> servers
        servers.isEmpty() || !dns.hasCustomDnsSelected() -> {
            w("no dns set, using fallback")
            FALLBACK_DNS
        }
        else -> servers
    }

    fun reloadConfig(ktx: AndroidKontext, onWifi: Boolean) = async(CTRL) {
        v("reloading config")
        createComponents(ktx, onWifi)
        filters.setUrl(ktx, currentUrl)
        if (filters.sync(ktx)) {
            filters.save(ktx)
            restartTunnelThread(ktx)
        }
    }

    fun setUrl(ktx: AndroidKontext, url: String, onWifi: Boolean) = async(CTRL) {
        if (url != currentUrl) {
            currentUrl = url

            val cfg = Persistence.config.load(ktx)
            v("setting url, firstLoad: ${cfg.firstLoad}", url)
            createComponents(ktx, onWifi)
            filters.setUrl(ktx, url)
            if (filters.sync(ktx)) {
                v("first fetch successful, unsetting firstLoad flag")
                Persistence.config.save(cfg.copy(firstLoad = false))
            }
            filters.save(ktx)
            restartTunnelThread(ktx)
        } else w("ignoring setUrl, same url already set")
    }


    fun stop(ktx: AndroidKontext) = async(CTRL) {
        v("stopping tunnel")
        maybeStopTunnelThread(ktx)
        maybeStopVpn(ktx)
        currentServers = emptyList()
        enabled = false
    }

    fun load(ktx: AndroidKontext) = async(CTRL) {
        filters.load(ktx)
        restartTunnelThread(ktx)
    }

    fun sync(ktx: AndroidKontext, restartVpn: Boolean = false) = async(CTRL) {
        v("syncing on request")
        if (filters.sync(ktx)) {
            filters.save(ktx)
            if (restartVpn) restartVpn(ktx)
            restartTunnelThread(ktx)
        }
    }

    fun findFilterBySource(source: String) = async(CTRL) {
        filters.findBySource(source)
    }

    fun putFilter(ktx: AndroidKontext, filter: Filter, sync: Boolean = true) = async(CTRL) {
        v("putting filter", filter.id)
        filters.put(ktx, filter)
        if (sync) sync(ktx, restartVpn = filter.source.id == "app")
    }

    fun putFilters(ktx: AndroidKontext, newFilters: Collection<Filter>) = async(CTRL) {
        v("batch putting filters", newFilters.size)
        newFilters.forEach { filters.put(ktx, it) }
        if (filters.sync(ktx)) {
            filters.save(ktx)
            if (newFilters.any { it.source.id == "app" }) restartVpn(ktx)
            restartTunnelThread(ktx)
        }
    }

    fun removeFilter(ktx: AndroidKontext, filter: Filter) = async(CTRL) {
        filters.remove(ktx, filter)
        if (filters.sync(ktx)) {
            filters.save(ktx)
            restartTunnelThread(ktx)
        }
    }

    fun invalidateFilters(ktx: AndroidKontext) = async(CTRL) {
        v("invalidating filters")
        filters.invalidateCache(ktx)
        if(filters.sync(ktx)) {
            filters.save(ktx)
            restartTunnelThread(ktx)
        }
    }

    fun deleteAllFilters(ktx: AndroidKontext) = async(CTRL) {
        filters.removeAll(ktx)
        if (filters.sync(ktx)) {
            restartTunnelThread(ktx)
        }
    }

    fun protect(socket: Socket) {
        val protected = binder?.service?.protect(socket) ?: false
        if (!protected && isVpnOn()) e("could not protect", socket)
    }

    private fun createComponents(ktx: AndroidKontext, onWifi: Boolean) {
        config = Persistence.config.load(ktx)
        blockaConfig = Persistence.blocka.load(ktx)
        v("create components, onWifi: $onWifi, firstLoad: ${config.firstLoad}", config)
        filters = FilterManager(
                blockade = blockade,
                doResolveFilterSource = doResolveFilterSource,
                doProcessFetchedFilters = doProcessFetchedFilters,
                doValidateRulesetCache = { it ->
                    it.source.id in listOf("app")
                            || it.lastFetch + config.cacheTTL * 1000 > System.currentTimeMillis()
                            || config.wifiOnly && !onWifi && !config.firstLoad && it.source.id == "link"
                },
                doValidateFilterStoreCache = { it ->
                    it.cache.isNotEmpty()
                            && (it.lastFetch + config.cacheTTL * 1000 > System.currentTimeMillis()
                            || config.wifiOnly && !onWifi)
                }
        )
        filters.load(ktx)
    }

    private suspend fun startVpn(ktx: AndroidKontext) {
        Result.of {
            val binding = connector.bind(ktx)
            runBlocking { binding.join() }
            binder = binding.getCompleted()
            fd = binder!!.service.turnOn(ktx)
            ktx.on(Events.REQUEST, onRequest)
            v("vpn started")
        }.onFailure { ex ->
            e("failed starting vpn", ex)
            onVpnClose(ktx)
        }
    }

    private fun startTunnelThread(ktx: AndroidKontext) {
        proxy = createProxy()
        tunnel = createTunnel()
        val f = fd
        if (f != null) {
            tunnelThread = Thread({ tunnel.runWithRetry(ktx, f) }, "tunnel-${threadCounter++}")
            tunnelThread?.start()
            v("tunnel thread started", tunnelThread!!)
        } else w("attempting to start tunnel thread with no fd")
    }

    private fun stopTunnelThread(ktx: Kontext) {
        v("stopping tunnel thread", tunnelThread!!)
        tunnel.stop(ktx)
        Result.of { tunnelThread?.interrupt() }.onFailure { ex ->
            w("could not interrupt tunnel thread", ex)
        }
        Result.of { tunnelThread?.join(5000); true }.onFailure { ex ->
            w("could not join tunnel thread", ex)
        }
        tunnelThread = null
        v("tunnel thread stopped")
    }

    private fun stopVpn(ktx: AndroidKontext) {
        ktx.cancel(Events.REQUEST, onRequest)
        binder?.service?.turnOff(ktx)
        connector.unbind(ktx)//.mapError { ex -> w("failed unbinding connector", ex) }
        binder = null
        fd = null
        v("vpn stopped")
    }

    private fun restartTunnelThread(ktx: AndroidKontext) {
        if (tunnelThread != null) {
            stopTunnelThread(ktx)
            startTunnelThread(ktx)
        }
    }

    private suspend fun restartVpn(ktx: AndroidKontext) {
        if (isVpnOn()) {
            stopVpn(ktx)
            startVpn(ktx)
        }
    }

    private fun maybeStartTunnelThread(ktx: AndroidKontext) = if (tunnelThread == null) {
        startTunnelThread(ktx)
    } else Unit

    private fun maybeStopTunnelThread(ktx: Kontext) = if (tunnelThread != null) {
        stopTunnelThread(ktx); true
    } else false

    private suspend fun maybeStartVpn(ktx: AndroidKontext) = if (!isVpnOn()) startVpn(ktx) else Unit

    private fun maybeStopVpn(ktx: AndroidKontext) = if (isVpnOn()) {
        stopVpn(ktx); true
    } else false

    private fun isVpnOn() = binder != null
}
