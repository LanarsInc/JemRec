# Implementation Plan: Logging work calls in the Sales Engine

**Branch**: `feautre/implement-integration-with-sales-engine-api` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-sales-engine-call-logging/spec.md`

## Summary

After each phone call on the seller's work SIM, the app looks the number up
in LANARS's Sales Engine, gives the seller two minutes to keep the call
private or choose the deal, and sends the call's facts; once the call's
transcript and its (now always English) summary are made on the phone, it
sends them too, or "failed". Calls come from the call log, so missed and
unrecorded calls are logged as well. Sign-in is Sign in with Google
exchanged for an API token. Everything waits in a local queue and goes when
it can, once each. Never audio.

## Technical Context

**Language/Version**: Kotlin (AGP 9.4 built-in Kotlin), JDK 21 to build

**Primary Dependencies**: new: `androidx.credentials:credentials` and
`credentials-play-services-auth` 1.6.0, `googleid` 1.2.1 (research R2);
test only: `okhttp3:mockwebserver` 4.12.1. Existing: Room 2.8.4, WorkManager
2.9.1, Compose Material 3, `org.json`, `HttpURLConnection` + Conscrypt (R1)

**Storage**: Room `crm.db` (work calls); prefs for session and setup; the
token encrypted with a Keystore key (R3)

**Testing**: JUnit + Robolectric units and Roborazzi screens; MockWebServer
for the client; an emulator check against a mock of the API; the A24 against
the stand

**Target Platform**: Android 12+ (minSdk 31), arm64; Google Play services on
the phone (Sign in with Google)

**Project Type**: mobile app; `shellserver/` untouched

**Performance Goals**: SC-002 (on the deal within 5 min, of which 2 are the
Private window), SC-004 (offline calls within 10 min of the network)

**Constraints**: nothing before the notice is agreed; only the work line;
never audio; one request at a time; the heavy work chain untouched

**Scale/Scope**: tens of calls a day per seller; limits of §12 (40 writes
per 10 min) far above it

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are
CLAUDE.md's rules.

| Gate | Status |
|---|---|
| JDK 21; the daemon's bytes unchanged | Pass: `shellserver/` untouched |
| Decisions in plain code, tested off the phone | Pass: failure table, deal decision, state machine, bodies, dates, versions are Android-free in `crm/` with unit tests |
| Screen tests, PNGs looked at | Pass: Sales Engine settings, notice, call sheet, Calls tab marks |
| `./gradlew lint` no warnings | Required; new permission and channels must lint clean |
| Generated files from their source | Pass: site via `tools/build_site.py` |
| Feature landing: every place says the same | Planned: last phase (README, site incl. privacy, ARCHITECTURE incl. permission table, DEVELOPMENT, tools/README, changelog with 1.0.0) |
| Never commit call audio | Pass: nothing recorded is used in tests |
| Comments say why, with the measurement | Pass: R4 and R9 are measured on the A24 before they are relied on |
| Replacing a code path lists what it did | `SummaryLanguage` stops following the call's language (FR-033): what it did and why is listed in its doc comment, and the faithfulness samples are re-run (R9) |
| Ask before creating or deleting an AVD | Open: only needed for the emulator check |
| Commit, push or PR only when asked | Commits per story (the user asked, 2026-10-29); no push or PR |

Re-checked after design: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/002-sales-engine-call-logging/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── api-client.md
│   └── ui.md
├── reference/sales-engine-api-2026-11-02.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
gradle.properties                              # salesEngine* properties; 1.0.0 at the end
app/build.gradle.kts                           # buildConfig, credentials, googleid, mockwebserver
app/schemas/com.jemcik.jemrec.crm.db.CrmDatabase/
app/src/main/AndroidManifest.xml               # READ_PHONE_STATE kept (not removed), CallSheetActivity
app/src/main/java/com/jemcik/jemrec/crm/       # new
├── api/                                       # Android-free
│   ├── SalesEngineClient.kt                   # HttpURLConnection + org.json (R1)
│   ├── ApiModels.kt                           # Session, Notice, Lookup, CallState, Candidate
│   ├── ApiErrors.kt                           # the failure table
│   └── Bodies.kt                              # step 1, pick, step 2 (R8)
├── CallDecision.kt                            # §5.1 table: what to send, whether to ask
├── WorkCallMachine.kt                         # local and content states (data-model)
├── ContentText.kt                             # transcript lines, summary text, truncation, language
├── OsloTime.kt  AppVersion.kt
├── db/                                        # CrmDatabase, WorkCallEntry, WorkCallDao
├── SessionStore.kt  TokenCipher.kt            # R3
├── GoogleSignIn.kt                            # Credential Manager (R2)
├── WorkLine.kt                                # SIMs, matching (R4)
├── CallIntake.kt                              # call-log sweep (R5)
├── CrmSync.kt  CrmSyncWorker.kt               # the queue (R6)
├── ContentHook.kt                             # after a summary: step 2; deadlines
└── CrmNotifications.kt                        # channel, after-call and prompts (R7)
app/src/main/java/com/jemcik/jemrec/summary/SummaryLanguage.kt, SummaryPrompt.kt   # English (R9)
app/src/main/java/com/jemcik/jemrec/ui/
├── SalesEngineSettingsScreen.kt  NoticeScreen.kt   # new
├── CallSheet.kt  CallSheetActivity.kt             # new
├── CallRows.kt  CallsScreen.kt  ListChips.kt      # CRM mark, chip
├── SettingsList.kt  MainScreen.kt  MainViewModel.kt
app/src/debug/.../diag/SpikeReceiver.kt         # crm-token, crm-state ops
app/src/test/java/com/jemcik/jemrec/crm/        # units, client tests, fixtures from the document
app/src/test/java/com/jemcik/jemrec/ui/screens/ # new screens
tools/sales_engine_mock.py  tools/emulator_checks.py
README.md  docs/ARCHITECTURE.md  docs/DEVELOPMENT.md  tools/README.md  tools/build_site.py
```

