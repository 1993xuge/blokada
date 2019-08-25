package notification

import android.app.IntentService
import android.content.Intent
import android.os.Handler
import core.id
import core.ktx
import org.blokada.R
import tunnel.Filter
import tunnel.FilterSourceDescriptor
import tunnel.tunnelManager

class ANotificationsWhitelistService : IntentService("notificationsWhitelist") {

    private var mHandler: Handler = Handler()

    override fun onHandleIntent(intent: Intent) {
        val host = intent.getStringExtra("host") ?: return

        val f = Filter(
                id(host, whitelist = true),
                source = FilterSourceDescriptor("single", host),
                active = true,
                whitelist = true
        )

        tunnelManager.putFilter(ktx("whitelistFromNotification"), f)

        mHandler.post(DisplayToastRunnable(this, getString(R.string.notification_blocked_whitelist_applied)))
        hideNotification(this)
    }

}
