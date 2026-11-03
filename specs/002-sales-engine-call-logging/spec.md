# Feature Specification: Logging work calls in the Sales Engine

**Feature Branch**: `feautre/implement-integration-with-sales-engine-api` (spec dir `002-sales-engine-call-logging`)

**Created**: 2026-11-03

**Status**: Draft

**Input**: Log work-line calls in the LANARS Sales Engine CRM from the app,
following the Sales Engine API for the Android call app (document dated
2026-11-02, kept beside this spec in
[reference/sales-engine-api-2026-11-02.md](reference/sales-engine-api-2026-11-02.md),
"the API" below, its sections as §n). The user signs in with their lanars.com
or lanars.no Google account, acknowledges LANARS's notice and chooses the work
line. After each call on it, the call goes to the right deal, then its
transcript and an English summary follow. Never audio. The app is LANARS's
fork, `com.lanars.jemrec`, shipped as an APK only.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Get connected to the Sales Engine (Priority: P1)

A seller opens the app, signs in with their LANARS Google account, reads
LANARS's notice and agrees, and picks which SIM is their work line. From then
on the app shows that it is connected, as whom, and on which line; the seller
can sign out.

**Why this priority**: nothing is sent without it (§2, §3, §13).

**Independent Test**: on the test stand, sign in, acknowledge the notice, pick
the line; the app shows the signed-in name; signing out returns it to "not
connected".

**Acceptance Scenarios**:

1. **Given** a lanars.com account that is a Sales Engine user, **When** the
   seller signs in, **Then** the app shows their name and asks them to read the
   notice before anything else.
2. **Given** an account that is not a Sales Engine user, or a private Google
   account, **When** they sign in, **Then** the app says why in plain words
   ("Ask LANARS to add you", "Use your lanars.com or lanars.no account") and
   stays not connected.
3. **Given** the notice is shown, **When** the seller agrees, **Then** the
   app records it and moves on to the work line; **Given** LANARS later
   changes the notice, **Then** the app shows the new one and sends nothing
   until it is agreed to.
4. **Given** a phone with two SIMs, **When** the seller picks one, **Then**
   only calls on that SIM are ever looked up or sent; a phone with one SIM
   has it picked for them.
5. **Given** a connected app, **When** the seller signs out, **Then** the
   server is told, and nothing more is sent until they sign in again.

---

### User Story 2 - A work call lands on its deal by itself (Priority: P1)

After a call on the work line ends, the app checks the number against the CRM.
When exactly one open deal fits, the call is logged on it without the seller
doing anything, unless they tap Private in time. A number not in the CRM is
kept outside it; a colleague's number sends nothing more. Missed and
unanswered calls are logged too.

**Why this priority**: it is the point of the feature: the calls show on the
deal without typing.

**Independent Test**: on the stand, call a number that has one open deal;
the call appears on that deal in the Sales Engine web app with its direction,
time and length.

**Acceptance Scenarios**:

1. **Given** a number with one open deal, **When** an answered call ends and
   the seller does nothing, **Then** the call is logged on that deal, and a
   planned call due that day is completed by it (§6.4).
2. **Given** a number not in the CRM, **When** the call ends, **Then** it is
   sent and kept outside the CRM ("Not in CRM").
3. **Given** a colleague's number, **When** the call ends, **Then** nothing
   after the lookup is sent for it.
4. **Given** a missed incoming call, **Then** it is logged as a missed call,
   with a "Call back" the server plans, and no transcript is expected.
5. **Given** a hidden number, **Then** no lookup is made and the call is sent
   without a number.
6. **Given** a call on the other SIM, a messenger call, or a call from before
   setup, **Then** nothing about it is sent, not even a lookup.

---

### User Story 3 - Choose the deal, or keep a call private (Priority: P2)

Right after a work call, the seller can tap **Private** so that nothing more
is sent. When the number is on several open deals, they pick one or choose
"Don't log". A call whose deal is still open to choose waits in a list, where
the seller can pick later; the list shows the deals as they are now.

**Why this priority**: without it, calls with several deals never reach a
deal, and the seller has no way to keep a personal call out.

**Independent Test**: on the stand, call a number on two open deals; pick
the second; the call appears on that deal. Call it again and tap Private;
nothing about that call reaches the server after the lookup.

**Acceptance Scenarios**:

1. **Given** a call on several open deals, **When** it ends, **Then** the
   seller is shown the candidates with the suggested one preselected, plus
   "Don't log", and nothing is logged until they tap.
2. **Given** the seller ignores the choice, **Then** the call is sent as
   undecided and waits in the pending list; **When** they open it later,
   **Then** the deals shown are fetched afresh.
3. **Given** the picked deal is no longer a candidate, **Then** the seller is
   told and shown the current candidates again.
