package ui.bits

import android.graphics.Point
import com.github.michaelbull.result.onSuccess
import core.*
import kotlinx.coroutines.runBlocking
import org.blokada.R
import tunnel.Events
import tunnel.Request
import ui.bits.menu.adblocking.SlotMutex
import ui.bits.menu.isLandscape
import kotlin.math.max

class AdsDashboardSectionVB(
        override val name: Resource = R.string.menu_ads.res()
) : ListViewBinder(), NamedViewBinder {

    private val screenHeight: Int by lazy {
        val point = Point()
        runBlocking {
            getActivity()?.windowManager?.defaultDisplay?.getSize(point)
        }
        if (point.y > 0) point.y else 2000
    }

    private val countLimit: Int by lazy {
        val ctx = runBlocking { getApplicationContext()!! }
        val limit = screenHeight / ctx.dpToPx(80)
        max(5, limit)
    }

    private val displayingEntries = mutableListOf<String>()
    private val slotMutex = SlotMutex()

    private val items = mutableListOf<SlotVB>()

    private val request = { it: Request ->
        if (!displayingEntries.contains(it.domain)) {
            displayingEntries.add(it.domain)
            val dash = if (it.blocked)
                DomainBlockedVB(it.domain, it.time, onTap = slotMutex.openOneAtATime) else
                DomainForwarderVB(it.domain, it.time, onTap = slotMutex.openOneAtATime)
            items.add(dash)
            view?.add(dash)
            trimListIfNecessary()
            onSelectedListener(null)
        }
        Unit
    }

    private fun trimListIfNecessary() {
        if (items.size > countLimit) {
            items.firstOrNull()?.apply {
                items.remove(this)
                view?.remove(this)
            }
        }
    }

    override fun attach(view: VBListView) {
        if (isLandscape(view.context)) {
            view.enableLandscapeMode(reversed = false)
        }

        tunnel.Persistence.request.load(0).onSuccess {
            it.forEach(request)
        }
        core.on(Events.REQUEST, request)
    }

    override fun detach(view: VBListView) {
        slotMutex.detach()
        displayingEntries.clear()
        core.cancel(Events.REQUEST, request)
    }
}
