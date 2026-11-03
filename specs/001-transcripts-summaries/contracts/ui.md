# Contract: what the user sees

In JemRec's look, in each of its three looks. Words below are the English the
app shows.

## Recordings list

- **Row**: a **Transcript** icon in `RowActions` beside Keep and Share. A row
  with a transcript shows a small text mark in its details line; while one is
  being made, a small progress ring there.
- **Search**: also matches transcript and summary words. A row matched that
  way shows the matching words, highlighted, under its details ("…said the
  **delivery** is on Friday…").
- **Chip**: **Transcribed**, by the existing rule (shown only when it would
  leave some, not all and not none).

## Transcript screen

Top bar: back, the call's name and time, overflow: **Language for this
call**, **Transcribe again**, **Copy all**, **Export** (TXT, Markdown, SRT,
VTT, JSON → Android's share sheet).

Under it, a segmented button: **Transcript | Summary**.

| State | Transcript tab | Summary tab |
|---|---|---|
| No speech model | "Transcribe on the phone: download <model>, <size>. The call never leaves the phone." **Download** | - |
| Ready | Estimate ("about 6 minutes"); **Transcribe** | - |
| Queued / running | progress, "part 2 of 3", **Cancel** | - |
| Done | lines | as below |
| Failed | the reason in plain words; **Try again** | - |

A line: time, **You** / **Them** in the colours the look gives the two sides
(no names when the sides are mixed), text. Tap: plays from there. Long press:
copy the line. Footer: model, language, "took 6 min for 5 min of call".

Summary tab: no summary model → offer with size and memory need; a model that
does not fit says "Needs a phone with 8 GB" and is not offered. Ready →
**Summarise**. Done → **Why** (one line), the account, then **Key points**,
**Decisions**, **To do**, **Facts**, each item tappable to its lines in the
Transcript tab. Footer: model and time taken.

## Settings → Transcripts and summaries

- **Language**: 17 languages by their own names, sorted by the displayed
  name, plus Detect automatically.
- **Speech model**: each with size, state, Download / Delete; marks "Made for
  Norwegian" and "Recommended for this phone".
- **Use a model made for the language**: on.
- **Who spoke**: Automatic / Always by channel / Never.
- **When**: Only when I ask / After each call / Overnight while charging.
- **Summary model**: those that fit, with size and memory; **Summarise after
  each transcript**.
- **Download over Wi-Fi only**.
- **Space used**: models and transcripts.
- Debug build: **Imported models** from `Android/data/<app>/files/models/`.

## Notifications

Channel **Transcripts and summaries**: "Transcribing <call>" / "Summarising
<call>" with progress and **Cancel**; "Transcript ready" opens the screen;
failures say why. Downloads: "Downloading <model>" with progress.
