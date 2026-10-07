package org.freegram.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Content rules and terms, from docs/freegram/PRINCIPLES.md and docs/freegram/play/TERMS.md. Keep them in step. */
object Rules {
    const val VERSION = "1 (October 2026)"

    val notAllowed = listOf(
        "Child sexual abuse material. Zero tolerance.",
        "Fake content presented as real: doctored photos, invented events, false locations of help.",
        "Pretending to be someone else or an official body.",
        "Threats or calls for violence against people.",
        "Posting someone's address, phone number or ID documents, or exposing people to danger.",
        "Intimate images shared without consent.",
        "Hate that targets people for religion, caste, gender, ethnicity or similar.",
        "Scams, fraud and spam.",
    )

    val allowed = "Real events, criticism of anyone, dark humour, strong opinions and hard truths are allowed."

    val terms = listOf(
        "Everything you post is public and can be copied anywhere. Posts can't be recalled once shared.",
        "You are responsible for what you post. Follow the law where you live.",
        "Maintainers you follow can hide posts and people for you. They can't delete anything, and you can switch them off.",
        "Report posts that break these rules from a post's menu. Reports go privately to your maintainers.",
        "Freegram has no company server and keeps no account for you. Your ID lives on your phone; back it up.",
        "Freegram is an early test version, provided as is, without guarantees.",
    )
}

@Composable
fun RulesSummary() {
    val c = Fg.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Not allowed", fontWeight = FontWeight.Bold, color = c.ink, fontSize = 15.sp)
        Rules.notAllowed.forEach { Bullet(it) }
        Text(Rules.allowed, color = c.ink2, fontSize = 14.sp)
    }
}

@Composable
fun TermsList() {
    val c = Fg.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Terms", fontWeight = FontWeight.Bold, color = c.ink, fontSize = 15.sp)
        Rules.terms.forEach { Bullet(it) }
        Text("Version ${Rules.VERSION}", color = c.ink3, fontSize = 12.sp)
    }
}

@Composable
private fun Bullet(text: String) {
    val c = Fg.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = c.ink2, fontSize = 14.sp)
        Text(text, color = c.ink2, fontSize = 14.sp)
    }
}
