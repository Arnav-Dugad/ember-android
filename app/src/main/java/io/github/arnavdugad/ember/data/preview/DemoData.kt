package io.github.arnavdugad.ember.data.preview

import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import kotlin.random.Random

/**
 * Demo people for offline Preview mode. Avatars are DiceBear "notionists" illustrations
 * (CC0, generated from a seed): no photos of real people, nothing explicit. Every profile is
 * flagged [Profile.isDemo] and wears a Demo badge.
 */
internal object DemoData {
    data class DemoPerson(val profile: Profile, val likesBack: Boolean, val matchOnly: MatchOnlyDetails)

    private val womenNames = listOf(
        "Ava", "Maya", "Sofia", "Zara", "Leila", "Nina", "Chloe", "Priya", "Amara", "Isla", "Mei", "Lucia",
        "Freya", "Yasmin", "Elena", "Noor", "Camila", "Hana", "Talia", "Ines", "Aisha", "Jade", "Rosa", "Kira",
    )
    private val menNames = listOf(
        "Leo", "Kai", "Mateo", "Omar", "Ethan", "Ravi", "Julian", "Theo", "Marcus", "Diego", "Kenji", "Felix",
        "Noah", "Andre", "Sami", "Luca", "Arjun", "Hugo", "Malik", "Elias", "Tomas", "Jonah", "Idris", "Rafael",
    )
    private val nonBinaryNames = listOf("River", "Sage", "Rowan", "Quinn", "Ari", "Remy", "Jules", "Emery", "Skyler", "Lane")

    private val cities = listOf(
        "Brooklyn", "Austin", "London", "Berlin", "Lisbon", "Barcelona", "Toronto", "Melbourne",
        "Amsterdam", "Mexico City", "Seoul", "Paris", "Chicago", "Los Angeles", "Mumbai",
    )

    private val bios = listOf(
        "Equal parts golden retriever and night owl. Will steal your fries and your hoodie.",
        "Here for good chemistry and better playlists. Bonus points if you can make me laugh mid-sip.",
        "Recovering overthinker, full-time flirt. Let's skip the small talk.",
        "Looking for a partner in crime for rooftop bars and 2 a.m. dumplings.",
        "I dance badly but confidently. Swipe right if that's your type.",
        "Spontaneous trips, strong coffee, stronger opinions about pizza toppings.",
        "Sunset chaser. Terrible at texting back, great at making plans.",
        "Ask me about my questionable karaoke record.",
        "Soft heart, sharp wit, dangerous dimples. Allegedly.",
        "Fluent in sarcasm and eyebrow raises. Let's see if we vibe.",
        "Plant parent, vinyl hoarder, chaos coordinator.",
        "Will plan the date, the playlist and the after-party.",
        "Bold enough to swipe first. Curious enough to see where it goes.",
        "Gym in the morning, cocktails at night, balance is everything.",
        "Here to have fun and keep it honest. Good vibes only, no games.",
    )

    private val answers = mapOf(
        "p_guilty_pleasure" to listOf("Reality TV with a glass of wine, judging everyone's choices", "Ordering dessert first", "Singing in the car like it's a sold-out show"),
        "p_first_date_dare" to listOf("Order for me without asking a single question", "Challenge me to a game of pool, loser buys round two", "Teach me your worst dance move"),
        "p_flirt_style" to listOf("Aggressively making eye contact, then looking away", "Roasting you until you blush", "Sending songs that say what I won't"),
        "p_late_night" to listOf("Deep chats about aliens and exes", "Raiding the fridge and pretending I'm on a cooking show", "Probably convincing you to get tacos"),
        "p_weakness" to listOf("Forearms and good manners", "A deep voice and a terrible pun", "Someone who remembers my coffee order"),
        "p_perfect_night" to listOf("Rooftop, city lights and a slow walk home", "Dancing until the lights come on", "Takeout, a movie and absolutely no plans"),
        "p_type" to listOf("Funny, curious, bold", "Kind, chaotic, tall-ish", "Confident but not cocky"),
        "p_red_flag" to listOf("You argue with the DJ about song choices", "You've never once been on time", "You flirt with the waiter to get extra bread"),
        "p_chemistry" to listOf("We lose track of time mid-conversation", "The banter writes itself", "You laugh at my worst jokes on purpose"),
        "p_after_midnight" to listOf("A philosopher with snacks", "Much braver than I should be", "The best dance partner you'll ever have"),
        "p_two_truths" to listOf("I've been skydiving, I can juggle, I've met a celebrity on a train", "I speak three languages, I hate chocolate, I once won a dance battle"),
        "p_win_me_over" to listOf("A handwritten note and a cheeky wink", "Matching my energy on the dance floor", "Bringing snacks without being asked"),
        "p_spice_level" to listOf("Extra hot, in every sense", "Mild on the plate, wild after midnight", "Medium, but I can be persuaded"),
        "p_whisper" to listOf("I already booked us a table", "Let's skip to dessert", "You pick the next song"),
    )

