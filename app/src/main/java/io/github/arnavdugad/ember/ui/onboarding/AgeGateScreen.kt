package io.github.arnavdugad.ember.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GradientText
import io.github.arnavdugad.ember.core.designsystem.ScreenTitle
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.model.Ages
import io.github.arnavdugad.ember.ui.legal.LegalDoc
import io.github.arnavdugad.ember.ui.legal.LegalSheet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun AgeGateScreen(viewModel: AgeGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    var pickerOpen by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<LegalDoc?>(null) }
    var acceptedTerms by rememberSaveable { mutableStateOf(false) }
    var acceptedGuidelines by rememberSaveable { mutableStateOf(false) }
    var acceptedConsent by rememberSaveable { mutableStateOf(false) }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow() }) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text("STEP 1 OF 2", style = EmberTheme.type.overline, color = colors.accent)
            Spacer(Modifier.height(12.dp))
            ScreenTitle("First, the grown-up stuff", subtitle = "Ember is for adults 18 and over. Your birthday is private; only your age is shown.")
            Spacer(Modifier.height(28.dp))

            GlassSurface(
                Modifier.fillMaxWidth().pressable(onClick = { pickerOpen = true }),
                shape = EmberShapes.panel,
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).background(colors.accentBrush, EmberShapes.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Cake, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Date of birth", style = EmberTheme.type.caption, color = colors.textSecondary)
                        Text(
                            state.birthDate?.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) ?: "Tap to choose",
                            style = EmberTheme.type.title,
                            color = if (state.birthDate == null) colors.textTertiary else colors.textPrimary,
                        )
                    }
                    AnimatedContent(
                        targetState = state.age,
                        transitionSpec = { (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn()) togetherWith androidx.compose.animation.fadeOut() },
                        label = "age",
                    ) { age ->
                        if (age != null) GradientText("$age", style = EmberTheme.type.headline)
                    }
                }
            }
            if (state.age != null && state.age!! < Ages.MINIMUM) {
                Text(
                    "You need to be 18 or older to join Ember.",
                    style = EmberTheme.type.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsentRow(
                    checked = acceptedTerms,
                    onToggle = { acceptedTerms = !acceptedTerms },
                    text = "I agree to the ",
                    link = "Terms of Service",
                    onLink = { legal = LegalDoc.Terms },
                )
                ConsentRow(
                    checked = acceptedGuidelines,
                    onToggle = { acceptedGuidelines = !acceptedGuidelines },
                    text = "I'll follow the ",
                    link = "Community Guidelines",
                    onLink = { legal = LegalDoc.Guidelines },
                )
                ConsentRow(
                    checked = acceptedConsent,
                    onToggle = { acceptedConsent = !acceptedConsent },
                    text = "I'll respect consent: ask first, take no for an answer, and never share private photos.",
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(28.dp))
            state.error?.let {
                Text(it, style = EmberTheme.type.bodySmall, color = colors.danger, modifier = Modifier.padding(bottom = 12.dp))
            }
            EmberButton(
                "Continue",
                onClick = viewModel::submit,
                loading = state.submitting,
                enabled = state.age != null && acceptedTerms && acceptedGuidelines && acceptedConsent && state.age!! <= Ages.MAXIMUM,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = viewModel::signOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Use a different account", style = EmberTheme.type.label, color = colors.textSecondary)
            }
        }
        LegalSheet(legal, onDismiss = { legal = null })
    }

    if (pickerOpen) {
        BirthdayPicker(
            initial = state.birthDate,
            onPicked = {
                viewModel.setBirthDate(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onToggle: () -> Unit, text: String, link: String? = null, onLink: () -> Unit = {}) {
    val colors = EmberTheme.colors
    val fill by animateColorAsState(if (checked) colors.accent else Color.Transparent, label = "check")
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onToggle, role = Role.Checkbox, pressedScale = 0.98f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .background(fill, EmberShapes.small)
                .border(1.5.dp, if (checked) colors.accent else colors.textTertiary, EmberShapes.small),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        // Only the linked words open the document; the rest of the row toggles the checkbox.
        Text(
            buildAnnotatedString {
                append(text)
                if (link != null) {
                    withLink(LinkAnnotation.Clickable("doc", TextLinkStyles(SpanStyle(color = colors.accent))) { onLink() }) { append(link) }
                }
            },
            style = EmberTheme.type.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BirthdayPicker(initial: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val start = initial ?: today.minusYears(25)
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = (today.year - Ages.MAXIMUM)..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
        },
    )
    val pickerColors = DatePickerDefaults.colors(
        containerColor = EmberTheme.colors.surface,
        selectedDayContainerColor = EmberTheme.colors.accent,
        todayDateBorderColor = EmberTheme.colors.accent,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
        colors = pickerColors,
    ) {
        DatePicker(state = pickerState, showModeToggle = true, colors = pickerColors)
    }
}
