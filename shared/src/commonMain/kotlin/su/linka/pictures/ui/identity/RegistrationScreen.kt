package su.linka.pictures.ui.identity

import su.linka.pictures.identity.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import su.linka.pictures.ui.app.ProductSection
import su.linka.pictures.ui.theme.LinkaTheme
import kotlinx.coroutines.delay

@Composable
internal fun RegistrationScreen(identity: IdentitySession, reauthenticate: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val state by identity.view.collectAsState()
    val initialRevision = remember(identity) { state.registrationRevision }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var representative by remember { mutableStateOf(false) }
    var now by remember(identity) { mutableStateOf(identity.device.nowMillis()) }
    LaunchedEffect(identity, state.retryAt, state.resendAt) {
        now = identity.device.nowMillis()
        val deadline = maxOf(state.retryAt, state.resendAt)
        while (now < deadline) {
            delay(minOf(1000, deadline - now))
            now = identity.device.nowMillis()
        }
    }
    val representativeLabel = stringResource(Res.string.identity_representative)
    LinkaTheme {
        Surface(modifier) {
            Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(Res.string.identity_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.h5)
                Text(stringResource(Res.string.identity_explanation))
                PrivacyLinks()
                when (if (reauthenticate && state.registrationRevision > initialRevision) RegistrationPhase.REGISTERED
                    else if (reauthenticate && state.phase == RegistrationPhase.REGISTERED)
                    if (state.hasChallenge) RegistrationPhase.CHALLENGE else RegistrationPhase.REQUIRED else state.phase) {
                    RegistrationPhase.LOADING -> CircularProgressIndicator()
                    RegistrationPhase.RECOVERY -> {
                        Text(stringResource(Res.string.identity_recovery), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = identity::retryLoad) { Text(stringResource(Res.string.identity_retry)) }
                    }
                    RegistrationPhase.REQUIRED -> {
                        OutlinedTextField(email, { email = it }, label = { Text(stringResource(Res.string.identity_email)) },
                            singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        Row {
                            Checkbox(representative, { representative = it }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = representativeLabel })
                            Text(representativeLabel)
                        }
                        Button(onClick = { identity.begin(email, if (representative) "representative" else "self"); email = "" },
                            enabled = !state.busy && now >= state.retryAt && now >= state.resendAt && email.isNotBlank()) { Text(stringResource(Res.string.identity_send)) }
                    }
                    RegistrationPhase.CHALLENGE -> {
                        Text(stringResource(if (state.challengeStatus == "queued" || state.challengeStatus == null)
                            Res.string.identity_queued else if (state.challengeStatus == "pending") Res.string.identity_pending else Res.string.identity_new_code),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        state.expiresAt?.let { Text(stringResource(Res.string.identity_deadline, it)) }
                        state.resendAfter?.let { Text(stringResource(Res.string.identity_resend_after, it)) }
                        Button(onClick = { identity.begin("", "self", poll = true) }, enabled = !state.busy && now >= state.retryAt) {
                            Text(stringResource(Res.string.identity_check_status))
                        }
                        Button(onClick = { identity.resend(); code = "" }, enabled = !state.busy && !state.hasReceivedProof && now >= state.resendAt &&
                            state.challengeStatus in setOf("queued", "pending", "verified", "delivery_failed", "expired", "locked", "cancelled")) {
                            Text(stringResource(Res.string.identity_resend))
                        }
                        if (state.challengeStatus == "pending") {
                            OutlinedTextField(code, { if (it.length <= 6 && it.all { c -> c in '0'..'9' }) code = it },
                                label = { Text(stringResource(Res.string.identity_code)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !state.busy)
                            Button(onClick = { identity.verify(code); code = "" }, enabled = !state.busy && now >= state.retryAt && code.length == 6) {
                                Text(stringResource(Res.string.identity_verify))
                            }
                        }
                        TextButton(onClick = identity::resumeVerification, enabled = !state.busy) { Text(stringResource(Res.string.identity_restore_proof)) }
                        TextButton(onClick = { identity.changeEmail(); code = "" }, enabled = !state.busy) { Text(stringResource(Res.string.identity_change_email)) }
                    }
                    RegistrationPhase.REGISTERED -> Text(stringResource(Res.string.identity_verified), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (state.busy) CircularProgressIndicator()
                if (state.resendAt > now) Text(stringResource(Res.string.identity_wait, (state.resendAt - now - 1) / 1000 + 1),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.retryAt > now) Text(stringResource(Res.string.identity_retry_deadline, timestamp(state.retryAt)),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.problem) Text(stringResource(Res.string.identity_problem), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
internal fun TelemetrySettings(identity: IdentitySession) {
    val state by identity.view.collectAsState()
    var reauthenticate by remember { mutableStateOf(false) }
    var confirmDeletion by remember { mutableStateOf(false) }
    val telemetryLabel = stringResource(Res.string.telemetry_title)
    ProductSection(stringResource(Res.string.identity_online_title)) {
        Text(stringResource(Res.string.identity_online_explanation))
        TextButton(onClick = { reauthenticate = true }, enabled = !state.busy && state.deletionStatus in setOf(null, "not_found")) {
            Text(stringResource(if (state.phase == RegistrationPhase.REGISTERED) Res.string.identity_online_restore else Res.string.identity_online_register))
        }
        if (state.phase == RegistrationPhase.REGISTERED && state.deletionStatus == null) {
            TextButton(onClick = { confirmDeletion = true }, enabled = !state.busy) { Text(stringResource(Res.string.identity_delete_account)) }
        }
        if (state.problem && state.deletionStatus == null) Text(stringResource(Res.string.identity_deletion_problem))
        state.deletionStatus?.let { status ->
            Text(stringResource(when (status) {
                "requested" -> Res.string.identity_deletion_requested
                "processing" -> Res.string.identity_deletion_processing
                "completed" -> Res.string.identity_deletion_completed
                "rejected" -> Res.string.identity_deletion_rejected
                "cancelled" -> Res.string.identity_deletion_cancelled
                "not_found" -> Res.string.identity_deletion_not_found
                else -> Res.string.identity_deletion_unconfirmed
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (status in setOf("completed", "rejected", "cancelled")) {
                TextButton(onClick = identity::dismissAccountDeletion, enabled = !state.busy) { Text(stringResource(Res.string.close)) }
            } else {
                TextButton(onClick = identity::checkAccountDeletion, enabled = !state.busy) { Text(stringResource(Res.string.identity_deletion_check)) }
                if (status == "not_found") TextButton(onClick = { confirmDeletion = true }, enabled = !state.busy) {
                    Text(stringResource(Res.string.identity_deletion_retry))
                }
            }
            if (state.problem) Text(stringResource(Res.string.identity_deletion_problem))
        }
    }
    if (confirmDeletion) su.linka.pictures.ui.app.ProductDialog(stringResource(Res.string.identity_delete_account),
        { confirmDeletion = false }, actions = {
            TextButton(onClick = { confirmDeletion = false; identity.requestAccountDeletion() }, enabled = !state.busy) {
                Text(stringResource(Res.string.identity_delete_account))
            }
            TextButton(onClick = { confirmDeletion = false }) { Text(stringResource(Res.string.cancel)) }
        }) { Text(stringResource(Res.string.identity_deletion_explanation)) }
    if (reauthenticate) su.linka.pictures.ui.app.ProductDialog(stringResource(Res.string.identity_online_title), { reauthenticate = false }, actions = {
        TextButton(onClick = { reauthenticate = false }) { Text(stringResource(Res.string.close)) }
    }) { RegistrationScreen(identity, reauthenticate = state.phase == RegistrationPhase.REGISTERED, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) }
    ProductSection(stringResource(Res.string.telemetry_title)) {
        Text(stringResource(Res.string.telemetry_explanation))
        Row {
            Switch(checked = state.consent == "allowed", onCheckedChange = identity.telemetry::setConsent,
                enabled = state.phase == RegistrationPhase.REGISTERED && state.deletionStatus == null,
                modifier = Modifier.semantics { contentDescription = telemetryLabel })
            Text(stringResource(if (state.consentConflict) Res.string.telemetry_unconfirmed else if (state.consent == "allowed") Res.string.telemetry_on else Res.string.telemetry_off))
        }
        if (state.consentPending) Text(stringResource(Res.string.telemetry_pending), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.consentConflict) Text(stringResource(Res.string.telemetry_conflict), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.consentConflict && state.phase == RegistrationPhase.REGISTERED && state.deletionStatus == null) {
            TextButton(onClick = { identity.telemetry.setConsent(false) }) { Text(stringResource(Res.string.telemetry_resolve_off)) }
            TextButton(onClick = { identity.telemetry.setConsent(true) }) { Text(stringResource(Res.string.telemetry_resolve_on)) }
        } else if (state.consent == "allowed" || state.consentPending || state.problem) TextButton(onClick = identity.telemetry::synchronize) { Text(stringResource(Res.string.identity_retry)) }
    }
    ProductSection(stringResource(Res.string.identity_privacy_title)) { PrivacyLinks() }
}

@Composable
private fun PrivacyLinks() {
    val uri = LocalUriHandler.current
    var problem by remember { mutableStateOf(false) }
    fun open(url: String) {
        try { uri.openUri(url); problem = false } catch (_: Exception) { problem = true }
    }
    Text(stringResource(Res.string.identity_privacy_summary))
    TextButton(onClick = { open("https://linka.su/privacy-policy/") }) { Text(stringResource(Res.string.identity_privacy_policy)) }
    TextButton(onClick = { open("mailto:feedback@linka.su") }) { Text(stringResource(Res.string.identity_privacy_contact)) }
    if (problem) Text(stringResource(Res.string.identity_link_problem), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