4. **Given** more deals exist than are shown, **Then** the app says so and
   that the call can be logged in the web app.
5. **Given** a call already sent but not logged on a deal, **When** the seller
   chooses Make private, **Then** the server drops its number, summary and
   transcript; a call already logged on a deal says it is edited in the web
   app instead.

---

### User Story 4 - The transcript and an English summary follow (Priority: P2)

A recorded work call is transcribed on the phone as it is today, and its
transcript, in the call's language, and a summary in English are sent to the
CRM, where the summary joins the call's note on the deal. When the
transcription fails, or is not ready within 72 hours, the CRM is told it
failed.

**Why this priority**: the summary on the deal is what saves the seller the
write-up; the call itself (Story 2) is useful without it.

**Independent Test**: on the stand, make a recorded call to a one-deal
number, let it transcribe; the deal's call note holds the English summary and
the transcript opens from the call.

**Acceptance Scenarios**:

1. **Given** a logged call whose transcript is done, **Then** the transcript
   and an English summary are sent, and the summary appears in the call's note.
2. **Given** a call still waiting for a deal, **Then** its content is sent
   anyway and moves into the note when the call is logged.
3. **Given** transcription failed, **Then** "failed" is sent; **Given** it
   later succeeds once, **Then** the content replaces the failure.
4. **Given** a call with no recording (missed, unanswered, not recorded),
   **Then** no transcript is expected, and none is sent.
5. **Given** a transcript longer than the CRM accepts, **Then** it is cut and
   marked as cut.

---

### User Story 5 - Set the outcome and plan the next call (Priority: P3)

Before the call is sent, the seller can say how it went (reached, no answer,
voicemail, wrong number, call back, not interested) and plan a next call on
the same deal, with a date, an optional time and a subject.

**Why this priority**: useful for the pipeline, but a call logged with a
default outcome and no next call is still logged.

**Independent Test**: on the stand, after a call to a one-deal number, set
"Call back" and a next call for tomorrow 10:00; both appear on the deal.

**Acceptance Scenarios**:

1. **Given** an answered call, **When** the seller does not choose, **Then**
   it is sent as "reached"; an unanswered one as "no answer".
2. **Given** a next call planned, **Then** its date and time are in Norwegian
   time, whatever the phone's time zone, and not before the call's day.
3. **Given** a missed incoming call, **Then** no next call can be planned.

---

### User Story 6 - Nothing is lost when the phone is offline or the session ends (Priority: P2)

Calls made without network, during a server outage, after the session
expired, or while an update is required, wait on the phone and are sent when
it is possible again, in order and once each. The seller is told only when
they have to act: sign in again, read a new notice, update the app.

**Why this priority**: sellers call from cars and basements; a call lost to
a dead zone undermines trust in the whole log.

**Independent Test**: in flight mode, make three work calls; switch the
network on; all three reach the stand once each, within minutes.

**Acceptance Scenarios**:

1. **Given** calls made offline, **When** the network returns, **Then** each
   is sent once, and resending after a cut connection never makes a
   duplicate.
2. **Given** the session has ended, **Then** the seller is asked to sign in
   again, and the waiting calls go once they have.
3. **Given** the server requires a newer version, **Then** sending stops and
   the seller is asked to update.
4. **Given** LANARS blocked this phone, **Then** the app forgets the session
   and its queued call data and stops.
5. **Given** another person signs in on the phone, **Then** nothing queued
   for the previous person is sent.
6. **Given** the server is over its limits, **Then** the app waits as long as
   it is told and does not lose the call.

---

### Edge Cases

- A call ends while the phone has no network: the lookup is skipped and the
  call is sent later as undecided; the server decides (§5).
- The phone's clock is far off: the server refuses a start in the future; the
  app retries later rather than dropping the call.
- A call waits more than 30 days (phone off, never signed in again): the
  server refuses it; the app marks it as not sent and says so.
- The same call is reported twice by Android (call log and call tracking):
  one call, one identifier, sent once.
- The recording is deleted, trashed or cleaned up before its transcript is
  made: "failed" is sent, or no transcript expected when known in time.
- Call waiting and conference calls on the work line: each call is its own
  entry.
- The seller taps Private after step 1 already went: the call is made private
  on the server (Story 3, scenario 5).
- The server answers with something that is not its JSON (a captive portal,
  a gateway page): treated as no network, never as signed out.
- The notice changes while calls wait: they wait until it is agreed to.
- A transcript in two languages (each side its own): one language is reported
  for the call.
- A call recorded in one channel (both voices mixed, as on a Galaxy A24): the
  transcript still has a label on every line.

## Requirements *(mandatory)*

### Functional Requirements

**Connecting**

- **FR-001**: The app MUST let the seller sign in with a LANARS Google
  Workspace account and MUST show, in plain words, why a sign-in was refused
  (each refusal in §2.2).
