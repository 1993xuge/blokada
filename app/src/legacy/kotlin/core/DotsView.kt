package core

import android.content.Context
import android.os.Handler
import android.support.v4.view.ViewPager
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.tbuonomo.viewpagerdotsindicator.WormDotsIndicator
import org.blokada.R

class DotsView(
        ctx: Context,
        attributeSet: AttributeSet
) : FrameLayout(ctx, attributeSet) {

    init {
        inflate(ctx, R.layout.dots, this)
    }

    private val sectionView = findViewById<TextView>(R.id.section)
    private val dotsView = findViewById<WormDotsIndicator>(R.id.worm_dots)
    private val backgroundView = findViewById<View>(R.id.background)

    var section: CharSequence = ""
        set(value) {
            field = value
            sectionView.text = value.toString().capitalize()
        }

    var viewPager: ViewPager? = null
        set(value) {
            field = value
            value?.apply { dotsView.setViewPager(this) }
        }

    var sleeping = false
        set(value) {
            field = value
            if (value) activateSleeping()
            else deactivateSleeping()
        }

    var sleepingListener = { sleeping: Boolean -> }

    var background = false
        set(value) {
            field = value
            if (value) backgroundView.visibility = View.VISIBLE
            else backgroundView.visibility = View.INVISIBLE
        }

    private fun activateSleeping() = viewPager?.apply {
        pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrollStateChanged(state: Int) = when (state) {
                ViewPager.SCROLL_STATE_DRAGGING -> {
                    sleepingAnimationHandler.removeMessages(WAKE)
                    sleepingAnimationHandler.sendEmptyMessage(WAKE)
                    Unit
                }
                else -> Unit
            }
            override fun onPageSelected(position: Int) {
                sleepingAnimationHandler.removeMessages(SLEEP)
                sleepingAnimationHandler.sendEmptyMessageDelayed(SLEEP, FALL_MS)
                Unit
            }
        }
        pagerListener?.apply { addOnPageChangeListener(this) }
        sleepingAnimationHandler.sendEmptyMessageDelayed(SLEEP, FALL_MS)
    }

    private fun deactivateSleeping() = viewPager?.apply {
        pagerListener?.apply { removeOnPageChangeListener(this) }
        pagerListener = null
        sleepingAnimationHandler.sendEmptyMessage(WAKE)
    }

    private var pagerListener: ViewPager.OnPageChangeListener? = null

    private var sleepingAnimationHandler = Handler {
        when (it.what) {
            WAKE ->  {
                sectionView.animate().setDuration(200).alpha(1f)
                dotsView.animate().setDuration(200).alpha(1f)
                if (background) backgroundView.animate().setDuration(200).alpha(1f)
                sleepingListener(false)
            }
            SLEEP -> {
                sectionView.animate().setDuration(500).alpha(0f)
                dotsView.animate().setDuration(500).alpha(0.5f)
                if (background) backgroundView.animate().setDuration(200).alpha(0f)
                sleepingListener(true)
            }
        }
        true
    }

    private val WAKE = 0
    private val SLEEP = 1
    private val FALL_MS = 2000L
}