    private val backgrounds = listOf(
        "ffb38a,ff7aa8", "ffd6a5,ff8fab", "c8b6ff,ffb3c6", "ff9e7a,ffcf99",
        "a0c4ff,ffc6ff", "fbc4ab,f08080", "ffafcc,ffc8dd", "f9c6c9,c9a7eb",
    )

    fun avatarUrl(seed: String, background: String): String =
        "https://api.dicebear.com/9.x/notionists/svg?seed=$seed&backgroundType=gradientLinear&backgroundColor=$background"

    fun generate(count: Int = 72): List<DemoPerson> {
        val random = Random(42)
        return (0 until count).map { index ->
            val gender = when {
                index % 10 == 9 -> Gender.NonBinary
                index % 2 == 0 -> Gender.Woman
                else -> Gender.Man
            }
            val name = when (gender) {
                Gender.Woman -> womenNames[index / 2 % womenNames.size]
                Gender.Man -> menNames[index / 2 % menNames.size]
                Gender.NonBinary -> nonBinaryNames[index / 10 % nonBinaryNames.size]
            }
            val uid = "demo-preview-$index"
            val photos = (0 until 2 + random.nextInt(3)).map { n ->
                val seed = "$name$index-$n"
                Photo(id = "$uid-$n", url = avatarUrl(seed, backgrounds[(index + n) % backgrounds.size]))
            }
            val prompts = answers.keys.shuffled(random).take(2 + random.nextInt(2)).map { id ->
                ProfilePrompt(id, Catalog.prompt(id)?.question.orEmpty(), answers.getValue(id).random(random))
            }
            val lookingFor = LookingFor.entries[random.nextInt(LookingFor.entries.size)]
            val interestedIn = when (random.nextInt(4)) {
                0 -> Gender.entries.toSet()
                1 -> setOf(Gender.Woman, Gender.NonBinary)
                2 -> setOf(Gender.Man, Gender.NonBinary)
                else -> setOf(if (gender == Gender.Man) Gender.Woman else Gender.Man)
            }
            val profile = Profile(
                uid = uid,
                name = name,
                age = 21 + random.nextInt(20),
                gender = gender,
                interestedIn = interestedIn,
                bio = bios[random.nextInt(bios.size)],
                photos = photos,
                prompts = prompts,
                interests = Catalog.interests.shuffled(random).take(3 + random.nextInt(4)),
                vibes = Catalog.vibes.shuffled(random).take(2 + random.nextInt(3)).map { it.key },
                lookingFor = lookingFor,
                heightCm = 155 + random.nextInt(40),
                city = cities[random.nextInt(cities.size)],
                distanceKm = 1 + random.nextInt(60),
                afterDark = lookingFor.isAdventurous || random.nextInt(3) == 0,
                privateAlbumCount = if (random.nextInt(3) == 0) 2 + random.nextInt(5) else 0,
                isDemo = true,
                onboarded = true,
            )
            DemoPerson(
                profile = profile,
                likesBack = random.nextInt(100) < 55,
                matchOnly = MatchOnlyDetails(
                    boundaries = Catalog.boundaries.shuffled(random).take(2).map { it.key },
                    turnOns = Catalog.turnOns.shuffled(random).take(3).map { it.key },
                ),
            )
        }
    }
}
