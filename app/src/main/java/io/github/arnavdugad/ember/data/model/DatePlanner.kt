package io.github.arnavdugad.ember.data.model

import kotlin.random.Random

/**
 * First-date ideas, picked on the device from both people's interests (the [Catalog.interests]
 * labels) and the city. No server, no model: a curated catalog and a little scoring.
 */
object DatePlanner {
    private class Idea(val emoji: String, val title: String, val detail: String, val interests: Set<String>)

    private fun idea(emoji: String, title: String, detail: String, vararg interests: String) =
        Idea(emoji, title, detail, interests.toSet())

    /** `{city}` in a detail becomes the city name (or "town"). */
    private val ideas = listOf(
        idea("🍸", "Rooftop cocktails at golden hour", "Find the best view in {city} and order something neither of you can pronounce.", "Cocktails"),
        idea("🎸", "A gig at a tiny venue", "Pick a local band neither of you knows and judge the encore together.", "Live music"),
        idea("🎧", "Late set, then sunrise snacks", "Dance till late, then refuel at the first place in {city} that opens.", "Techno", "Dancing"),
        idea("🎤", "Karaoke duet, no backing out", "Each of you picks the other's song. Commitment is mandatory.", "Karaoke"),
        idea("🥾", "Sunrise trail + chai after", "An easy trail outside {city}, then hot chai while your legs recover.", "Hiking"),
        idea("🧗", "Bouldering and smoothies", "Spot each other on the beginner wall, then argue about the best route.", "Climbing"),
        idea("🧘", "Sunset yoga in the park", "Bring two mats and zero expectations of flexibility.", "Yoga"),
        idea("🏃", "Easy 5K, then pancakes", "Conversation pace only. The pancakes are the finish line.", "Running"),
        idea("🍝", "Cook-off for two", "Shop for ingredients together, then make one dish each. The loser does the dishes.", "Cooking"),
        idea("🍷", "Blind wine tasting", "Order a flight, hide the labels and guess the prices.", "Wine"),
        idea("☕", "Café hop", "Three coffee spots in {city}, one small cup each. Crown a winner.", "Coffee"),
        idea("🥞", "Long, lazy brunch", "Somewhere with bottomless coffee and nowhere to be after.", "Brunch"),
        idea("🌮", "Street food crawl", "Five stalls, share everything, rank as you go.", "Street food"),
        idea("🗺️", "Plan a trip you might take", "Pick a dream destination over drinks and build the itinerary on a napkin.", "Travel"),
        idea("🚗", "Day trip with a shared playlist", "Each of you adds ten songs. Aim for a town an hour out of {city}.", "Road trips"),
        idea("⛺", "Stargazing picnic", "A blanket, snacks and a star app somewhere dark outside {city}.", "Camping", "Astrology"),
        idea("📸", "Photo walk", "Wander {city}'s oldest streets with one rule: take each other's best photo.", "Photography"),
        idea("🖼️", "Gallery date + hot takes", "Each of you picks the piece you'd steal, then defends it.", "Art galleries"),
        idea("🎬", "Indie film, then a debrief", "A late screening, then dessert and a very honest review.", "Film"),
        idea("🍜", "Ramen + anime pitches", "Slurp ramen and pitch each other the one series they have to watch.", "Anime"),
        idea("🕹️", "Arcade rematch", "Retro arcade, best of five. The winner picks the next spot.", "Gaming"),
        idea("🎲", "Board game café", "Pick a game neither of you knows and learn it together.", "Board games"),
        idea("📚", "Bookshop swap", "Twenty minutes to pick a book for each other, then coffee to compare.", "Reading"),
        idea("🖋️", "Open mic night", "Find an open mic in {city}. Snapping is encouraged; reading is optional.", "Poetry", "Comedy"),
        idea("👗", "Style each other", "Hit the shops and choose one outfit for the other person to try on.", "Fashion"),
        idea("🛍️", "Thrift challenge", "Fixed budget: find each other the most iconic piece in the store.", "Thrifting", "Fashion"),
        idea("✒️", "Flash-sheet browsing", "Visit a tattoo studio's flash wall and pick matching ones you'll never get. Probably.", "Tattoos"),
        idea("💃", "Beginners' salsa class", "Nobody is good on the first night, which is the whole point.", "Dancing"),
        idea("🎪", "Night market wander", "Lights, food stalls and a ride on whatever spins.", "Festivals", "Street food"),
        idea("😂", "Comedy club, front row-ish", "Close enough to laugh, far enough not to get roasted.", "Comedy"),
        idea("🎙️", "Walk + podcast swap", "Each of you queues a favourite episode for the walk, then you compare notes.", "Podcasts"),
        idea("🔮", "Birth charts over dessert", "Pull up both charts and decide whether the stars approve.", "Astrology"),
        idea("🐶", "Dog park meet-cute", "Bring a dog, borrow a dog, or just rate everyone else's.", "Dogs"),
        idea("🐱", "Cat café", "Coffee with a side of being ignored by very fluffy judges.", "Cats"),
        idea("🌿", "Botanical garden stroll", "Find the weirdest plant and name it after each other.", "Plants"),
        idea("🏖️", "Beach sunset + fries", "Feet in the sand, fries from the nearest shack, no phones until dark.", "Beach"),
        idea("⛷️", "Slopes, then hot chocolate", "Hit the slopes (or just the lodge) and warm up with cocoa.", "Skiing"),
        idea("🏄", "Beginner surf lesson", "Wipe out together, then breakfast burritos.", "Surfing", "Beach"),
        idea("⚽", "The big match at a pub", "Back opposite teams for maximum banter.", "Football"),
        idea("🏀", "Pickup game, then milkshakes", "Shoot around at a public court. The loser buys the shakes.", "Basketball"),
    )

    /** For when you have little in common, or haven't filled in interests yet. */
    private val classics = listOf(
        idea("🍦", "Ice cream walk", "Two scoops, the long way round {city}."),
        idea("🌅", "Golden hour picnic", "Sandwiches, a blanket and the best viewpoint in {city}."),
        idea("🧩", "Escape room", "Sixty minutes to find out how you two work under pressure."),
        idea("🍕", "Slice crawl", "Three pizzerias in {city}, one slice each, a very serious verdict."),
    )

    const val MIN_IDEAS = 3
    const val MAX_IDEAS = 5

    /**
     * Three to five ideas, best first: an idea scores two points for each interest you share and
     * one for an interest only one of you has. Ties shuffle with [random], so asking again gives
     * a fresh mix. Classics fill in when there aren't enough matches.
     */
    fun suggest(mine: Collection<String>, theirs: Collection<String>, city: String, random: Random = Random.Default): List<DateIdea> {
        val shared = mine.toSet() intersect theirs.toSet()
        val either = mine.toSet() union theirs.toSet()
        val scored = ideas
            .map { it to it.interests.sumOf { tag -> if (tag in shared) 2 else if (tag in either) 1 else 0 } }
            .filter { (_, score) -> score > 0 }
            .shuffled(random)
            .sortedByDescending { (_, score) -> score }
            .map { (idea, _) -> idea }
        val count = scored.size.coerceIn(MIN_IDEAS, MAX_IDEAS)
        val picked = (scored + classics.shuffled(random)).take(count)
        val place = city.trim().ifBlank { "town" }
        return picked.map { DateIdea(title = it.title, detail = it.detail.replace("{city}", place), emoji = it.emoji) }
    }
}
