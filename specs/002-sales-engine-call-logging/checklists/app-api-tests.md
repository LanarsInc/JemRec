# Sales Engine: API checks on the stand, and what to test in the app

Stand: `lanars-sales-engine-stand.vercel.app/api/v1`, user 1000000 (Bohdan
Plastun), API document of 2026-11-03, app 1.1.0 debug. Numbers below are
placeholders; no real number is written here.

## 1. API checks run with curl (2026-11-04)

Run from a script with the test token in an environment variable, against
made-up numbers (+380 50 000 00xx) and the document's examples (+47 400 00 0xx).
Calls 2-11 on the stand are these tests.

| # | Request | Expected (doc) | Got | |
|---|---|---|---|---|
| L1 | lookup, 5 numbers (doc examples, made-up) | `match` | `none` for all: **the stand has no deals** (search "as" finds 0) | ⚠ |
| C1 | step 1, `auto`, unknown number | 201 `not_in_crm` | 201 `not_in_crm` | ✅ |
| C2 | the same body again | 200, same state | 200, same state | ✅ |
| C3 | same `client_call_id`, duration +10 s | 409 `duplicate` | 409 `duplicate` | ✅ |
| C3b | same id, duration +1 s | 200 (within 2 s) | 200 | ✅ |
| C3c | same id, other `deal_choice` | 200, choice ignored | 200 | ✅ |
| C4 | hidden number, `phone: null` | 201 `hidden` | 201 `hidden` | ✅ |
| C5 | missed incoming, `transcript_expected: false` | 201, content `none` | 201 `not_in_crm`, `none` | ✅ |
| C6 | Other (`none`) on a number with no deal | `not_in_crm` (6.1) | `not_in_crm` | ✅ |
| C7 | unknown field | 400 | 400 `Unrecognized key` | ✅ |
| C8 | `answered: false`, duration 5 | 400 | 400 | ✅ |
| C9 | `started_at` 31 days old | 400 | 400 | ✅ |
| C10 | `started_at` 1 h ahead | 400 | 400 `in the future` | ✅ |
| C11 | `Idempotency-Key` header | 400 | 400 | ✅ |
| C12 | `next` on a missed incoming call | 400 | 400 | ✅ |
| C13 | `deal` with a deal that is not a candidate, number on no deal | `pending` `deal_not_candidate` (6.1) | 201 **`not_in_crm`** | ❓ ask LANARS |
| C14 | `deal_id` with `auto` | 400 | 400 | ✅ |
| C15 | unknown `presentation` | 400 | 400 | ✅ |
| C16 | no `x-app-version` | 426 when a minimum is set | 201: **no minimum on the stand** | ⚠ |
| C17 | wrong token | 401 | 401 | ✅ |
| C18 | body not an object | 400 | 400 | ✅ |
| S1-S2 | content `failed`, then `ready` | 200, 200 (once) | 200 `failed`, 200 `ready` | ✅ |
| S3 | the same `ready` again | 200 stored | 200 | ✅ |
| S4 | other texts after `ready` | 409 | 409 | ✅ |
| S5 | `failed` after `ready` | 409 | 409 | ✅ |
| S6 | `ready` after content `none` | 200 | 200 | ✅ |
| S7 | `summary_language: "uk"` | `en` only (8) | **200: not enforced** | ℹ the app always sends `en` |
| S8 | transcript 200,001 chars | 400 | 400 | ✅ |
| S9 | summary 10,001 chars | 400 | 400 | ✅ |
| S10 | transcript 200,000 chars (Cyrillic; emoji = 200,000 UTF-16 units), as org.json sends it | 200 | 200 | ✅ |
| S11 | `ready` without `language` | 400 | 400 | ✅ |
| S12 | `failed` with texts | 400 | 400 | ✅ |
| G1 | GET own call | 200 | 200 | ✅ |
| G2 | GET unknown call | 404 | 404 | ✅ |
| P1 | pick on a `not_in_crm` call | 409 `not waiting for a deal` | 409 | ✅ |
| Q1-Q4 | search: 1 char, 101 chars, " a ", unknown field | 400 | 400 | ✅ |
| Q5 | search "as" | ≤ 10 open deals | 0 (no deals on the stand) | ⚠ |
| M1 | old `/calls/{id}/private` | 404 `unknown endpoint` | 404 | ✅ |
| M2 | GET `/calls/search` | 405 | 405 | ✅ |

