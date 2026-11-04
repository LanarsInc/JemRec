# Contract: what the seller sees

In JemRec's look, in each of its three looks. The words are the English the
app shows. "Sales Engine" is how the CRM is named on screen.

## Settings: Sales Engine

A section of its own in Settings, above Transcripts. Its row says the state:
"Not connected", "Connected as Test Seller · SIM 1 (Telia)", "Sign in again",
"Update the app", "Read the new notice".

The screen, top to bottom:

1. **Account**: the Sign in with Google button; signed in: name, e-mail,
   **Sign out**. A refusal shows its message (§2.2) under the button.
2. **Notice**: "Read and agree" until acknowledged; then "Agreed on
   3 Nov 2026" and **Read again**.
3. **Work line**: the phone's SIMs (name, carrier, last digits), one chosen;
   with one SIM, shown and not changeable. Asks for the phone permission it
   needs, with why.
4. **What reaches the CRM**: plain words: calls on this line, their
   transcript and an English summary; never audio; every call on the line is sent (a work phone).
   And a warning when transcripts will not reach it: "No speech model:
   the CRM gets calls without a summary", "Summaries are off: ...".
5. Needs the call-log permission: asked here when it is missing.
6. **Calls** (once there are work calls; added 2026-11-04): "All sent", or
   "2 waiting to be sent · 1 not sent · 1 transcript not sent" (in the
   error colour when something was given up), with why the first retry
   waits and when it tries again; **Retry now** while a call waits out a
   failed try (never the 2-minute hold, nor a server's Retry-After).

## The notice screen

The server's text as given: title, intro, each section's heading,
paragraphs, items (bullets), ordered items (numbered). At the bottom
**I agree**; a back arrow leaves without agreeing.

## After a call: the notification

Channel "Sales Engine". One per work call, replaced as it changes:

| CRM said | Title | Text | Actions |
|---|---|---|---|
| one open deal | the deal's title | "Logging in 2 min · Eksempel AS" | **Change** |
| several open deals | "Choose a deal" | "2 deals for this number" | **Choose** |
| not in the CRM / lookup failed | "Call to the Sales Engine" | "Sending in 2 min" | |
| colleague | none | | |
| after sending | "Logged on Eksempel AS – nettside" | | auto-dismissed |
| waiting for a deal | "Choose a deal" | "Not logged yet" | **Choose** |
| waiting for a lead (`no_match`) | "Which lead?" | "Pick the deal for this call, or search for it" | **Choose** |
| transcript going as "failed", still sendable | "Transcript not sent" | "The Sales Engine has the call without it. Tap to make it again and send it." | **Send** (opens the sheet) |

The last is not shown for a call with no speech or a deleted recording, and
goes when the transcript is on its way after all.

No Private since the API of 2026-11-03. With notifications blocked, calls go
all the same.

## The call sheet

Opened from Change/Choose, or from a call's row. A bottom sheet:

- the call: name or number, direction, time, length;
- **Deal**: the candidates as radio rows (title, organisation, stage,
  status chip for won/lost, "call due" mark), the suggested one preselected;
  **Other**; "3 more deals: log this call in the web app" when the total
  exceeds them; a refreshed list for a call that waited (§7);
- **Outcome**: segmented chips: Reached, No answer, Voicemail, Wrong number,
  Call back, Not interested;
- **Next call**: off, or date (Oslo), time (optional), subject;
- **Log call**; for a waiting call it needs a deal or Other ticked.
- **Send the transcript** when its transcript went as "failed" (or is
  about to), the call has a recording and is under 30 days old: the phone
  transcribes and summarises it again, queued behind any run, and the texts
  go when the summary is stored (§8 takes one after "failed"). It says why
  when it cannot: the recording is gone, or no summary model is here.
- **Which lead?** for a `no_match` call: a search field (deals or companies,
  from 2 characters, searched 0.4 s after the last key, 60 a 10 minutes),
  its results, then the server's **Suggestions**, then **Other**; nothing
  ticked at first. A deal picked from the search stays shown while the
  search changes. A refused search says why and is not retried.

## Calls tab, Recordings tab, transcript

A work call's row gets a line under it: "Sales Engine · On Eksempel AS –
nettside", "Choose a deal" or "Which lead?" (in the error colour), "Not in
the CRM", "Other: not on a deal", "Not sent (older than 30 days)". While a
failed try waits: "Not sent yet: no connection, or the Sales Engine is busy;
trying again at 15:40". Once the call reached the
server, its transcript's state follows: "· transcript to follow", "· sending
the transcript", "· transcript sent", "· transcript not sent" (none for a
call with no recording). The same line is under the row in the Recordings
tab and under the call's time on its transcript (added 2026-11-04: a seller
could not tell whether a call's texts had gone). Over the Calls list, a card
says how many calls wait for a deal. Tapping either opens the call sheet. (A
card, not the chip first planned: it says what to do.)

## Prompts that need the seller

Notifications on the same channel: "Sign in to the Sales Engine again",
"Read the Sales Engine's new notice", "Update JemRec to keep logging calls";
each opens the Sales Engine settings.
