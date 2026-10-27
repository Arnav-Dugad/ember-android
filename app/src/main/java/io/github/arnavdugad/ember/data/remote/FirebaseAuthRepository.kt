package io.github.arnavdugad.ember.data.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.PhoneVerification
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context,
) : AuthRepository {
    override val mode = BackendMode.Firebase

    /** Fires on sign-in, sign-out and every token refresh, so new custom claims flow through. */
    override val user: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.IdTokenListener { firebaseAuth ->
            val current = firebaseAuth.currentUser
            if (current == null) {
                trySend(null)
            } else {
                current.getIdToken(false)
                    .addOnSuccessListener { result ->
                        trySend(AuthUser(current.uid, current.email, current.phoneNumber, isAdult = result.claims["adult"] == true))
                    }
                    .addOnFailureListener {
                        trySend(AuthUser(current.uid, current.email, current.phoneNumber, isAdult = false))
                    }
            }
        }
        auth.addIdTokenListener(listener)
        awaitClose { auth.removeIdTokenListener(listener) }
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
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google didn't return an account."
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun startPhoneVerification(activity: Activity, phoneNumber: String): PhoneVerification =
        suspendCancellableCoroutine { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    // Instant verification or SMS auto-retrieval. Signing in updates the session
                    // even if the code screen is already showing.
                    auth.signInWithCredential(credential).addOnCompleteListener { task ->
                        if (!cont.isActive) return@addOnCompleteListener
                        if (task.isSuccessful) {
                            cont.resume(PhoneVerification.AutoVerified)
                        } else {
                            cont.resumeWithException(task.exception ?: IllegalStateException("Verification failed"))
                        }
                    }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    if (cont.isActive) cont.resume(PhoneVerification.CodeSent(verificationId))
                }
            }
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }

    override suspend fun confirmPhoneCode(verificationId: String, code: String) {
        auth.signInWithCredential(PhoneAuthProvider.getCredential(verificationId, code.trim())).await()
    }

    override suspend fun enterPreview() = error("Preview mode is only available without a Firebase project.")

    override suspend fun refreshClaims(): Boolean {
        val user = auth.currentUser ?: return false
        return user.getIdToken(true).await().claims["adult"] == true
    }

    override suspend fun signOut() {
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
