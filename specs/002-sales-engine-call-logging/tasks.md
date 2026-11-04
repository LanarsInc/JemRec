# Tasks: Logging work calls in the Sales Engine

**Input**: Design documents from `specs/002-sales-engine-call-logging/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: included. CLAUDE.md requires decisions to be tested off the phone
and screen tests for new screens.

## Conventions for every task

- `JR/` means `app/src/main/java/com/jemcik/jemrec/`; `JRT/` means
  `app/src/test/java/com/jemcik/jemrec/`; `API` means
  [reference/sales-engine-api-2026-11-02.md](reference/sales-engine-api-2026-11-02.md),
  its sections as §n.
- Everything in `JR/crm/api/` and the decision files (`CallDecision`,
  `WorkCallMachine`, `ContentText`, `OsloTime`, `AppVersion`) has no Android
  types and is unit tested (CLAUDE.md). Comments say why, in plain words.
- Never log or show a phone number, a token, a transcript or a summary.
- Status line before any device step. Device: Galaxy A24, serial
  `R58X30P75XV`, running `com.lanars.jemrec`. Never commit call audio.
- Commit after each phase (the user asked for commits per story); no push.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: US1..US6 of the spec

---

## Phase 1: Setup

- [X] T001 Add `androidx.credentials:credentials:1.6.0`, `androidx.credentials:credentials-play-services-auth:1.6.0`, `com.google.android.libraries.identity.googleid:googleid:1.2.1` and `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.1")` in `app/build.gradle.kts`, each with a one-line why (research R1, R2)
- [X] T002 Add `salesEngineUrl=https://crm.lanars.no/api/v1`, `salesEngineStandUrl=` and `salesEngineWebClientId=` to `gradle.properties` with a comment; enable `buildFeatures.buildConfig` and expose them as `BuildConfig.SALES_ENGINE_URL` (debug: stand when set, else production; release: production) and `SALES_ENGINE_WEB_CLIENT_ID` in `app/build.gradle.kts` (research R11)
- [X] T003 [P] Copy every JSON body of the API document into `app/src/test/resources/crm/` as one file per example (`session-201.json`, `lookup-deal.json`, `lookup-no-org.json`, `lookup-internal.json`, `lookup-none.json`, `call-logged.json`, `call-pending.json`, the §6.2 one-liners, `deal-*.json`, `content-*.json`, `get-call.json`, `private.json`, `notice.json` with the cut items filled by invented ones, each error body of §11), named after the section
- [X] T004 Keep `READ_PHONE_STATE` in `app/src/main/AndroidManifest.xml` (drop its `tools:node="remove"`, comment why: the work line, research R4) and update the comment block that explains the removed permissions
- [X] T005 Confirm `./gradlew assembleDebug testDebugUnitTest lint` passes with the new dependencies (lint may flag the new permission: answer it in `app/lint.xml` only with a reason)

## Phase 2: Foundation (blocks every story)

