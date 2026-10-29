# Quickstart: checking transcripts and summaries

## Prerequisites

- JDK 21 (`JEMREC_JAVA_HOME`), NDK 28.2.13676358, CMake 3.22.1.
- Submodules: `git submodule update --init third_party/whisper.cpp third_party/llama.cpp`
- The Galaxy A24 (`R58X30P75XV`) on USB. Stop the recorder before any
  `adb uninstall` (CLAUDE.md).
- Test audio **outside the repo**: per language, about five minutes of FLEURS
  clips with their reference text, joined; for Norwegian, `nb_no`.

## Off the phone

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew testDebugUnitTest --tests '*.ui.screens.*' -PrecordScreens=true
```

Then look at every PNG that changed: the Transcript screen's states in each
look, the Summary tab, Settings.

```bash
./gradlew lint
```

No warnings.

## On the A24

1. **Channels first.** Make a phone call from a second phone, each side
   speaking in turn; check whether the recording's two channels differ
   (`ChannelSides` logs its decision). The answer goes in ARCHITECTURE.
2. Install the debug build; both `systemInfo` lines show DOTPROD.
3. Settings → Transcripts and summaries: download the recommended speech
   model; it shows as downloaded only after verification.
4. Play a language's test file into a call; open the recording → Transcript →
   Transcribe; turn the screen off. Expected: it finishes; the text is in that
   language; lines have You / Them if step 1 said the sides are apart; a tap
   plays from the line.
5. Summary tab: the offered summary model fits (6 GB phone: not Gemma 4 E2B).
   Summarise. Expected: in the transcript's language; each fact is in the
   transcript; tapping a point shows its lines.
6. Per-call language: set another language for one recording; only it
   changes.
7. After each call: switch on, make a call; the transcript appears unasked.
8. Start a transcription, then make a call: the recording is complete.
9. Search a word from a transcript on Recordings: the row shows with the
   words. The Transcribed chip narrows the list.
10. Export as SRT and VTT; open them in a player with the audio: timings line
    up.
11. Delete the recording for good: its transcript is gone from search.

## Measurements (into research.md R14)

For each speech model and language: wall time, load, real-time factor, peak
memory (`adb shell dumpsys meminfo com.jemcik.jemrec` during the run),
threads 2 / 4 / 8, word error rate against the reference (lower case, no
punctuation). For each summary model: time, peak memory, and every fact
checked by hand in English, Norwegian and Ukrainian. Device and date on each;
measured and inferred apart.

```bash
python3 tools/emulator_checks.py --serial emulator-5554
```

On the AVDs CLAUDE.md names, once they exist on this machine.

Pass: SC-001 to SC-006 in [spec.md](spec.md).
