package za.co.madbot.stickermaker

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** Everything the screens need from the licence layer: build it once per activity. */
class Licensing(private val activity: AppCompatActivity) {
    private val store = PrefsLicenceStore(activity)
    private val fakeBilling = if (BuildConfig.FAKE_LICENCE) FakeBilling(activity) else null
    private val billing: Billing = fakeBilling ?: PlayBilling(activity)
    val manager = UnlockManager(
        store,
        if (BuildConfig.FAKE_LICENCE) FakeSignIn() else GoogleSignIn(activity, BuildConfig.GOOGLE_WEB_CLIENT_ID),
        billing,
        if (BuildConfig.FAKE_LICENCE) FakeBackend() else HttpBackend(BuildConfig.LICENCE_BASE_URL),
    )

    val unlocked get() = manager.unlocked

    /** Locks again after a refund. Quiet when Google Play cannot be reached. */
    fun recheck(then: () -> Unit = {}) {
        activity.lifecycleScope.launch { manager.recheck(); then() }
    }

    /** Test builds only: back to a fresh phone that never bought the unlock. */
    fun resetFake() {
        fakeBilling?.forget()
        store.clear()
    }

    fun close() { (billing as? PlayBilling)?.close() }

    fun showUnlock(reason: String, onChanged: () -> Unit = {}) {
        val price = TextView(activity).apply { text = "One-time purchase, no subscription"; textSize = 13f; alpha = 0.7f }
        val body = TextView(activity).apply {
            text = reason + "\n\nThe unlock gives you:\n" +
                "- Unlimited sticker packs\n- Background removal\n- The Windows desktop app\n- Google Drive sync of your packs\n- No ads\n\nBuy it once, use it on every phone with the same Google account."
            textSize = 15f
        }
        lateinit var dlg: AlertDialog
        val unlock = MaterialButton(activity).apply {
            text = "Unlock"
            setOnClickListener { dlg.dismiss(); run("Unlocking...", onChanged) { manager.unlock() } }
        }
        val restore = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Restore purchase"
            setOnClickListener { dlg.dismiss(); run("Looking for your purchase...", onChanged) { manager.restore() } }
        }
        val later = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Not now"
            setOnClickListener { dlg.dismiss() }
        }
        // Unlock is the big button on its own row; the two quiet ones sit under it so nothing gets squeezed.
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(16), 0, 0)
            addView(unlock, LinearLayout.LayoutParams(-1, -2))
            addView(
                LinearLayout(activity).apply { gravity = Gravity.END; addView(later); addView(restore) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(4) },
            )
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(16))
            addView(TextView(activity).apply { text = "Unlock StikBot"; textSize = 20f; setPadding(0, 0, 0, activity.dp(8)) })
            addView(body); addView(price); addView(buttons)
        }
        dlg = AlertDialog.Builder(activity).setView(box).create()
        dlg.show()
        activity.lifecycleScope.launch {
            val p = try { billing.price() } catch (e: Exception) { null }
            if (p != null && dlg.isShowing) { unlock.text = "Unlock $p"; price.text = "$p, one time. No subscription." }
        }
    }

    /** Who is unlocked, and how to use the same account on the desktop. */
    fun showStatus(onChanged: () -> Unit = {}) {
        val b = AlertDialog.Builder(activity).setTitle("StikBot unlocked")
            .setMessage(
                "Signed in as ${manager.email ?: "your Google account"}.\n\nTo use the Windows desktop app, sign in there with the same Google account. " +
                    "Only one computer can be signed in at a time: signing in on another one signs this one out.",
            )
            .setPositiveButton("OK", null)
        if (BuildConfig.FAKE_LICENCE) b.setNeutralButton("Reset (test build)") { _, _ -> resetFake(); onChanged() }
        b.show()
    }

    private fun run(label: String, onChanged: () -> Unit, block: suspend () -> UnlockResult) {
        var result: UnlockResult = UnlockResult.Cancelled
        activity.busy(label, { _ -> result = block() }, { ok ->
            if (ok) report(result)
            onChanged()
        })
    }

    private fun report(r: UnlockResult) {
        when (r) {
            UnlockResult.Unlocked -> Toast.makeText(activity, "Unlocked. Thank you!", Toast.LENGTH_LONG).show()
            UnlockResult.Cancelled -> {}
            UnlockResult.Pending -> activity.showError("Payment pending", "Google Play has not finished the payment yet. Once it has, tap Restore purchase to unlock.")
            UnlockResult.NothingToRestore -> activity.showError("No purchase found", "This Google Play account has not bought the unlock. If you bought it with another account, sign in with that one.")
            is UnlockResult.Failed -> activity.showError("Could not unlock", r.message)
        }
    }
}