- [X] T006 [P] `JR/crm/api/ApiModels.kt`: `SessionData`, `UserInfo`, `NoticeInfo`, `Notice` (sections with heading, paragraphs, items, ordered), `Lookup` (match with unknown -> NONE, candidates, total, auto, suggested), `Candidate`, `CallState` (status with unknown -> final `OTHER`, pendingReason unknown -> SEVERAL_OPEN_DEALS, content status), parsed with `org.json`, unknown fields ignored (§1)
- [X] T007 [P] `JR/crm/api/ApiErrors.kt`: `classify(endpoint, status, error, detail, retryAfter, bodyWasJson)` -> the outcomes of [contracts/api-client.md](contracts/api-client.md) "Failures", one sealed class
- [X] T008 [P] `JR/crm/OsloTime.kt`: instant formatting `YYYY-MM-DDTHH:MM:SS.sssZ`, parsing the server's, the Oslo date of an instant, the earliest allowed next-call date, `HH:MM` validation (research R10)
- [X] T009 [P] `JR/crm/AppVersion.kt`: dotted comparison ignoring a suffix like `-debug` (§12), `isBelow(min)`
- [X] T010 `JR/crm/api/SalesEngineClient.kt`: `HttpURLConnection`, no redirects, timeouts 15/30 s, headers of §1, one function per call of the contract, returning `ApiResult<T>` (success or an `ApiErrors` outcome); a non-JSON or bodiless answer is a network error
- [X] T011 [P] `JR/crm/api/Bodies.kt`: step-1 body, pick body, content bodies, notice ack, exchange body with `device`, as `org.json` text (§2.2, §6, §7, §8)
- [X] T012 [P] `JR/crm/db/`: `CrmDatabase` (`crm.db`, schema exported to `app/schemas/`), `WorkCallEntry` (fields of [data-model.md](data-model.md)), `WorkCallDao` (by id, by callLogId, by clientCallId, due now, pending a deal, by recording, delete all)
- [X] T013 [P] `JR/crm/TokenCipher.kt` (AES-GCM, Keystore alias `jemrec_crm_token`) and `JR/crm/SessionStore.kt` (session and setup fields of the data model in prefs file `crm`, device id made once)
- [X] T014 `JR/crm/CrmSync.kt` and `JR/crm/CrmSyncWorker.kt`: unique work `crm-sync` (KEEP, network constraint) that runs due actions one at a time, and `crm-sync-later` (REPLACE) for the next due time; empty action list for now (research R6)
- [X] T015 [P] Tests `JRT/crm/api/ApiModelsTest.kt` (every fixture of T003 parses; unknown fields and values follow §1), `JRT/crm/api/ApiErrorsTest.kt` (every row of the contract's table), `JRT/crm/OsloTimeTest.kt` (DST days, a phone in another zone), `JRT/crm/AppVersionTest.kt`, `JRT/crm/api/BodiesTest.kt` (bodies equal the document's requests)
- [X] T016 Test `JRT/crm/api/SalesEngineClientTest.kt` with MockWebServer: headers, no `Authorization`, 3xx and HTML 502 as network errors, `retry-after` read, each endpoint against its fixture
- [X] T017 Commit "Sales Engine: client, storage and queue skeleton"

## Phase 3: US1 - Get connected (P1) 🎯 MVP part 1

**Goal**: sign in, agree to the notice, choose the line, see the state, sign out.
**Independent test**: on the stand (or with a debug token on the mock), Settings → Sales Engine shows "Connected as …"; sign out returns to "Not connected".

- [X] T018 [US1] `JR/crm/GoogleSignIn.kt`: Credential Manager with `GetSignInWithGoogleOption(BuildConfig.SALES_ENGINE_WEB_CLIENT_ID)` for the button and `GetGoogleIdOption(filterByAuthorizedAccounts = true, autoSelectEnabled = true)` for silent renewal; returns the ID token or a reason; never stores it (research R2)
- [X] T019 [US1] `JR/crm/CrmAccount.kt`: sign in (exchange, store session, compare user id with the stored one and drop queued calls of another person, FR-044), renew when `expiresAt` is under 7 days away (called when the main screen opens), sign out (`DELETE`, clear token, set `signedOutAt`), refusal messages of §2.2, `min_app_version` check
- [X] T020 [US1] `JR/crm/WorkLine.kt`: active subscriptions (name, carrier, last digits), chosen line, its `simCountryIso`, `matches(callLogRow)` trying subscription id, then ICCID, then single-SIM (research R4)
- [X] T021 [US1] Measure on the A24 what the call log's `PHONE_ACCOUNT_ID` holds for SIM calls (debug diag op printing the last rows' component and id beside the subscription list); write the answer with device and date in research R4 and in `WorkLine.kt`'s comment
- [X] T022 [US1] `JR/crm/CrmSetup.kt`: notice fetch and acknowledge (409 -> refetch), `setupAt` set the first time sign-in, notice and line are all done; `state()` for the screen: not connected, needs notice, needs line, needs call-log permission, connected, needs sign-in, update required, blocked
- [X] T023 [P] [US1] `JR/ui/NoticeScreen.kt`: the server's text as given (title, intro, sections with paragraphs, bullet items, numbered items), **I agree** ([contracts/ui.md](contracts/ui.md))
- [X] T024 [US1] `JR/ui/SalesEngineSettingsScreen.kt`: account, notice, work line, what reaches the CRM (with the no-model / summaries-off warnings), permissions; row in `JR/ui/SettingsList.kt` with the state line; wired in `JR/ui/MainScreen.kt` and `JR/ui/MainViewModel.kt`
- [X] T025 [US1] Debug diag op `crm-token` (set a token and user by hand, §2.3) and `crm-state` (print session and setup, never the token) in `app/src/debug/java/com/jemcik/jemrec/diag/SpikeReceiver.kt`
- [X] T026 [P] [US1] Tests: `JRT/crm/CrmAccountTest.kt` (fake client: another user drops the queue, renewal window, each refusal), `JRT/crm/CrmSetupTest.kt` (state order, `setupAt` set once), `JRT/crm/WorkLineTest.kt` (matching rules)
- [X] T027 [US1] Screen tests in `JRT/ui/screens/SalesEngineScreenshots.kt`: settings in each state, notice, in the three looks; record, look at every PNG
- [X] T028 [US1] Commit "Sales Engine: sign in, notice and work line"

