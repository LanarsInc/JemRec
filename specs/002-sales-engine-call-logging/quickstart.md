# Quickstart: checking the Sales Engine integration

What proves the feature works, from the cheapest check to the real one.
Commands that need things not yet in the repo (the mock, the stand) say so.

## 1. Off the phone

```bash
./gradlew testDebugUnitTest --tests 'com.jemcik.jemrec.crm.*'
```

Covers: every JSON body of the API document parsed and built
([contracts/api-client.md](contracts/api-client.md)); the failure table;
the deal decision table (§5.1); a work call's state machine
([data-model.md](data-model.md)); step-1 and step-2 bodies, truncation,
language codes, Oslo dates.

Screens, after a meant change:

```bash
./gradlew testDebugUnitTest --tests '*.ui.screens.*' -PrecordScreens=true
```

then look at every PNG that changed ([contracts/ui.md](contracts/ui.md)).

Gates: `./gradlew lint` and `./gradlew :daemon-tests:errorProne`.

## 2. On an emulator, against the mock

A mock of the API in `tools/` answers with the document's examples and
records what it was sent; the emulator reaches it at `10.0.2.2`. The check
signs in with a test token, acknowledges the notice, simulates calls (one
deal, two deals, not in CRM, colleague, hidden, missed) and compares the
requests the mock received with what each case must send. Run as the other
emulator checks (`tools/emulator_checks.py`), on the Android 12, 13, 16 and
17 AVDs, two at a time.

## 3. On the A24, against the stand

Needs from LANARS: the stand's host, the Web client id, and a stand user for
the tester (asked 2026-11-03).

1. Debug build with the stand set: `JEMREC_SALES_ENGINE_DEV_URL` and `JEMREC_SALES_ENGINE_DEV_WEB_CLIENT_ID` (or the same keys in `local.properties`).
2. Settings → Sales Engine: sign in, agree to the notice, choose the SIM.
3. Call a number with one open deal, say nothing for 2 minutes: the call is
   on that deal in the web app, with direction, time and length.
4. Call a number with two open deals, choose the second: it is there.
5. As a stand user with a weekly goal, call a number not in the CRM: the
   notification asks "Which lead?"; search a company, pick its deal: the call
   is on it, and the next call to that number finds the deal by itself.
6. Flight mode, three calls, network back: three calls, once each.
7. With a speech and summary model and "after each call": the deal's call
   note holds the English summary; the transcript opens from the call.
8. Sign out, call, sign in: the call made while signed out never appears.

Write down what was measured, with the device and date, in research.md.