The app's own limits match: transcript cut at 200,000 UTF-16 units with
`transcript_truncated`, summary at 10,000 (`ContentText`).

Not run, on purpose: the rate limits (429) - they would block the test user
for 10 minutes to a day; ask LANARS to lower one for a test, or use the mock.

### To ask LANARS

1. C13: a `deal` pick for a number with no deal answers `not_in_crm`, not
   `pending` / `deal_not_candidate` as 6.1 says. Which is meant?
2. S7: is `summary_language` other than `en` meant to be accepted?
3. Seed the stand: numbers (+380) on one open deal, on two open deals, on a
   closed deal only, a colleague's number; a weekly goal for user 1000000.
4. Set a minimum app version (e.g. 1.2.0) for a test of 426, then remove it.
5. Revoke the test token once, and publish a new notice version once.
6. The Web client id and the SHA-1 registration, for Google sign-in.
7. `revision` for content (T082), when it is in the document.

### Content revision (2026-11-05)

LANARS: a resend goes as `POST /content` with `revision: 1`. On stand calls
20 and 21 (made-up numbers), with the API's field names:

| # | Request | Got |
|---|---|---|
| R1 | ready | 200 |
| R2 | other texts, no revision | 409 `duplicate` |
| R3 | other texts, `revision: 1` | 200, replaces |
| R4 | the same again, `revision: 1` | 200 |
| R5 | other texts, `revision: 1` again | 409 `duplicate` |
| R6 | `revision: 0`, other texts | 200 (a lower revision: kept or ignored, the API does not say) |
| R7 | `revision: 3`, skipping 2 | 200 |
| R8 | `revision: 2` after 3 | 200 |
| R10-R13 | 4, -1, 1.5, "x" | 400 "revision must be an integer from 0 to 3" |
| F1-F2 | failed, then ready with `revision: 1` | 200, 200 |
| F3 | failed with a revision | 400 "only allowed with content_status ready" |

To ask: is a lower revision stored or ignored (R6, R8)? The app never sends one.

## 2. What to test in the app (A24, debug build, stand)

Each line: what to do, what should happen. Calls are made by the tester.

### 2.1 Setup and account

- [ ] Fresh setup: Settings → Sales Engine shows the stand host; token (or Google) → name shown.
- [ ] Notice: read, **I agree**; "Agreed on ..." shown. Before agreeing, no call is sent.
- [ ] Work line: two SIMs listed, one chosen; the phone permission asked with why.
- [ ] Call log permission missing → "Allow the call log" row.
- [ ] Summaries off / no summary model → the warning under Summaries.
- [ ] Sign out → calls made while signed out are never sent (make one, sign in, check).
- [ ] Google sign-in (when LANARS registers it): lanars.com works; a gmail account is refused with its message.

### 2.2 Which calls go

- [ ] A call on the work SIM → notification, logged after 2 min.
- [ ] A call on the other SIM → nothing: no notification, no line, nothing on the stand.
- [ ] A Telegram (or other app) call → nothing.
- [ ] Calls from before setup → never sent.
- [ ] A colleague's number (when seeded) → "A colleague: not sent", nothing on the stand.
- [ ] Hidden number → "Hidden number: not on a deal", no lookup.
- [ ] Notifications blocked for JemRec → calls still go (`auto`).

### 2.3 Each kind of call (needs seeded numbers)

