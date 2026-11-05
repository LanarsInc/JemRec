# Sales Engine API for the Android call app

The HTTP API the Android call app uses to log calls in the LANARS Sales Engine, LANARS's CRM. Date: 2026-11-03.

- Production: `https://crm.lanars.no/api/v1`. Test stand: `https://<stand-host>/api/v1` (LANARS sends the host).
- Every JSON body below is what the server really sends or takes; a contract test on the server compares each one
  with the live answer. Build the mock server from them. Ids, names, numbers and addresses are invented
  (`+47 400 00 0xx`, `example.no`). In the notice example, `"…"` marks where the example is cut.

## What changed (2026-11-03)

- **`pending_reason: "no_match"`.** LANARS sets a weekly call goal for some sellers, and their outgoing calls are
  all linked to a lead. For such a user, an outgoing call to a number with no deal answers `pending` with
  `pending_reason: "no_match"`, and its `candidates` are suggestions (6.1, 6.2).
- **Any open deal.** For a `no_match` call, `POST /calls/{call_id}/deal` takes any open deal, not only a candidate
  (section 7).
- **`POST /calls/search`**, a new endpoint: open deals by deal title or company name, for the picker (section 10).
- **Other** replaces "Don't log" in every picker. On the wire it is still `deal_choice: "none"`, which answers
  `declined`.
- **Removed (it is a work phone):** Make private (`POST /calls/{call_id}/private` now answers 404 like any unknown
  endpoint), Private in the notification and the call list, Pause logging and always-private numbers. Every call on
  the work line is sent.
