package za.co.madbot.stickermaker

import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * One banner at the bottom of the pack list and the pack screen in the free version (decision Q46). Never in the editor and never
 * once the app is unlocked. Consent (EEA/UK/US-state rules) is asked through Google's UMP before the first ad request.
 * BuildConfig.BANNER_AD_UNIT is Google's test unit until the real AdMob ids are in local.properties (see app/build.gradle.kts).
 */
object Ads {
    private var initialised = false

    /**
     * Shows a banner in [box] for the free version, or clears it when unlocked. Safe to call on every resume: a banner that is
     * already showing is left alone. [onHeight] gets the pixel height the banner takes (0 when none) so other views can make room.
     */
    fun attach(activity: AppCompatActivity, box: FrameLayout, unlocked: Boolean, onHeight: (Int) -> Unit = {}) {
        if (!Gate.showAds(unlocked)) {
            detach(box)
            box.visibility = View.GONE
            onHeight(0)
            return
        }
        if (box.childCount > 0) return
        whenAdsAllowed(activity) {
            if (activity.isDestroyed || activity.isFinishing || box.childCount > 0) return@whenAdsAllowed
            val widthDp = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
            val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
            val view = AdView(activity).apply {
                setAdSize(size)
                adUnitId = BuildConfig.BANNER_AD_UNIT
                adListener = object : AdListener() {
                    override fun onAdLoaded() { box.visibility = View.VISIBLE; onHeight(size.getHeightInPixels(activity)) }
                    override fun onAdFailedToLoad(e: LoadAdError) { box.visibility = View.GONE; onHeight(0) }
                }
            }
            box.addView(view, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_HORIZONTAL))
            box.visibility = View.GONE // shown when an ad has actually loaded, so there is no empty strip
            view.loadAd(AdRequest.Builder().build())
        }
    }

    /** Releases the banner (call from onDestroy, or when the app unlocks). */
    fun detach(box: FrameLayout) {
        for (i in 0 until box.childCount) (box.getChildAt(i) as? AdView)?.destroy()
        box.removeAllViews()
    }

    private fun whenAdsAllowed(activity: AppCompatActivity, go: () -> Unit) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val proceed = {
            if (consent.canRequestAds()) {
                if (!initialised) { initialised = true; MobileAds.initialize(activity.applicationContext) }
                go()
            }
        }
        consent.requestConsentInfoUpdate(
            activity, ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { proceed() } },
            { proceed() }, // could not reach the consent service: ask the SDK whether ads are allowed from what it already knows
        )
    }
}
