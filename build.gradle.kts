plugins {
    // AGP 9 compiles Kotlin itself - there is no separate kotlin-android
    // plugin here on purpose. The Compose compiler is still its own plugin.
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    // Room's processor. 2.3.9 is what CallVault builds Room with on the same
    // Kotlin (2.3.21) and AGP 9.
    id("com.google.devtools.ksp") version "2.3.9" apply false
    // Only for app/pure-tests, which is plain JVM: the app's pure files
    // compiled with no Android at all. The Compose plugin's version: AGP asks
    // for Kotlin 2.2.10 and gets this one (./gradlew buildEnvironment,
    // 2026-11-01), so the app and that module compile with one Kotlin.
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
}