- **FR-002**: The app MUST keep the session secret on the phone, never show it,
  and send it only to the Sales Engine.
- **FR-003**: The app MUST renew the session before it expires and MUST ask
  the seller to sign in again when it has ended; it MUST NOT sign in again on
  its own because of a network error.
- **FR-004**: The app MUST show LANARS's notice as the server gives it and
  MUST send nothing (no lookup, no call) until the current version is agreed
  to.
- **FR-005**: The seller MUST be able to choose the work line among the
  phone's SIMs; with one SIM it is chosen for them. The choice MUST be
  changeable later.
- **FR-006**: The seller MUST be able to sign out, which ends the session on
  the server.
- **FR-007**: Sign-in, notice and work line MUST be reachable from Settings,
  which shows whether the app is connected, as whom, and on which line.

**Which calls**

- **FR-010**: Only phone calls on the work line, started after setup was
  completed, MUST be considered: never other SIMs, messenger calls, other
  calling apps, or calls from before setup.
- **FR-011**: Missed incoming and unanswered outgoing calls on the work line
  MUST be sent, as calls without a transcript.
- **FR-012**: Hidden or unknown numbers MUST NOT be looked up and MUST be sent
  without a number.
- **FR-013**: A call whose lookup says it was with a colleague MUST have
  nothing more sent.
- **FR-014**: Audio MUST never be sent; recordings stay on the phone, as the
  API requires (§13). Transcript and summary are what reaches the CRM
  (decided 2026-11-03).
- **FR-015**: Messenger calls (WhatsApp, Telegram, ...) MUST NOT be sent,
  though the app records them: the API takes only calls on the work line
  (§13). Asking LANARS to take them is out of scope (decided 2026-11-03).
- **FR-016**: A call's start and length MUST come from the phone's call log:
  the start includes the ringing, the length is Android's, whatever the
  recording's own start and length.
- **FR-017**: A call the answering machine took MUST be sent as answered,
  with the outcome "no answer", and the message's transcript as its content.
  Its test calls MUST NOT be sent.

**After the call**

- **FR-020**: After a work call ends, the app MUST look the number up and then
  give the seller a chance to tap Private before anything else is sent: a
  notification after the call, with Private and, when several deals fit, the
  deal choice; the call is sent 2 minutes after it ended unless the seller
  acted sooner (decided 2026-11-03). The notice promises this chance (§3), so
  the call is never sent before it.
- **FR-021**: A call the seller made Private before it was sent MUST have
  nothing more sent.
- **FR-022**: When the lookup gives one open deal, the call MUST be sent to it
  unless the seller changes it; with several, the seller MUST be shown the
  candidates (suggested one preselected) and "Don't log", and nothing is
  logged on a deal without their tap; with none, the call MUST be sent for the
  server to decide.
- **FR-023**: A failed lookup MUST NOT be retried; the call is then sent for
  the server to decide.
- **FR-024**: Calls waiting for a deal MUST be listed in the app, and the
  seller MUST be able to pick a deal or "Don't log" there at any time; the
  candidates MUST be fetched afresh before they are shown.
- **FR-025**: The seller MUST be able to choose the call's outcome; without a
  choice an answered call is "reached" and any other "no answer".
- **FR-026**: The seller MUST be able to plan a next call (date, optional
  time, subject) for a call that is not a missed incoming one, in Norwegian
  time.
- **FR-027**: The seller MUST be able to make private a sent call that is not
  logged on a deal.
- **FR-028**: Each call MUST show its state in the app: waiting to send, sent
  (logged on which deal, not in CRM, waiting for a deal, declined, private,
  hidden), content sent or failed.

**Transcript and summary**

- **FR-030**: For a recorded work call, the transcript MUST be sent in the
  call's language with a speaker label on every line, and a summary MUST be
  sent in English as plain text. The language sent is the transcript's main
  one: the one the seller chose, or else the one detected for it.
- **FR-030a**: Transcription and summaries run as the seller set them, as
  today (after each call, only when asked, or overnight); work calls are not
  forced (decided 2026-11-03). The integration's settings MUST say plainly
  when the CRM will get no transcript: no speech model, no summary model, or
  summaries switched off.
- **FR-031**: The CRM takes a transcript only together with its summary
  (§8). When transcription or summarising fails, summaries are off or have no
  model, or nothing is ready 72 hours after the call, "failed" MUST be sent;
  a later success MUST be sent once.
- **FR-032**: A transcript longer than the CRM accepts MUST be cut and marked
  as cut; a summary MUST fit the CRM's limit.
- **FR-033**: Summaries MUST be written in English for every call, whatever
  its language: the one summary is shown on the phone and sent to the CRM
  (decided 2026-11-03). The transcript stays in the call's language.
