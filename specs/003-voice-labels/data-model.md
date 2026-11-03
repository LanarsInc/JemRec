# Data model: Who spoke in a one-mix call

## Speaker (enum, `transcribe/TranscriptSegment.kt`)

| Key | Meaning | Shown | Summary | CRM |
|---|---|---|---|---|
| `you` | the owner: left channel, or the voice matching the print, or marked | You | You | Seller |
| `them` | the other person | Them | the contact's name or Them | Customer |
| `voice1` | the voice heard first in a one-mix call, owner unknown | Voice 1 | Voice 1 | Speaker 1 |
| `voice2` | the other voice | Voice 2 | Voice 2 | Speaker 2 |
| `none` | not told apart | nothing | nothing | Call |

`Speaker.of` keeps reading unknown keys as `none`. `QuietSide` and
`LineCleanup` act only on `you` / `them` from channels, as now (they need two
channels); Voice 1/2 pass through them.

## transcript_segments (Room, unchanged columns)

`speaker` takes the two new keys. No other change.

## transcripts (Room, version 1 -> 2)

| Column | Type | Rule |
|---|---|---|
| voiceSource | TEXT NOT NULL DEFAULT 'none' | `none`, `channels`, `voices`, `print`, `marked` |
| voiceModel | TEXT | id of the voice model used, null if none ran |

Migration: add both; set `channels` where the transcript has a `you` or
`them` line, else `none`.

Transitions of `voiceSource` for one transcript:

```text
none ──(voice step: two voices, print undecided or absent)──> voices
none ──(voice step: print decides)──────────────────────────> print
voices ──(user marks a voice)───────────────────────────────> marked
print ──(user marks the other voice / undoes)───────────────> marked / voices
marked ──(undo)─────────────────────────────────────────────> voices
any ──(transcribed again)───────────────────────────────────> as a new call
channels: never changed by this feature
```

A print made or deleted later does not touch `marked` transcripts (spec Key
Entities) and does not redo old ones (FR-012).

## OwnerPrint (file `noBackupFilesDir/voice/print.txt`, `key=value` lines)

| Field | Type | Rule |
|---|---|---|
| version | int | file format, 1 |
| modelId | string | the embedding model; another model voids it |
| source | `sample` or `calls` | a sample replaces calls |
| call | float[n], one line each | the calls' own vectors, newest 5, when `calls`; the print is their mean |
| madeAt | epoch ms | |
| vector | float[n] | L2-normalised; n from the model (192 for TitaNet-small) |

No audio, no recording names. Deleted with "Delete your voice" and with the
voice model.

Settings (prefs): `voiceFromCalls` boolean, default true.

## VoiceModel (`transcribe/model/VoiceModel.kt`)

Two `DownloadableModel` files (segmentation, embedding) presented as one: id,
display size (sum), `peakMemoryBytes` (from V4's measurement, 400 MB until
then), `minTotalMemory` by the same twice-the-peak rule as the summary models.

## In-memory results (plain Kotlin, tested off the phone)

- `VoiceTurn(startMs, endMs, voice: Int)` - from one chunk.
- `ChunkVoices(turns, centroids: List<FloatArray>)` - per chunk, up to two.
- `CallVoices(turns, centroids)` - after `VoiceLink`; empty when FR-004.
- `VoiceNames.decide(centroids, print?) -> Pair<Speaker, Speaker>` - V6.
- `VoiceSeam.apply(lines, words, turns) -> lines` - V5.
