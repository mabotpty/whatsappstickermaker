package za.co.madbot.stickermaker

import android.content.Context

/**
 * The one-time unlock (decisions Q44, Q46, Q47). The free app allows one pack and has no Remove BG; the unlock removes every limit.
 * Sign-in, billing and the licence server sit behind small interfaces so the whole flow can run with fakes until the real
 * Google and Play keys exist (BuildConfig.FAKE_LICENCE), and so it can be unit-tested without Android.
 */

/** What the free version may do. Pure, so the rules are tested in one place. */
object Gate {
    const val FREE_PACKS = 1

    fun canAddPack(unlocked: Boolean, packCount: Int) = unlocked || packCount < FREE_PACKS
    fun canRemoveBackground(unlocked: Boolean) = unlocked
    fun showAds(unlocked: Boolean) = !unlocked
}

/** Why something failed, in words that can be shown to the person. */
class LicenceException(val code: String, message: String) : Exception(message)

class GoogleAccount(val idToken: String, val email: String)

interface SignIn {
    /** Shows Google's account picker. Returns null if the person backs out. */
    suspend fun signIn(): GoogleAccount?
}

sealed class Purchase {
    class Owned(val token: String) : Purchase()
    object Cancelled : Purchase()
    object Pending : Purchase()
}

interface Billing {
    /** Formatted price from Google Play (for example "$6.99"), or null if it cannot be read. */
    suspend fun price(): String?

    /** The unlock purchase this Google Play account already owns, if any (also how a reinstall is restored). Throws if Play cannot be reached, so null always means "not owned". */
    suspend fun owned(): String?

    suspend fun buy(): Purchase
}

interface LicenceBackend {
    /** Turns a Google Play purchase into the account's licence key. Throws [LicenceException] with the server's reason. */
    suspend fun claim(idToken: String, purchaseToken: String): String
}

interface LicenceStore {
    var key: String?
    var email: String?
    val unlocked: Boolean get() = key != null
    fun clear() { key = null; email = null }
}

class PrefsLicenceStore(ctx: Context) : LicenceStore {
    private val p = ctx.applicationContext.getSharedPreferences("licence", Context.MODE_PRIVATE)
    override var key: String?
        get() = p.getString("key", null)
        set(v) { p.edit().putString("key", v).apply() }
    override var email: String?
        get() = p.getString("email", null)
        set(v) { p.edit().putString("email", v).apply() }
}

sealed class UnlockResult {
    object Unlocked : UnlockResult()
    object Cancelled : UnlockResult()
    /** The bank or Google has not finished the payment yet; it unlocks when the person taps Restore later. */
    object Pending : UnlockResult()
    object NothingToRestore : UnlockResult()
    class Failed(val message: String) : UnlockResult()
}

class UnlockManager(
    private val store: LicenceStore,
    private val signIn: SignIn,
    private val billing: Billing,
    private val backend: LicenceBackend,
) {
    val unlocked get() = store.unlocked
    val email get() = store.email

    /** Sign in with Google first (so a payment is never taken without somewhere to attach it), then buy, then claim. */
    suspend fun unlock(): UnlockResult {
        val who = try { signIn.signIn() } catch (e: Exception) { return fail(e) } ?: return UnlockResult.Cancelled
        val token = try {
            billing.owned() ?: when (val r = billing.buy()) {
                is Purchase.Owned -> r.token
                Purchase.Cancelled -> return UnlockResult.Cancelled
                Purchase.Pending -> return UnlockResult.Pending
            }
        } catch (e: Exception) { return fail(e) }
        return claim(who, token)
    }

    /** After a reinstall or a new phone: the purchase is already on the Google Play account, so only the claim is needed. */
    suspend fun restore(): UnlockResult {
        val who = try { signIn.signIn() } catch (e: Exception) { return fail(e) } ?: return UnlockResult.Cancelled
        val token = try { billing.owned() } catch (e: Exception) { return fail(e) } ?: return UnlockResult.NothingToRestore
        return claim(who, token)
    }

    /** Locks again if Google Play no longer shows the purchase (a refund). Does nothing when Play cannot be reached. */
    suspend fun recheck() {
        if (!store.unlocked) return
        val owned = try { billing.owned() } catch (e: Exception) { return }
        if (owned == null) store.clear()
    }

    private suspend fun claim(who: GoogleAccount, purchaseToken: String): UnlockResult = try {
        store.key = backend.claim(who.idToken, purchaseToken)
        store.email = who.email
        UnlockResult.Unlocked
    } catch (e: Exception) {
        fail(e)
    }

    private fun fail(e: Exception) = UnlockResult.Failed(
        when {
            e is LicenceException -> e.message ?: "The licence could not be checked."
            e is java.io.IOException -> "Could not reach the licence server. Check your connection and try again."
            else -> e.message ?: e.javaClass.simpleName
        },
    )
}
