package za.co.madbot.stickermaker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LicenceTest {
    private class MemStore : LicenceStore {
        override var key: String? = null
        override var email: String? = null
    }

    private class Account(var account: GoogleAccount? = GoogleAccount("id-token", "pat@example.com"), var error: Exception? = null) : SignIn {
        override suspend fun signIn(): GoogleAccount? { error?.let { throw it }; return account }
    }

    private class Play(var owned: String? = null, var buyResult: Purchase = Purchase.Owned("ptoken"), var error: Exception? = null) : Billing {
        var buys = 0
        override suspend fun price() = "$6.99"
        override suspend fun owned(): String? { error?.let { throw it }; return owned }
        override suspend fun buy(): Purchase { buys++; if (buyResult is Purchase.Owned) owned = (buyResult as Purchase.Owned).token; return buyResult }
    }

    private class Server(var key: String = "SM-AAAAA-BBBBB-CCCCC-DDDDD", var error: Exception? = null) : LicenceBackend {
        val calls = ArrayList<Pair<String, String>>()
        override suspend fun claim(idToken: String, purchaseToken: String): String {
            calls += idToken to purchaseToken
            error?.let { throw it }
            return key
        }
    }

    private fun manager(store: LicenceStore = MemStore(), s: SignIn = Account(), b: Billing = Play(), srv: LicenceBackend = Server()) = UnlockManager(store, s, b, srv)

    // --- what the free version may do -----------------------------------------------------------------------------------

    @Test fun freeAllowsOnePackAndNoBackgroundRemoval() {
        assertTrue(Gate.canAddPack(false, 0))
        assertFalse(Gate.canAddPack(false, 1))
        assertFalse(Gate.canAddPack(false, 5))
        assertFalse(Gate.canRemoveBackground(false))
    }

    @Test fun adsOnlyInTheFreeVersion() {
        assertTrue(Gate.showAds(false))
        assertFalse(Gate.showAds(true))
    }

    @Test fun unlockedHasNoLimits() {
        assertTrue(Gate.canAddPack(true, 1))
        assertTrue(Gate.canAddPack(true, 50))
        assertTrue(Gate.canRemoveBackground(true))
    }

    // --- buying ---------------------------------------------------------------------------------------------------------

    @Test fun buyingSignsInPaysClaimsAndStoresTheLicence() = runBlocking {
        val store = MemStore(); val play = Play(); val server = Server()
        val r = manager(store, b = play, srv = server).unlock()

        assertTrue(r is UnlockResult.Unlocked)
        assertEquals("SM-AAAAA-BBBBB-CCCCC-DDDDD", store.key)
        assertEquals("pat@example.com", store.email)
        assertTrue(store.unlocked)
        assertEquals(listOf("id-token" to "ptoken"), server.calls)
        assertEquals(1, play.buys)
    }

    @Test fun anAlreadyOwnedPurchaseIsNotBoughtTwice() = runBlocking {
        val play = Play(owned = "old-token")
        val server = Server()

        assertTrue(manager(b = play, srv = server).unlock() is UnlockResult.Unlocked)

        assertEquals(0, play.buys)
        assertEquals("old-token", server.calls.single().second)
    }

    @Test fun backingOutOfSignInChargesNothing() = runBlocking {
        val play = Play()
        val r = manager(s = Account(account = null), b = play).unlock()

        assertTrue(r is UnlockResult.Cancelled)
        assertEquals(0, play.buys)
    }

    @Test fun cancellingThePurchaseLeavesItLocked() = runBlocking {
        val store = MemStore()
        val r = manager(store, b = Play(buyResult = Purchase.Cancelled)).unlock()

        assertTrue(r is UnlockResult.Cancelled)
        assertFalse(store.unlocked)
    }

    @Test fun aPendingPaymentLeavesItLockedAndSaysSo() = runBlocking {
        val store = MemStore(); val server = Server()
        val r = manager(store, b = Play(buyResult = Purchase.Pending), srv = server).unlock()

        assertTrue(r is UnlockResult.Pending)
        assertFalse(store.unlocked)
        assertTrue(server.calls.isEmpty())
    }

    @Test fun aServerRefusalIsShownAndNothingIsStored() = runBlocking {
        val store = MemStore()
        val r = manager(store, srv = Server(error = LicenceException("purchase_in_use", "This purchase is already linked to another Google account."))).unlock()

        assertEquals("This purchase is already linked to another Google account.", (r as UnlockResult.Failed).message)
        assertFalse(store.unlocked)
    }

    @Test fun noConnectionGivesAFriendlyMessage() = runBlocking {
        val r = manager(srv = Server(error = IOException("timeout"))).unlock()

        assertTrue((r as UnlockResult.Failed).message.startsWith("Could not reach the licence server"))
    }

    // --- restoring and refunds ------------------------------------------------------------------------------------------

    @Test fun restoreAfterAReinstallClaimsTheOwnedPurchase() = runBlocking {
        val store = MemStore(); val play = Play(owned = "ptoken"); val server = Server()
        val r = manager(store, b = play, srv = server).restore()

        assertTrue(r is UnlockResult.Unlocked)
        assertEquals(0, play.buys)
        assertTrue(store.unlocked)
    }

    @Test fun restoreWithoutAPurchaseSaysThereIsNothing() = runBlocking {
        val store = MemStore()
        assertTrue(manager(store, b = Play(owned = null)).restore() is UnlockResult.NothingToRestore)
        assertFalse(store.unlocked)
    }

    @Test fun aRefundLocksTheAppAgain() = runBlocking {
        val store = MemStore().apply { key = "SM-AAAAA-BBBBB-CCCCC-DDDDD"; email = "pat@example.com" }

        manager(store, b = Play(owned = null)).recheck()

        assertFalse(store.unlocked)
        assertNull(store.email)
    }

    @Test fun anOwnedPurchaseStaysUnlocked() = runBlocking {
        val store = MemStore().apply { key = "SM-AAAAA-BBBBB-CCCCC-DDDDD" }
        manager(store, b = Play(owned = "ptoken")).recheck()
        assertTrue(store.unlocked)
    }

    @Test fun beingOfflineNeverLocksAnUnlockedApp() = runBlocking {
        val store = MemStore().apply { key = "SM-AAAAA-BBBBB-CCCCC-DDDDD" }
        manager(store, b = Play(error = IOException("no Play"))).recheck()
        assertTrue(store.unlocked)
    }

    @Test fun recheckOnALockedAppDoesNothing() = runBlocking {
        val store = MemStore()
        manager(store, b = Play(owned = "ptoken")).recheck()
        assertFalse(store.unlocked)
    }

    // --- the server's answers -------------------------------------------------------------------------------------------

    @Test fun aGoodAnswerYieldsTheKey() {
        assertEquals("SM-AAAAA-BBBBB-CCCCC-DDDDD", HttpBackend.parseClaim(200, """{"key":"SM-AAAAA-BBBBB-CCCCC-DDDDD","status":"active"}"""))
    }

    @Test fun refusalsKeepTheServersCodeAndMessage() {
        for ((status, code) in listOf(401 to "bad_sign_in", 402 to "not_purchased", 403 to "revoked", 409 to "purchase_in_use", 503 to "unavailable")) {
            try {
                HttpBackend.parseClaim(status, """{"error":"$code","message":"msg $code"}""")
                fail("should refuse $status")
            } catch (e: LicenceException) {
                assertEquals(code, e.code)
                assertEquals("msg $code", e.message)
            }
        }
    }

    @Test fun anUnreadableAnswerIsAnErrorNotAnUnlock() {
        for ((status, text) in listOf(200 to "", 200 to "<html>", 200 to """{"status":"active"}""", 500 to "<html>Oops</html>")) {
            try {
                HttpBackend.parseClaim(status, text)
                fail("should refuse $status $text")
            } catch (e: LicenceException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }
}