## Phase 4: US2 - A work call lands on its deal by itself (P1) 🎯 MVP part 2

**Goal**: after a work call, lookup, 2-minute window, step 1, logged.
**Independent test**: on the mock or stand, a call to a one-deal number is logged with no tap; a colleague's number sends nothing after the lookup.

- [X] T029 [P] [US2] `JR/crm/CallDecision.kt`: from a lookup (or none) to what to show and what to send: `internal` -> stop; `deal` + auto -> send `deal` with `chosen_by: auto` unless changed; `deal` without auto -> ask, preselect suggested, send `auto` if unanswered; `none` / failed -> send `auto` (§5.1 table, FR-022, FR-023)
- [X] T030 [P] [US2] `JR/crm/WorkCallMachine.kt`: local states and content states of [data-model.md](data-model.md), transitions as pure functions; content expected iff a recording exists
- [X] T031 [P] [US2] `JR/crm/CallLogRows.kt`: a call-log row to call facts: types (OUTGOING answered when duration > 0, INCOMING, MISSED/REJECTED/VOICEMAIL as unanswered incoming, BLOCKED and ANSWERED_EXTERNALLY skipped), presentation mapping (any other -> unknown), hidden -> phone null, duration 0 when unanswered (research R5, FR-016)
- [X] T032 [US2] `JR/crm/CallIntake.kt`: the sweep: rows after `lastCallLogId` and `setupAt`, on the work line, with Telephony's component only (FR-010, FR-015); one `WorkCallEntry` each with a new `clientCallId`; the recording matched as `CallLogLookup.callFor` does, and the answering machine's `message` mark (FR-017); retries up to 30 s after a call end for the row to appear; skips everything after a deliberate sign-out (FR-046)
- [X] T033 [US2] Trigger the sweep from the call's end in `JR/capture/CallTracker.kt` (`onCallRemoved`) and `JR/capture/CallMonitorService.kt` (`ACTION_CALL_ENDED`), from the app opening, and from the periodic job that runs the missed-call check; a recording saved later updates its work call's link (`JR/capture/RecordingSaver.kt`)
- [X] T034 [US2] Lookup action in `JR/crm/CrmSync.kt`: not for hidden numbers, not retried, stored on the call; `internal` -> nothing more (FR-012, FR-013)
- [X] T035 [US2] `JR/crm/CrmNotifications.kt`: channel "Sales Engine"; the after-call notification of [contracts/ui.md](contracts/ui.md) with **Private** and **Change/Choose**; replaced as the call moves; Private handled by a receiver that marks the call PRIVATE locally (FR-020, FR-021)
- [X] T036 [US2] Step-1 action in `JR/crm/CrmSync.kt`: body built once and stored (§6.3), due 2 minutes after the call ended or at the seller's confirm; answer stored; 201/200 handled alike; `internal` answer -> nothing more; notification updated
- [X] T037 [P] [US2] Tests: `JRT/crm/CallDecisionTest.kt` (each row of §5.1), `JRT/crm/WorkCallMachineTest.kt`, `JRT/crm/CallLogRowsTest.kt`, `JRT/crm/CallIntakeTest.kt` (Robolectric call-log provider: other SIM, messenger component, before setup, after sign-out, a row taken once)
- [X] T038 [US2] `JRT/crm/CrmSyncTest.kt` with MockWebServer: one-deal call logged with `chosen_by: auto` after the window; colleague sends nothing after the lookup; hidden number sends no lookup and `phone: null`; missed call has `transcript_expected: false` and no `next`; Private before the window sends nothing
- [X] T039 [US2] Commit "Sales Engine: work calls logged after the call"

