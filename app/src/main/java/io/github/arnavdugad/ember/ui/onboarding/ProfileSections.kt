package io.github.arnavdugad.ember.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberChip
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.PromptTemplate

@Composable
fun BasicsSection(state: EditorState, vm: ProfileEditorViewModel) {
    val d = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        EmberTextField(d.name, vm::setName, label = "First name", placeholder = "Your first name", maxLength = 40)
        Column {
            SectionHeader("I am a")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Gender.entries.forEach { g -> EmberChip(g.label, selected = d.gender == g, onClick = { vm.setGender(g) }) }
            }
        }
        Column {
            SectionHeader("Show me")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Gender.entries.forEach { g -> EmberChip(g.plural, selected = g in d.interestedIn, onClick = { vm.toggleInterestedIn(g) }) }
            }
        }
    }
}

@Composable
fun PhotosSection(state: EditorState, vm: ProfileEditorViewModel) {
    val colors = EmberTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(EditorState.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) vm.addPhotos(uris)
    }
    val launch = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val photos = state.draft.photos
    Column {
        (0 until EditorState.MAX_PHOTOS).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { index ->
                    val photo = photos.getOrNull(index)
                    val uploadingSlot = photo == null && index < photos.size + state.uploading
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.75f)
                            .clip(EmberShapes.tile),
                    ) {
                        when {
                            photo != null -> {
                                EmberImage(
                                    photo.url,
                                    photo.blurHash,
                                    contentDescription = "Photo ${index + 1}",
                                    modifier = Modifier.fillMaxSize().pressable(onClick = { vm.makeMainPhoto(photo) }, pressedScale = 0.97f),
                                )
                                if (index == 0) {
                                    Text(
                                        "MAIN",
                                        style = EmberTheme.type.overline,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(8.dp)
                                            .background(colors.accentHorizontal, PillShape)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(26.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                        .pressable(onClick = { vm.removePhoto(photo) }, pressedScale = 0.85f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                            uploadingSlot -> Box(Modifier.fillMaxSize().shimmer(EmberShapes.tile), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            }
                            else -> Box(
                                Modifier
                                    .fillMaxSize()
                                    .border(1.5.dp, colors.textTertiary.copy(alpha = 0.4f), EmberShapes.tile)
                                    .pressable(onClick = { if (state.canAddPhotos) launch() }, pressedScale = 0.95f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(34.dp).background(colors.accentBrush, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Add photo", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Tap a photo to make it your main one. Photos are re-encoded before upload, which strips hidden location data.",
            style = EmberTheme.type.caption,
            color = colors.textTertiary,
        )
    }
}

@Composable
fun AboutSection(state: EditorState, vm: ProfileEditorViewModel) {
    val colors = EmberTheme.colors
    val d = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        EmberTextField(
            d.bio,
            vm::setBio,
            label = "Bio",
            placeholder = "Two truths, one flirt, zero clichés…",
            singleLine = false,
            minLines = 4,
            maxLength = 500,
            imeAction = ImeAction.Default,
        )
        GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Height", style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(d.heightCm?.let { formatHeight(it) } ?: "Not shown", style = EmberTheme.type.label, color = colors.accent)
                }
                Slider(
                    value = (d.heightCm ?: 170).toFloat(),
                    onValueChange = { vm.setHeight(it.toInt()) },
                    valueRange = 140f..215f,
                    colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.surfaceHigh),
                )
                if (d.heightCm != null) {
                    Text(
                        "Hide my height",
                        style = EmberTheme.type.caption,
                        color = colors.textSecondary,
                        modifier = Modifier.pressable(onClick = { vm.setHeight(null) }).padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

fun formatHeight(cm: Int): String {
    val totalInches = (cm / 2.54).toInt()
    return "$cm cm · ${totalInches / 12}'${totalInches % 12}\""
}

@Composable
fun PromptsSection(state: EditorState, vm: ProfileEditorViewModel) {
    var pickerOpen by remember { mutableStateOf(false) }
    val colors = EmberTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.draft.prompts.forEach { prompt ->
            GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(prompt.question, style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove prompt",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(22.dp).pressable(onClick = { vm.removePrompt(prompt.id) }),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    EmberTextField(
                        prompt.answer,
                        { vm.answerPrompt(prompt.id, it) },
                        placeholder = "Your answer…",
                        singleLine = false,
                        minLines = 2,
                        maxLength = 160,
                        imeAction = ImeAction.Default,
                    )
                }
            }
        }
        if (state.draft.prompts.size < EditorState.MAX_PROMPTS) {
            GlassButton("Add a prompt", onClick = { pickerOpen = true }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
        }
    }
    PromptPickerSheet(
        visible = pickerOpen,
        taken = state.draft.prompts.map { it.id }.toSet(),
        onPick = {
            vm.addPrompt(it)
            pickerOpen = false
        },
        onDismiss = { pickerOpen = false },
    )
}

@Composable
private fun PromptPickerSheet(visible: Boolean, taken: Set<String>, onPick: (PromptTemplate) -> Unit, onDismiss: () -> Unit) {
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        val colors = EmberTheme.colors
        Text("Spicy prompts", style = EmberTheme.type.headline, color = colors.textPrimary)
        Text("Cheeky, never explicit. Pick one that sounds like you.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.prompts.filterNot { it.id in taken }.forEach { prompt ->
                GlassSurface(Modifier.fillMaxWidth().pressable(onClick = { onPick(prompt) }, pressedScale = 0.98f), shape = EmberShapes.tile) {
                    Text(prompt.question, style = EmberTheme.type.body, color = colors.textPrimary, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun VibesSection(state: EditorState, vm: ProfileEditorViewModel) {
    val d = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            SectionHeader("Vibes · ${d.vibes.size}/${EditorState.MAX_VIBES}")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Catalog.vibes.forEach { v -> EmberChip(v.label, selected = v.key in d.vibes, onClick = { vm.toggleVibe(v.key) }, emoji = v.emoji) }
            }
        }
        Column {
            SectionHeader("Interests · ${d.interests.size}/${EditorState.MAX_INTERESTS}")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Catalog.interests.forEach { i -> EmberChip(i, selected = i in d.interests, onClick = { vm.toggleInterest(i) }) }
            }
        }
    }
}

@Composable
fun IntentSection(state: EditorState, vm: ProfileEditorViewModel) {
    val colors = EmberTheme.colors
    val d = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LookingFor.entries.forEach { option ->
            val selected = d.lookingFor == option
            val border by animateColorAsState(if (selected) colors.accent else Color.Transparent, label = "intentBorder")
            GlassSurface(
                Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, border, EmberShapes.panel)
                    .pressable(onClick = { vm.setLookingFor(option) }, pressedScale = 0.98f),
                shape = EmberShapes.panel,
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option.emoji, style = EmberTheme.type.headline)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                        Text(option.blurb, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                    }
                    val dot by animateFloatAsState(if (selected) 1f else 0f, label = "dot")
                    Box(Modifier.size(22.dp).border(2.dp, if (selected) colors.accent else colors.textTertiary, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp * dot).background(colors.accent, CircleShape))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            SectionHeader("Only your matches see these")
        }
        Text("Boundaries", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.boundaries.forEach { t -> EmberChip(t.label, selected = t.key in d.matchOnly.boundaries, onClick = { vm.toggleBoundary(t.key) }, emoji = t.emoji) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Turn-ons", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.turnOns.forEach { t -> EmberChip(t.label, selected = t.key in d.matchOnly.turnOns, onClick = { vm.toggleTurnOn(t.key) }, emoji = t.emoji) }
        }
    }
}

@Composable
fun LocationSection(state: EditorState, vm: ProfileEditorViewModel) {
    val colors = EmberTheme.colors
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.locate()
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EmberButton(
            if (state.draft.lat != null) "Update my city" else "Use my location",
            onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            loading = state.locating,
            icon = Icons.Rounded.MyLocation,
            modifier = Modifier.fillMaxWidth(),
        )
        EmberTextField(state.draft.city, vm::setCity, label = "City", placeholder = "Or type your city", maxLength = 80)
        Text(
            "Ember uses approximate location (about 1 km), rounds it, and shows only your city and a rough distance.",
            style = EmberTheme.type.caption,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(4.dp))
        AfterDarkOptIn(checked = state.draft.afterDark, onChange = vm::setAfterDark)
    }
}

@Composable
fun AfterDarkOptIn(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(colors.accentBrush, EmberShapes.small), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Show me in After Dark", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text("Appear to people browsing for casual and adventurous connections.", style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White),
            )
        }
    }
}
