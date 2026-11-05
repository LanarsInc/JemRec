package com.jemcik.jemrec.crm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.jemcik.jemrec.calls.CallLogLookup

/**
 * The work line: the SIM whose calls go to the Sales Engine, and only those
 * (§13, FR-005, FR-010). Research R4.
 */
object WorkLine {

    /** A SIM as the seller picks it: its name, carrier and last digits, never its number. */
    data class Sim(
        val subscriptionId: Int,
        val slot: Int,
        val name: String,
        val carrier: String,
        val iccId: String?,
        val countryIso: String?,
    ) {
        val label: String get() = listOf("SIM ${slot + 1}", name.takeIf { it.isNotBlank() && it != carrier }, carrier.takeIf { it.isNotBlank() })
            .filterNotNull().distinct().joinToString(" · ")
    }

    fun canList(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** The phone's active SIMs; empty without READ_PHONE_STATE (research R4). */
    @SuppressLint("MissingPermission") // checked by canList
    fun sims(context: Context): List<Sim> {
        if (!canList(context)) return emptyList()
        val subs = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList ?: return emptyList()
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return subs.map { s ->
            Sim(
                subscriptionId = s.subscriptionId,
                slot = s.simSlotIndex,
                name = s.displayName?.toString().orEmpty(),
                carrier = s.carrierName?.toString().orEmpty(),
                iccId = runCatching { s.iccId }.getOrNull()?.takeIf { it.isNotBlank() },
                countryIso = telephony?.createForSubscriptionId(s.subscriptionId)?.simCountryIso?.takeIf { it.length == 2 }
                    ?: s.countryIso?.takeIf { it.length == 2 },
            )
        }.sortedBy { it.slot }
    }

    /** The chosen line among [sims]: the stored one, or the only one there is. */
    fun chosen(sims: List<Sim>, storedId: Int?): Sim? =
        sims.firstOrNull { it.subscriptionId == storedId } ?: sims.singleOrNull()

    /**
     * Whether a call-log row was on the work line. Pure.
     *
     * Only Telephony's own rows (CallLogLookup.isPhoneCall): a messenger or
     * Google Meet logs through another account and is never sent (FR-015).
     * Which SIM is in `PHONE_ACCOUNT_ID`, and how, differs by phone - the
     * subscription id on AOSP, the ICCID on some Samsung builds - so both are
     * tried; with one SIM in the phone, any Telephony row is that SIM's.
     * On a Galaxy A24 (Android 14, 2026-11-03) it was the subscription id.
     */
    fun matches(component: String?, accountId: String?, line: Sim, simCount: Int): Boolean {
        if (!CallLogLookup.isPhoneCall(component)) return false
        if (simCount <= 1) return true
        val id = accountId?.trim().orEmpty()
        if (id.isEmpty()) return false
        if (id == line.subscriptionId.toString()) return true
        val icc = line.iccId ?: return false
        // Some builds write the ICCID with its trailing check digit or "F" padding cut.
        return id.equals(icc, ignoreCase = true) || icc.startsWith(id, ignoreCase = true) || id.startsWith(icc, ignoreCase = true)
    }
}
