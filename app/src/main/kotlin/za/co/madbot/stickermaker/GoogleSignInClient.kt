package za.co.madbot.stickermaker

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Real Google sign-in through Credential Manager. [webClientId] is the OAuth *web* client id of the Google Cloud project; the ID token it
 * produces is checked by the licence server, which accepts only client ids listed in STICKER_GOOGLE_CLIENT_IDS.
 */
class GoogleSignIn(private val activity: Activity, private val webClientId: String) : SignIn {
    override suspend fun signIn(): GoogleAccount? {
        if (webClientId.isBlank()) throw LicenceException("not_configured", "Google sign-in is not set up in this build yet.")
        val request = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build()).build()
        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw LicenceException("no_account", "No Google account is available on this phone. Add one in Settings and try again.")
        } catch (e: GetCredentialException) {
            throw LicenceException("sign_in_failed", e.message ?: "Google sign-in failed.")
        }
        val c = result.credential
        if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val g = GoogleIdTokenCredential.createFrom(c.data)
            return GoogleAccount(g.idToken, g.id)
        }
        throw LicenceException("sign_in_failed", "Google sign-in returned something this app does not understand.")
    }
}
