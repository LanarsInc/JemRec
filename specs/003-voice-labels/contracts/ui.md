# Contract: what the user sees

In JemRec's look, in each of its three looks. The words are the English the
app shows.

## Settings > Transcripts

Below the speech and summary models, a **Voices** group:

1. **Voice model** row, as the other models: "Tells two voices apart in
   phone calls that come out as one mix. 42 MB to download once, about
   400 MB of memory while it works." **Download** / progress / **Delete**.
   On a phone with too little memory: "Needs a phone with more memory" and
   no button.
2. **Your voice** row, enabled once the voice model is there:
   - none: "Not known. Phone calls show Voice 1 and Voice 2." **Record**
   - from calls: "Learned from 3 of your messenger calls." **Record**
     (better), **Delete**
   - recorded: "Recorded on 3 Nov 2026." **Record again**, **Delete**
3. **Learn my voice from my calls** switch, on by default: "Uses your side
   of messenger calls when it is plainly only you speaking. Nothing leaves
   the phone."

When the voice model is missing and the last one-mix call was unnamed, the
transcription settings' note says: "Phone calls on this phone come out as
one mix. Download the voice model to tell the two voices apart."

## Record your voice

A screen of its own: a short text in the app's language to read aloud, a
level meter, a 30 s progress ring, **Start** / **Stop**, and "Kept as numbers
that describe your voice, not as a recording. Never leaves the phone." The
microphone permission is asked at **Start**, with why. Under 20 s of speech:
"Too short - read it again." Done: back to Settings with the row updated.

## The transcript

- Lines labelled Voice 1 / Voice 2 in two colours, the same two as You and
  Them; You and Them as now.
- With Voice 1 / Voice 2, a line above the first: "Which one is you?" with
  **Voice 1** / **Voice 2** buttons; once marked, an **Undo** in a snackbar,
  and the line goes. With no print yet, the same line adds: "Record your
  voice in Settings so the app knows next time."
- As built: for a transcript named by the print or marked, a line above the
  first says "You were found by your voice." or "You marked your voice.",
  with **Swap** and **Not marked** beside it - not an overflow menu, which
  the transcript screen does not have; its row of actions under the lines
  (Copy all, Export, Transcribe again) is full. No snackbar Undo: Not marked
  is the undo, and it stays there.
- While the voice step runs after the lines are shown: "Telling voices
  apart..." under the title, as the summary's progress is shown.
- Copy and share: "Voice 1:", "Voice 2:" as they write "You:", "Them:".

## Notices

- Not enough memory for the voice step: the same notice as a summary's, with
  "voices" in place of "summary".