**Structure Decision**: one new package `crm` beside `capture`,
`transcribe`, `summary`; its `api/` and decision files carry no Android
types, so they test off the phone. The existing flow is touched at three
points only: a call ending (to start the sweep), a summary finishing (to
send content), a recording deleted (to send "failed").

## Phases (for /speckit-tasks)

1. **Setup**: dependencies, `BuildConfig` properties, `crm.db`, test
   fixtures from the API document.
2. **Foundation**: API client, failure table, bodies, Oslo time, version;
   session store and token cipher; sync worker skeleton. All unit tested.
3. **US1 Connect** (P1): Google sign-in and exchange, notice screen, work
   line (measure R4 on the A24), Sales Engine settings, sign-out.
4. **US2 Logged by itself** (P1): call-log sweep, work-line filter, lookup,
   decision, after-call notification with the 2-minute window, step 1,
   missed calls, hidden numbers, colleagues.
5. **US3 Choose or keep private** (P2): call sheet, picker, pending chip in
   the Calls tab, fresh candidates, Make private.
6. **US6 Nothing lost** (P2): retries, `retry-after`, 426, 401, blocked,
   another person, deliberate sign-out, 30 days. (Before US4: content rides
   on the same queue.)
7. **US4 Transcript and English summary** (P2): English summaries (measure
   R9), content text, hook after the summary, failed cases, 72 h deadline,
   first only.
8. **US5 Outcome and next call** (P3): in the call sheet; Oslo dates.
9. **Every place that describes it**: README, site (features, FAQ, manual,
   privacy and its chips), ARCHITECTURE (section, contents, permission
   table), DEVELOPMENT and tools/README (tests, the mock), changelog, demo
   data, screenshots of the new screens, the emulator check; 1.0.0.
10. **Gates**: unit, screen, lint, Error Prone, emulator checks, the A24
    quickstart against the stand when LANARS has sent it.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Google Play services libraries in an app that had none | The API takes only a Google ID token (§2.1) | Browser OAuth gives an auth code, not what the server verifies |
| `READ_PHONE_STATE`, which the manifest removed on purpose | Listing SIMs to choose the work line (R4) | Without it, a dual-SIM phone's lines cannot be told apart |
| A second Room database | The queue's lifetime and deletion (blocked device) differ from transcripts' | One database would make a blocked phone's wipe a selective delete |
