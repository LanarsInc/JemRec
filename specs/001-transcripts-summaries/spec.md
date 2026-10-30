# Feature Specification: Transcripts and summaries of calls

**Feature Branch**: `feature/add-transcribe-and-summarize` (spec dir `001-transcripts-summaries`)

**Created**: 2026-10-29

**Status**: Draft

**Input**: Transcripts and summaries of calls, as CallVault has them, in this
internal fork of JemRec - the full feature, not a spike. Every language
CallVault offers, plus Norwegian. Tested on a Samsung (Galaxy A24); the Honor
is left out for now. The fork is internal and ships as an APK; licensing is
settled later, so CallVault's code may be taken directly.

## Languages

CallVault's code offers 16 (`TranscriptionLanguageChoice.SUPPORTED`; its README
still says 13): Arabic, Chinese, English, French, German, Greek, Hebrew,
Hungarian, Italian, Persian, Polish, Portuguese, Russian, Spanish, Ukrainian,
Vietnamese. This fork adds **Norwegian** (Bokmål): 17, plus "detect
automatically".

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read what was said (Priority: P1)

On a recording the user opens **Transcript**. The first time, JemRec offers
the speech model with its size. It transcribes on the phone and shows lines
marked **You** or **Them** with their time; a tap plays from there.

**Independent Test**: on the Samsung, with a model downloaded, transcribe a
recorded call and read it.

**Acceptance Scenarios**:

1. **Given** no model, **When** the transcript is opened, **Then** the
   download is offered with its size; nothing runs until it is verified.
2. **Given** a model, **When** Transcribe is tapped, **Then** progress shows,
   the work survives the screen going off, and Cancel stops it.
3. **Given** a finished transcript, **Then** it opens at once, every time.
4. **Given** a recording with the sides on separate channels, **Then** each
   line says who spoke; **given** one where both channels carry the same mix,
   **Then** the lines are not doubled and carry no names.
5. **Given** a line, **When** tapped, **Then** playback starts at its time.

### User Story 2 - The call's language (Priority: P1)

The user picks the language in Settings (default: the phone's own if offered,
else Norwegian), and can override it for one recording. The transcript is
in that language, never translated. For Norwegian, a model trained on
Norwegian (NB-Whisper) is offered beside the general one.

**Acceptance Scenarios**:

1. **Given** any of the 17 languages, **When** a call in it is transcribed,
   **Then** the text is in that language and script.
2. **Given** a per-recording override, **Then** only that recording uses it.
3. **Given** Norwegian and NB-Whisper downloaded, **Then** NB-Whisper is used
   for Norwegian and the general model for the rest.

### User Story 3 - What the call was about (Priority: P1)

Under a transcript, **Summary** gives: why the call happened (one line), an
account in prose, key points, decisions, action items and key facts (names,
numbers, dates), in the transcript's language. A point links to the lines it
came from. The summary model is a separate download, offered with its size and
memory need, and only models the phone can hold are offered.

**Acceptance Scenarios**:

1. **Given** a transcript and a summary model, **When** Summarise is tapped,
   **Then** a summary appears in the transcript's language.
2. **Given** a phone with 6 GB, **Then** only summary models that fit are
   offered, and the large one says why it is not.
3. **Given** a point with sources, **When** tapped, **Then** the transcript
   scrolls to those lines.
4. Nothing in a summary is a fact absent from the transcript (checked on the
   test set by hand).

### User Story 4 - Without asking each time (Priority: P2)

Settings → Transcripts → **When**: Only when I ask / After each call
(default) / Overnight, while charging (works through the backlog). **Summarise
too** (on by default) follows a transcript automatically. Defaults changed
from Only when I ask and off on 2026-10-30, at the user's request; nothing
runs until a model is downloaded.

**Acceptance Scenarios**:

1. **Given** After each call, **When** a call is saved, **Then** it is
   transcribed (and summarised if on) without opening the app.
2. **Given** Overnight, **Then** untranscribed recordings are done only while
   charging and battery not low, newest first.
3. A call recorded while a transcription runs is saved complete.

### User Story 5 - Find and take out (Priority: P2)

The Recordings search also finds words in transcripts and summaries, and says
which matched. A **Transcribed** chip narrows the list. A transcript can be
copied, or shared as text, Markdown, SRT, VTT or JSON.

### User Story 6 - Keep it tidy (Priority: P3)

Settings shows each model's size with Delete, and how much transcripts use.
Downloads can be limited to Wi-Fi. Deleting a recording for good deletes its
transcript and summary.

### Edge Cases

- Silent channel: only the speaking side has lines.
- Calls over an hour: memory stays bounded (chunked decode, chunked summary).
- Interrupted or corrupt download: resumed or discarded, never loaded.
- App killed mid-run: the job resumes or restarts; nothing half-done shows as
  done.
- Transcription in the wrong script (auto-detect, or a mislabelled call): one
  retry with the pinned language, as CallVault's `WrongScriptRetry`.
- Not enough free memory for the summary model at run time: it says so and
  does not start.
- A recording moved to Recently deleted keeps its transcript until deleted for
  good.

## Requirements *(mandatory)*

- **FR-001**: Speech-to-text and summaries run on the phone. The only network
  use is downloading a model, on the user's tap, optionally Wi-Fi only.
- **FR-002**: Every model is verified by size and SHA-256 before it is loaded.
- **FR-003**: 17 languages plus automatic; a Settings default and a
  per-recording override; never translated.
- **FR-004**: Who spoke comes from the channels when they carry different
  sides, decided per recording; otherwise no names.
- **FR-005**: One heavy job (transcribe or summarise) at a time, in a
  foreground notification with progress and Cancel.
- **FR-006**: Transcripts and summaries are stored per recording, searchable,
  and survive app updates.
- **FR-007**: Summary fields as in Story 3, in the transcript's language,
  with sources.
- **FR-008**: Summary models are offered by the phone's total memory.
- **FR-009**: Automatic modes as Story 4.
- **FR-010**: Search, chip, copy, share and export as Story 5.
- **FR-011**: Deleting for good deletes derived text.
- **FR-012**: The recorder (`shellserver/`) is unchanged.
- **FR-013**: Every place CLAUDE.md lists for a landed feature says the same
  thing (README, site, manual, privacy policy, ARCHITECTURE, DEVELOPMENT,
  fastlane, screenshots, emulator checks).

## Success Criteria *(mandatory)*

- **SC-001**: On the Galaxy A24, a 5-minute call transcribes in under 10
  minutes with the default model.
- **SC-002**: Word error rate measured per language on the test set for each
  speech model; the default per phone is the best one meeting SC-001.
- **SC-003**: Peak memory: transcription under 1.5 GB; summary under 2.5 GB on
  a 6 GB phone.
- **SC-004**: A 5-minute call's summary on the A24 in under 5 minutes.
- **SC-005**: Recording during a heavy job saves a complete recording.
- **SC-006**: Unit, screen and lint gates pass; emulator checks pass.

## Assumptions

- Test phone: Samsung Galaxy A24 (SM-A245F), Android 14, MediaTek Helio G99
  (2x Cortex-A76 2.2 GHz + 6x A55 2.0 GHz, DOTPROD, no i8mm), 5.8 GB memory,
  91 GB free. Read over adb 2026-10-29.
- arm64 only.
- Test audio: public speech sets with reference text (FLEURS) per language,
  played into calls; real calls are never committed.
- CallVault's measurements hold until measured here.
