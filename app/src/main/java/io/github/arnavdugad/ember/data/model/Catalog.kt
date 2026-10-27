package io.github.arnavdugad.ember.data.model

/** A tag with a stable storage key and a display label. */
data class Tag(val key: String, val label: String, val emoji: String)

/** A profile prompt. Cheeky and suggestive, never explicit. */
data class PromptTemplate(val id: String, val question: String)

/**
 * The fixed vocabularies Ember uses. Keys are what is stored in Firestore and must match the
 * seed script (firebase/seed/prompts.json mirrors [prompts]).
 */
object Catalog {
    const val TERMS_VERSION = "2026-10"

    val vibes = listOf(
        Tag("flirty", "Flirty", "😘"),
        Tag("adventurous", "Adventurous", "🧭"),
        Tag("night_owl", "Night owl", "🦉"),
        Tag("romantic", "Romantic", "🌹"),
        Tag("playful", "Playful", "🎲"),
        Tag("spontaneous", "Spontaneous", "⚡"),
        Tag("homebody", "Homebody", "🛋️"),
        Tag("foodie", "Foodie", "🍜"),
        Tag("gym_rat", "Gym rat", "🏋️"),
        Tag("creative", "Creative", "🎨"),
        Tag("chill", "Chill", "🌊"),
        Tag("wanderlust", "Wanderlust", "✈️"),
    )

    val interests = listOf(
        "Cocktails", "Live music", "Techno", "Karaoke", "Hiking", "Climbing", "Yoga", "Running",
        "Cooking", "Wine", "Coffee", "Brunch", "Street food", "Travel", "Road trips", "Camping",
        "Photography", "Art galleries", "Film", "Anime", "Gaming", "Board games", "Reading", "Poetry",
        "Fashion", "Thrifting", "Tattoos", "Dancing", "Festivals", "Comedy", "Podcasts", "Astrology",
        "Dogs", "Cats", "Plants", "Beach", "Skiing", "Surfing", "Football", "Basketball",
    )

    /** Shown only to matches. Boundaries are about consent; turn-ons are about chemistry. */
    val boundaries = listOf(
        Tag("ask_first", "Ask before sending pics", "✋"),
        Tag("slow_burn", "Slow burn", "🕯️"),
        Tag("meet_public", "First meet in public", "☕"),
        Tag("no_pressure", "No pressure, ever", "🕊️"),
        Tag("sober_dates", "Sober dates", "🧃"),
        Tag("respect_no", "No means no", "🛑"),
        Tag("discreet", "Discretion matters", "🤫"),
        Tag("check_in", "Check in often", "💬"),
    )

    val turnOns = listOf(
        Tag("confidence", "Confidence", "😎"),
        Tag("banter", "Witty banter", "🗣️"),
        Tag("eye_contact", "Eye contact", "👀"),
        Tag("good_smell", "Smelling amazing", "🌸"),
        Tag("dancing", "Someone who dances", "💃"),
        Tag("kindness", "Kindness", "💛"),
        Tag("ambition", "Ambition", "🚀"),
        Tag("cooks", "A good cook", "🍳"),
        Tag("voice", "A great voice", "🎙️"),
        Tag("tattoos", "Tattoos", "🖋️"),
    )