- **Blocked notifications** no longer skip calls: those calls go with `deal_choice: "auto"` (section 13).
- **`deleted`** (the call's activity was deleted in the CRM): the server now keeps the call's number and texts.
- **The notice** is version `2026-11-03`, rewritten for all of this: every phone acknowledges it again (section 3).

## 1. Basics

| Rule | Value |
|---|---|
| Headers | `x-api-token: <token>` on every request except `POST /mobile/session`; `x-app-version: <versionName>` on every request; `content-type: application/json` with a body |
| Bodies | one JSON object, at most 1,000,000 characters. Unknown fields are refused (400 `invalid_input`, `detail` names the field, never its value) |
| Optional fields | may be omitted or sent as `null`; both mean "not set" |
| Answers | a `data` object with 200 or 201, or an `error` code with an optional `detail` (section 11); always JSON with `cache-control: no-store` |
| New fields | answers may gain fields and values. Ignore unknown fields; treat an unknown call `status` as final, an unknown `pending_reason` as `several_open_deals` and an unknown `match` as `none` |
| Not an answer | anything else (an HTML 502 from the platform, a captive portal, an empty or cut body) is a network error: retry later, never sign in again because of it |
| `detail` | text for people and logs. Parse it only on a 403 from `POST /mobile/session` |
| Ids | JSON integers from 1 to 2,147,483,647 |
| Times | instants in UTC ISO 8601, answered as `YYYY-MM-DDTHH:MM:SS.sssZ`. Dates (`YYYY-MM-DD`) and times (`HH:MM`) of a next call are Europe/Oslo, whatever the phone's time zone |
| `Idempotency-Key` | refused on every endpoint here (400). Calls are idempotent on their `client_call_id` instead (6.3) |
| Redirects | none: treat a 3xx as an error |

## 2. Sign-in and token

### 2.1 The Google ID token

The user signs in with the Google Workspace account they use in the Sales Engine web app (`lanars.com` or
`lanars.no`). The app requests a Google ID token from Sign in with Google with the Sales Engine's **Web** client id
as the server client id, and sends it at once to `POST /mobile/session`. The server accepts it only when it is
signed by Google (RS256) and issued by `accounts.google.com`; `aud` is the Web client id and `azp` one of the app's
Android client ids; it is unexpired (60 s skew) and at most 10 minutes old; it was never used before; and its
account is a verified address in an allowed domain of an active Sales Engine user.

LANARS sends the Web client id and registers one Android client per signing certificate (package name and SHA-1).
Do not filter by hosted domain in the app: the server checks both domains.

### 2.2 `POST /api/v1/mobile/session`

No `x-api-token`. Request:

```json
{
  "id_token":"<Google ID token>",
  "device":{
    "id":"0b7f6c1e-2d4a-4e8b-9f31-6a5c2e7d8b90",
    "label":"Example Phone 12",
    "app_version":"1.4.0",
    "android_sdk":35
  }
}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `id_token` | string, at most 8,192 characters | yes | the Google ID token, never stored by the app |
| `device.id` | UUID v4, lower-case | yes | made once per install and kept; it identifies this phone |
| `device.label` | string, 1–100 | yes | make and model, e.g. `Build.MANUFACTURER + " " + Build.MODEL` |
| `device.app_version` | string, 1–32 | yes | the `versionName` |
| `device.android_sdk` | integer | yes | `Build.VERSION.SDK_INT` |

Response, 201. `token` is 40 random lower-case hex characters and `token_prefix` its first 8; `notice` says
whether this phone has acknowledged the current notice (section 3); `min_app_version` is null when there is no
minimum.

```json
{
  "data":{
    "token":"0123456789abcdef0123456789abcdef01234567",
    "token_prefix":"01234567",
    "expires_at":"2027-01-31T09:00:00.000Z",
    "idle_timeout_days":30,
    "scopes":["calls:lookup","calls:write"],
    "user":{"id":1000001,"name":"Test Seller","email":"android-test@example.no","role":"member"},
    "notice":{"version":"2026-11-03","acknowledged":false},
    "min_app_version":"1.4.0"
  }
}
```

Errors:

| HTTP | Body | Meaning | What the user should see |
|---|---|---|---|
| 400 | e.g. `{"error":"invalid_input","detail":"device.id: must be a lower-case UUID v4"}` | a field is missing or wrong | (app bug) |
| 401 | `{"error":"unauthorized"}` | the ID token failed verification; no reason is given | "Sign in again" |
| 403 | `{"error":"forbidden","detail":"no_user"}` | the account is not a Sales Engine user | "Ask LANARS to add you" |
| 403 | `detail` `domain_not_allowed` or `email_unverified` | not a verified lanars.com or lanars.no account | "Use your lanars.com or lanars.no account" |
| 403 | `detail` `inactive` | the user is deactivated | "Your Sales Engine account is inactive" |
| 403 | `detail` `account_mismatch` | the address belongs to another Google account | "Contact LANARS" |
| 403 | `{"error":"forbidden","detail":"device_blocked"}` | LANARS blocked this phone (lost or replaced) | delete the token and all local call data, and stop |
| 426 | `{"error":"upgrade_required","detail":"minimum app version 1.4.0"}` | `x-app-version` is missing or older than the minimum | "Update the app" |
| 429 | `{"error":"rate_limited"}` | more than 20 exchanges by this user today (Oslo) | retry after `retry-after` seconds |

### 2.3 Using the token

- Send it in `x-api-token` only (`Authorization: Bearer` is refused). Treat it as an opaque string.
- It works 90 days after the exchange (`expires_at`) and stops after 30 days without use (`idle_timeout_days`).
  There is no refresh token: renew with a new exchange, e.g. when `expires_at` is less than 7 days away.
- One live token per phone: an exchange for the same `device.id` revokes the previous token at once.
- A 401 `{"error":"unauthorized"}` means the token is dead (revoked, expired, idle, signed out, user
  deactivated, phone blocked): sign in again, then retry. If the exchange answers 403, act on its `detail`.
- If a new exchange returns another `data.user.id`, the phone changed hands: nothing queued for the previous user
  may be sent with the new token.
- The token reaches only the endpoints in this document and `GET /me`. Anything else answers 403
  `{"error":"forbidden","detail":"key lacks the scope"}`.

On the stand, LANARS can also issue a test token directly (optionally short-lived) and send it privately; it
behaves exactly like an exchanged one, including the notice rule.

### 2.4 `DELETE /api/v1/mobile/session`

Signs this phone out. No body. Response, 200: `{"data":{"signed_out":true}}`. The token stops working at once. A
401 means it had already stopped, which also counts as signed out.

## 3. Notice

Before any lookup or call upload, the user reads LANARS's notice on the phone (English only), and the app records
that. Lookups, the deal search and the call writes (`POST /calls/lookup`, `/calls/search`, `POST /calls`, `/deal`,
`/content`) answer 403
`{"error":"notice_required"}` until this phone has acknowledged the current version. `GET /me`,
`GET /calls/{call_id}`, the notice itself and sign-out are not held back. When LANARS changes the notice, its
version changes, every phone has to acknowledge again, and calls wait until it has.

### 3.1 `GET /api/v1/mobile/notice`

Response, 200 (the example is cut at `"…"`; the real answer has every item and section):

```json
{
  "data":{
    "version":"2026-11-03",
    "acknowledged":false,
    "title":"Call logging in LANARS Sales Engine",
    "intro":"After a call on your work line, this app sends the call to the Sales Engine, LANARS's CRM, so it shows on the right deal. Read this before the app sends anything.",
    "sections":[
      {
        "heading":"What is sent",
        "items":[
          "This is a work phone: every call on the work line is sent to the CRM, except calls with colleagues. Make personal calls on another phone.",
          "…"
        ]
      },
      "…"
    ]
  }
}
```

`version` is an opaque string. Each section has a `heading` and optional `paragraphs`, `items` and `ordered`
(numbered items); all texts are plain strings. Show them as they are.

### 3.2 `POST /api/v1/mobile/notice`

Request: `{"version":"2026-11-03"}`, the version the user read. Response, 200, with the time of the first
acknowledgement (a repeat keeps it): `{"data":{"version":"2026-11-03","acknowledged_at":"2026-11-03T08:01:12.000Z"}}`.

A version that is no longer current answers 409 `{"error":"conflict","detail":"the notice version is not current"}`: fetch the notice
again and show it.

## 4. `GET /api/v1/me`

Who the token acts as; use it as a connectivity and token check. Response, 200:

```json
{
  "data":{
    "user":{"id":1000001,"name":"Test Seller","email":"android-test@example.no","role":"member"},
    "key":{
      "name":"device Example Phone 12",
      "prefix":"01234567",
      "kind":"device",
      "scopes":["calls:lookup","calls:write"],
      "lead_source":null
    }
  }
}
```

## 5. `POST /api/v1/calls/lookup`

After a call on the work line ends, the app asks whether the number belongs to the CRM. The lookup stores nothing.

Request:

```json
{"phone":"400 00 001","country_iso":"no"}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `phone` | string, 1–64 | yes | the number as the call log has it. Never look up a hidden number |
| `country_iso` | 2 letters, any case | no | the work line's SIM country; omit it when unknown (Norway is the default) |

Response, 200, the number belongs to CRM deals:

```json
{
  "data":{
    "match":"deal",
    "candidates":[
      {
        "deal_id":1000456,
        "title":"Eksempel AS – nettside",
        "org_name":"Eksempel AS",
        "status":"open",
        "stage_name":"Contact attempted",
        "owner":{"id":1000001,"name":"Test Seller"},
        "has_due_call":true
      },
      {
        "deal_id":1000457,
        "title":"Eksempel AS – app",
        "org_name":"Eksempel AS",
        "status":"lost",
        "stage_name":"Proposal sent (fixed price)",
        "owner":{"id":1000001,"name":"Test Seller"},
        "has_due_call":false
      }
    ],
    "candidates_total":2,
    "auto_deal_id":1000456,
    "suggested_deal_id":1000456
  }
}
```

Candidate fields (the same in every call object below):

| Field | Type | Meaning |
|---|---|---|
| `deal_id` | id | |
| `title` | string | the deal's title |
| `org_name` | string or **null** | the deal's organization; null when the deal has none, e.g. a private person's deal |
| `status` | `open` \| `won` \| `lost` | |
| `stage_name` | string | |
| `owner` | `{id, name}` | the deal's owner |
| `has_due_call` | boolean | see 5.1 |

A deal with no organization:

```json
{"phone":"400 00 003","country_iso":"no"}
```

```json
{
  "data":{
    "match":"deal",
    "candidates":[
      {
        "deal_id":1000459,
        "title":"Nettside for Test Person C",
        "org_name":null,
        "status":"open",
        "stage_name":"Contact attempted",
        "owner":{"id":1000001,"name":"Test Seller"},
        "has_due_call":false
      }
    ],
    "candidates_total":1,
    "auto_deal_id":1000459,
    "suggested_deal_id":1000459
  }
}
```

Response, 200, the number of a LANARS colleague: the app sends **nothing more** for this call, no step 1 and no
step 2.

```json
{"data":{"match":"internal","candidates":[],"candidates_total":0,"auto_deal_id":null,"suggested_deal_id":null}}
```

Response, 200, every other case (an unknown number, persons with no deal that can take the call, a number that does
not normalise):

```json
{"data":{"match":"none","candidates":[],"candidates_total":0,"auto_deal_id":null,"suggested_deal_id":null}}
```

### 5.1 Matching rules

- **Number.** The server normalises the number to E.164. With `country_iso` `no` or absent, `+47 400 00 001`,
  `0047 400 00 001`, `400 00 001`, `40000001`, `4740000001` and `tel:+4740000001` are all `+4740000001`. With any
  other country only `+…` and `00…` forms match, so a Danish SIM's 8-digit number never becomes Norwegian. Short
  codes and foreign numbers without a prefix match nothing.
- **Colleagues first.** The number of an active LANARS user answers `internal`, even when the number is also in
  the CRM.
- **Candidates.** Live deals where a person with this number is the contact or a participant, plus deals whose
  contact phone is this number. Archived deals and read-only pipelines are never candidates.
- **Order.** Open deals first (the suggested deal first, then the most recently updated), then won and lost deals,
  newest close first. At most 5 are returned; `candidates_total` counts them all. When it exceeds the list, tell the
  user that more deals exist and the call can be logged in the web app.
- **`has_due_call`:** the deal has an open call activity due on or before the call's date (Oslo), whoever owns it.
- **`auto_deal_id`:** set when exactly one candidate is open. The call can go to that deal without asking.
- **`suggested_deal_id`:** among the open candidates, the deal with the earliest due call, else the most recently
  updated one; null when no candidate is open. Preselect it in a picker, but never send it without the user's tap.
- The answer has no person names, numbers or dates. Candidates have the same shape in every call object below.

| Lookup answer | What the app does |
|---|---|
| `match: "internal"` | nothing more for this call |
| `match: "deal"` with `auto_deal_id` | no picker; step 1 with that deal unless the user changes it |
| `match: "deal"`, no `auto_deal_id` | picker with the candidates (preselect `suggested_deal_id` if any) plus Other |
| `match: "none"` | no picker; step 1 with `deal_choice: "auto"` ("Not in CRM"). For a user with a weekly goal, an outgoing call then answers `pending` (6.1): show the picker then |

A lookup that fails for any reason, a 429 included, is not retried: step 1 then goes with `deal_choice: "auto"`, and
the server decides.

## 6. `POST /api/v1/calls`: step 1

The call, sent when the hold ends: the user picked a deal or confirmed, or the hold time ran out. The server stores
it and, when the deal is known, logs it as a call activity on that deal.

Request:

```json
{
  "client_call_id":"3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c",
  "direction":"outgoing",
  "answered":true,
  "phone":"+47 400 00 001",
  "presentation":"allowed",
  "country_iso":"no",
  "started_at":"2026-11-03T08:14:05.123Z",
  "duration_seconds":184,
  "outcome":"reached",
  "deal_choice":"deal",
  "deal_id":1000456,
  "chosen_by":"auto",
  "next":{"due_date":"2026-11-05","due_time":"10:00","subject":"Follow up offer"},
  "transcript_expected":true
}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `client_call_id` | UUID v4, lower-case | yes | made by the app once per call and never changed (6.3) |
| `direction` | `outgoing` \| `incoming` | yes | |
| `answered` | boolean | yes | false ⇒ `duration_seconds` must be 0 |
| `phone` | string ≤ 64, or null | yes | null when `presentation` is not `allowed` or the call log has no number |
| `presentation` | `allowed` \| `restricted` \| `unknown` \| `payphone` | yes | map any other Android value to `unknown`. Not `allowed`: `phone` is ignored |
| `country_iso` | as in the lookup | no | |
| `started_at` | UTC instant | yes | the call's start; at most 10 minutes in the future and at most 30 days old |
| `duration_seconds` | integer, 0–86,400 | yes | |
| `outcome` | `reached` \| `no_answer` \| `voicemail` \| `wrong_number` \| `callback` \| `not_interested` | yes | for a missed incoming call the server stores `no_answer` whatever is sent |
| `deal_choice` | `auto` \| `deal` \| `none` | yes | see 6.1 |
| `deal_id` | id | with `deal` only | the chosen deal; refused with `auto` and `none` |
| `chosen_by` | `auto` \| `user` | no | for diagnostics: whether the user picked the deal |
| `next` | object | no | a next call: `due_date` (Oslo, on or after the call's Oslo date), `due_time` (`HH:MM`, optional), `subject` (≤ 300, default "Follow up", also when empty). Used only if this request logs the call; refused for a missed incoming call |
| `transcript_expected` | boolean | yes | false when the app has no recording of this call, so no step 2 follows |

### 6.1 Deal choice

| `deal_choice` | Send it when | The server |
|---|---|---|
| `deal` | the lookup gave `auto_deal_id` and the user changed nothing (`chosen_by: "auto"`), or the user picked a deal (`chosen_by: "user"`) | logs on that deal if it is still a candidate; otherwise answers `pending` with `pending_reason: "deal_not_candidate"` and fresh candidates |
| `none` | the user chose Other | keeps the call outside the CRM: `declined`; `not_in_crm` for a number with no deal, unless the user has a weekly goal and the call is outgoing (`declined`) |
| `auto` | every other case: several deals and no pick, closed deals only, no match, lookup skipped or failed, notifications blocked | logs when exactly one open candidate exists; otherwise `pending` (several open deals, or closed deals only) or `not_in_crm`; for a user with a weekly goal, an outgoing call to a number with no deal answers `pending` with `no_match` |

The server decides again at write time; its answer is the truth.

**Users with a weekly goal.** LANARS sets a weekly call goal for some sellers, and every outgoing call of theirs is
linked to a lead. Their outgoing call to a number with no deal (unknown, or persons with no deal that can take the
call) answers `pending` with `pending_reason: "no_match"`, whatever `deal_choice` says, except `none` (Other), which
answers `declined`. Incoming calls, users without a goal and numbers that do not normalise keep `not_in_crm`. The
app does not know who has a goal and needs no setting: it acts on the answer, as for any `pending` call.

### 6.2 Answers

201 when the call was stored now, 200 when this `client_call_id` was already stored, or for a colleague's number.
The answer is the **call object**; it never carries the number, the summary or the transcript.

Logged, completing the deal's planned call and adding the next call:

```json
{
  "data":{
    "call":{
      "id":5012,
      "client_call_id":"3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c",
      "status":"logged",
      "deal":{"id":1000456,"title":"Eksempel AS – nettside"},
      "activity_id":1000789,
      "completed_planned_call":true,
      "next_activity_id":1000790,
      "callback_activity_id":null,
      "content_status":"expected",
      "pending_reason":null,
      "candidates":[],
      "candidates_total":0
    }
  }
}
```

Pending: the number is on two open deals and the app sent `auto`. Ask the user to pick (section 7); `candidates`
are current when the answer is made.

```jsonc
{"client_call_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","direction":"outgoing","answered":true,"phone":"+47 400 00 002","presentation":"allowed","country_iso":"no","started_at":"2026-11-03T08:05:00.000Z","duration_seconds":75,"outcome":"reached","deal_choice":"auto","transcript_expected":true}
```

```json
{
  "data":{
    "call":{
      "id":5013,
      "client_call_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
      "status":"pending",
      "deal":null,
      "activity_id":null,
      "completed_planned_call":false,
      "next_activity_id":null,
      "callback_activity_id":null,
      "content_status":"expected",
      "pending_reason":"several_open_deals",
      "candidates":[
        {
          "deal_id":1000456,
          "title":"Eksempel AS – nettside",
          "org_name":"Eksempel AS",
          "status":"open",
          "stage_name":"Contact attempted",
          "owner":{"id":1000001,"name":"Test Seller"},
          "has_due_call":true
        },
        {
          "deal_id":1000458,
          "title":"Eksempel AS – support",
          "org_name":"Eksempel AS",
          "status":"open",
          "stage_name":"In conversation",
          "owner":{"id":1000002,"name":"Other Seller"},
          "has_due_call":false
        }
      ],
      "candidates_total":2
    }
  }
}
```

The other outcomes, request then answer (one JSON body per line):

```jsonc
// A missed incoming call: logged, with a "Call back" planned for that day
{"client_call_id":"8e2f3a4b-5c6d-4e7f-9a81-223344556677","direction":"incoming","answered":false,"phone":"40000001","presentation":"allowed","country_iso":"NO","started_at":"2026-11-03T10:00:00.000Z","duration_seconds":0,"outcome":"no_answer","deal_choice":"auto","transcript_expected":false}
{"data":{"call":{"id":5014,"client_call_id":"8e2f3a4b-5c6d-4e7f-9a81-223344556677","status":"logged","deal":{"id":1000456,"title":"Eksempel AS – nettside"},"activity_id":1000791,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":1000792,"content_status":"none","pending_reason":null,"candidates":[],"candidates_total":0}}}
// A number not in the CRM: kept outside the CRM; send step 2 as usual
{"client_call_id":"9f3a4b5c-6d7e-4f80-8b92-334455667788","direction":"outgoing","answered":true,"phone":"+47 400 00 009","presentation":"allowed","country_iso":"no","started_at":"2026-11-03T10:30:00.000Z","duration_seconds":240,"outcome":"reached","deal_choice":"auto","transcript_expected":true}
{"data":{"call":{"id":5015,"client_call_id":"9f3a4b5c-6d7e-4f80-8b92-334455667788","status":"not_in_crm","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":null,"candidates":[],"candidates_total":0}}}
// The same request from a user with a weekly goal: 201, pending until the user picks a lead, with suggestions
{"data":{"call":{"id":5015,"client_call_id":"9f3a4b5c-6d7e-4f80-8b92-334455667788","status":"pending","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":"no_match","candidates":[{"deal_id":1000459,"title":"Nettside for Test Person C","org_name":null,"status":"open","stage_name":"Contact attempted","owner":{"id":1000001,"name":"Test Seller"},"has_due_call":false},{"deal_id":1000456,"title":"Eksempel AS – nettside","org_name":"Eksempel AS","status":"open","stage_name":"Contact attempted","owner":{"id":1000001,"name":"Test Seller"},"has_due_call":true}],"candidates_total":2}}}
// A hidden number
{"client_call_id":"7d1e2f3a-4b5c-4d6e-8f70-112233445566","direction":"incoming","answered":true,"phone":null,"presentation":"restricted","started_at":"2026-11-03T09:00:00.000Z","duration_seconds":95,"outcome":"reached","deal_choice":"auto","transcript_expected":true}
{"data":{"call":{"id":5016,"client_call_id":"7d1e2f3a-4b5c-4d6e-8f70-112233445566","status":"hidden","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":null,"candidates":[],"candidates_total":0}}}
// Other
{"client_call_id":"a04b5c6d-7e8f-4091-9ca3-445566778899","direction":"outgoing","answered":true,"phone":"+47 400 00 002","presentation":"allowed","country_iso":"no","started_at":"2026-11-03T11:00:00.000Z","duration_seconds":60,"outcome":"reached","deal_choice":"none","transcript_expected":true}
{"data":{"call":{"id":5017,"client_call_id":"a04b5c6d-7e8f-4091-9ca3-445566778899","status":"declined","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":null,"candidates":[],"candidates_total":0}}}
// A colleague's number that reached step 1 anyway (the lookup failed): 200, nothing stored, id null
{"client_call_id":"b15c6d7e-8f90-4a1b-8cd4-556677889900","direction":"outgoing","answered":true,"phone":"+47 400 00 010","presentation":"allowed","country_iso":"no","started_at":"2026-11-03T11:30:00.000Z","duration_seconds":120,"outcome":"reached","deal_choice":"auto","transcript_expected":true}
{"data":{"call":{"id":null,"client_call_id":"b15c6d7e-8f90-4a1b-8cd4-556677889900","status":"internal","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"none","pending_reason":null,"candidates":[],"candidates_total":0}}}
```

A `no_match` call's candidates are **suggestions**, at most 5: the deals this user opened in the CRM in the last 12
hours (newest first, at most 3), then the top of their "Call next" list. `candidates_total` counts the suggestions.
Show them in a "Which lead?" picker with a search field (section 10) and Other; any open deal may be picked
(section 7). The
user may also leave the call for later: it stays `pending` and shows to them in the CRM too, where they can link it.

Call object fields:

| Field | Meaning |
|---|---|
| `id` | the call id for the paths below; null only for `internal` |
| `status` | `logged`, `pending`, `declined` (Other), `not_in_crm`, `hidden`, `deleted` (the activity was deleted in the CRM; the server keeps the call), `internal`, and `private` (older calls only). All but `pending` are final |
| `deal` | the deal it is logged on, while `logged` |
| `activity_id` | the call activity: a new one, or the planned call it completed (`completed_planned_call: true`) |
| `next_activity_id`, `callback_activity_id` | the next call from `next`, and the "Call back" for a missed call |
| `content_status` | `expected`, `none`, `ready`, `failed` (section 8) |
| `pending_reason` | while `pending`: `several_open_deals`, `only_closed_deals`, `deal_not_candidate` or `no_match` (a user with a weekly goal called a number with no deal) |
| `candidates`, `candidates_total` | while `pending`: the deals the user can pick, as in the lookup; for `no_match`, suggestions |

### 6.3 Idempotency

`client_call_id` is the key of the call for every request about it, across retries, token renewals and re-sign-ins.
When the user already has a call with this `client_call_id`, the server compares the facts: `direction` and
`answered` equal, `started_at` equal to the whole second, `duration_seconds` within 2 s, and the normalised number
equal. If they match it answers 200 with the **stored** state and ignores `deal_choice`, `outcome` and `next`; to
change a pending choice, use section 7. Other facts answer 409 `{"error":"duplicate","detail":"client_call_id already used with other call facts"}`: fetch the call
(section 9) and adopt the server's state.

Resending step 1, a pick or step 2 is always safe. Build the step 1 body once and resend it unchanged.

### 6.4 What logging writes

- An answered call, or an unanswered outgoing one, completes the deal's due call (5.1) at the call's time, hands
  it to the caller and links it to the person called (`completed_planned_call: true`); without one it becomes a new
  done call.
- A missed incoming call is always a new "Missed call" with outcome `no_answer`, plus a "Call back" due that day
  unless the deal already has a due call.
- `next` adds an open call on the same deal, owned by the caller. Won and lost deals take calls like open ones.
- The step 2 summary becomes part of the activity's note; the transcript opens from the call on the deal page.

## 7. `POST /api/v1/calls/{call_id}/deal`

The user's pick for a `pending` call. Request, a deal (with an optional `next`, as in step 1) or Other:

```json
{"deal_choice":"deal","deal_id":1000458,"next":{"due_date":"2026-11-06"}}
```

```json
{"deal_choice":"none"}
```

Responses, 200:

```jsonc
// Logged on the picked deal
{"data":{"call":{"id":5013,"client_call_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","status":"logged","deal":{"id":1000458,"title":"Eksempel AS – support"},"activity_id":1000793,"completed_planned_call":false,"next_activity_id":1000794,"callback_activity_id":null,"content_status":"expected","pending_reason":null,"candidates":[],"candidates_total":0}}}
// The picked deal is no longer a candidate (archived, deleted or no longer linked to the number): still pending,
// with fresh candidates; show the picker again
{"data":{"call":{"id":5013,"client_call_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","status":"pending","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":"deal_not_candidate","candidates":[{"deal_id":1000456,"title":"Eksempel AS – nettside","org_name":"Eksempel AS","status":"open","stage_name":"Contact attempted","owner":{"id":1000001,"name":"Test Seller"},"has_due_call":true}],"candidates_total":1}}}
// Other
{"data":{"call":{"id":5013,"client_call_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","status":"declined","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":null,"candidates":[],"candidates_total":0}}}
```

Before showing the picker for a call that has waited, fetch it (section 9) and use its candidates. A repeat of the
applied choice answers 200 with the stored state; any other choice after the call is logged or declined answers 409
`{"error":"duplicate","detail":"the call is not waiting for a deal"}`.

**A `no_match` call** (6.2) takes **any open deal**: one that is not deleted, archived, won, lost or in a read-only
pipeline, among the suggestions or not; the app finds others with the search (section 10). A deal the server cannot
take leaves the call `pending` with `no_match` and fresh suggestions, never `deal_not_candidate`. When the call is
logged, the server saves its number, so the next call with that number finds the deal: as an extra phone on the
deal's contact person, unless that person is deleted or a placeholder record; else as the deal's contact phone
when that is empty; else nothing is saved and the call is still logged. When the deal's last number turned out wrong
(an earlier call with the outcome `wrong_number`), the number goes in the deal's contact phone instead, replacing the
wrong one, unless this call was a wrong number too. The call of 6.2, linked to a deal found by search:

```jsonc
{"deal_choice":"deal","deal_id":1000458}
{"data":{"call":{"id":5015,"client_call_id":"9f3a4b5c-6d7e-4f80-8b92-334455667788","status":"logged","deal":{"id":1000458,"title":"Eksempel AS – support"},"activity_id":1000795,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"expected","pending_reason":null,"candidates":[],"candidates_total":0}}}
```

## 8. `POST /api/v1/calls/{call_id}/content`: step 2

The summary and transcript made on the phone, or that transcription failed. Step 2 needs only the call `id`; it
does not wait for a pick.

Request, `ready` or `failed`:

```json
{
  "content_status":"ready",
  "summary_text":"Customer wants a quote for a new website by Friday.\n• Decision maker: the CEO.",
  "transcript_text":"Seller: Hei, det er Test Seller fra LANARS.\nCustomer: Hei, vi trenger en ny nettside.",
  "language":"nb",
  "summary_language":"en",
  "transcript_truncated":false
}
```

```json
{"content_status":"failed"}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `content_status` | `ready` \| `failed` | yes | `failed`: transcription failed; send no texts |
| `summary_text` | string ≤ 10,000 | with `ready` | plain text in **English**, newlines kept. Markdown is not rendered: write bullets as `• ` |
| `transcript_text` | string ≤ 200,000 | with `ready` | in the call's language, one `<label>: <text>` line per turn; the label (e.g. "Seller", "Customer") is free text, never parsed |
| `language` | BCP 47 tag, 2–35 | with `ready` | the call's language, e.g. `nb`, `nn`, `en` |
| `summary_language` | BCP 47 tag | with `ready` | `en` |
| `transcript_truncated` | boolean | no | default false. Cut a longer transcript on the phone at a character boundary and set it true |

Lengths count UTF-16 code units (Kotlin `String.length`).

Response, 200: the call object with the new `content_status`; texts are never returned.

```jsonc
// failed on a logged call
{"data":{"call":{"id":5012,"client_call_id":"3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c","status":"logged","deal":{"id":1000456,"title":"Eksempel AS – nettside"},"activity_id":1000789,"completed_planned_call":true,"next_activity_id":1000790,"callback_activity_id":null,"content_status":"failed","pending_reason":null,"candidates":[],"candidates_total":0}}}
// then ready
{"data":{"call":{"id":5012,"client_call_id":"3f1c2a9e-6b7d-4c1e-9a52-1d2e3f4a5b6c","status":"logged","deal":{"id":1000456,"title":"Eksempel AS – nettside"},"activity_id":1000789,"completed_planned_call":true,"next_activity_id":1000790,"callback_activity_id":null,"content_status":"ready","pending_reason":null,"candidates":[],"candidates_total":0}}}
// ready again on the logged call, the same transcript with another summary: 200 as above; the note keeps the first summary
{"content_status":"ready","summary_text":"Customer asked for a quote.","transcript_text":"Seller: Hei, det er Test Seller fra LANARS.\nCustomer: Hei, vi trenger en ny nettside.","language":"nb","summary_language":"en","transcript_truncated":false}
// ready on a call not in the CRM
{"data":{"call":{"id":5015,"client_call_id":"9f3a4b5c-6d7e-4f80-8b92-334455667788","status":"not_in_crm","deal":null,"activity_id":null,"completed_planned_call":false,"next_activity_id":null,"callback_activity_id":null,"content_status":"ready","pending_reason":null,"candidates":[],"candidates_total":0}}}
```

Transitions of `content_status`:

| From | To | Answer |
|---|---|---|
| `expected` | `ready` or `failed` | 200 |
| `failed` | `ready` | 200, once |
| `none` (step 1 said `transcript_expected: false`) | `ready` | 200 |
| the same status, the same texts | | 200 with the stored state |
| `ready` again on a `logged` call, the same transcript, languages and `transcript_truncated`, any summary | | 200 with the stored state; the summary is already in the activity note, which people edit, so it is not compared and not added again |
| anything else (other texts after `ready`, `failed` after `ready`) | | 409 `duplicate` |
| any, on a `deleted` call or an older `private` one | | 200; nothing is stored |

Content may arrive in any status: on a `logged` call the summary goes into the activity note at once; on a
`pending` call it waits and moves into the note when the call is logged; otherwise it stays outside the CRM. If
content is not ready 72 hours after the call, send `failed`.

## 9. `GET /api/v1/calls/{call_id}`

The current state of one of the user's own calls, with current `candidates` while it is `pending`. Another user's
call, or one the server no longer has, answers 404 `{"error":"not_found","detail":"call 5099"}`. There is no list endpoint.
It needs no notice and is not rate-limited: call it before every picker and after every 409 `duplicate`. Errors:
401, 403 `forbidden`, 404, 426, 500.

```json
{
  "data":{
    "call":{
      "id":5013,
      "client_call_id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
      "status":"pending",
      "deal":null,
      "activity_id":null,
      "completed_planned_call":false,
      "next_activity_id":null,
      "callback_activity_id":null,
      "content_status":"expected",
      "pending_reason":"several_open_deals",
      "candidates":[
        {
          "deal_id":1000456,
          "title":"Eksempel AS – nettside",
          "org_name":"Eksempel AS",
          "status":"open",
          "stage_name":"Contact attempted",
          "owner":{"id":1000001,"name":"Test Seller"},
          "has_due_call":true
        },
        {
          "deal_id":1000458,
          "title":"Eksempel AS – support",
          "org_name":"Eksempel AS",
          "status":"open",
          "stage_name":"In conversation",
          "owner":{"id":1000002,"name":"Other Seller"},
          "has_due_call":false
        }
      ],
      "candidates_total":2
    }
  }
}
```

## 10. `POST /api/v1/calls/search`

Open deals a call can be linked to, by deal title or company name: the search field of the picker for a `no_match`
call (section 7). It stores nothing. Request:

```json
{"q":"Eksempel"}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `q` | string, 2–100 characters after trimming | yes | part of a deal title or a company name; case does not matter |

Response, 200: at most 10 deals, best match first, each with the candidate fields of section 5. Only deals a pick
takes are listed: open, not archived and not in a read-only pipeline. A company that matches lists its open deals,
the most recently updated first. `deals` is empty when nothing matches.

```json
{
  "data":{
    "deals":[
      {
        "deal_id":1000458,
        "title":"Eksempel AS – support",
        "org_name":"Eksempel AS",
        "status":"open",
        "stage_name":"In conversation",
        "owner":{"id":1000002,"name":"Other Seller"},
        "has_due_call":false
      },
      {
        "deal_id":1000456,
        "title":"Eksempel AS – nettside",
        "org_name":"Eksempel AS",
        "status":"open",
        "stage_name":"Contact attempted",
        "owner":{"id":1000001,"name":"Test Seller"},
        "has_due_call":true
      }
    ]
  }
}
```

It needs the notice (section 3) and has its own limit (section 12). Errors: 400 (`q` missing, too short or too
long, or an unknown field), 401, 403 (`forbidden`, `notice_required`), 426, 429, 500.

## 11. Errors

| HTTP | `error` | When | What the app does |
|---|---|---|---|
| 400 | `invalid_input` | a field is missing, of the wrong type, unknown or out of range; the body is not a JSON object or too large; `Idempotency-Key` sent | an app bug: mark the step failed. If `detail` names `next`, resend once without it; if `started_at` is in the future, retry once an hour later |
| 401 | `unauthorized` | no token, or a dead one (2.3); on `/mobile/session`, the ID token failed | sign in again, then retry once |
| 403 | `forbidden` | the token may not use this endpoint; on `/mobile/session`, sign-in refused (2.2) | stop sending; start again after the next successful sign-in |
| 403 | `notice_required` | the phone has not acknowledged the current notice | show the notice (section 3), then retry |
| 404 | `not_found` | `detail` "unknown endpoint": the server is older than this document; otherwise the call does not exist or is another user's | hold the queue and retry later; for a call, mark it failed |
| 405 | `method_not_allowed` | wrong method on a known path | app bug |
| 409 | `conflict` | the same call written in parallel ("this call is being written"); a notice version that is not current | retry with backoff; for the notice, fetch it again |
| 409 | `duplicate` | other facts for a `client_call_id`; another choice or other content after one is stored | `GET /calls/{call_id}` and adopt the server's state |
| 426 | `upgrade_required` | `x-app-version` missing or below the minimum | hold everything and ask the user to update |
| 429 | `rate_limited` | a limit of section 12 | lookup and search: do not retry; anything else: retry after `retry-after` seconds |
| 500 | `internal` | server error | retry with backoff |
| — | — | network error, timeout, not JSON | retry with backoff |

Bodies:

```jsonc
// 400
{"error":"invalid_input","detail":"Idempotency-Key is not accepted on this endpoint"}
{"error":"invalid_input","detail":"Unrecognized key: \"colour\""}
{"error":"invalid_input","detail":"next.due_date must be on or after the call date"}
// 401
{"error":"unauthorized"}
// 403
{"error":"forbidden","detail":"key lacks the scope"}
{"error":"notice_required"}
// 404
{"error":"not_found","detail":"unknown endpoint"}
{"error":"not_found","detail":"call 5099"}
// 405
{"error":"method_not_allowed"}
// 409
{"error":"conflict","detail":"the notice version is not current"}
{"error":"duplicate","detail":"client_call_id already used with other call facts"}
{"error":"duplicate","detail":"the call is not waiting for a deal"}
// 426
{"error":"upgrade_required","detail":"minimum app version 1.4.0"}
// 429, with the header retry-after: <seconds>
{"error":"rate_limited"}
// 500
{"error":"internal"}
```

## 12. Limits and versions

| What | Limit |
|---|---|
| Lookups | 20 per 10 minutes and 200 per day (Oslo) per user; every answered lookup counts |
| Deal searches | `POST /calls/search`: 60 per 10 minutes and 600 per day per user; every answered search counts |
| Call writes | `POST /calls`, `/deal` and `/content`: 40 per 10 minutes and 400 per day per user; a request counts when it stores a new call (201) or its answer carries a number's candidates, so plain repeats are free, and so are answers that carry a `no_match` call's suggestions. `GET /calls/{call_id}` is not limited |
| Sign-in exchanges | 20 per day per user. The platform also limits exchanges per network address; that refusal may not be JSON and counts as a network error |

The 10-minute windows start at whole tens of minutes (UTC); a day ends at midnight in Oslo. Over a limit the answer
is 429 `{"error":"rate_limited"}` with `retry-after` set to the seconds until the limit that refused it resets. A
refused request does not count, and neither does one that fails with 500.

**App version.** Every request carries `x-app-version: <versionName>`. LANARS can set a minimum version; the
exchange and every request with the token then answer 426 `{"error":"upgrade_required","detail":"minimum app version 1.4.0"}` to a
missing or older version. Versions compare as dotted numbers, part by part; a suffix such as `-debug` is ignored.
The exchange tells the current minimum in `min_app_version`.

## 13. What the app sends, and never sends

- Every call on the work line the user chose: it is a work phone. Calls on other lines and from other calling apps
  send nothing, not even a lookup.
- With notifications blocked, calls are still sent, with `deal_choice: "auto"`; a `pending` one waits in the app's
  call list for the user's pick.
- After a lookup answered `internal` (a colleague), nothing more about that call.
- No lookup for a hidden number; its step 1 goes with `phone: null`.
- With Pause recording on, the call is sent with `transcript_expected: false` and no step 2.
- Never audio. Recordings stay on the phone.
- Summaries in English (`summary_language: "en"`); transcripts in the call's language.
- Nothing while the notice is not acknowledged; nothing from before the app was set up.
- Numbers, summaries and transcripts only to this API, and the token only in `x-api-token`.
