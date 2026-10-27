package io.github.arnavdugad.ember.ui.profile

import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.ui.onboarding.SetupStep

/** Where in Edit profile a tip's button lands. */
enum class EditSection(val key: String) {
    Photos("photos"),
    About("about"),
    Voice("voice"),
    Prompts("prompts"),
    Vibes("vibes"),
    Location("location"),
    ;

    /** The editor section it lives in (the voice intro is part of "About you"). */
    val step: SetupStep
        get() = when (this) {
            Photos -> SetupStep.Photos
            About, Voice -> SetupStep.About
            Prompts -> SetupStep.Prompts
            Vibes -> SetupStep.Vibes
            Location -> SetupStep.Location
        }

    companion object {
        fun of(key: String?): EditSection? = entries.firstOrNull { it.key == key }
    }
}

/** The single most valuable next step, with the label for its button. */
data class StrengthTip(val text: String, val action: String, val section: EditSection)

/** How complete a profile is, and what to do next. Pure, so it's unit-tested. */
object ProfileStrength {
    private class Check(val done: (Profile) -> Boolean, val tip: (Profile) -> StrengthTip)

    /** In order of how much each one helps: the first unmet check is the tip shown. */
    private val checks = listOf(
        Check({ it.photos.size >= 2 }) { StrengthTip("Add a 2nd photo. Profiles with 3+ photos get 2× more likes.", "Add a photo", EditSection.Photos) },
        Check({ it.prompts.isNotEmpty() }) { StrengthTip("Answer a prompt. It's the easiest opener you'll ever give someone.", "Answer a prompt", EditSection.Prompts) },
        Check({ it.photos.size >= 3 }) { StrengthTip("Add a 3rd photo. Profiles with 3+ photos get 2× more likes.", "Add a photo", EditSection.Photos) },
        Check({ it.bio.length >= 40 }) { p ->
            StrengthTip(if (p.bio.isBlank()) "Write a short bio. Two lines is plenty." else "Say a little more in your bio.", "Edit bio", EditSection.About)
        },
        Check({ it.hasVoice }) { StrengthTip("Turn on your voice intro. Thirty seconds of you beats any bio.", "Record intro", EditSection.Voice) },
        Check({ it.prompts.size >= 2 }) { StrengthTip("Answer a second prompt. More ways in, more conversations.", "Answer a prompt", EditSection.Prompts) },
        Check({ it.vibes.size >= 2 }) { StrengthTip("Pick a couple of vibes so people know your energy.", "Pick vibes", EditSection.Vibes) },
        Check({ it.interests.size >= 3 }) { StrengthTip("Add 3 interests. Shared ones put you in more daily picks.", "Add interests", EditSection.Vibes) },
        Check({ it.heightCm != null }) { StrengthTip("Add your height. It's one of the first things people filter on.", "Add height", EditSection.About) },
        Check({ it.city.isNotBlank() }) { StrengthTip("Add your city so people nearby can find you.", "Add city", EditSection.Location) },
    )

    /** 0..1, the share of checks met. */
    fun completeness(profile: Profile): Float = checks.count { it.done(profile) } / checks.size.toFloat()

    /** Null at 100%. */
    fun nextTip(profile: Profile): StrengthTip? = checks.firstOrNull { !it.done(profile) }?.tip?.invoke(profile)
}
