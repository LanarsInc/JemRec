# Research: Logging work calls in the Sales Engine

The API is [reference/sales-engine-api-2026-11-02.md](reference/sales-engine-api-2026-11-02.md),
cited as §n. Decisions made with the user are in the spec (2026-11-03); this
file is the how.

## R1. HTTP and JSON

- **Decision**: `HttpURLConnection` (with the Conscrypt provider the app
  already installs) and `org.json`, in one small client class. No OkHttp,
  Retrofit or kotlinx.serialization.
- **Rationale**: ten endpoints, one request at a time, JSON bodies of known
  shape. The app already parses its summaries with `org.json` on purpose
  (`CallSummary`: "six known keys ... does not justify adding a compiler
  plugin"). `HttpURLConnection` lets us refuse redirects
  (`instanceFollowRedirects = false`, §1), set timeouts, and read the
  `retry-after` header. Unknown fields are ignored for free with `org.json`
  (§1 "New fields").
- **Alternatives**: OkHttp (+1 MB, its own TLS stack; nothing here needs
  HTTP/2 or pooling); kotlinx.serialization (compiler plugin for ~15 shapes).
- **Tests**: `okhttp3:mockwebserver` 4.12.1, test only: the client against
  every JSON body of the API document.

## R2. Sign in with Google

- **Decision**: Credential Manager, `androidx.credentials:credentials` and
  `credentials-play-services-auth` 1.6.0 (latest stable; 1.7.0 is alpha), and
  `com.google.android.libraries.identity.googleid:googleid` 1.2.1, with
  `GetSignInWithGoogleOption(serverClientId = <Web client id>)`: the "Sign in
  with Google" button flow, which lets the seller pick any account. No hosted
  domain filter in the app (§2.1: the server checks both domains).
- **Rationale**: the API needs a Google ID token with `aud` = the Web client
  id and `azp` = our Android client (§2.1); Credential Manager is Google's
  current way to get one; the legacy `GoogleSignInClient` is deprecated.
- **Silent renewal**: `GetGoogleIdOption(filterByAuthorizedAccounts = true,
  autoSelectEnabled = true)` returns a fresh token without UI when the seller
  has signed in before; it still needs an Activity. So renewal (§2.3: when
  `expires_at` is under 7 days away) runs when the app's screen opens; a
  token that dies anyway means a notification "Sign in again". Never from a
  network error (§1 "Not an answer").
- **The ID token** is sent at once and never stored (§2.2).
- **Alternatives**: AppAuth / browser OAuth (needs a redirect and the user to
  type their account; the API asks for a Google ID token, not an auth code).

## R3. The token at rest

- **Decision**: the 40-hex token is encrypted with an AES-GCM key in the
  Android Keystore (`KeyGenParameterSpec`, no user authentication) and kept
  in its own `SharedPreferences` file; only the ciphertext and IV are on
  disk. `device.id` (UUID v4, lower case) is made once per install and kept
  in plain prefs.
- **Rationale**: backups are already off (`allowBackup="false"`); the
  Keystore keeps a copied data folder from yielding the token.
  `androidx.security:security-crypto` is deprecated (2025) and wraps the same
  thing.

## R4. The work line

- **Decision**: the seller chooses among `SubscriptionManager`'s active
  subscriptions (name, carrier, last digits); with one, it is chosen for them.
  A call belongs to the line when its call-log row's
  `PHONE_ACCOUNT_COMPONENT_NAME` is the phone's own Telephony component and
  its `PHONE_ACCOUNT_ID` matches the chosen subscription. `country_iso` is
  `TelephonyManager.createForSubscriptionId(id).simCountryIso` (the SIM's, not
  the network's: roaming must not change it).
- **Permission**: listing subscriptions needs `READ_PHONE_STATE`, which the
  manifest removes today (`tools:node="remove"`, it was pulled in by the ADB
  library). It becomes a requested permission, asked for in the integration's
  setup only.
- **Open, measured during implementation (T-measure)**: what
  `PHONE_ACCOUNT_ID` holds on the A24 and the Honor: the subscription id, the
  ICCID, or something else. OEMs differ (AOSP writes the subscription id as a
  string; older Samsung builds wrote the ICCID). The match tries the
  subscription id, then the ICCID (`SubscriptionInfo.iccId` needs
  `READ_PHONE_STATE` too), then, with one active SIM, any Telephony row.
  **Measured on the Galaxy A24 (SM-A245F, Android 14), 2026-11-03**, one SIM
  (KYIVSTAR, slot 0): every call-log row's `PHONE_ACCOUNT_ID` was `1`, the
  SIM's subscription id (`dumpsys isub`: `subId=1`), with Telephony's own
  component. So the first match holds there. Not yet seen: a phone with two
  SIMs in, and the Honor.
- **Messenger and other calling apps**: their call-log rows have another
  component (`VoipApps`, Google Meet through Telecom), so the component check
  excludes them (FR-015).

## R5. Where a call comes from

- **Decision**: the **call log** is the source of every work call: its row
  gives direction and type, number and `NUMBER_PRESENTATION`, `DATE` (start,
  with ringing), `DURATION`, and the line. A sweep reads rows newer than the
  last one taken (by `_ID` and `DATE`), on the work line, after the setup
  instant. It runs when a call ends (both paths that already know it: the
  tracker's `onCallRemoved` and the daemon's `CALL_ENDED`), when the app
  opens, and from the periodic job the missed-call check already has.
- **Why not the recording**: unanswered and missed calls have none (FR-011),
  and a call the rules left out has none either; the spec wants all of them.
- **The call log is written late**: Android writes the row a moment after
  the call ends. The sweep after a call end retries for up to 30 s (the same
  pattern `RecordingSaver.logged` uses with `pause()`).
- **Types**: `OUTGOING` (answered when `DURATION > 0`), `INCOMING`
  (answered), `MISSED`, `REJECTED`, `VOICEMAIL` -> incoming unanswered;
  `BLOCKED` and `ANSWERED_EXTERNALLY` are not sent (the seller blocked the
  number; another device took it).
- **Recording link**: a recording is matched to its row the way the app
  already names files (`CallLogLookup.callFor(start, incoming)`), and the
  match is stored on the work call. The answering machine's calls carry the
  recording's `message` mark (`RecordingTags.Facts.message`): sent as
  answered, outcome `no_answer` (FR-017); its test calls are in `CallLedger`
  and are skipped.
- **`client_call_id`**: a UUID v4 made when the row is first taken, stored
  with the row's `_ID` so a second sweep never makes another.

## R6. Delivery

- **Decision**: a Room database of its own, `crm.db` (not `transcripts.db`:
  the two have nothing to join and different lifetimes). One
  `CoroutineWorker`, unique work `crm-sync` (policy `KEEP`), network
  constraint, runs every due action in order, one request at a time, and
  ends by scheduling itself (`crm-sync-later`, `REPLACE`) for the earliest
  next due time: the 2-minute Private window, a `retry-after`, a backoff.
- **Not in the heavy chain**: transcription and summaries are one chain that
  nothing may fail (ARCHITECTURE, "When"). Sync is separate and may retry.
- **Errors** follow §11 exactly, as a pure function from (endpoint, status,
  error, detail) to an action: retry with backoff (1, 2, 4 ... 60 min), wait
  `retry-after`, sign in, show notice, hold for update, adopt server state
  (`GET /calls/{id}` after 409 `duplicate`), mark failed. Lives in plain
  Kotlin with unit tests (CLAUDE.md: decisions stay testable off the phone).
- **Step 1's body** is built once, stored as text, and resent unchanged
  (§6.3). A 400 naming `next` resends once without it; a 400 on a future
  `started_at` waits an hour (§11).
- **30 days**: a call whose step 1 has not gone 30 days after its start is
  marked "not sent: too old" (FR-045) rather than sent to be refused.

## R7. After the call: the notification

- **Decision**: one notification per work call on a new channel "Sales
  Engine", posted after the lookup (or at once if offline), showing the CRM's
  answer: "Eksempel AS – nettside" with **Private** and **Change**; with
  several open deals "2 deals - choose" with **Private** and **Choose**; not
  in CRM "Not in the CRM" with **Private**. Choose/Change open a small
  activity (like `RecordPromptActivity`) with the candidates (preselected
  `suggested_deal_id`), "Don't log", the outcome and the next call. The call
  is sent 2 minutes after it ended, or at once when the seller confirms.
- **Rationale**: a notification can carry at most three actions, and
  candidates are up to five, so the choice is a screen. The existing
  after-call notification (save/delete) stays; the CRM's is separate so
  either can be off.
- **Private** before step 1: the call is marked private locally and nothing
  more is sent (§13). After step 1 the same button calls `/private` (§10),
  which works for anything not `logged`.

## R8. What step 2 sends

- **Transcript**: one line per merged turn, `Seller: ...` for You and
  `Customer: ...` for Them; lines with no side get `Call: ...`. No times.
  Cut at 200,000 UTF-16 units on a line end, else on a code point boundary
  (never inside a surrogate pair), with `transcript_truncated: true`.
- **Summary**: from `CallSummary`: intent, a blank line, summary, then each
  non-empty list under its heading with `• ` bullets; cut at 10,000 units the
  same way (the grammar's output is far below it).
- **Language**: the transcript's `TranscriptFacts.language`, which is the
  pinned or detected main language; `no` becomes `nb` (FR-037); the code is
  validated as BCP 47 (2-35 chars).
- **First only** (FR-035): the texts sent are stored on the work call; a
  later transcript changes nothing upstream.

## R9. English summaries

- **Decision**: `SummaryLanguage` resolves to `en` always; the prompt says
  the transcript may be in another language and the summary is written in
  English, keeping names, numbers and quoted terms as said. The phone shows
  the same English summary (FR-033).
- **Risk, measured before it ships**: Qwen3 1.7B, left to itself on a
  Norwegian call, once filled action items with tasks nobody gave
  (ARCHITECTURE, "Summaries"); that was fixed by saying what belongs in
  decisions and action items, which stays. Re-run the faithfulness samples
  (Norwegian, Ukrainian, English) on the A24 with English output and record
  the result here.

## R10. Time

- Instants are sent as `YYYY-MM-DDTHH:MM:SS.sssZ` in UTC
  (`DateTimeFormatter.ISO_INSTANT` on an `Instant` truncated to millis).
- Next-call dates and times are `Europe/Oslo` (§1): the picker shows Oslo
  dates, never earlier than the call's Oslo date (§6 `next`).
- `started_at` is checked against the phone clock: more than 10 minutes in
  the future means the clock is wrong; the call waits (§11).

## R11. Configuration and the stand

- **Decision**: `gradle.properties` holds `salesEngineUrl`
  (`https://crm.lanars.no/api/v1`), `salesEngineStandUrl` and
  `salesEngineWebClientId` (both empty until LANARS sends them); they become
  `BuildConfig` fields (`buildFeatures.buildConfig = true`). Release uses
  production; debug uses the stand when set, else production. A debug-only
  diag op sets a token by hand (§2.3: LANARS can issue one) so the API can be
  tried before sign-in works.
- `x-app-version` and `device.app_version` are `versionName`.

## R12. Testing

- Plain-Kotlin units: error mapping, deal decision table (§5.1), state
  machine of a work call, step-1 body, step-2 texts, truncation, language,
  Oslo dates, version comparison, call-log row to call facts.
- Client tests against MockWebServer with every example body of the API
  document.
- Screen tests for the new screens (Roborazzi), PNGs looked at.
- An emulator check: a small mock of the API in `tools/` (Python, from the
  document's examples), reached from the emulator at `10.0.2.2`, a simulated
  call, and the requests it received checked.
- On the A24: the stand, once LANARS sends it (quickstart).

## R13. Version

- `1.0.0` (`versionCode` 10000) is set with the feature's release, last
  (FR-047). The server compares dotted numbers and ignores `-debug` (§12).
