# Data model: Logging work calls in the Sales Engine

Kept in `crm.db` (Room) unless said otherwise. Field names are the plan's,
not the API's; the API's are in [contracts/api-client.md](contracts/api-client.md).

## Session (prefs, token encrypted - research R3)

| Field | Type | Notes |
|---|---|---|
| token | String, encrypted | 40 hex; never shown or logged |
| tokenPrefix | String | first 8, for diagnostics |
| expiresAt | Instant | renew when under 7 days away |
| userId | Int | `data.user.id`; a different one on sign-in = another person |
| userName, userEmail | String | shown in Settings |
| minAppVersion | String? | from the exchange |
| deviceId | UUID v4 | made once per install, never changes |
| state | enum | `SIGNED_OUT`, `ACTIVE`, `NEEDS_SIGN_IN`, `UPGRADE_REQUIRED`, `BLOCKED` |
| signedOutAt | Instant? | set by a sign-out on purpose (FR-046) |

## Setup (prefs)

| Field | Type | Notes |
|---|---|---|
| noticeVersion | String? | the version acknowledged on this phone |
| noticeCurrent | String? | the current one, when the server says it changed |
| workSubscriptionId | Int? | the chosen SIM |
| workCountryIso | String? | its SIM country |
| setupAt | Instant? | first time sign-in, notice and line were all done; calls before it are never taken |
| lastCallLogId | Long | the sweep's watermark |

## WorkCall (table `work_call`)

One call on the work line, from the call log.

| Field | Type | Notes |
|---|---|---|
| id | Long PK | local |
| clientCallId | String UUID v4 | made once (research R5), unique |
| callLogId | Long | unique: a row is taken once |
| userId | Int | the person it was queued for (FR-044) |
| direction | `OUTGOING` / `INCOMING` | |
| answered | Boolean | false => duration 0 |
| phone | String? | as the call log has it; null when hidden |
| presentation | `ALLOWED` / `RESTRICTED` / `UNKNOWN` / `PAYPHONE` | |
| countryIso | String? | |
| startedAt | Instant | call log `DATE` |
| durationSeconds | Int | 0..86,400 |
| recording | String? | file name, when one was matched |
| byMachine | Boolean | the answering machine took it (FR-017) |
| lookup | Lookup? (JSON) | `match`, candidates, totals, auto and suggested ids; null = not made or failed |
| choice | `AUTO` / `DEAL` / `NONE` | |
| dealId | Int? | with `DEAL` |
| chosenBy | `AUTO` / `USER` | |
| outcome | enum of six | default from answered (FR-025) |
| next | Next? (JSON) | date, time, subject, in Oslo time |
| localState | see below | |
| step1Body | String? | built once, resent unchanged (§6.3) |
| sendAfter | Instant | end of the 2-minute hold, or a retry time |
| serverId | Int? | `call.id` |
| server | CallState? (JSON) | the last call object the server sent |
| contentState | see below | |
| contentBody | String? | the step-2 body, once built (FR-035) |
| attempts | Int | for backoff |
| lastError | String? | for the screen and diagnostics; no number, no text |

### Local state of a call

Private and Make private went with the API of 2026-11-03; `crm.db` version 2
drops their column (a version-1 row in the PRIVATE state reads as not sent;
only debug installs had any). A `pending` call with `pending_reason`
`no_match` takes any open deal in its pick, found by `POST /calls/search`.

```
NEW -> LOOKED_UP -> WAITING (2-minute hold)
  LOOKED_UP --internal--> INTERNAL (final, nothing more sent)
  WAITING --window ends or seller confirms--> SENDING
  SENDING --2xx--> SENT  (server state from then on)
  SENDING --too old / refused--> NOT_SENT (final, visible)
  SENT --seller picks--> PICK_SENDING --> SENT
  any --another person signs in / blocked--> DROPPED (deleted)
```

Final server statuses: everything but `pending` (§6.2); an unknown one is
final too.

### Content state

```
NOT_EXPECTED (no recording)                      final
EXPECTED -> READY_TO_SEND -> SENT_READY          final
EXPECTED -> FAILED_TO_SEND -> SENT_FAILED -> READY_TO_SEND (once) -> SENT_READY
EXPECTED --72 h after start--> FAILED_TO_SEND
```

`FAILED_TO_SEND` when: transcription failed; summaries off or no summary
model when the transcript is done; the recording deleted; 72 hours passed.

## Lookup and DealCandidate (JSON inside WorkCall)

As §5: `match` (`deal`, `internal`, `none`; unknown -> `none`), up to five
candidates (`dealId`, `title`, `orgName?`, `status`, `stageName`,
`owner{id,name}`, `hasDueCall`), `candidatesTotal`, `autoDealId?`,
`suggestedDealId?`.

## Next (JSON inside WorkCall)

`dueDate` (Oslo, on or after the call's Oslo date), `dueTime?` (`HH:MM`),
`subject?` (up to 300; empty means the server's "Follow up").
