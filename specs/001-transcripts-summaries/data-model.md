# Data model: transcripts and summaries

Shapes follow CallVault's `data/transcripts/db` (schema 9), trimmed to what
this feature uses. Keys are JemRec's recording names (`Recording.name`).

## Catalogue entries (code, not stored)

**SpeechModel**: `id`, `label`, `fileName`, `url`, `sha256`, `sizeBytes`,
`realTimeFactor` (opening estimate; replaced by the phone's own measurement),
`languages` (null = all; `{"no"}` for NB-Whisper). Entries: research R3.

**SummaryModel**: as SpeechModel plus `peakMemoryBytes`. Offered only when
`ActivityManager.MemoryInfo.totalMem >= 2 * peakMemoryBytes`. Entries:
research R6.

**Model file state**: `Absent → Downloading(bytes, total) → Verifying → Ready`,
or `Failed(reason)`. Short file: resumed. Hash mismatch: deleted.

## Settings (Prefs)

| Key | Values | Default |
|---|---|---|
| `tx_language` | 17 codes or `auto` | the phone's language if offered, else `no` |
| `tx_model` | SpeechModel id | from R14 per phone class |
| `tx_language_models` | on/off: use a model made for the language when downloaded | on |
| `tx_sides` | `auto`, `split`, `mix` | `auto` |
| `tx_mode` | `manual`, `after_call`, `overnight` | `after_call` |
| `tx_charging_only` | Boolean (overnight) | true |
| `tx_threads` | Int or 0 = measured default | 0 |
| `sum_model` | SummaryModel id | largest that fits |
| `sum_auto` | Boolean: summarise after each transcript | true |
| `models_wifi_only` | Boolean | true |
| `tx_rtf.<model>`, `tx_load_ms.<model>` | measured on this phone | - |

## Room: `transcripts.db`

### transcripts

| Column | Type | Rule |
|---|---|---|
| recording | TEXT PK | Recording name |
| modelId | TEXT | |
| language | TEXT | what whisper was given (`auto` allowed) |
| detectedLanguage | TEXT? | when auto |
| sides | TEXT | `split` or `mix`, as decided |
| audioMs, wallMs, loadMs | INTEGER | |
| threads | INTEGER | |
| createdAt | INTEGER | epoch ms |
| status | TEXT | `done`; a row exists only when done |

One transcript per recording: Transcribe again replaces it (CallVault does
the same; the model id says which made it).

### transcript_segments

| Column | Type | Rule |
|---|---|---|
| recording | TEXT | FK → transcripts, cascade delete |
| idx | INTEGER | order; (recording, idx) PK |
| startMs, endMs | INTEGER | from the recording's start; end ≥ start |
| speaker | TEXT | `you`, `them`, `none` |
| text | TEXT | trimmed, never blank |

`transcript_segments_fts`: FTS4, unicode61, content = `text`.

### call_summaries

| Column | Type | Rule |
|---|---|---|
| recording | TEXT PK | FK → transcripts, cascade delete |
| modelId | TEXT | |
| language | TEXT | the language it was written in |
| json | TEXT | `CallSummary.toJson()`: intent, summary, keyPoints, decisions, actionItems, keyFacts; each list item may carry `sources: [idx]` |
| wallMs | INTEGER | |
| createdAt | INTEGER | |

`call_summaries_fts`: FTS4 over `CallSummary.searchableText()` (no JSON keys).

### recording_language

| Column | Type | Rule |
|---|---|---|
| recording | TEXT PK | |
| language | TEXT | per-recording override; kept after use, so Transcribe again uses it too |

## Jobs (WorkManager)

**Transcription**: unique `jr_tx_now` (manual, a list of recordings),
`jr_tx_overnight` (periodic 24 h, constraints), after-call one-shot per
recording. Input: recording names, optional language override.
States: `Queued → Running(percent, part i/n, side) → Done | Cancelled |
Failed(reason)`.

**Summary**: unique `jr_sum_now`; follows a transcription when `sum_auto`.
Same states.

One engine lock across both kinds; a summary never loads while whisper is
loaded.

## Lifecycle

- `Trash.deleteForGood`, `Trash.purgeExpired`: delete the transcript row
  (segments and summary cascade) and the override.
- Recently deleted: kept.
- App update: Room migrations; first schema is version 1.
