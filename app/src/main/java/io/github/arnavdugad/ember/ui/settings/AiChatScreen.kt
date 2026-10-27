package io.github.arnavdugad.ember.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberChip
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.ai.AiChatStore
import io.github.arnavdugad.ember.data.ai.AiClient
import io.github.arnavdugad.ember.data.ai.AiConfig
import io.github.arnavdugad.ember.data.ai.AiEndpoints
import io.github.arnavdugad.ember.data.ai.AiException
import io.github.arnavdugad.ember.data.ai.AiPace
import io.github.arnavdugad.ember.data.ai.AiProvider
import io.github.arnavdugad.ember.data.ai.AiSettingsRepository
import io.github.arnavdugad.ember.data.ai.ModelPicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The result of checking a key against the provider. */
sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Ok(val text: String) : KeyCheck
    data class Failed(val text: String) : KeyCheck
}

data class AiChatUi(
    val check: KeyCheck = KeyCheck.Idle,
    /** Chat models the saved key can use, best first, for the current provider. */
    val models: List<String> = emptyList(),
    val cleared: Boolean = false,
)

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settings: AiSettingsRepository,
    private val client: AiClient,
    private val store: AiChatStore,
) : ViewModel() {
    val config: StateFlow<AiConfig?> = settings.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _ui = MutableStateFlow(AiChatUi())
    val ui: StateFlow<AiChatUi> = _ui.asStateFlow()

    init {
        // A saved key: list its models so the picker is ready.
        viewModelScope.launch {
            val current = settings.current()
            if (current.hasKey) refreshModels(current, quiet = true)
        }
    }

    fun selectProvider(provider: AiProvider) = viewModelScope.launch {
        settings.setProvider(provider)
        _ui.value = AiChatUi()
        val current = settings.current()
        if (current.hasKey) refreshModels(current, quiet = true)
    }

    /** Checks the key with the provider first; a key the provider rejects is never saved. */
    fun connect(apiKey: String) = viewModelScope.launch {
        val config = settings.current()
        val key = apiKey.trim()
        if (key.isEmpty()) return@launch
        _ui.update { it.copy(check = KeyCheck.Checking) }
        val result = runCatching { client.models(config.provider, key, config.baseUrl) }
        val error = result.exceptionOrNull() as? AiException
        if (result.isFailure && (error == null || error.kind == AiException.Kind.BadKey || error.kind == AiException.Kind.Setup)) {
            _ui.update { it.copy(check = KeyCheck.Failed(error?.message ?: "Couldn't check that key. Try again.")) }
            return@launch
        }
        settings.saveKey(config.provider, key)
        val ids = result.getOrDefault(emptyList())
        if (ids.isNotEmpty()) settings.setModel(config.provider, ModelPicker.preferred(config.provider, ids, config.models[config.provider].orEmpty()))
        settings.setEnabled(true)
        _ui.update {
            it.copy(
                models = ModelPicker.chatModels(config.provider, ids),
                check = if (error == null) {
                    KeyCheck.Ok("Connected. Demo profiles will now text back.")
                } else {
                    // Saved anyway: the key wasn't rejected, the provider just couldn't list models right now.
                    KeyCheck.Ok("Key saved. ${error.message}")
                },
            )
        }
    }

    fun test() = viewModelScope.launch { refreshModels(settings.current(), quiet = false) }

    private suspend fun refreshModels(config: AiConfig, quiet: Boolean) {
        val key = settings.key(config.provider) ?: run {
            if (!quiet) _ui.update { it.copy(check = KeyCheck.Failed("This key can't be read on this phone any more. Remove it and add it again.")) }
            return
        }
        if (!quiet) _ui.update { it.copy(check = KeyCheck.Checking) }
        runCatching { client.models(config.provider, key, config.baseUrl) }
            .onSuccess { ids ->
                _ui.update { state ->
                    state.copy(
                        models = ModelPicker.chatModels(config.provider, ids),
                        check = if (quiet) state.check else KeyCheck.Ok("Working. Your key can use ${ids.size} models."),
                    )
                }
            }
            .onFailure { e -> if (!quiet) _ui.update { it.copy(check = KeyCheck.Failed(e.message ?: "Couldn't reach the provider.")) } }
    }

    fun removeKey() = viewModelScope.launch {
        val provider = settings.current().provider
        settings.removeKey(provider)
        _ui.value = AiChatUi()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEnabled(enabled) }

    fun setModel(model: String) = viewModelScope.launch { settings.setModel(settings.current().provider, model) }

    fun setBaseUrl(url: String) = viewModelScope.launch { settings.setBaseUrl(url) }

    fun usePreset(baseUrl: String, model: String) = viewModelScope.launch {
        settings.setBaseUrl(baseUrl)
        if (model.isNotBlank()) settings.setModel(AiProvider.Compatible, model)
    }

    fun setPace(pace: AiPace) = viewModelScope.launch { settings.setPace(pace) }

    fun setFirstMove(enabled: Boolean) = viewModelScope.launch { settings.setFirstMove(enabled) }

    fun clearChats() = viewModelScope.launch {
        store.wipe()
        _ui.update { it.copy(cleared = true) }
    }
}

