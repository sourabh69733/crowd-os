package org.freegram.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freegram.shared.model.FreegramUi
import org.freegram.shared.model.PlatformActions

private const val MIN_PASSWORD = 10

/** First launch: what Freegram is, the public-posts warning, a name, and an encrypted backup. */
@Composable
fun OnboardingFlow(ui: FreegramUi, platform: PlatformActions) {
    val c = Fg.colors
    var step by rememberSaveable { mutableStateOf(0) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(c.paper).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Dots(step)
        when {
            restoring -> RestoreStep(ui, onBack = { restoring = false })
            step == 0 -> Intro(
                big = "News that travels without the internet",
                body = "Post what's happening around you. When the network is down, phones nearby pass posts along, and they reach the internet once someone is back online.",
                cta = "Get started", onNext = { step = 1 },
            )
            step == 1 -> Intro(
                big = "Everything you post is public",
                body = "Anyone who receives a post can copy it anywhere, including the internet. There are no private posts. Don't post anything you'd regret others seeing.",
                cta = "I understand and agree", onNext = { step = 2 }, warning = true,
                extra = {
                    RulesSummary()
                    TermsList()
                },
            )
            step == 2 -> NameStep(ui, onNext = { step = 3 }, onRestore = { restoring = true })
            else -> BackupStep(ui, platform)
        }
    }
}

@Composable
private fun Dots(step: Int) {
    val c = Fg.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(4) { i ->
            Box(Modifier.height(7.dp).width(if (i == step) 20.dp else 7.dp).clip(RoundedCornerShape(7.dp)).background(if (i == step) c.teal else c.line))
        }
    }
}

@Composable
private fun Intro(big: String, body: String, cta: String, onNext: () -> Unit, warning: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    val c = Fg.colors
    Spacer(Modifier.height(40.dp))
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(if (warning) c.amberSoft else c.tealSoft), contentAlignment = Alignment.Center) {
        Text(if (warning) "!" else "F", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = if (warning) c.amber else c.teal)
    }
    Text(big, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
    Text(body, fontSize = 16.sp, color = c.ink2)
    extra?.invoke()
    Spacer(Modifier.height(24.dp))
    FgButton(cta, onNext)
}

@Composable
private fun NameStep(ui: FreegramUi, onNext: () -> Unit, onRestore: () -> Unit) {
    val c = Fg.colors
    var name by rememberSaveable { mutableStateOf(ui.me.name) }
    Text("Choose a name", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
    Text("Freegram has made an ID for you on this phone. No phone number or email is used. Your name is only a label; it doesn't prove who you are, so others also see the end of your ID: ${ui.me.tag}.",
        fontSize = 15.sp, color = c.ink2)
    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name shown on your posts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FgButton("Continue", onClick = { if (name.isNotBlank()) ui.setName(name.trim()); onNext() }, enabled = ui.ready)
    FgButton("I already have a backup", onClick = onRestore, secondary = true)
}

@Composable
private fun BackupStep(ui: FreegramUi, platform: PlatformActions) {
    val c = Fg.colors
    var pw by rememberSaveable { mutableStateOf("") }
    var pw2 by rememberSaveable { mutableStateOf("") }
    Text("Back up your ID now", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
    val backup = ui.freshBackup
    if (backup == null) {
        Text("If you lose this phone or delete the app without a backup, your ID is gone for good. Choose a password; you'll get a backup code to save somewhere safe.",
            fontSize = 15.sp, color = c.ink2)
        OutlinedTextField(pw, { pw = it }, label = { Text("Password (at least $MIN_PASSWORD characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (pw2.isNotEmpty() && pw != pw2) Text("Passwords don't match.", color = c.red, fontSize = 13.sp)
        FgButton(if (ui.busy) "Encrypting…" else "Create backup", onClick = { ui.createBackup(pw) },
            enabled = !ui.busy && pw.length >= MIN_PASSWORD && pw == pw2)
        FgButton("Remind me later", onClick = ui::finishOnboarding, secondary = true)
    } else {
        Text("Save this backup code somewhere other than this phone, for example a note to yourself or a password manager. You'll need it and your password to restore your ID.",
            fontSize = 15.sp, color = c.ink2)
        Text(backup, fontSize = 12.sp, color = c.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp))
        FgButton("Copy backup code", onClick = { platform.copyText("Freegram backup", backup) }, secondary = true)
        FgButton("Done", onClick = ui::finishOnboarding)
    }
}

@Composable
private fun RestoreStep(ui: FreegramUi, onBack: () -> Unit) {
    val c = Fg.colors
    var code by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    Text("Restore your ID", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
    Text("Paste the backup code you saved and enter its password.", fontSize = 15.sp, color = c.ink2)
    OutlinedTextField(code, { code = it.trim() }, label = { Text("Backup code (ncryptsec1…)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    FgButton(if (ui.busy) "Restoring…" else "Restore", onClick = { ui.restoreBackup(code, pw) },
        enabled = !ui.busy && code.startsWith("ncryptsec1", ignoreCase = true) && pw.isNotEmpty())
    FgButton("Back", onClick = onBack, secondary = true)
}