- **FR-034**: What is sent MUST be made from what the phone shows without
  changing the screens: the transcript as `label: text` lines without times,
  the summary's parts as plain text with `• ` bullets, in this order:
  intent, summary, then "Key points", "Decisions", "Action items" and "Key
  facts", each left out when empty.
- **FR-035**: Only the first finished transcript and summary of a call MUST
  be sent. Transcribing or summarising it again later changes the phone only:
  the CRM refuses other texts once it has some (§8).
- **FR-036**: When the recording is deleted (by the seller, the trash or the
  clean-up) before its content was sent, "failed" MUST be sent.
- **FR-037**: The language MUST be sent as the CRM expects it: Norwegian,
  which the speech engine names `no`, as `nb` (Bokmål).

**Delivery**

- **FR-040**: Every call MUST be sent exactly once in effect: retries after
  network errors, restarts or a new sign-in MUST NOT duplicate it.
- **FR-041**: Waiting sends MUST survive app restarts and reboots and go when
  the network returns.
- **FR-042**: The app MUST respect the server's limits and its "try again
  after" times, and MUST stop and ask for an update when the server requires
  a newer version.
- **FR-043**: When the server says the phone is blocked, the app MUST end the
  session and delete the integration's call data (queued sends and their
  states); recordings and transcripts on the phone are kept.
- **FR-044**: Calls queued for one person MUST NOT be sent after another
  person signs in.
- **FR-045**: A call the server can no longer take (older than 30 days)
  MUST be marked as not sent, visibly.
- **FR-046**: Calls made after the seller signed out on purpose MUST never be
  sent; calls made while a session ended by itself wait for the next sign-in.
- **FR-047**: The first release with this feature MUST be version 1.0.0, so
  the minimum version LANARS sets compares against it.

### Key Entities

- **Session**: who is signed in (name, e-mail, user id), until when it is
  valid, the minimum app version the server asked for, this phone's id.
- **Notice**: the version the seller agreed to, and when.
- **Work line**: the chosen SIM, its country.
- **Work call**: one call on the work line: its own id, made once; direction,
  answered, number or its absence and why, start, length, outcome, deal
  choice, next call; the server's id and state; whether a transcript is
  expected and whether content was sent; the person it was queued for; a
  link to its recording when there is one.
- **Deal candidate**: a deal the call may go to, as the server lists it:
  title, organisation, status, stage, owner, whether a call is due.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A seller goes from first opening the app to connected (sign-in,
  notice, line) in under 3 minutes.
- **SC-002**: With network, a work call to a number with one open deal is
  on that deal within 5 minutes of hanging up, with no tap.
- **SC-003**: Picking a deal for a call takes at most 2 taps from the prompt
  after the call.
- **SC-004**: Calls made offline all reach the CRM, once each, within 10
  minutes of the network returning (none lost, none twice, across 20 test
  calls).
- **SC-005**: No call on another SIM, from a messenger, from before setup, or
  marked Private reaches the server; no audio ever leaves the phone (checked
  on every send in the test runs).
- **SC-006**: For every recorded work call, the CRM has either its transcript
  and English summary or a "failed" within 72 hours.
- **SC-007**: A seller is never asked to sign in because of a network error;
  only when the session really ended.

## Assumptions

- The API document of 2026-11-02 is the contract; where this spec and it
  differ, it wins. A mock server is built from its examples until the stand
  is available.
- LANARS registers `com.lanars.jemrec` with the shared debug key and the
  release key, and sends the Web client id, the stand's host and stand users
  for the testers (asked 2026-11-03).
- The app is distributed as an APK to LANARS sellers only: no F-Droid, no
  Google Play. Google Play services are on the sellers' phones (needed for
  Sign in with Google).
- The phone permission to read the call log becomes required for this
  feature (the call's number, direction and SIM come from it), and reading
  which SIMs the phone has is added for choosing the work line.
- Recording keeps working as today; this feature only reads what it made.
  Which calls are recorded stays the seller's choice; an unrecorded call is
  still logged, with no transcript expected.
- Transcript labels are "Seller" and "Customer" where the two sides are told
  apart, and one neutral label ("Call") where they are not.
- English summaries come from the same on-phone model as today's summaries.
  Their quality is checked on the Galaxy A24 against known calls, as the
  summaries' were: Qwen3 1.7B, left to write English for a Norwegian call,
  once filled its action items with tasks nobody gave (ARCHITECTURE,
  "Summaries"), so the English prompt is measured, not assumed.
- Telling the two voices apart in a call recorded as one mix is its own
  feature (research R16 of spec 001); until it lands such a call's lines carry
  one neutral label.
- Removing the F-Droid parts of the project (listing, site, release workflow)
  is separate work, not part of this feature.
