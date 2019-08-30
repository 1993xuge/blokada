package tunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import core.Time
import core.e
import core.getApplicationContext
import core.v
import kotlinx.coroutines.*
import java.io.FileDescriptor

class ServiceBinder(
        val service: Service,
        var onClose: () -> Unit = {},
        var onConfigure: (vpn: VpnService.Builder) -> Time = { _ -> 0L }
) : Binder()

internal class ServiceConnector(
        var onClose: () -> Unit = {},
        var onConfigure: (vpn: VpnService.Builder) -> Time = { _ -> 0L }
) {

    private var deferred = CompletableDeferred<ServiceBinder>()

    private val serviceConnection = object: ServiceConnection {
        @Synchronized override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            v("onServiceConnected called")
            if (binder !is ServiceBinder) {
                e("service binder of wrong type", binder::class.java)
                return
            }
            val b = binder
            b.onClose = onClose
            b.onConfigure = onConfigure
            deferred.complete(b)
        }

        @Synchronized override fun onServiceDisconnected(name: ComponentName?) {
            deferred.completeExceptionally(Exception("service bind disconnected"))
        }
    }

    suspend fun bind(): CompletableDeferred<ServiceBinder>  {
        return withContext(Dispatchers.Main.immediate) {
            v("binding vpn service")
            val ctx = getApplicationContext()!!
            this@ServiceConnector.deferred = CompletableDeferred()
            val intent = Intent(ctx, Service::class.java)
            intent.action = Service.BINDER_ACTION
            if (!ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE
                            or Context.BIND_ABOVE_CLIENT or Context.BIND_IMPORTANT)) {
                deferred.completeExceptionally(Exception("could not bind to service"))
            } else GlobalScope.launch {
                delay(3000)
                if (!deferred.isCompleted) deferred.completeExceptionally(
                        Exception("timeout waiting for binding to service"))
            }
            deferred
        }
    }

    fun unbind() = runCatching {
        val ctx = runBlocking { getApplicationContext()!! }
        ctx.unbindService(serviceConnection)
    }
}

class Service : VpnService() {

    companion object Statics {
        val BINDER_ACTION = "Service"

        private var tunDescriptor: ParcelFileDescriptor? = null
        private var tunFd = -1
        private var lastReleasedMillis = 0L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = {
        v("VPN service onStartCommand")
        android.app.Service.START_STICKY
    }()

    private var binder: ServiceBinder? = null
    override fun onBind(intent: Intent?): IBinder? {
        v("VPN service onBind called")
        if (BINDER_ACTION == intent?.action) {
            v("onBind returning binder")
            binder = ServiceBinder(this)
            return binder
        }
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (BINDER_ACTION == intent?.action) {
//            binder = null
            return true
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        turnOff()
        binder?.onClose?.invoke()
        super.onDestroy()
    }

    override fun onRevoke() {
        turnOff()
        binder?.onClose?.invoke()
        super.onRevoke()
    }

    fun turnOn(): FileDescriptor {
        tunDescriptor = establishTunnel()
        if (tunDescriptor != null) {
            tunFd = tunDescriptor?.fd ?: -1
            v("vpn established")
        } else {
            e("failed establishing vpn, no permissions?")
            tunFd = -1
        }
        return tunDescriptor?.fileDescriptor!!
    }

    private fun establishTunnel(): ParcelFileDescriptor? {
        val tunnel = super.Builder()
        val cooldownMillis = binder?.onConfigure?.invoke(tunnel) ?: 0L

        val timeSinceLastReleased = SystemClock.uptimeMillis() - lastReleasedMillis - cooldownMillis
        if (timeSinceLastReleased < 0) {
            // To not trigger strange VPN bugs (establish not earlier than X sec after last release)
            lastReleasedMillis = 0
            Thread.sleep(0 - timeSinceLastReleased)
        }
        v("asking system for vpn")
        return tunnel.establish()
    }

    fun turnOff() {
        if (tunDescriptor != null) {
            runCatching { tunDescriptor?.close() ?: Unit }
                    .onFailure {
                        // I can't remember why we try it twice, but it probably matters
                        runCatching { tunDescriptor?.close() ?: Unit }
                    }
            lastReleasedMillis = SystemClock.uptimeMillis()
            tunDescriptor = null
            tunFd = -1
            v("closed vpn")
        }
    }

}
