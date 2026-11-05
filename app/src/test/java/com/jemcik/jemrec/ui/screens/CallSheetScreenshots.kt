package com.jemcik.jemrec.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.jemcik.jemrec.crm.api.Candidate
import com.jemcik.jemrec.crm.api.DealStatus
import com.jemcik.jemrec.crm.api.NextCall
import com.jemcik.jemrec.crm.api.Outcome
import com.jemcik.jemrec.ui.CallSheetContent
import com.jemcik.jemrec.ui.CallSheetUi
import com.jemcik.jemrec.ui.DealSearchUi
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * The Sales Engine's call sheet as drawn (contracts/ui.md "The call sheet"):
 * one open deal before sending, several with a next call planned, more than
 * the five shown, a call waiting for its deal, and a logged one. The sheet's
 * content alone, on the surface it sits on: the bottom sheet's window is
 * Material's own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class CallSheetScreenshots : ScreenTest() {

    private fun draw(ui: CallSheetUi, look: LookChoice = LookChoice(), search: DealSearchUi = DealSearchUi()) {
        show(look = look) {
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                CallSheetContent(ui, onChoose = {}, search = search)
            }
        }
        compose.waitForIdle()
    }

    private fun deal(id: Int, title: String, org: String?, stage: String, status: DealStatus = DealStatus.OPEN, due: Boolean = false, owner: String = "Test Seller") =
        Candidate(id, title, org, status, stage, 1, owner, due)

    private val oneDeal = CallSheetUi(
        id = 1, outgoing = true, answered = true, phone = "+47 400 00 001",
        startedAtMs = 1_793_700_000_000L, durationSeconds = 184, loading = false,
        where = "Logging to the Sales Engine in 2 min", canChoose = true, beforeSending = true,
        candidates = listOf(
            deal(1000456, "Eksempel AS – nettside", "Eksempel AS", "Contact attempted", due = true),
            deal(1000457, "Eksempel AS – app", "Eksempel AS", "Proposal sent (fixed price)", status = DealStatus.LOST),
        ),
        candidatesTotal = 2, preselected = 1000456, outcome = Outcome.REACHED, next = null,
        note = null,
    )

    @Config(qualifiers = "+h1400dp")
    @Test fun oneOpenDeal() {
        draw(oneDeal)
        snap("call-sheet-one-deal")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun severalWithNextCall() {
        draw(
            oneDeal.copy(
                candidates = listOf(
                    deal(1000456, "Eksempel AS – nettside", "Eksempel AS", "Contact attempted", due = true),
                    deal(1000458, "Eksempel AS – support", "Eksempel AS", "In conversation", owner = "Other Seller"),
                    deal(1000459, "Nettside for Test Person C", null, "Contact attempted"),
                ),
                candidatesTotal = 8,
                preselected = 1000456,
                outcome = Outcome.CALLBACK,
                next = NextCall("2026-11-05", "10:00", "Follow up offer"),
            ),
        )
        snap("call-sheet-several")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun waitingForDeal() {
        draw(oneDeal.copy(where = "Choose a deal", beforeSending = false, preselected = null))
        snap("call-sheet-pending")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun whichLead() {
        draw(
            oneDeal.copy(
                phone = "+47 400 00 009", where = "Which lead?", beforeSending = false, whichLead = true, preselected = 1000458,
                candidates = listOf(
                    deal(1000459, "Nettside for Test Person C", null, "Contact attempted"),
                    deal(1000456, "Eksempel AS – nettside", "Eksempel AS", "Contact attempted", due = true),
                ),
            ),
            search = DealSearchUi(
                query = "Eksempel",
                deals = listOf(
                    deal(1000458, "Eksempel AS – support", "Eksempel AS", "In conversation", owner = "Other Seller"),
                    deal(1000456, "Eksempel AS – nettside", "Eksempel AS", "Contact attempted", due = true),
                ),
            ),
        )
        snap("call-sheet-which-lead")
    }

    @Test fun logged() {
        draw(
            oneDeal.copy(
                where = "On Eksempel AS – nettside", canChoose = false, beforeSending = false, candidates = emptyList(),
                note = "A logged call is changed or deleted on its deal in the Sales Engine's web app.",
            ),
        )
        snap("call-sheet-logged")
    }

    /** Its transcript reached the CRM as "failed": the sheet offers to make it again and send it. */
    @Test fun transcriptNotSent() {
        draw(
            oneDeal.copy(
                where = "Not in the CRM · transcript not sent", canChoose = false, beforeSending = false, candidates = emptyList(),
                canResend = true,
            ),
        )
        snap("call-sheet-transcript-not-sent")
    }

    /** Its transcript went: the sheet offers to make it again and replace it, as often as revisions are left. */
    @Test fun transcriptSentAgain() {
        draw(
            oneDeal.copy(
                where = "Not in the CRM · transcript sent", canChoose = false, beforeSending = false, candidates = emptyList(),
                canResend = true, resendAgain = true, resendsLeft = 3,
            ),
        )
        snap("call-sheet-send-again")
    }

    @Config(qualifiers = "+h1400dp-night")
    @Test fun deckDark() {
        draw(oneDeal, LookChoice(Look.DECK))
        snap("call-sheet-deck-dark")
    }

    @Config(qualifiers = "+h1400dp")
    @Test fun missedHidden() {
        draw(
            oneDeal.copy(
                outgoing = false, answered = false, phone = null, durationSeconds = 0,
                where = "Logging to the Sales Engine in 2 min", candidates = emptyList(), candidatesTotal = 0,
                preselected = null, outcome = Outcome.NO_ANSWER,
            ),
        )
        snap("call-sheet-missed-hidden")
    }
}
