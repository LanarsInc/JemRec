package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.crm.CrmState
import com.jemcik.jemrec.crm.WorkLine
import com.jemcik.jemrec.crm.api.Notice
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.SalesEngineUi
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Sales Engine as drawn (specs/002, contracts/ui.md): not yet
 * connected, connected on a two-SIM phone, a session that ended, the notice,
 * and the looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class SalesEngineScreenshots : ScreenTest() {

    private fun draw(ui: SalesEngineUi, look: LookChoice = LookChoice()) {
        val state = Fixtures.ready().copy(screen = Screen.SALES_ENGINE)
        val vm = viewModel(state)
        vm.salesEngine.showForTest(ui)
        show(look = look) { AppScaffold(state, vm) }
        vm.salesEngine.showForTest(ui)
        compose.waitForIdle()
    }

    private val sims = listOf(
        WorkLine.Sim(1, 0, "Work", "Telia", null, "no"),
        WorkLine.Sim(2, 1, "Telenor", "Telenor", null, "no"),
    )

    private val connected = SalesEngineUi(
        state = CrmState.CONNECTED,
        name = "Test Seller",
        email = "android-test@example.no",
        noticeAgreedAtMs = 1_793_700_000_000L,
        sims = sims,
        line = sims[0],
        phoneStateAllowed = true,
        callLogAllowed = true,
    )

    @Test fun notConnected() {
        draw(SalesEngineUi(state = CrmState.NOT_CONNECTED))
        snap("sales-engine-not-connected")
    }

    @Test fun refused() {
        draw(SalesEngineUi(state = CrmState.NOT_CONNECTED, message = "Use your lanars.com or lanars.no account."))
        snap("sales-engine-refused")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun connected() {
        draw(connected)
        snap("sales-engine-connected")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun needsNoticeAndNoModel() {
        draw(
            connected.copy(
                state = CrmState.NEEDS_NOTICE,
                noticeAgreedAtMs = null,
                contentWarning = "No speech model on the phone: the Sales Engine gets calls without a transcript or summary.",
            ),
        )
        snap("sales-engine-needs-notice")
    }

    @Test fun needsSignIn() {
        draw(connected.copy(state = CrmState.NEEDS_SIGN_IN))
        snap("sales-engine-sign-in-again")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun notice() {
        draw(
            connected.copy(
                reading = true,
                notice = Notice(
                    version = "2026-11-02",
                    acknowledged = false,
                    title = "Call logging in LANARS Sales Engine",
                    intro = "After a call on your work line, this app sends the call to the Sales Engine, LANARS's CRM, so it shows on the right deal. Read this before the app sends anything.",
                    sections = listOf(
                        Notice.Section(
                            "What is sent",
                            paragraphs = emptyList(),
                            items = listOf(
                                "When a call on the work line ends, its number is checked against the CRM. This happens before you can tap Private, and the check stores nothing.",
                                "An invented second item, standing in for the ones the document cuts.",
                            ),
                            ordered = emptyList(),
                        ),
                        Notice.Section("An invented section", listOf("An invented paragraph."), emptyList(), listOf("First invented step.", "Second invented step.")),
                    ),
                ),
            ),
        )
        snap("sales-engine-notice")
    }

    @Config(qualifiers = "+h1400dp-night")
    @Test fun deckDark() {
        draw(connected, LookChoice(Look.DECK))
        snap("sales-engine-deck-dark")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun multitrack() {
        draw(connected, LookChoice(Look.MULTITRACK))
        snap("sales-engine-multitrack")
    }
}