    val prompts = listOf(
        PromptTemplate("p_guilty_pleasure", "My most irresistible guilty pleasure is"),
        PromptTemplate("p_first_date_dare", "Dare me to do this on our first date"),
        PromptTemplate("p_flirt_style", "My flirting style is"),
        PromptTemplate("p_green_flag", "The fastest way to get my attention"),
        PromptTemplate("p_late_night", "Ask me what I'm doing at 2 a.m."),
        PromptTemplate("p_wild_story", "My wildest story starts with"),
        PromptTemplate("p_kiss_review", "Rate my kissing on a scale of"),
        PromptTemplate("p_cheeky_secret", "A cheeky secret I'll tell you over drinks"),
        PromptTemplate("p_unpopular_opinion", "My most unpopular opinion"),
        PromptTemplate("p_perfect_night", "My perfect night out ends with"),
        PromptTemplate("p_weakness", "I'm weak for"),
        PromptTemplate("p_bold_move", "The boldest move I've ever made"),
        PromptTemplate("p_never_have_i", "Never have I ever"),
        PromptTemplate("p_trouble", "We'll get along if you're the kind of trouble that"),
        PromptTemplate("p_pickup_line", "Use your best pickup line on me, here's mine"),
        PromptTemplate("p_spice_level", "My spice level, in food and in life"),
        PromptTemplate("p_dance_floor", "On the dance floor I'm"),
        PromptTemplate("p_type", "My type, in three words"),
        PromptTemplate("p_red_flag", "A red flag I secretly find attractive"),
        PromptTemplate("p_chemistry", "I know there's chemistry when"),
        PromptTemplate("p_tease", "I'll tease you about"),
        PromptTemplate("p_after_midnight", "After midnight I become"),
        PromptTemplate("p_simple_pleasures", "Simple pleasures that hit different"),
        PromptTemplate("p_two_truths", "Two truths and a lie"),
        PromptTemplate("p_win_me_over", "Win me over with"),
        PromptTemplate("p_room_vibe", "Walk into my room and you'll find"),
        PromptTemplate("p_fantasy_trip", "Let's run away together to"),
        PromptTemplate("p_confession", "Confession time"),
        PromptTemplate("p_love_language", "My love language is"),
        PromptTemplate("p_dating_me", "Dating me is like"),
        PromptTemplate("p_bucket_list", "Something on my naughty-but-legal bucket list"),
        PromptTemplate("p_compliment", "The compliment that always works on me"),
        PromptTemplate("p_temptation", "I can resist everything except"),
        PromptTemplate("p_signature_move", "My signature move"),
        PromptTemplate("p_hot_take", "My hot take on first dates"),
        PromptTemplate("p_sunday", "A lazy Sunday with me looks like"),
        PromptTemplate("p_show_off", "I'll show off my skills in"),
        PromptTemplate("p_wink", "Swipe right if you"),
        PromptTemplate("p_rule_break", "A rule I love to break"),
        PromptTemplate("p_whisper", "Whisper this to me and I'm yours"),
    )

    private val promptById = prompts.associateBy { it.id }

    fun prompt(id: String): PromptTemplate? = promptById[id]

    fun vibe(key: String): Tag? = vibes.firstOrNull { it.key == key }

    fun boundary(key: String): Tag? = boundaries.firstOrNull { it.key == key }

    fun turnOn(key: String): Tag? = turnOns.firstOrNull { it.key == key }

    /** Playful, consent-forward Truth or Dare cards for the chat icebreaker. */
    val truths = listOf(
        "What was your first impression of me?",
        "What's the most romantic thing you've ever done?",
        "What's your biggest turn-on in a first message?",
        "What's the boldest thing you've done on a date?",
        "Which song would be the soundtrack to our first date?",
        "What's something you've never told a match before?",
        "Describe your perfect kiss in three words.",
        "What's the cheesiest line that actually worked on you?",
        "What's the most spontaneous trip you've ever taken?",
        "What's a secret talent nobody expects from you?",
        "What do you notice first about someone?",
        "What's your most embarrassing dating story?",
        "Where would you take me for a late-night adventure?",
        "What's the flirtiest text you've ever sent?",
        "What would make you blush on a date?",
    )

    val dares = listOf(
        "Send your best (clean) pickup line.",
        "Describe your outfit for our first date.",
        "Send a voice-note-worthy compliment in text.",
        "Plan our first date in exactly five emojis.",
        "Tell me the song you'd slow dance to with me.",
        "Write me a two-line poem.",
        "Send the last photo in your camera roll that isn't a screenshot (only if it's safe to share!).",
        "Guess my coffee order.",
        "Tell me one thing you'd whisper to me on a dance floor.",
        "Rate my profile out of ten, honestly.",
        "Name the city we should get lost in together.",
        "Describe me using only a movie title.",
        "Tell me your go-to karaoke song and promise to sing it.",
        "Give me a nickname.",
        "Send your most chaotic emoji combo.",
    )

    val genders = Gender.entries
}
