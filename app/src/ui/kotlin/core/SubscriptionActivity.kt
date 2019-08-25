package core

import android.app.Activity
import android.widget.FrameLayout
import android.widget.ImageView
import gs.presentation.WebDash
import gs.property.IWhen
import gs.property.kctx
import gs.property.newProperty
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.blokada.R
import tunnel.BLOCKA_CONFIG
import tunnel.BlockaConfig
import tunnel.showSnack
import java.net.URL


class SubscriptionActivity : Activity() {

    private val container by lazy { findViewById<FrameLayout>(R.id.view) }
    private val close by lazy { findViewById<ImageView>(R.id.close) }

    private val subscriptionUrl by lazy { newProperty(kctx, { URL("https://localhost") }) }
    private val updateUrl = { cfg: BlockaConfig ->
        subscriptionUrl %= URL("https://app.blokada.org/#/activate/${cfg.accountId}")
    }

    private val dash by lazy {
        WebDash(subscriptionUrl, reloadOnError = true,
                javascript = true, forceEmbedded = true, big = true,
                onLoadSpecificUrl = "app.blokada.org/#/success" to {
                    this@SubscriptionActivity.finish()
                    GlobalScope.launch { showSnack(R.string.subscription_success) }
                    Unit
                })
    }

    private var view: android.view.View? = null
    private var listener: IWhen? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.subscription_container)

        view = dash.createView(this, container)
        listener = subscriptionUrl.doOnUiWhenChanged().then {
            view?.run { dash.attach(this) }
        }
        container.addView(view)
        close.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        view?.run { dash.detach(this) }
        container.removeAllViews()
        subscriptionUrl.cancel(listener)
        GlobalScope.launch { modalManager.closeModal() }
    }

    override fun onStart() {
        super.onStart()
        core.on(BLOCKA_CONFIG, updateUrl)
    }

    override fun onStop() {
        super.onStop()
        core.cancel(BLOCKA_CONFIG, updateUrl)

        GlobalScope.async {
            core.getMostRecent(BLOCKA_CONFIG)?.run {
                delay(3000)
                tunnel.checkAccountInfo(this)
            }
        }
    }

    override fun onBackPressed() {
//        if (!dashboardView.handleBackPressed()) super.onBackPressed()
        super.onBackPressed()
    }

}
