package core

import android.app.Activity
import com.github.salomonbrys.kodein.instance
import org.blokada.R
import tunnel.Filter
import tunnel.FilterSourceDescriptor


class StepActivity : Activity() {

    private val stepView by lazy { findViewById<VBStepView>(R.id.view) }
    private val ktx = ktx("StepActivity")

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.vbstepview)


        val nameVB = EnterNameVB(ktx, accepted = {
            name = it
            saveNewFilter()
        })

        stepView.pages = listOf(
                EnterDomainVB(ktx, accepted = { it ->
                    nameVB.inputForGeneratingName = if (it.size == 1) it.first().source else ""
                    sources = it
                    stepView.next()
                }),
                nameVB
        )
    }

    override fun onBackPressed() {
//        if (!dashboardView.handleBackPressed()) super.onBackPressed()
        super.onBackPressed()
    }


    private var sources: List<FilterSourceDescriptor> = emptyList()
    private var name = ""

    private val tunnelManager by lazy { ktx.di().instance<tunnel.Main>() }

    private fun saveNewFilter() = when {
        sources.isEmpty() || name.isBlank() -> Unit
        else -> {
            sources.map {
                val name = if (sources.size == 1) this.name else this.name + " (${it.source})"
                Filter(
                        id = sourceToId(it),
                        source = it,
                        active = true,
                        customName = name
                )
            }.apply {
                tunnelManager.putFilters(ktx, this)
            }
            finish()
        }
    }

    private fun sourceToId(source: FilterSourceDescriptor): String {
        return "lol id " + source.source
    }

}
