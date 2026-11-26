package za.co.madbot.stickermaker

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Stand-ins for Google sign-in, Google Play and the licence server, used while FAKE_LICENCE is on (every build until the real keys exist).
 * "Owning" the unlock is remembered in preferences so Restore behaves like a reinstalled app on the same Google account.
 * The release bundle refuses to build with these on (see app/build.gradle.kts), because they unlock for free.
 */
class FakeSignIn : SignIn {
    override suspend fun signIn(): GoogleAccount? {
        delay(400)
        return GoogleAccount("fake-id-token", "tester@example.com")
    }
}

class FakeBilling(ctx: Context, private val outcome: () -> Purchase = { Purchase.Owned("fake-purchase-token") }) : Billing {
    private val p = ctx.applicationContext.getSharedPreferences("licence_fake", Context.MODE_PRIVATE)

    override suspend fun price(): String = "$6.99"

    override suspend fun owned(): String? = if (p.getBoolean("owned", false)) "fake-purchase-token" else null

    override suspend fun buy(): Purchase {
        delay(600)
        val r = outcome()
        if (r is Purchase.Owned) p.edit().putBoolean("owned", true).apply()
        return r
    }

    /** Test builds only: act as a fresh phone that never bought it. */
    fun forget() { p.edit().clear().apply() }
}

class FakeBackend : LicenceBackend {
    override suspend fun claim(idToken: String, purchaseToken: String): String {
        delay(400)
        if (idToken.isEmpty() || purchaseToken.isEmpty()) throw LicenceException("not_purchased", "Google Play does not show a completed purchase.")
        return "SM-FAKE2-FAKE3-FAKE4-FAKE5"
    }
}
