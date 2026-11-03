# Contract: the app's client of the Sales Engine API

The server's contract is [the API document](../reference/sales-engine-api-2026-11-02.md);
this is the app's side of it: what each call takes and returns, and what an
answer makes the app do. Everything here is plain Kotlin, Android-free, so it
is unit tested off the phone (CLAUDE.md).

## Every request

- Base URL from `BuildConfig` (research R11); paths below are under it.
- Headers: `x-app-version: <versionName>` always; `x-api-token` on all but
  the exchange; `content-type: application/json` with a body. Never
  `Authorization`, never `Idempotency-Key` (§1).
- No redirects: a 3xx is a network error. Timeouts: connect 15 s, read 30 s.
- An answer that is not a JSON object with `data` or `error` is a network
  error (§1 "Not an answer"), whatever its status.

## Calls

| Function | Request | Success |
|---|---|---|
| `exchange(idToken, device)` | `POST /mobile/session` | `Session` (token, prefix, expiresAt, idleDays, user, notice{version, acknowledged}, minAppVersion?) |
| `signOut()` | `DELETE /mobile/session` | Unit; a 401 also counts as done |
| `notice()` | `GET /mobile/notice` | `Notice` (version, acknowledged, title, intro, sections[heading, paragraphs?, items?, ordered?]) |
| `acknowledge(version)` | `POST /mobile/notice` | `acknowledgedAt` |
| `me()` | `GET /me` | user, key |
| `lookup(phone, countryIso?)` | `POST /calls/lookup` | `Lookup` |
| `postCall(body)` | `POST /calls` | `CallState`, and whether it was new (201) |
| `pickDeal(id, choice, dealId?, next?)` | `POST /calls/{id}/deal` | `CallState` |
| `postContent(id, content)` | `POST /calls/{id}/content` | `CallState` |
| `getCall(id)` | `GET /calls/{id}` | `CallState` |
| `makePrivate(id)` | `POST /calls/{id}/private` | `CallState` |

`CallState` is the call object of §6.2; unknown `status` -> final, unknown
`pending_reason` -> `several_open_deals`, unknown `match` -> `none`. Ids are
`Int` (1 .. 2,147,483,647); `call.id` may be null only with `internal`.

## Failures: what the app does

Every failure is one of these, decided by `ApiErrors.classify(endpoint,
status, error, detail, retryAfter)`:

| Answer | Outcome | The app |
|---|---|---|
| network error, timeout, not JSON, 3xx, 500 | `Retry` | backoff 1, 2, 4 ... 60 min |
| 409 `conflict` on a call | `Retry` | backoff (parallel write) |
| 429 on lookup | `GiveUp` | send step 1 with `auto` (§5) |
| 429 elsewhere | `RetryAfter(s)` | wait the header's seconds |
| 401 on exchange | `SignInFailed` | "Sign in again" |
| 401 elsewhere | `SessionDead` | state `NEEDS_SIGN_IN`; queue holds; notification |
| 403 `notice_required` | `NoticeRequired` | queue holds; show the notice |
| 403 on exchange, `detail` `no_user` / `domain_not_allowed` / `email_unverified` / `inactive` / `account_mismatch` | `Refused(reason)` | the matching message (§2.2) |
| 403 `device_blocked` (exchange) | `Blocked` | delete session and `crm.db`; stop |
| 403 `forbidden` elsewhere | `Forbidden` | stop sending until the next sign-in |
| 404 `unknown endpoint` | `Retry` (long) | server older than the app; hold |
| 404 on a call | `CallGone` | mark it `NOT_SENT` |
| 409 `duplicate` | `Adopt` | `GET /calls/{id}`, take its state |
| 409 notice not current | `NoticeChanged` | fetch the notice, show it |
| 426 | `UpgradeRequired(min)` | hold everything; "Update the app" |
| 400 naming `next` | `DropNext` | resend once without `next` |
| 400 on `started_at` in the future | `RetryIn(1 h)` | |
| 400 other, 405 | `Bug` | mark the step failed, keep `detail` (no values in it, §1) |

## Bodies the app builds

- **Step 1** (`StepOneBody.build(call, line)`): from a `WorkCall`, once.
  `phone` null unless presentation is `allowed`; `deal_id` only with `deal`;
  `next` never for a missed incoming call; `transcript_expected` = a
  recording exists and is not deleted; times as research R10.
- **Pick**: `{"deal_choice":"deal","deal_id":…, "next":…}` or
  `{"deal_choice":"none"}`.
- **Step 2** (`ContentBody.ready(transcript, summary)` / `failed()`):
  research R8. Lengths in UTF-16 units.
