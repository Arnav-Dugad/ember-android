package io.github.arnavdugad.ember.ui.legal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet

enum class LegalDoc(val title: String, val sections: List<Pair<String, String>>) {
    Guidelines(
        "Community Guidelines",
        listOf(
            "Adults only" to "Ember is strictly 18+. We verify age at sign-up and remove anyone who appears to be under 18. Report it if you suspect someone is.",
            "Consent, always" to "Flirting is welcome; pressure isn't. Ask before sending anything intimate, respect a no the first time, and never share someone's private photos or messages.",
            "Keep public photos public-friendly" to "Profile photos must be non-explicit. Private album photos are only visible to people you approve, and you can revoke access any time.",
            "Be real" to "Use your own photos and your real age. Impersonation, catfishing and fake profiles are removed. Demo profiles are clearly badged and never message you.",
            "No hate, harassment or scams" to "No bullying, hate speech, threats, spam, selling or soliciting money. One report from you is enough for us to take a look.",
            "Meet safely" to "Meet in public first, tell a friend where you're going, and arrange your own transport. Trust your gut: unmatching and blocking are always one tap away.",
        ),
    ),
    Terms(
        "Terms of Service",
        listOf(
            "Eligibility" to "You must be at least 18 years old and legally able to agree to these terms. Accounts found to belong to minors are disabled and deleted.",
            "Your content" to "You own what you post. You give Ember permission to display it to other members as the app intends: public photos to everyone, private photos only to people you approve.",
            "Conduct" to "You agree to follow the Community Guidelines. We may remove content or accounts that break them.",
            "Privacy" to "We store your location at city level only and strip location data from uploaded photos. You can delete your account and all your data at any time from Settings.",
            "No guarantees" to "Ember is provided as is. We can't vet every member, so use your judgment, especially when meeting in person.",
        ),
    ),
    Safety(
        "Safety tips",
        listOf(
            "Take your time" to "Chat in the app until you feel comfortable. Anyone who rushes you to move off the app is a red flag.",
            "Guard your details" to "Don't share your home address, workplace, financial details or anything you wouldn't want public.",
            "Video before you meet" to "A quick video call is the easiest way to confirm someone is who they say they are.",
            "Public first" to "Meet somewhere busy, tell a friend the plan, and keep control of how you get home.",
            "Private albums" to "Only approve people you trust, and revoke access whenever you like. Screenshots are blocked on private content, but no app can stop a second camera.",
        ),
    ),
}

@Composable
fun LegalSheet(doc: LegalDoc?, onDismiss: () -> Unit) {
    GlassBottomSheet(visible = doc != null, onDismiss = onDismiss) {
        if (doc == null) return@GlassBottomSheet
        val colors = EmberTheme.colors
        Text(doc.title, style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            doc.sections.forEach { (heading, body) ->
                Text(heading, style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(4.dp))
                Text(body, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
