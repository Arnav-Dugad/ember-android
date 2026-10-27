package io.github.arnavdugad.ember.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.Profile
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** A daily pick and the one-line reason it was chosen. */
data class Pick(val profile: Profile, val reason: String)

/**
 * Today's picks: five people chosen on the device by compatibility. A new set is released at 6 pm
 * local time; before that, yesterday's set is shown. The choice is seeded by the day and the uid,
 * so it's stable all day (the ViewModel also remembers the chosen uids).
 */
object DailyPicks {
    const val COUNT = 5
    const val RELEASE_HOUR = 18

    /** The picks day showing at [now]: today from 6 pm, yesterday before. */
    fun dayOf(now: LocalDateTime): LocalDate = if (now.hour >= RELEASE_HOUR) now.toLocalDate() else now.toLocalDate().minusDays(1)

    /** True once today's set is out (6 pm or later). */
    fun isFresh(now: LocalDateTime): Boolean = now.hour >= RELEASE_HOUR

    /** Shared interests x2, shared vibes x2, same intent x3, plus up to 4 for being close. */
    fun score(me: Profile, them: Profile): Int {
        val interests = them.interests.count { it in me.interests }
        val vibes = them.vibes.count { it in me.vibes }
        val intent = if (them.lookingFor == me.lookingFor) 3 else 0
        return interests * 2 + vibes * 2 + intent + distanceBonus(them.distanceKm)
    }

    fun distanceBonus(km: Int?): Int = when {
        km == null -> 0
        km <= 5 -> 4
        km <= 15 -> 3
        km <= 40 -> 2
        km <= 100 -> 1
        else -> 0
    }

    /**
     * The [COUNT] best candidates for [day]. Each candidate gets a small jitter seeded by the day,
     * [uid] and their own uid, so ties break differently every day but never differ between two
     * loads of the same day (whatever order the candidates arrive in).
     */
    fun choose(candidates: List<Profile>, me: Profile, uid: String, day: LocalDate, count: Int = COUNT): List<Profile> {
        val seed = day.toEpochDay() * 1_000_003L + uid.hashCode()
        return candidates
            .distinctBy { it.uid }
            .filter { it.uid != uid }
            .map { them -> them to score(me, them) + Random(seed xor them.uid.hashCode().toLong()).nextDouble() * 1.5 }
            .sortedWith(compareByDescending<Pair<Profile, Double>> { it.second }.thenBy { it.first.uid })
            .take(count)
            .map { it.first }
    }

    /** The strongest reason, in a few words. */
    fun reason(me: Profile, them: Profile): String {
        val interests = them.interests.filter { it in me.interests }
        val vibes = them.vibes.filter { it in me.vibes }.mapNotNull(Catalog::vibe)
        return when {
            interests.size >= 2 -> "${interests.size} shared interests"
            vibes.isNotEmpty() -> "Both ${vibes.first().emoji} ${vibes.first().label.lowercase()}"
            them.lookingFor == me.lookingFor -> "Both after ${them.lookingFor.label.lowercase()}"
            interests.size == 1 -> "Both into ${interests.first().lowercase()}"
            (them.distanceKm ?: Int.MAX_VALUE) <= 15 -> "Close by"
            else -> "A wildcard"
        }
    }
}

/** A sheet with today's five picks; tapping one opens their profile with like and nope. */
@Composable
fun PicksSheet(visible: Boolean, picks: List<Pick>, fresh: Boolean, onOpen: (Profile) -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Today's picks ✨", style = EmberTheme.type.headline, color = colors.textPrimary)
        Text(
            if (fresh) "Chosen for you tonight. A fresh five lands tomorrow at 6 pm." else "Last night's five. Fresh picks land at 6 pm.",
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        if (picks.isEmpty()) {
            Text(
                "You've already seen today's picks. New ones arrive at 6 pm.",
                style = EmberTheme.type.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                items(picks, key = { it.profile.uid }) { pick -> PickTile(pick) { onOpen(pick.profile) } }
            }
        }
    }
}

@Composable
private fun PickTile(pick: Pick, onClick: () -> Unit) {
    val p = pick.profile
    Box(
        Modifier
            .width(148.dp)
            .height(200.dp)
            .pressable(onClick = onClick, pressedScale = 0.95f)
            .clip(EmberShapes.tile)
            .background(EmberTheme.colors.surfaceHigh),
    ) {
        EmberImage(p.firstPhoto?.url, p.firstPhoto?.blurHash, contentDescription = "${p.name}'s photo", modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text("${p.name}, ${p.age}", style = EmberTheme.type.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pick.reason, style = EmberTheme.type.caption, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.isFreeTonight()) FreeTonightBadge(Modifier.align(Alignment.TopStart).padding(8.dp), compact = true)
    }
}
