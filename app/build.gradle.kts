import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // Room's annotation processor, for the transcripts database.
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.jemcik.jemrec"

    // compileSdk and targetSdk are deliberately different, and so is minSdk.
    // Three separate decisions that must not be collapsed:
    //
    //   compileSdk 37  - what we compile and lint against. Newest installed.
    //   targetSdk  36  - which platform behaviour changes we opt into. The
    //                    device under test reports ro.build.version.sdk 36, so
    //                    36 is the newest target any measurement here covers.
    //   minSdk     31  - the run-time floor. Android 12 is where the shell-side
    //                    capture approach starts working at all.
    //
    // A FOURTH "36" exists elsewhere and is not this one: the shell-side
    // capture binary must be compiled against ANDROID_PLATFORM=36 because it
    // links @hide framework internals and has to match the platform the device
    // actually runs. That is a property of the binary, not of the app, and the
    // two happening to share the number 36 today is a coincidence.
    compileSdk = 37

    // Pinned because shellserver/build.sh reads d8 out of build-tools/36.0.0 by
    // path, and until now that worked only because 36.0.0 happens to be AGP's
    // default. Nothing declared it, so nothing would have caught AGP moving on:
    // the daemon build would fail on a machine that had never been told which
    // build-tools to install - F-Droid's, for one, which installs only what the
    // Gradle build asks for plus what its metadata names. Stating it here makes
    // AGP install the version build.sh expects. Keep the two in step; build.sh
    // takes BUILD_TOOLS from the environment, so it can be told otherwise.
    buildToolsVersion = "36.0.0"

    // The NDK builds whisper.cpp and llama.cpp (src/main/cpp/CMakeLists.txt):
    // transcripts and summaries run on the phone. Pinned for the same reason
    // as build-tools above: named, so a machine that has never built this
    // installs the one the build expects. CallVault built the same engines
    // with 27.2; this is the one installed here (2026-10-29).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.jemcik.jemrec"
        minSdk = 31
        targetSdk = 36

        // From gradle.properties, which is the one place the version is
        // written - see the comment there. property() rather than
        // findProperty() so a missing key fails with its name instead of
        // shipping a fallback: a fallback here once meant every release
        // would have been versionCode 1.
        versionCode = (property("jemrecVersionCode") as String).toInt()
        versionName = property("jemrecVersionName") as String

        // arm64 only. The speech and language engines are native, and their
        // ARM kernels (dotprod, i8mm) are the reason they run at phone speed;
        // a 32-bit build would be both slower and a second set of libraries
        // to ship. Every phone that runs Android 12 and has the memory for
        // these models is arm64.
        ndk { abiFilters += "arm64-v8a" }

        // Instrumented tests: the JNI bridges and Room migrations can only
        // be checked on a phone (app/src/androidTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment, never from the repo.
    val keystorePath: String? = System.getenv("JEMREC_KEYSTORE")
    val signingReady = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (signingReady) create("release") {
            storeFile = file(keystorePath!!)
            storePassword = System.getenv("JEMREC_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JEMREC_KEY_ALIAS") ?: "jemrec"
            keyPassword = System.getenv("JEMREC_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8, on. Measured on 0.4 before this: 38 MB on disk, of which
            // 28.5 MB was dex and 0.2 MB of that was this app - the rest was
            // libraries shipped whole, and 12.8 MB was nothing but the names
            // of their classes, methods and fields. proguard-rules.pro says
            // what has to survive and why. Debug stays unshrunk: its
            // diagnostic receivers are reached by name from tools/, and a
            // build that takes a minute longer is a measurement that does
            // not get made.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // With no keystore configured the APK is left UNSIGNED rather than
            // falling back to the debug key: a debug-signed "release" installs
            // once and can never be upgraded by a properly signed one.
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Coverage of the unit and screen tests, only when asked for:
            // ./gradlew createDebugUnitTestCoverageReport -Pcoverage. A report
            // to read, not a gate, and JaCoCo slows every test it watches -
            // so a plain build, and the release build F-Droid reproduces,
            // carry none of it.
            enableUnitTestCoverage = providers.gradleProperty("coverage").isPresent
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Lint as a gate, not noise. Until 0.21 it ran on every build with its
    // warnings ignored - 108 of them, nobody reading. Now every warning is an
    // error, and there is no baseline of old ones left to hide behind: all
    // were fixed, or are allowed where they stand with the reason beside them
    // (lint.xml, tools:ignore, @SuppressLint). So any warning fails the
    // build, locally and in CI.
    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = true
        abortOnError = true
    }

    // No Google Play dependency metadata. By default AGP writes an encrypted
    // list of the app's dependencies into the APK Signing Block at signing
    // time, for the Play Console to read - and nobody else can, which is why
    // F-Droid's scanner refuses an APK that carries it. Found by their check
    // on the 0.5 release: "found extra signing block 'Dependency metadata'",
    // 5,977 bytes, inside the one part of the APK the reproducibility
    // comparison skips by design. There is no Play listing to inform.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Package every native library exactly as its Maven artifact ships it.
    //
    // AGP strips debug symbols from .so files when an NDK happens to be
    // installed, and silently skips the step when one is not. Measured on
    // 0.3: the GitHub runner has an NDK and ran :app:stripReleaseDebugSymbols,
    // the machine without one did not, and the four libconscrypt_jni.so came
    // out the same size and different bytes - the only difference between the
    // two APKs apart from the daemon jar. F-Droid's builder has no NDK unless
    // asked for one. Keeping the symbols removes the step on every machine,
    // and costs nothing: Conscrypt ships its libraries stripped already, which
    // is why the size never changed.
    //
    // Only the libraries that come from Maven, though. The speech and language
    // engines are built here, unstripped, and the build now needs an NDK on
    // every machine anyway - so stripping them is no longer a step that some
    // builders skip. Kept whole they made the debug APK 95 MB, 78 MB of it
    // libllama.so's symbols (2026-10-29).
    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "**/libconscrypt_jni.so",
                "**/libspake2.so",
                "**/libandroidx.graphics.path.so",
            )
            // Phones are ARM. The x86 and x86_64 copies of Conscrypt's
            // BoringSSL are 4.9 MB that only an emulator could run, and this
            // app cannot record a call on an emulator anyway - there is no
            // voice-call audio source to open. armeabi-v7a went with the
            // native engines (abiFilters above).
            excludes += listOf("**/x86/**", "**/x86_64/**")
            // Extracted to the native library directory as real files.
            // ggml picks its CPU backend at run time by dlopen'ing one of
            // several libggml-cpu-*.so from there; left compressed inside the
            // APK there is nothing to open, and no model loads.
            useLegacyPackaging = true
        }
        resources {
            // BouncyCastle's jar carries 1.3 MB of Java resources this app
            // never opens: a megabyte of lookup tables for the Picnic
            // post-quantum signature scheme, and the message catalogues of a
            // certificate-path reviewer. R8 removes the classes that would
            // read them; nothing removes the files, so this does.
            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*",
                "org/bouncycastle/pkix/CertPathReviewerMessages*",
            )
        }
    }

    testOptions {
        // android.util.Log and the rest of the stub android.jar throw on the
        // JVM. With this they return defaults instead, so pure logic that
        // happens to log a line (Setup.currentStep) is testable off-device.
        unitTests.isReturnDefaultValues = true
        // The screen tests (app/src/test/.../ui/screens) draw the real
        // composables under Robolectric, which needs the app's resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Screenshots are compared against the PNGs committed under
            // app/src/test/screenshots, and a difference fails the build.
            // `./gradlew testDebugUnitTest -PrecordScreens=true` writes them
            // anew instead - after a change to a screen that was meant.
            val record = project.findProperty("recordScreens") == "true"
            test.systemProperty("roborazzi.test.record", record.toString())
            test.systemProperty("roborazzi.test.verify", (!record).toString())
            // Robolectric's Android 16 sets up a file descriptor through a
            // JDK internal that Java 21 keeps closed unless asked.
            test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            // With -Pcoverage: Robolectric loads the app's classes through a
            // class loader of its own, which JaCoCo skips unless told - and
            // then the screens the screen tests draw count as never run.
            test.extensions.findByType(JacocoTaskExtension::class.java)?.apply {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

dependencies {
    // The embedded ADB client. This is the whole transport: SPAKE2 pairing
    // against the phone's own Wireless debugging, then a TLS connection to
    // loopback and a shell-privileged stream. Dual licensed GPL-3.0-or-later
    // OR Apache-2.0; we take it under Apache-2.0. See NOTICE.md.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")

    // Self-signed X.509 for the ADB identity. libadb already pulls bcprov at
    // RUNTIME (it is a runtime-scope dep in its POM), but runtime scope is not
    // on the compile classpath, so both are declared explicitly here.
    //
    // The alternative the library's own sample uses is MuntashirAkon's
    // sun-security-android shim, which drags android.sun.* repackaged JDK
    // internals in. BouncyCastle is a real, auditable, Maven Central artifact
    // and one of the two is already in the graph regardless.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // REQUIRED FOR PAIRING, and not obvious why.
    //
    // SPAKE2 binds the pairing exchange to the TLS channel by mixing in RFC
    // 5705 exported keying material, so libadb has to call
    // Conscrypt.exportKeyingMaterial(). Which Conscrypt it calls is decided by
    // whether a bundled one is on the classpath:
    //
    //   bundled present -> org.conscrypt.Conscrypt            (public API)
    //   bundled absent  -> com.android.org.conscrypt.Conscrypt (HIDDEN API)
    //
    // Without this dependency it takes the second path and Android's hidden-API
    // blocklist makes the method simply not exist. Measured, on the first real
    // pairing attempt:
    //
    //   pair failed: java.lang.NoSuchMethodException:
    //     com.android.org.conscrypt.Conscrypt.exportKeyingMaterial
    //
    // minSdk 31 guarantees TLSv1.3 from the platform, which is why everything
    // SHORT of pairing worked without this: the connection reached adbd and was
    // correctly told "ADB pairing is required". Only the pairing handshake
    // needs the keying export, so this was invisible until the first pair.
    //
    // The upstream sample solves it the other way, with LSPosed's
    // HiddenApiBypass and a blanket addHiddenApiExemptions("L"). Bundling
    // Conscrypt is better here: no hidden-API exemptions at all, and no
    // exposure to Android 17 tightening them further.
    //
    // 2.7.0, not 2.5.3: 2.5.3's libconscrypt_jni.so ends its RELRO segment on
    // a 4 KB boundary inside a page it shares with writable data, so on a
    // phone with 16 KB pages Android refused to load it normally and ran the
    // whole app in page-size compat mode, with a warning dialog at launch
    // (measured on the Android 17 QPR2 emulator). 2.7.0 ends it on 16 KB.
    // The dialog also named libspake2.so and libandroidx.graphics.path.so;
    // both keep RELRO in a segment of their own, which the check accepts,
    // and with this alone the app was no longer put in compat mode
    // (pageSizeCompat 256 before, 0 after, in `dumpsys package`).
    implementation("org.conscrypt:conscrypt-android:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    // Play and Share glyphs for the recordings list. The -core set, not
    // -extended: extended is thousands of vectors for the two we use.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    // Playing a recording with the screen off: a media session in a service,
    // Android's documented route, under the system's media controls. The
    // session alone, not ExoPlayer: recordings still play with MediaPlayer
    // (capture/SessionPlayer.kt). Measured on the release APK, 2026-10-23:
    // 9.19 MB -> 9.75 MB with it; ExoPlayer as well would have made 10.73.
    implementation("androidx.media3:media3-session:1.11.1")

    // Keeps the shell-side recorder alive without a permanent foreground
    // service: a periodic job revives it when it is down and Wi-Fi is around.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Transcripts and summaries, with full-text search over both. The only
    // database in the app: everything else is kept in files, and these are
    // the one thing searched word by word. The schema is CallVault's,
    // trimmed (specs/001-transcripts-summaries/data-model.md).
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("androidx.room:room-testing:2.8.4")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    // Compose's own pitfalls, which Android Lint does not know: Slack's rules
    // for modifiers (passed on, in the right place), state hoisting, unstable
    // parameters and the like. Only lint loads it - the release build's
    // lintVital included, which reads the sources and writes nothing into the
    // APK, so the reproducible bytes are untouched.
    lintChecks("com.slack.lint.compose:compose-lint-checks:1.6.1")

    testImplementation("junit:junit:4.13.2")
    // Mockito stands in for the two Android types the pure-logic tests cannot
    // construct on the JVM: android.net.Uri (abstract in the stub android.jar,
    // and Uri.EMPTY/parse() are null there) and android.app.Application. The
    // inline mock maker in Mockito 5 mocks abstract classes without extra
    // setup. Nothing else in the tests needs a framework.
    testImplementation("org.mockito:mockito-core:5.14.2")

    // The screen tests: Robolectric runs the real Compose UI on the JVM, and
    // Roborazzi turns what it draws into a PNG to compare. Test-only, so the
    // release APK - and F-Droid's reproducible build of it - is untouched;
    // no Gradle plugin either, so a release build resolves nothing new.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Room writes each schema version here, so a migration can be tested against
// the one before it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The shell-side capture daemon ships INSIDE the APK as an asset.
//
// That is the "single APK, nothing else installed" requirement, and it is also
// why the daemon is built from vendored source here rather than downloaded: an
// asset compiled by this build is part of the binary, an opaque blob fetched at
// runtime is a dependency on someone else's server.
//
// It is built by shellserver/build.sh rather than by Gradle, because it must be
// compiled against the platform the DEVICE runs (android-36) with its @hide
// internals intact - which is a different toolchain from the app's, not a
// variant of it.
val shellServerDir = rootProject.file("shellserver")
val shellServerJar = File(shellServerDir, "jemrec-capture.jar")

val buildShellServer = tasks.register<Exec>("buildShellServer") {
    description = "Compiles the shell-side capture daemon to a classes.dex jar"
    workingDir = shellServerDir
    commandLine("./build.sh")
    inputs.dir(File(shellServerDir, "src"))
    inputs.file(File(shellServerDir, "build.sh"))
    outputs.file(shellServerJar)
}

val copyShellServer = tasks.register<Copy>("copyShellServer") {
    dependsOn(buildShellServer)
    from(shellServerJar)
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(copyShellServer) }
