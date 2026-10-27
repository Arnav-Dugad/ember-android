package io.github.arnavdugad.ember.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.arnavdugad.ember.data.ai.AiAwareSafetyRepository
import io.github.arnavdugad.ember.data.ai.AiChatEngine
import io.github.arnavdugad.ember.data.ai.AiChatStore
import io.github.arnavdugad.ember.data.ai.AiMatchRepository
import io.github.arnavdugad.ember.data.ai.AiSettingsRepository
import io.github.arnavdugad.ember.data.ai.BACKEND
import io.github.arnavdugad.ember.data.preview.PreviewBackend
import io.github.arnavdugad.ember.data.remote.FirebaseAlbumRepository
import io.github.arnavdugad.ember.data.remote.FirebaseAuthRepository
import io.github.arnavdugad.ember.data.remote.FirebaseDiscoveryRepository
import io.github.arnavdugad.ember.data.remote.FirebaseMatchRepository
import io.github.arnavdugad.ember.data.remote.FirebasePrivacyRepository
import io.github.arnavdugad.ember.data.remote.FirebaseProfileRepository
import io.github.arnavdugad.ember.data.remote.FirebaseSafetyRepository
import io.github.arnavdugad.ember.data.repo.AlbumRepository
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.PrivacyRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Picks the backend once per process (Firebase on the free Spark plan: Auth, Firestore, Remote
 * Config). With a google-services.json the Firebase SDK initialises
 * itself and every repository talks to Firebase; without one the app runs on [PreviewBackend].
 * Firebase classes are behind [Provider]s so they are never touched in Preview mode (their
 * getInstance() would throw without a FirebaseApp).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun backendMode(@ApplicationContext context: Context): BackendMode =
        if (FirebaseApp.getApps(context).isNotEmpty()) BackendMode.Firebase else BackendMode.Preview

    @Provides fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun auth(mode: BackendMode, firebase: Provider<FirebaseAuthRepository>, preview: Provider<PreviewBackend>): AuthRepository =
        if (mode == BackendMode.Firebase) firebase.get() else preview.get()

    @Provides
    @Singleton
    fun profiles(mode: BackendMode, firebase: Provider<FirebaseProfileRepository>, preview: Provider<PreviewBackend>): ProfileRepository =
        if (mode == BackendMode.Firebase) firebase.get() else preview.get()

    @Provides
    @Singleton
    fun discovery(mode: BackendMode, firebase: Provider<FirebaseDiscoveryRepository>, preview: Provider<PreviewBackend>): DiscoveryRepository =
        if (mode == BackendMode.Firebase) firebase.get() else preview.get()

    @Provides
    @Singleton
    @Named(BACKEND)
    fun backendMatches(mode: BackendMode, firebase: Provider<FirebaseMatchRepository>, preview: Provider<PreviewBackend>): MatchRepository =
        if (mode == BackendMode.Firebase) firebase.get() else preview.get()

    /** What the app uses: the backend plus demo profiles' AI replies. */
    @Provides
    @Singleton
    fun matches(@Named(BACKEND) backend: MatchRepository, store: AiChatStore, engine: AiChatEngine): MatchRepository =
        AiMatchRepository(backend, store, engine)

    @Provides
    @Singleton
    fun albums(mode: BackendMode, firebase: Provider<FirebaseAlbumRepository>, preview: Provider<PreviewBackend>): AlbumRepository =
        if (mode == BackendMode.Firebase) firebase.get() else preview.get()

    @Provides
    @Singleton
    fun safety(
        mode: BackendMode,
        firebase: Provider<FirebaseSafetyRepository>,
        preview: Provider<PreviewBackend>,
        store: AiChatStore,
        ai: AiSettingsRepository,
    ): SafetyRepository = AiAwareSafetyRepository(if (mode == BackendMode.Firebase) firebase.get() else preview.get(), store, ai)

    @Provides
    @Singleton
    fun privacy(mode: BackendMode, firebase: Provider<FirebasePrivacyRepository>, preview: Provider<PreviewBackend>): PrivacyRepository =
        if (mode == BackendMode.Firebase) firebase.get() else preview.get()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
