package io.github.arnavdugad.ember.data.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val account: Account,
    @ApplicationContext private val context: Context,
) : AuthRepository {
    override val mode = BackendMode.Firebase

    /**
     * Signed-in user plus whether they passed the age gate, which on the Spark plan means
     * "users/{uid}/private/account exists" (rules only let adults create it).
     */
    override val user: Flow<AuthUser?> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(null)
        } else {
            account.accountRef(uid).snapshots()
                // A cold cache says "missing" before the server answers; wait for the server.
                .filter { snap -> snap == null || snap.exists() || !snap.metadata.isFromCache }
                .map { snap ->
                    val current = auth.currentUser
                    AuthUser(uid, current?.email, current?.phoneNumber, isAdult = snap?.exists() == true)
                }
        }
    }.distinctUntilChanged()

    override suspend fun signInWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    override suspend fun signUpWithEmail(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }

    override suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    override suspend fun signInWithGoogle(activity: Activity) {
        val option = GetSignInWithGoogleOption.Builder(webClientId()).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        // The "Sign in with Google" sheet returns the SIWG subtype; the One Tap picker returns the
        // plain one. Both carry the same ID token.
        val googleTypes = setOf(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
        )
        check(credential is CustomCredential && credential.type in googleTypes) {
            "Google didn't return an account (got ${credential.type})."
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun enterPreview() = error("Preview mode is only available without a Firebase project.")

    override suspend fun refreshClaims(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return account.accountRef(uid).get(com.google.firebase.firestore.Source.SERVER).await().exists()
    }

    override suspend fun signOut() {
        account.forget()
        auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
    }

    /**
     * The OAuth web client id is generated into resources by the google-services plugin, which
     * only runs when google-services.json is present, so it can't be referenced as R.string.
     */
    @SuppressLint("DiscouragedApi")
    private fun webClientId(): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        check(id != 0) { "Google sign-in isn't configured for this build. See docs/FIREBASE_SETUP.md." }
        return context.getString(id)
    }
}
