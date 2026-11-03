package com.jemcik.jemrec.crm

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.jemcik.jemrec.BuildConfig

/**
 * A Google ID token for the Sales Engine, from Credential Manager (research R2).
 *
 * The token's audience is LANARS's Web client id, and Google issues it only
 * to an app whose package and certificate LANARS registered (§2.1) - so a
 * debug build signed with another key gets nothing here, before the server
 * is even asked. It is handed to the exchange at once and never kept: the
 * server takes each one once, within 10 minutes (§2.2).
 *
 * No hosted-domain filter: the server checks lanars.com and lanars.no both,
 * and a filter here could only name one (§2.1).
 */
object GoogleSignIn {

    sealed interface Result {
        data class Token(val idToken: String) : Result
        /** The seller closed the chooser. */
        data object Cancelled : Result
        /** Silent renewal found no account that signed in before: the button is needed. */
        data object NoAccount : Result
        /** This build has no Web client id: LANARS has not sent it yet. */
        data object NotConfigured : Result
        data class Failed(val reason: String) : Result
    }

    /** This build knows where the Sales Engine is and which Google client to ask for (app/build.gradle.kts). */
    val configured: Boolean get() = BuildConfig.SALES_ENGINE_URL.isNotBlank() && BuildConfig.SALES_ENGINE_WEB_CLIENT_ID.isNotBlank()

    /** The Sign in with Google button's flow: any Google account on the phone, or another. */
    suspend fun withButton(activity: Activity): Result =
        get(activity, GetSignInWithGoogleOption.Builder(BuildConfig.SALES_ENGINE_WEB_CLIENT_ID).build())

    /**
     * A new token without a chooser, for renewing before the session expires:
     * only an account that signed in to this app before, picked by itself.
     */
    suspend fun silently(activity: Activity): Result = get(
        activity,
        GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.SALES_ENGINE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build(),
    )

    private suspend fun get(activity: Activity, option: androidx.credentials.CredentialOption): Result {
        if (!configured) return Result.NotConfigured
        return try {
            val response = CredentialManager.create(activity)
                .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val credential = response.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Result.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.Failed("Google answered with something other than an ID token")
            }
        } catch (_: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (_: NoCredentialException) {
            Result.NoAccount
        } catch (e: GetCredentialException) {
            // The type only: the message can carry the account's address.
            Log.w("JemRec", "crm: Google sign-in failed: ${e.type}")
            Result.Failed(e.type)
        }
    }
}
