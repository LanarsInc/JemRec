package com.jemcik.jemrec.crm

import com.jemcik.jemrec.BuildConfig

/**
 * The Sales Engine this build talks to (app/build.gradle.kts): its address and Google's Web
 * client id, read from here rather than from BuildConfig, so a screen test draws the same on every
 * machine - the stand's address lives in each developer's local.properties, and with the client id
 * committed (2026-11-05) Settings said "Not connected" on one machine and "Not set up" on another.
 */
object SalesEngineBuild {
    @Volatile var url: String = BuildConfig.SALES_ENGINE_URL
    @Volatile var webClientId: String = BuildConfig.SALES_ENGINE_WEB_CLIENT_ID
    val env: String get() = BuildConfig.SALES_ENGINE_ENV

    /** What screen tests use: no Sales Engine, as a build without one. */
    fun none() {
        url = ""
        webClientId = ""
    }

    fun reset() {
        url = BuildConfig.SALES_ENGINE_URL
        webClientId = BuildConfig.SALES_ENGINE_WEB_CLIENT_ID
    }
}
