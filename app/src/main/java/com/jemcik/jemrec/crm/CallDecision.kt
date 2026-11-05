package com.jemcik.jemrec.crm

import com.jemcik.jemrec.crm.api.DealChoice
import com.jemcik.jemrec.crm.api.Lookup
import com.jemcik.jemrec.crm.api.Match

/**
 * What a lookup's answer means for a call: what to show the seller and what
 * step 1 sends if they do nothing (§5.1's table, FR-022, FR-023). Pure.
 */
object CallDecision {

    sealed interface Shown {
        /** A colleague's number: nothing more is sent, and nothing is shown. */
        data object Colleague : Shown
        /** One open deal: logged there unless the seller changes it. */
        data class OneDeal(val dealId: Int, val title: String, val orgName: String?) : Shown
        /** Several deals and none to take without asking: the seller chooses. */
        data class Choose(val count: Int) : Shown
        /** Not in the CRM, or the lookup failed or was not made: the server decides. */
        data object ServerDecides : Shown
    }

    data class Default(val choice: DealChoice, val dealId: Int?, val chosenByUser: Boolean?)

    fun shown(lookup: Lookup?): Shown = when {
        lookup == null -> Shown.ServerDecides
        lookup.match == Match.INTERNAL -> Shown.Colleague
        lookup.match == Match.DEAL && lookup.autoDealId != null -> {
            val deal = lookup.candidates.firstOrNull { it.dealId == lookup.autoDealId }
            if (deal != null) Shown.OneDeal(deal.dealId, deal.title, deal.orgName) else Shown.ServerDecides
        }
        lookup.match == Match.DEAL && lookup.candidates.isNotEmpty() -> Shown.Choose(lookup.candidatesTotal.coerceAtLeast(lookup.candidates.size))
        else -> Shown.ServerDecides
    }

    /**
     * Step 1's deal choice when the seller does nothing: the single open
     * deal with `chosen_by: auto`; otherwise `auto`, and the server decides
     * again at write time (§6.1) - several open deals then wait as pending,
     * never logged on a deal the seller did not pick (§5.1: the suggested
     * deal is preselected, "never sent without the user's tap").
     */
    fun default(lookup: Lookup?): Default = when (val s = shown(lookup)) {
        is Shown.OneDeal -> Default(DealChoice.DEAL, s.dealId, false)
        else -> Default(DealChoice.AUTO, null, null)
    }
}
