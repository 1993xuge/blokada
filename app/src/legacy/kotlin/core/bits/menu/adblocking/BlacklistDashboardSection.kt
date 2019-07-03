package core.bits.menu.adblocking

import core.*
import core.bits.FilterVB
import core.bits.NewFilterVB
import core.bits.menu.MenuItemVB
import gs.presentation.ListViewBinder
import gs.presentation.NamedViewBinder
import org.blokada.R
import tunnel.Events
import tunnel.Filter

class BlacklistDashboardSection(
        val ktx: AndroidKontext,
        override val name: Resource = R.string.panel_section_ads_blacklist.res()
) : ListViewBinder(), NamedViewBinder {

    private val slotMutex = SlotMutex()

    private var updateApps = { filters: Collection<Filter> ->
        filters.filter { !it.whitelist && !it.hidden && it.source.id == "single" }.map {
            FilterVB(it, ktx, onTap = slotMutex.openOneAtATime)
        }.apply { view?.set(listOf(
                NewFilterVB(ktx),
                LabelVB(ktx, label = "Blacklisted hosts".res())
        ) + this) }
        Unit
    }

    override fun attach(view: VBListView) {
        view.enableAlternativeMode()
        ktx.on(Events.FILTERS_CHANGED, updateApps)
    }

    override fun detach(view: VBListView) {
        slotMutex.detach()
        ktx.cancel(Events.FILTERS_CHANGED, updateApps)
    }

}

fun createBlacklistMenuItem(ktx: AndroidKontext): NamedViewBinder {
    return MenuItemVB(ktx,
            label = R.string.panel_section_ads_blacklist.res(),
            icon = R.drawable.ic_shield_outline.res(),
            opens = BlacklistDashboardSection(ktx)
    )
}
