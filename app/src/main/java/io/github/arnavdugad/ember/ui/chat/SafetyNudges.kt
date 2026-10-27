package io.github.arnavdugad.ember.ui.chat

/**
 * On-device scam heuristics for the chat. Nothing is sent anywhere and nothing is blocked: a
 * message the patterns flag only earns a gentle, dismissible banner. Some are deliberately narrow
 * ("bank account", not "bank") because a nudge that fires on every river-bank walk gets ignored.
 */
object SafetyNudges {
    private val options = setOf(RegexOption.IGNORE_CASE)

    private val money = Regex(
        listOf(
            """\b(?:money|cash|payment|pay ?me|paypal|venmo|cash ?app|zelle|western union|moneygram|wire transfer)\b""",
            """\bbank (?:account|details|transfer|card)\b""",
            """\b(?:account number|routing number|iban|ifsc|swift code)\b""",
            """\b(?:upi|gpay|google pay|phonepe|paytm|bhim)\b""",
            // UPI ids look like name@okaxis.
            """\b[\w.\-]+@(?:ok\w+|ybl|ibl|axl|apl|upi|paytm)\b""",
            """\bgift ?cards?\b|\b(?:itunes|apple|steam|google play|amazon) cards?\b""",
            """\b(?:crypto|bitcoin|btc|ethereum|usdt|binance|wallet address)\b""",
            // "Send me" only counts near money: "send me your playlist" is just flirting.
            """\bsend (?:me|over) (?:\S+ ){0,3}?(?:money|cash|funds|gift|\x24|₹|€|£|\d)""",
            """[\x24₹€£]\s?\d|\b\d+\s?(?:rs|inr|usd|dollars|bucks|rupees)\b""",
        ).joinToString("|"),
        options,
    )

    private val offPlatform = Regex(
        listOf(
            """\b(?:whats ?app|telegram|snap ?chat|signal|kik|wechat)\b""",
            """\b(?:wa\.me|t\.me)/""",
            """\b(?:add|text|message|dm|hit) me (?:up )?on\b""",
            """\bmy (?:snap|insta|ig|tg|telegram|whatsapp|number|cell|digits)\b""",
        ).joinToString("|"),
        options,
    )

    // Runs of digits with the usual separators; the digit count is checked separately.
    private val phoneCandidate = Regex("""\+?\d[\d\s\-.()]{7,}\d""")

    /** True when a message from the other person mentions money or moving off Ember. */
    fun isRisky(text: String): Boolean =
        text.isNotBlank() && (money.containsMatchIn(text) || offPlatform.containsMatchIn(text) || containsPhoneNumber(text))

    /** Ten to fifteen digits in one run, the shape of a phone number anywhere in the world. */
    fun containsPhoneNumber(text: String): Boolean =
        phoneCandidate.findAll(text).any { match -> match.value.count(Char::isDigit) in 10..15 }
}