## Phase 5: US3 - Choose the deal, or keep a call private (P2)

**Goal**: picker, pending list, fresh candidates, Make private.
**Independent test**: on the mock or stand, a two-deal call waits; picking the second logs it there; Make private on a not-in-CRM call answers `private`.

- [X] T040 [US3] `JR/ui/CallSheet.kt`: the deal part of the call sheet (radio rows with title, organisation, stage, won/lost chip, call-due mark; suggested preselected; Don't log; "N more deals: log this call in the web app"); **Log call** and **Private** / **Make private**
- [X] T041 [US3] The sheet as a bottom sheet over the app (CallSheetHost in MainScreen), opened from the notification through a guarded deep link (DeepLink.Kind.CRM_CALL) and from the Calls tab - no activity of its own: one way in, the app's own look
- [X] T042 [US3] Pick and private actions in `JR/crm/CrmSync.kt`: `GET /calls/{id}` before showing a waited call's candidates (§7), `POST /deal`, `deal_not_candidate` -> show again with fresh candidates, `POST /private` for anything not logged, 409 on a logged call -> "edited in the web app" (§10)
- [X] T043 [US3] CRM mark under a work call's row and a card over the list for the calls waiting for a deal, in `JR/ui/CallsScreen.kt` and `JR/ui/CallSheet.kt` (a card rather than a chip: it names what to do, and the chips stay the Recordings tab's rule); state from `crm.db` (SalesEngineController.workCalls)
- [X] T044 [P] [US3] Tests: `JRT/crm/CrmSyncPickTest.kt` (pick logs, stale pick shows fresh list, Don't log -> declined, private, 409 on logged); screen tests for the sheet (one deal, several, more than five, after sending) and the Calls tab marks; record, look at every PNG
- [X] T045 [US3] Commit "Sales Engine: choosing the deal and keeping calls private"

## Phase 6: US6 - Nothing lost (P2)

**Goal**: offline, outages, sign-in, update, blocked, another person, 30 days.
**Independent test**: three calls in flight mode reach the mock once each when the network returns.

- [X] T046 [US6] Outcomes of `ApiErrors` acted on in `JR/crm/CrmSync.kt`: backoff 1..60 min, `retry-after`, 401 -> NEEDS_SIGN_IN and hold, 403 notice -> hold and prompt, 426 -> hold everything, 409 duplicate -> adopt `GET /calls/{id}`, 400 `next` -> resend once without, future `started_at` -> 1 h, 404 call -> NOT_SENT, `forbidden` -> stop until sign-in
- [X] T047 [US6] Blocked device: delete `crm.db` and the session, stop, keep recordings and transcripts (FR-043) in `JR/crm/CrmAccount.kt`
- [X] T048 [US6] Too old: a call not sent 30 days after its start becomes NOT_SENT, shown on its row (FR-045)
- [X] T049 [US6] Prompts on the channel: "Sign in again", "Read the new notice", "Update JemRec", each opening the Sales Engine settings, in `JR/crm/CrmNotifications.kt`
- [X] T050 [P] [US6] Tests `JRT/crm/CrmSyncFailuresTest.kt` with MockWebServer: each outcome, a cut connection after the server stored the call (resend -> 200, one call), restart in the middle (state from `crm.db`), another person's sign-in drops the queue
- [X] T051 [US6] Commit "Sales Engine: calls wait and go once"

## Phase 7: US4 - The transcript and an English summary follow (P2)

**Goal**: step 2 after the summary, or failed.
**Independent test**: on the mock or stand, a recorded one-deal call with models and "after each call" ends with `content_status: ready`, an English summary in the note.

- [X] T052 [US4] Summaries in English for every call: `JR/summary/SummaryLanguage.kt` resolves to `en` (list in its doc comment what it did before and why it changes, FR-033) and `JR/summary/SummaryPrompt.kt` says the transcript may be in another language and the summary is English, names and numbers as said (research R9); update `JRT/summary/` tests
- [ ] T053 [US4] Re-run the faithfulness samples on the A24 with English output (Norwegian, Ukrainian, English calls, `app/src/androidTest/.../SummaryFaithfulnessTest.kt`); record times and what was right or wrong in research R9; fix the prompt if action items invent tasks
- [X] T054 [P] [US4] `JR/crm/ContentText.kt`: transcript lines `Seller:` / `Customer:` / `Call:` without times; summary text from `CallSummary` (intent, summary, then Key points, Decisions, Action items, Key facts with `• `, empty ones left out); cut at 200,000 / 10,000 UTF-16 units on a line end or a code point, never inside a surrogate pair; language `no` -> `nb`, BCP 47 check (research R8, FR-034, FR-037)
- [X] T055 [US4] `JR/crm/ContentHook.kt`: after a summary is stored (`JR/summary/SummaryWorker.kt` / `JR/transcribe/SummaryAfterTranscript.kt`) build the content for the recording's work call, once (FR-035); transcript done but summaries off or no summary model -> failed (FR-031); transcription failed -> failed; recording deleted in `JR/capture/Trash.kt` and the after-call delete -> failed (FR-036)
- [X] T056 [US4] Content action in `JR/crm/CrmSync.kt`: needs only the server id (§8); 72-hour deadline from the call's start -> failed; `failed` then `ready` once; content of a `private` call is answered 200 and stored nowhere
- [X] T057 [US4] The "What reaches the CRM" warnings in `JR/ui/SalesEngineSettingsScreen.kt` read the speech model, summary model and summaries switch (FR-030a)
- [X] T058 [P] [US4] Tests: `JRT/crm/ContentTextTest.kt` (labels, no times, headings, empty lists, cut at a surrogate pair, `no`->`nb`), `JRT/crm/ContentHookTest.kt` (first only, each failed case, deadline)
- [X] T059 [US4] Commit "Sales Engine: transcripts and English summaries"

## Phase 8: US5 - Outcome and next call (P3)

**Goal**: outcome chips and a next call in the sheet.
**Independent test**: on the mock or stand, "Call back" and a next call tomorrow 10:00 are in the step-1 body.

- [X] T060 [US5] Outcome chips and next call (date picker in Oslo dates from the call's Oslo date, optional time, subject up to 300) in `JR/ui/CallSheet.kt`; defaults: answered -> reached, else no answer; none for a missed incoming call (FR-025, FR-026)
- [X] T061 [US5] Outcome and next stored before step 1 and in the pick (`JR/crm/CrmSync.kt`); after step 1 the outcome is not resent (§6.3 ignores it)
- [X] T062 [P] [US5] Tests: bodies with outcome and next, Oslo date bounds; screen tests of the sheet with them; record, look at every PNG
- [X] T063 [US5] Commit "Sales Engine: outcome and next call"

## Phase 9: Every place that describes it

- [X] T064 [P] `README.md`: the feature in "What you can do", FAQ (what is sent, never audio, Private), privacy and permission lines (internet now sends calls to the Sales Engine; phone state for the work line), tests badge and count
- [X] T065 [P] `tools/build_site.py` (features, FAQ, manual's own section and Settings, notifications table, `PRIVACY_DATA` and its chips for the data now sent), then run it
- [X] T066 [P] `docs/ARCHITECTURE.md`: a section "The Sales Engine", the contents line, the permission table (`INTERNET` row, `READ_PHONE_STATE` no longer removed, `READ_CALL_LOG`), "Summaries" now English
- [X] T067 [P] `docs/DEVELOPMENT.md` and `tools/README.md`: test counts, the mock, the stand properties, the debug token op
- [ ] T068 [P] `tools/sales_engine_mock.py` (answers from `app/src/test/resources/crm/`, records requests) and an emulator check in `tools/emulator_checks.py` (debug token, simulated calls of each case, requests compared). The mock is written (2026-11-03); the emulator check is not yet.
- [ ] T069 [P] Demo data in `app/src/debug/.../DemoRecordings.kt` so the Calls tab marks show; screenshots of the new screens (`SCREENS` in `build_site.py`, `LISTING_ORDER` in `tools/shoot.py`, new names sort last), then `tools/shoot.py`
- [X] T070 Version 1.0.0 (`jemrecVersionName=1.0.0`, `jemrecVersionCode=10000`) in `gradle.properties` and the changelog `fastlane/metadata/android/en-US/changelogs/10000.txt` (at most 500 characters) (FR-047)
- [X] T071 Commit "Sales Engine: every place that describes it, and 1.0.0"

## Phase 10: Gates

- [X] T072 `./gradlew testDebugUnitTest lint :daemon-tests:errorProne` clean
- [ ] T073 Emulator checks on the Android 12, 13, 16 and 17 AVDs, two at a time (ask before creating an AVD)
- [ ] T074 The A24 against the stand, [quickstart.md](quickstart.md) section 3, once LANARS sent the host, client id and users; results with date in research.md

---

## Phase 11: The API of 2026-11-03 (revised 2026-11-04)

LANARS's new document: no Private, Other for "Don't log", `no_match` and the
deal search. Spec, research R7, R14, R15 and the contracts say what and why.

- [X] T075 Spec, plan, research, data model, contracts and quickstart revised; the new document in `reference/`
- [X] T076 Private gone: the notification's button and `CrmPrivateReceiver` (manifest too), `CrmActions.makePrivate`, `Local.PRIVATE`, `Action.MAKE_PRIVATE`, `SalesEngineClient.makePrivate`; the hold kept as `WorkCallMachine.HOLD_MS`; `crm.db` version 2 drops `privateAsked` (auto-migration, `JRT/crm/db/CrmMigrationTest.kt`)
- [X] T077 Other in place of Don't log in `JR/ui/CallSheet.kt`; marks "Other: not on a deal", "Kept outside the CRM" for an older private call; Settings footer says it is a work phone
- [X] T078 `PendingReason.NO_MATCH`; `SalesEngineClient.search` (`POST /calls/search`, `Bodies.search`, `ApiModels.deals`, `Endpoint.SEARCH`); the sheet's "Which lead?" mode with a debounced search (`SalesEngineController.search`), suggestions, Other, nothing ticked; "Which lead?" notification and mark
- [X] T079 Fixtures from the new document (`call-no-match`, `deal-found-*`, `search*`, notice 2026-11-03; Make private's removed); tests: pick for `no_match` (found deal, refused, Other), search body and client, blocked notifications; screenshot `call-sheet-which-lead`
- [X] T080 `tools/sales_engine_mock.py`: `/calls/search`, numbers ending in 9 answer `no_match`, no `/private`
- [X] T081 README, `tools/build_site.py` (manual, notifications table, FAQ, privacy) and ARCHITECTURE without Private, with Which lead?; site regenerated
- [ ] T082 Resend content with `revision` once LANARS describes it (research R15)

---

## Dependencies

- Setup (T001-T005) -> Foundation (T006-T017) -> every story.
- US1 -> US2 (calls need a session, notice and line).
- US2 -> US3, US6, US4 (they act on work calls); US6 before US4 (content
  rides on the same queue and its failures).
- US5 needs US3's sheet.
- Phase 9 after the stories; Phase 10 last. T074 waits on LANARS.

## Parallel opportunities

- Foundation: T006, T007, T008, T009, T011, T012, T013 are separate files.
- US1: T023 beside T018-T022; tests T026 beside the screen.
- US2: T029, T030, T031 together, then T032.
- Phase 9: T064-T069 together.

## Implementation strategy

MVP = US1 + US2: a seller connects, and calls to one-deal numbers land on
their deal by themselves. Then US3 (choice; Private until 2026-11-04), US6 (robustness),
US4 (content), US5. The stand is not needed until T074: the mock and a
debug token carry everything before it.
