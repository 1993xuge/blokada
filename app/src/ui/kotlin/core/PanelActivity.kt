package core

import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.github.salomonbrys.kodein.instance
import gs.presentation.ViewBinderHolder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.blokada.R
import tunnel.tunnelPermissionResult




class PanelActivity : Activity() {

    private val ktx = ktx("PanelActivity")
    private val dashboardView by lazy { findViewById<DashboardView>(R.id.DashboardView) }
    private val viewBinderHolder by lazy { ktx.di().instance<ViewBinderHolder>() }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dashboard)
        runBlocking { setActivityContext() }
//        setFullScreenWindowLayoutInDisplayCutout(window)
        dashboardView.onSectionClosed = {
            filtersManager.changed %= true
        }
//        getNotch()
//        if (hasSoftKeys(getSystemService(Context.WINDOW_SERVICE) as WindowManager))
//            dashboardView.navigationBarPx = resources.getDimensionPixelSize(R.dimen.dashboard_navigation_inset)
    }

    override fun onResume() {
        super.onResume()
        GlobalScope.launch { modalManager.closeModal() }
    }

    override fun onBackPressed() {
        if (!dashboardView.handleBackPressed()) super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        viewBinderHolder.attach()
    }

    override fun onStop() {
        super.onStop()
        viewBinderHolder.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking { unsetActivity() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        tunnelPermissionResult(Kontext.new("permission:vpn:result"), resultCode)
    }

    fun trai() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN // Set layout full screen
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    @RequiresApi(28)
    private fun getNotch() {
        try {
            val displayCutout = window.decorView.rootWindowInsets.displayCutout
            dashboardView.notchPx = displayCutout.safeInsetTop
        } catch (e: Throwable) {
            if (!isAndroidTV())
                dashboardView.notchPx = resources.getDimensionPixelSize(R.dimen.dashboard_notch_inset)
        }
    }

    private fun isAndroidTV(): Boolean {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun hasNavBar(resources: Resources): Boolean {
        val id = resources.getIdentifier("config_showNavigationBar", "bool", "android")
        return id > 0 && resources.getBoolean(id)
    }

    fun hasSoftKeys(windowManager: WindowManager): Boolean {
        val d = windowManager.defaultDisplay

        val realDisplayMetrics = DisplayMetrics()
        d.getRealMetrics(realDisplayMetrics)

        val realHeight = realDisplayMetrics.heightPixels
        val realWidth = realDisplayMetrics.widthPixels

        val displayMetrics = DisplayMetrics()
        d.getMetrics(displayMetrics)

        val displayHeight = displayMetrics.heightPixels
        val displayWidth = displayMetrics.widthPixels

        return realWidth - displayWidth > 0 || realHeight - displayHeight > 0
    }
}

val modalManager = ModalManager()

