package com.jemcik.jemrec.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.BuildConfig
import com.jemcik.jemrec.crm.CrmState
import com.jemcik.jemrec.crm.api.Notice
import java.text.DateFormat
import java.util.Date

/**
 * Settings > Sales Engine: who is signed in, the notice, the work line, and
 * what reaches LANARS's CRM (specs/002, contracts/ui.md "Settings").
 *
 * Built from the rows the rest of Settings is, so it looks as they do in each
 * look. The notice is read in place, over the settings, with Back to leave it
 * unagreed.
 */
@Composable
fun SalesEngineSettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val c = vm.salesEngine
    DisposableEffect(Unit) {
        c.refresh()
        onDispose { c.closeNotice() }
    }
    val ui by c.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val permissions = rememberPermissionRequest { c.refresh() }
    if (ui.reading) {
        BackHandler { c.closeNotice() }
        NoticeContent(ui.notice, busy = ui.busy, onAgree = c::agree, modifier = modifier)
    } else {
        SalesEngineSettingsContent(
            ui = ui,
            onSignIn = { activity?.let(c::signIn) },
            onSignOut = c::signOut,
            onNotice = c::openNotice,
            onLine = c::chooseLine,
            onAskPhone = { permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) },
            onAskCallLog = { permissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)) },
            modifier = modifier,
        )
    }
}

@Composable
internal fun SalesEngineSettingsContent(
    ui: SalesEngineUi,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onNotice: () -> Unit,
    onLine: (com.jemcik.jemrec.crm.WorkLine.Sim) -> Unit,
    onAskPhone: () -> Unit,
    onAskCallLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = ui.name != null && ui.state != CrmState.NOT_CONNECTED && ui.state != CrmState.BLOCKED
    Column(modifier) {
        SettingsGroup("Account", note = ui.message) {
            if (!signedIn || ui.state == CrmState.NEEDS_SIGN_IN) {
                row {
                    SettingsRow(
                        title = if (ui.state == CrmState.NEEDS_SIGN_IN) "Sign in again" else "Sign in with Google",
                        summary = when (ui.state) {
                            CrmState.NOT_CONFIGURED -> "This build is not connected to the Sales Engine yet"
                            CrmState.BLOCKED -> "LANARS blocked this phone"
                            else -> "With your lanars.com or lanars.no account"
                        },
                        icon = Icons.Filled.AccountCircle,
                        enabled = !ui.busy && ui.state != CrmState.NOT_CONFIGURED,
                        onClick = onSignIn,
                    )
                }
            }
            if (signedIn) {
                row {
                    SettingsRow(
                        title = ui.name.orEmpty(),
                        summary = ui.email ?: "Signed in",
                        icon = Icons.Filled.AccountCircle,
                    )
                }
                row {
                    SettingsRow(
                        title = "Sign out",
                        summary = "Calls made while signed out are never sent",
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        enabled = !ui.busy,
                        onClick = onSignOut,
                    )
                }
            }
        }

        if (signedIn && ui.state != CrmState.UPGRADE_REQUIRED) {
            SettingsGroup("Notice") {
                row {
                    SettingsRow(
                        title = if (ui.noticeAgreedAtMs == null) "Read and agree" else "LANARS's notice",
                        summary = ui.noticeAgreedAtMs?.let { "Agreed on ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}" }
                            ?: "Nothing is sent until you have",
                        icon = Icons.AutoMirrored.Filled.List,
                        summaryColor = if (ui.noticeAgreedAtMs == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        enabled = !ui.busy,
                        onClick = onNotice,
                    )
                }
            }

            Column(Modifier.selectableGroup()) {
                SettingsGroup("Work line", note = "Only calls on this SIM go to the Sales Engine.") {
                    when {
                        !ui.phoneStateAllowed && ui.sims.isEmpty() -> row {
                            SettingsRow(
                                title = "Allow phone access",
                                summary = "To list the phone's SIMs, so you can choose the work line",
                                icon = Icons.Filled.Phone,
                                onClick = onAskPhone,
                            )
                        }
                        ui.sims.isEmpty() -> row {
                            SettingsRow(title = "No SIM", summary = "Put in the work SIM", icon = Icons.Filled.Phone)
                        }
                        ui.sims.size == 1 -> row {
                            SettingsRow(title = ui.sims.single().label, summary = "The phone's only SIM", icon = Icons.Filled.Phone)
                        }
                        else -> ui.sims.forEach { sim ->
                            row(sim.subscriptionId) {
                                ChoiceRow(
                                    title = sim.label,
                                    selected = sim.subscriptionId == ui.line?.subscriptionId,
                                    onSelect = { onLine(sim) },
                                    lines = listOf(
                                        "" to if (sim.subscriptionId == ui.line?.subscriptionId) "Its calls go to the Sales Engine" else "Its calls stay on the phone",
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (!ui.callLogAllowed) {
                SettingsGroup("Call log") {
                    row {
                        SettingsRow(
                            title = "Allow the call log",
                            summary = "Each call's number, time and length come from it",
                            icon = Icons.Filled.Call,
                            summaryColor = MaterialTheme.colorScheme.error,
                            onClick = onAskCallLog,
                        )
                    }
                }
            }

            if (ui.contentWarning != null) {
                SettingsGroup("Summaries") {
                    row {
                        SettingsRow(
                            title = "None reach the Sales Engine",
                            summary = ui.contentWarning,
                            icon = Icons.Filled.Warning,
                        )
                    }
                }
            }
        }

        if (ui.state == CrmState.UPGRADE_REQUIRED) {
            SettingsGroup {
                row { SettingsRow(title = "Update the app", summary = "The Sales Engine needs a newer version", icon = Icons.Filled.Info) }
            }
        }

        if (BuildConfig.SALES_ENGINE_ENV == "dev") {
            // A tester should never wonder which CRM their calls went to.
            SettingsGroup("Server") {
                row {
                    SettingsRow(
                        title = "LANARS's test stand",
                        summary = ui.serverHost.ifBlank { "No address in this build" },
                        icon = Icons.Filled.Info,
                    )
                }
            }
        }

        SettingsFooter(
            "After each call on the work line, its number, time and length go to LANARS's Sales Engine, so the call shows on the right deal.",
            "Its transcript and a summary in English follow when the phone has made them. The recording never leaves the phone.",
            "It is a work phone: every call on the work line is sent. Make personal calls on another phone.",
        )
    }
}

/**
 * LANARS's notice, as the server gives it: never reworded (§3.1), each
 * section's paragraphs, bullets and numbered items in order, and I agree.
 */
@Composable
internal fun NoticeContent(notice: Notice?, busy: Boolean, onAgree: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (notice == null) {
            Text("Loading the notice…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(notice.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        notice.intro?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        notice.sections.forEach { s ->
            Text(s.heading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
            s.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            s.items.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            s.ordered.forEachIndexed { i, it -> Text("${i + 1}.  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        }
        Button(onClick = onAgree, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { ButtonLabel("I agree") }
    }
}

/** The Settings row's line: who, on which line, or what the seller must do. */
internal fun salesEngineLine(ui: SalesEngineUi): String = when (ui.state) {
    CrmState.CONNECTED -> listOfNotNull("Connected as ${ui.name}", ui.line?.label).joinToString(" · ")
    else -> ui.state.line
}
