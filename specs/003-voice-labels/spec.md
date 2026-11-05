# Feature Specification: Who spoke in a one-mix call

**Feature Branch**: `feature/implement-voice-recognation` (spec dir `003-voice-labels`)

**Created**: 2026-11-03

**Status**: Draft

**Input**: You / Them labels on the transcripts of calls whose two channels
carry the same mix - phone calls on the Galaxy A24, and on a Pixel by issue
#20 - where the channel split (research R4 in
[001's research](../001-transcripts-summaries/research.md#r4-who-spoke))
gives no names. The two voices are told apart on the phone after the call is
transcribed, and the owner's voice is picked out by a print of it; where the
print does not decide it clearly, the lines say Voice 1 and Voice 2. Calls
whose channels differ keep the channel split. Builds on the spike R16 in the
same research (measured accuracy, speed and memory on the A24; the owner's
print needing a clean source and a margin; lines whisper merged across a turn
cut at the turn). The model downloads on a tap like the speech and summary
models; nothing leaves the phone. The Sales Engine transcript (002, FR-030)
gets the labels too.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Two voices apart in a one-mix call (Priority: P1)

A user on a phone whose phone calls come out as one mix opens a call's
transcript. Today every line is unnamed. With the voice model downloaded, the
lines are now grouped by voice: each line says Voice 1 or Voice 2, the two
coloured apart as You and Them are, and a line where the speaker changes
mid-way is cut at the change.

**Why this priority**: a transcript of two people with no turns is hard to
read and its summary cannot say who asked or promised what. Telling the two
voices apart is the part that works without knowing whose voice is whose, so
it stands on its own.

**Independent Test**: on the A24, record a phone call with two people taking
turns, transcribe it with the voice model downloaded; the transcript shows
two voices, each line under one of them, and the turns match what was said.

**Acceptance Scenarios**:

1. **Given** a call whose channels carry one mix and the voice model is
   downloaded, **When** it is transcribed, **Then** every line with speech
   says Voice 1 or Voice 2, and Voice 1 is the one heard first.
2. **Given** a line during which the speaker changes, **When** voices are
   told apart, **Then** it is cut into two lines at the change, each under
   its own voice, and no words are lost or repeated.
3. **Given** a call where only one voice is found (an answering machine, a
   voice menu that never got an answer), **Then** the lines are not split
   into two voices: they stay unnamed.
4. **Given** a call whose channels differ (every messenger call, phone calls
   on the Honor), **When** it is transcribed, **Then** its lines are named
   from the channels as today, and the voice model is not run.
5. **Given** the voice model is not downloaded, **Then** one-mix calls are
   transcribed as today, unnamed, and the transcription settings say that
   downloading the voice model would name them.

---

### User Story 2 - Your voice is You (Priority: P1)

The user's own voice is recognised, so in a one-mix call their lines say You
and the other person's say Them, as in a messenger call. When the app is not
sure enough which voice is the user's, it keeps Voice 1 and Voice 2 rather
than guess.

**Why this priority**: You and Them is what the user asked for and what the
Sales Engine needs (Seller and Customer); Voice 1 / Voice 2 alone leaves the
seller to work out which is which on every call.

**Independent Test**: give the app a print of the user's voice, then
transcribe a one-mix call between the user and someone else: the user's lines
say You, the other person's Them. Transcribe a call between two other people
(two synthetic voices, as 11:44 in R16): the lines say Voice 1 and Voice 2.

**Acceptance Scenarios**:

1. **Given** a print of the user's voice and a one-mix call in which one
   voice matches it clearly and the other does not, **Then** that voice's
   lines say You and the other's Them.
2. **Given** both voices match the print about equally, or neither matches
   it well enough, **Then** the lines say Voice 1 and Voice 2.
3. **Given** no print of the user's voice yet, **Then** the lines say Voice 1
   and Voice 2, and the transcript says, once, how to let the app know the
   user's voice.
4. **Given** the user sees a transcript with Voice 1 and Voice 2, **When**
   they mark one of the voices as theirs, **Then** that transcript says You
   and Them from then on.
5. **Given** a print is kept, **When** the user deletes it, **Then** it is
   gone from the phone, and later one-mix transcripts say Voice 1 and
   Voice 2.
6. **Given** the user records a short sample of their voice in Settings,
   **Then** the print is made from it, and it takes precedence over any
   other source (decided 2026-11-03).
7. **Given** no recorded sample, **When** a messenger call's left channel is
   plainly one person speaking (no played audio, no echo of the other side),
   **Then** the print may be made from it, and Settings says it was; a left
   side that is not plainly one person is never used (R16: test calls whose
   left side carried played audio or the other side's echo).

---

### User Story 3 - The labels reach the summary and the CRM (Priority: P2)

A one-mix call's summary can say who said what, and a work call's transcript
sent to the Sales Engine has Seller and Customer on its lines, as messenger
calls already do.

**Why this priority**: it follows from the labels with little work of its
own, but it is the reason the LANARS build wants them.

**Independent Test**: with the Sales Engine mock, a one-mix work call whose
voices were named sends a transcript with Seller and Customer lines; one
whose voices were not named sends Speaker 1 and Speaker 2.

**Acceptance Scenarios**:

1. **Given** a one-mix call with You and Them, **Then** its summary and its
   CRM transcript use them exactly as for a messenger call.
2. **Given** a one-mix call with Voice 1 and Voice 2, **Then** the summary
   is told there are two people without saying which is the user, and the
   CRM transcript labels its lines Speaker 1 and Speaker 2.
3. **Given** a work call already sent, **When** the user later marks a voice
   as theirs, **Then** nothing is sent again (002 sends a call's content
   once).

---

### User Story 4 - Old recordings (Priority: P3)

A user downloads the voice model after months of one-mix calls. They can name
the voices of a past call by transcribing it again, without the app doing it
to all of them at once.

**Why this priority**: useful, but not needed for new calls to work.

**Independent Test**: take a transcript made before the model was
downloaded, transcribe it again; it now has voices.

**Acceptance Scenarios**:

1. **Given** an unnamed one-mix transcript made before, **When** the user
   transcribes it again, **Then** the new one has its voices named as a new
   call's would be.
2. **Given** the voice model is downloaded, **Then** past transcripts are not
   redone by themselves.

---

### Edge Cases

- **Speakerphone**: both voices come through the microphone; telling them
  apart works the same, but the user's voice may be farther and weaker. A
  print match below the margin gives Voice 1 / Voice 2, not a guess.
- **More than two voices** (a conference call, someone handed the phone): the
  call is told apart as two voices; a third person's lines go to whichever
  voice they sound nearer. Accepted for now and said in the manual.
- **Talking over each other**: a line in which both speak goes to the voice
  heard more in it.
- **Very short calls** (under about 10 s of speech): too little to tell
  voices apart; the lines stay unnamed rather than wrongly split.
- **Not enough free memory** when the voice step starts: the transcript is
  kept unnamed and the app says why, as it does for summaries.
- **The channel decision is wrong for a call** (one side quiet, so it looks
  like a mix): the existing setting that forces the split or the mix still
  wins; forcing the mix on a call that has two sides runs the voice step.
- **A recording deleted** while its voices are being told apart: the work
  stops and nothing is left behind.
- **The print belongs to someone else** (the phone shared, or the user
  recorded another person by mistake): deleting it and making a new one is
  the way out; the app does not try to detect it.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST tell the two voices of a one-mix call apart and
  label each line with one of them, for every call the existing channel
  decision (R4) calls a mix, when the voice model is downloaded.
- **FR-002**: Calls whose channels differ MUST keep their channel labels;
  the voice step MUST NOT run on them.
- **FR-003**: A line whose speaker changes within it MUST be cut at the
  change, by the times of its words; the parts MUST together keep the line's
  words once each, in order.
- **FR-004**: When only one voice is found, or there is too little speech to
  tell voices apart, the lines MUST stay unnamed as today.
- **FR-005**: The app MUST name a voice You only when it matches the print
  of the user's voice by a set margin and better than the other voice by a
  set margin; otherwise both are Voice 1 and Voice 2, Voice 1 the one heard
  first. The margins come from measurement (plan), with wrong names counted
  as worse than no names.
- **FR-006**: The user MUST be able to make the print by recording a short
  sample, see that one is kept and where it came from, and delete it. Until
  they record one, the print MAY be taken from the left channel of their
  messenger calls when it is plainly one person; a recorded sample always
  replaces a print taken from calls. Taking it from calls MUST be switchable
  off. The print MUST stay on the phone, be left out of backups,
  and hold no audio from which speech could be played back.
- **FR-007**: The user MUST be able to mark one voice of a transcript as
  theirs (the other becomes Them) and undo it; it changes that transcript
  only.
- **FR-008**: The voice model MUST be downloaded only when the user taps to
  download it, shown with its size beside the speech and summary models, and
  be deletable; the voice step MUST run with no network.
- **FR-009**: The voice step MUST run where transcription runs (after each
  call, only when asked, or overnight, as the user set it) and MUST NOT delay
  the transcript: the lines show unnamed first if the voices are not ready.
- **FR-010**: The summary MUST use You and Them for a one-mix call that has
  them, and be told of two unnamed people for Voice 1 / Voice 2.
- **FR-011**: The Sales Engine transcript (002, FR-030) MUST label You
  Seller and Them Customer, as for messenger calls, and Voice 1 / Voice 2
  Speaker 1 / Speaker 2; a transcript already sent MUST NOT be sent again
  because its labels changed.
- **FR-012**: Transcribing a past call again MUST tell its voices apart as
  for a new call; past transcripts MUST NOT be redone without the user
  asking.
- **FR-013**: The voice model MUST be offered only on phones with the memory
  it needs, by the same rule as the other models, and the step MUST check
  free memory before it starts.
- **FR-014**: Search, copy and share of a transcript MUST carry the voice
  labels as they carry You and Them today.

### Key Entities

- **Voice label** of a transcript line: You, Them, Voice 1, Voice 2, or none.
  Voice 1 / Voice 2 belong to one transcript; they say nothing across calls.
- **Owner's voice print**: one per phone, made from the user's voice alone;
  when and from what it was made; deletable.
- **A transcript's voice choice**: whether its You was decided by the print
  or marked by the user, so a print made later does not undo the user's mark.
- **Voice model**: a download like the speech and summary models: size,
  memory needed, present or not.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On two-person calls taken from messenger recordings mixed down
  to one (so the channels give the truth), at least 95% of the speech is
  under the right voice; the 217 s call 14:46 of R16 gave 97-99%.
- **SC-002**: Of one-mix calls with You and Them, none in the test set has
  them the wrong way round; a call that cannot be decided says Voice 1 /
  Voice 2. On the user's own phone calls, at least 8 in 10 two-person calls
  get You and Them once a print is kept.
- **SC-003**: Telling voices apart adds at most half the call's length to
  its processing on the A24 (R16 measured 0.30-0.40 of it), and the
  transcript is never later than without it.
- **SC-004**: Peak memory of the voice step stays under 500 MB on the A24
  (R16: 349 MB), and the step never runs at the same time as a summary.
- **SC-005**: The extra download is under 60 MB (R16: about 42 MB of models).
- **SC-006**: Calls whose channels differ come out exactly as before: the
  same lines, labels and timings.

## Assumptions

- The voices are told apart and the user's voice recognised by the approach
  of spike R16 (a two-speaker diarisation model and a voice-embedding model,
  about 42 MB); the plan picks the exact models and checks their licences.
- Two voices per call. More is out of scope here.
- The channel decision of R4 and its forcing setting stay as they are; this
  feature only acts on what it calls a mix.
- The feature is in both builds, F-Droid's and LANARS's; only the CRM part
  (US3's last two scenarios) is LANARS's.
- The phones measured are the A24 for speed and memory and the Honor for
  calls whose channels differ; a phone that keeps one mix and is faster than
  the A24 needs no separate target.
- Lines stay in the call's language; the labels are words of the app's UI,
  not of the transcript.
- Voice prints are biometric data under GDPR: keeping one on the phone, made
  on the user's tap, deletable, and never sent, is the condition for having
  it at all; the privacy policy and permission lines say so.
