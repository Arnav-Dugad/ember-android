package io.github.arnavdugad.ember.data.preview

import android.content.Context
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

@Serializable
internal data class Portrait(val file: String, val gender: String)

@Serializable
internal data class Bios(val openers: List<String>, val closers: List<String>)

/**
 * Copy shared with the Firebase seed script (firebase/seed/shared, packaged as assets), so
 * Preview profiles read exactly like seeded ones.
 */
internal class DemoContent(
    val answers: Map<String, List<String>>,
    val bios: Bios,
    val portraits: List<Portrait>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): DemoContent {
            fun read(name: String): String? = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
            val answers = read("answers.json")?.let { text ->
                (json.parseToJsonElement(text) as JsonObject)
                    .filterValues { it is JsonArray }
                    .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            }.orEmpty()
            val bios = read("bios.json")?.let { json.decodeFromString<Bios>(it) } ?: Bios(listOf("Here for good chemistry."), listOf("Say hi."))
            val portraits = read("portraits.json")?.let { json.decodeFromString<List<Portrait>>(it) }.orEmpty()
            return DemoContent(answers, bios, portraits)
        }
    }
}

/**
 * Demo people for offline Preview mode. Main photos are AI-generated portraits of people who
 * don't exist (served from this repository), the rest are DiceBear illustrations. Nobody here is
 * a real person, and every profile is flagged [Profile.isDemo] and wears a Demo badge.
 */
internal object DemoData {
    data class DemoPerson(val profile: Profile, val likesBack: Boolean, val matchOnly: MatchOnlyDetails)

    const val PORTRAIT_BASE = "https://cdn.jsdelivr.net/gh/Arnav-Dugad/ember-android@main/firebase/seed/portraits/"

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

    private val backgrounds = listOf(
        "ffb38a,ff7aa8", "ffd6a5,ff8fab", "c8b6ff,ffb3c6", "ff9e7a,ffcf99",
        "a0c4ff,ffc6ff", "fbc4ab,f08080", "ffafcc,ffc8dd", "f9c6c9,c9a7eb",
    )

    fun avatarUrl(seed: String, background: String): String =
        "https://api.dicebear.com/9.x/notionists/svg?seed=$seed&backgroundType=gradientLinear&backgroundColor=$background"

    fun generate(content: DemoContent, count: Int = 72): List<DemoPerson> {
        val random = Random(42)
        val portraitsByGender = content.portraits.groupBy { it.gender }
        val nextPortrait = mutableMapOf<String, Int>()
        val answerable = content.answers.keys.filter { Catalog.prompt(it) != null }
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
            val illustrated = (0 until 1 + random.nextInt(3)).map { n ->
                Photo(id = "$uid-$n", url = avatarUrl("$name$index-$n", backgrounds[(index + n) % backgrounds.size]))
            }
            // Each portrait is used once before any repeats, matched to the profile's gender.
            val pool = portraitsByGender[gender.key].orEmpty()
            val portrait = pool.takeIf { it.isNotEmpty() }?.let {
                val i = nextPortrait.getOrDefault(gender.key, 0)
                nextPortrait[gender.key] = i + 1
                it[i % it.size]
            }
            val photos = listOfNotNull(portrait?.let { Photo(id = "$uid-p", url = PORTRAIT_BASE + it.file) }) + illustrated
            val prompts = answerable.shuffled(random).take(2 + random.nextInt(2)).map { id ->
                ProfilePrompt(id, Catalog.prompt(id)?.question.orEmpty(), content.answers.getValue(id).random(random))
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
                bio = "${content.bios.openers.random(random)} ${content.bios.closers.random(random)}",
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