@Composable
fun AiChatScreen(onBack: () -> Unit, viewModel: AiChatViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.7f) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Text("AI chat", style = EmberTheme.type.title, color = colors.textPrimary)
            }
            val c = config ?: return@Column
            Column(
                // Typing a key or address: the keyboard pushes the page up instead of covering the field.
                Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Hero()

                SettingsToggleRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "AI replies",
                    subtitle = when {
                        !c.hasKey -> "Add a key below to turn this on."
                        c.enabled -> "On: demo profiles reply with ${c.provider.label} (${c.model.ifBlank { "pick a model" }})."
                        else -> "Off: demo profiles won't reply."
                    },
                    checked = c.enabled && c.hasKey,
                    onCheckedChange = viewModel::setEnabled,
                    enabled = c.hasKey,
                )

                SettingsGroup("Provider") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiProvider.entries.forEach { provider ->
                            EmberChip(
                                text = if (provider in c.keyTails) "${provider.label} ✓" else provider.label,
                                selected = c.provider == provider,
                                onClick = { viewModel.selectProvider(provider) },
                            )
                        }
                    }
                    if (c.provider == AiProvider.Compatible) EndpointPanel(c, viewModel, open)
                    KeyPanel(c, ui.check, viewModel, open)
                }

                AnimatedVisibility(c.hasKey) {
                    SettingsGroup("Model") { ModelPanel(c, ui.models, viewModel::setModel) }
                }

                SettingsGroup("Feel") {
                    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsIconTile(Icons.Rounded.Bolt)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Reply speed", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                                    Text(c.pace.blurb, style = EmberTheme.type.caption, color = colors.textSecondary)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AiPace.entries.forEach { pace -> EmberChip(pace.label, selected = c.pace == pace, onClick = { viewModel.setPace(pace) }) }
                            }
                        }
                    }
                    SettingsToggleRow(
                        icon = Icons.Rounded.WavingHand,
                        title = "Let them text first",
                        subtitle = "Open a new demo match and they may send the opener.",
                        checked = c.firstMove,
                        onCheckedChange = viewModel::setFirstMove,
                    )
                }

                SettingsGroup("Privacy") {
                    SettingsRow(
                        icon = Icons.Rounded.Lock,
                        title = "What leaves your phone",
                        subtitle = "Your key is encrypted with the Android Keystore and sent only to ${c.provider.company}. " +
                            "To write a reply, Ember sends ${c.provider.company} that demo chat (the last 40 messages), the demo's profile, " +
                            "and your first name, age, city, bio, prompts and interests. Replies are stored only on this phone. " +
                            "Chats with real people never go to AI. Usage is billed to your own ${c.provider.company} account.",
                    )
                    SettingsRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = if (ui.cleared) "AI conversations cleared" else "Clear AI conversations",
                        subtitle = "Removes every demo profile's AI replies from this phone. Your own messages stay.",
                        onClick = if (ui.cleared) null else ({ viewModel.clearChats() }),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Hero() {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel, elevated = true) {
        Column(Modifier.padding(20.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(colors.accentBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Let demo profiles text back", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Bring your own key from Claude, Gemini, ChatGPT or any OpenAI-compatible provider. Each demo profile then replies in " +
                    "character: their bio, prompts, city and vibe, with a texting style of their own. They read, type, double-text, " +
                    "answer Truth or Dare and react to date ideas.",
                style = EmberTheme.type.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun KeyPanel(c: AiConfig, check: KeyCheck, viewModel: AiChatViewModel, open: (String) -> Unit) {
    val colors = EmberTheme.colors
    var draft by rememberSaveable(c.provider) { mutableStateOf("") }
    GlassSurface(Modifier.fillMaxWidth().animateContentSize(), shape = EmberShapes.panel) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconTile(Icons.Rounded.Key)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${c.provider.label} API key", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                    Text(
                        c.keyTails[c.provider]?.let { "Saved: ••••$it" } ?: "Not added yet",
                        style = EmberTheme.type.caption,
                        color = if (c.hasKey) colors.like else colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (c.hasKey) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Test", onClick = viewModel::test, modifier = Modifier.weight(1f))
                    GlassButton("Remove", onClick = viewModel::removeKey, modifier = Modifier.weight(1f), contentColor = colors.danger)
                }
            } else {
                EmberTextField(
                    value = draft,
                    onValueChange = { draft = it.trim() },
                    placeholder = c.provider.keyHint,
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onImeAction = { viewModel.connect(draft) },
                    maxLength = 400,
                )
                Spacer(Modifier.height(10.dp))
                EmberButton(
                    "Save & connect",
                    onClick = { viewModel.connect(draft) },
                    enabled = draft.length >= 8,
                    loading = check == KeyCheck.Checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                val keyUrl = if (c.provider == AiProvider.Compatible) AiEndpoints.presets.firstOrNull { it.baseUrl == c.baseUrl }?.keyUrl else c.provider.keyUrl
                if (!keyUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.pressable(onClick = { open(keyUrl) }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Get a key from ${if (c.provider == AiProvider.Compatible) "your provider" else c.provider.company}", style = EmberTheme.type.label, color = colors.accent)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
            when (check) {
                is KeyCheck.Ok -> CheckText(check.text, colors.like)
                is KeyCheck.Failed -> CheckText(check.text, colors.danger)
                KeyCheck.Checking -> if (c.hasKey) CheckText("Checking…", colors.textSecondary)
                KeyCheck.Idle -> Unit
            }
        }
    }
}

@Composable
private fun CheckText(text: String, color: Color) {
    Spacer(Modifier.height(10.dp))
    Text(text, style = EmberTheme.type.caption, color = color)
}

@Composable
private fun EndpointPanel(c: AiConfig, viewModel: AiChatViewModel, open: (String) -> Unit) {
    val colors = EmberTheme.colors
    var url by remember(c.baseUrl) { mutableStateOf(c.baseUrl) }
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("OpenAI-compatible API", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            Text("Pick one, or paste any https:// address that speaks the OpenAI chat format.", style = EmberTheme.type.caption, color = colors.textSecondary)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiEndpoints.presets.forEach { preset ->
                    EmberChip(preset.name, selected = c.baseUrl == preset.baseUrl, onClick = { viewModel.usePreset(preset.baseUrl, preset.model) })
                }
            }
            Spacer(Modifier.height(12.dp))
            EmberTextField(
                value = url,
                onValueChange = { url = it.trim() },
                placeholder = "https://api.example.com/v1",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = { viewModel.setBaseUrl(url) },
                maxLength = 300,
            )
            if (url != c.baseUrl) {
                Spacer(Modifier.height(8.dp))
                GlassButton("Use this address", onClick = { viewModel.setBaseUrl(url) }, modifier = Modifier.fillMaxWidth())
            }
            AiEndpoints.presets.firstOrNull { it.baseUrl == c.baseUrl }?.let { preset ->
                Row(Modifier.pressable(onClick = { open(preset.keyUrl) }).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${preset.name} keys", style = EmberTheme.type.label, color = colors.accent)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelPanel(c: AiConfig, models: List<String>, onModel: (String) -> Unit) {
    val colors = EmberTheme.colors
    var custom by remember(c.provider, c.model) { mutableStateOf(c.model) }
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(c.model.ifBlank { "No model picked" }, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            Text(
                when (c.provider) {
                    AiProvider.Claude -> "Opus writes the most natural texts. Haiku is fastest and cheapest."
                    AiProvider.Gemini -> "Flash models are quick and cheap; Pro models are sharper."
                    AiProvider.OpenAI -> "Mini models are quick and cheap; full models are sharper."
                    AiProvider.Compatible -> "Use the model id your provider lists."
                },
                style = EmberTheme.type.caption,
                color = colors.textSecondary,
            )
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The current model stays visible even when it isn't among the first few.
                    (listOf(c.model).filter { it in models } + models).distinct().take(MODEL_CHIPS).forEach { id ->
                        EmberChip(id, selected = id == c.model, onClick = { onModel(id) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            EmberTextField(
                value = custom,
                onValueChange = { custom = it.trim() },
                label = "Or type a model id",
                placeholder = c.provider.defaultModel.ifBlank { "model-id" },
                imeAction = ImeAction.Done,
                onImeAction = { onModel(custom) },
                maxLength = 120,
            )
            if (custom.isNotBlank() && custom != c.model) {
                Spacer(Modifier.height(8.dp))
                GlassButton("Use $custom", onClick = { onModel(custom) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private const val MODEL_CHIPS = 10
