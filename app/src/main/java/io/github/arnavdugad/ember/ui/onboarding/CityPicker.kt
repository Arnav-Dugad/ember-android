package io.github.arnavdugad.ember.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.location.Cities
import io.github.arnavdugad.ember.data.location.CitySuggestion
import io.github.arnavdugad.ember.data.model.Place
import kotlinx.coroutines.delay

/**
 * A searchable glass sheet of cities: the bundled list answers instantly as you type, and the
 * Geocoder ([search]) fills in anywhere else after a short pause. Picking returns the city and its
 * coordinates at city precision.
 */
@Composable
fun CityPickerSheet(
    visible: Boolean,
    title: String,
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
    search: suspend (String) -> List<CitySuggestion>,
    subtitle: String? = null,
) {
    val colors = EmberTheme.colors
    var query by remember(visible) { mutableStateOf("") }
    var results by remember(visible) { mutableStateOf(Cities.search("")) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, visible) {
        if (!visible) return@LaunchedEffect
        results = Cities.search(query)
        if (query.trim().length < 3) return@LaunchedEffect
        delay(350)
        searching = true
        results = runCatching { search(query) }.getOrDefault(results)
        searching = false
    }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(title, style = EmberTheme.type.headline, color = colors.textPrimary)
        if (subtitle != null) Text(subtitle, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(14.dp))
        EmberTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search cities",
            maxLength = 80,
            imeAction = ImeAction.Search,
            leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
            if (results.isEmpty() && !searching) {
                Text(
                    "No city by that name yet. Check the spelling, or try a nearby bigger city.",
                    style = EmberTheme.type.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { "${it.place.name}|${it.place.lat}|${it.place.lng}" }) { suggestion ->
                    CityRow(suggestion) { onPick(suggestion.place) }
                }
            }
            if (searching) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp).size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CityRow(suggestion: CitySuggestion, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.tile) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(suggestion.place.name, style = EmberTheme.type.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (suggestion.detail.isNotBlank()) {
                    Text(suggestion.detail, style = EmberTheme.type.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