- [ ] Outgoing, answered, number on one open deal → "Logging in 2 min · <deal>", then "Logged on <deal>"; on the deal page the activity, the summary in its note, the transcript.
- [ ] The same with a planned call on the deal → the planned call is completed.
- [ ] Number on two open deals → "Choose a deal"; pick in the sheet before the 2 min → logged on it.
- [ ] Same, no pick in 2 min → "Choose a deal" stays (pending); pick later from the line or the card → logged.
- [ ] Deal changed in the web app before the pick → the sheet shows the fresh deals.
- [ ] Number on a closed deal only → pending (`only_closed_deals`); pick it → logged.
- [ ] Unknown number → "Not in the CRM · transcript sent".
- [ ] Other in the sheet → "Other: not on a deal".
- [ ] Outgoing not answered (hang up while ringing) → logged with No answer, no transcript.
- [ ] Missed incoming → logged as Missed call with a Call back on the deal.
- [ ] Next call set in the sheet (date, time, subject) → an open call on the deal at that Oslo time.
- [ ] Next call refused (e.g. a date the server will not take) → the call still logs without it.
- [ ] Weekly goal (when set): outgoing to an unknown number → "Which lead?" notification; search ≥ 2 chars finds deals; pick → logged, and the next call to that number finds the deal by itself.
- [ ] Weekly goal, Other → declined; incoming to an unknown number → not in the CRM.

### 2.4 Transcript and summary (step 2)

- [ ] Line goes "transcript to follow" → "transcript sent"; the server has `content_status: ready`.
- [ ] Short call (< 15 words) → summary "Short call", no model run; it is sent.
- [ ] Call to a voice menu → summary "Automated message ...", no decisions.
- [ ] Summaries off → "transcript not sent" + notification; turn on, **Send the transcript** → "transcript sent".
- [ ] No summary model → Send the transcript says to download one.
- [ ] Transcript sent → **Send the transcript again** (3 more times) → "making the transcript again" → "transcript sent again"; the server has the new texts (`revision` 1).
- [ ] Recording deleted before the transcript → "transcript not sent", no notification.
- [ ] Long call (> 5 min) → voices in several chunks; transcript under 200,000 chars or cut with `truncated`.
- [ ] Transcribe again with a pinned language → runs (not "Stopped" at once).
- [ ] Cancel during "Telling the two voices apart" → "Stopping…", ends within seconds; no automatic summary after.
- [ ] Summary right after the voice step on the A24 → waits for memory ("Waited ... for memory" in the log) instead of failing.

### 2.5 Network, retries, limits

- [ ] Airplane mode during and after the call → line "Not sent yet: no connection ...; trying again at HH:MM"; network back → sent.
- [ ] Settings → Calls shows "1 waiting to be sent" and the reason; **Retry now** → sent at once.
- [ ] Reboot with a call waiting → still sent afterwards.
- [ ] Captive portal / Wi-Fi without internet → treated as no network, never "sign in again".
- [ ] 429 (when LANARS lowers a limit): search says "Too many searches for now"; a call waits for `retry-after`.
- [ ] Phone clock set an hour ahead → "the phone's clock is ahead" (or the call waits), never refused for good.

### 2.6 Session and server changes (need LANARS)

- [ ] Token revoked → "Sign in to the Sales Engine again"; calls wait; after sign-in they go.
- [ ] New notice version → "Read the Sales Engine's new notice"; calls wait; after agreeing they go.
- [ ] Minimum app version above 1.1.0 → "Update JemRec to keep logging calls"; calls wait.
- [ ] Call deleted in the web app → line "Deleted in the CRM"; content sent anyway is accepted, nothing stored.
- [ ] Device or user blocked → sending stops; Settings says so.

### 2.7 What the user sees

- [ ] The line under the call in Calls, Recordings and on the transcript says the same thing.
- [ ] Tapping the line opens the sheet; the sheet's top line matches.
- [ ] "Waiting for a deal" card on the Calls tab counts the pending calls.
- [ ] Notifications: one per call, updated in place; "Logged on" goes by itself; "Transcript not sent" goes when it is sent.
- [ ] Dark theme and the Deck look: the sheet and Settings read well.

### 2.8 Before a release

- [ ] Emulator checks on the 4 AVDs (`tools/emulator_checks.py`), mock server included (T068, T073).
- [ ] Demo data and screenshots (T069).
- [ ] English summaries measured on the A24 (T053).
- [ ] The whole quickstart §3 on the stand (T074).
