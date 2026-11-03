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
   transcript and an English summary; never audio; Private keeps a call out.
   And a warning when transcripts will not reach it: "No speech model:
   the CRM gets calls without a summary", "Summaries are off: ...".
5. Needs the call-log permission: asked here when it is missing.

## The notice screen

The server's text as given: title, intro, each section's heading,
paragraphs, items (bullets), ordered items (numbered). At the bottom
**I agree**; a back arrow leaves without agreeing.

## After a call: the notification

Channel "Sales Engine". One per work call, replaced as it changes:

| CRM said | Title | Text | Actions |
|---|---|---|---|
| one open deal | the deal's title | "Logging in 2 min · Eksempel AS" | **Private**, **Change** |
| several open deals | "Choose a deal" | "2 deals for this number" | **Private**, **Choose** |
| not in the CRM / lookup failed | "Call to the Sales Engine" | "Sending in 2 min" | **Private** |
| colleague | none | | |
| after sending | "Logged on Eksempel AS – nettside" | | auto-dismissed |
| waiting for a deal | "Choose a deal" | "Not logged yet" | **Choose** |

Private replaces it with "Kept private" for a few seconds.

## The call sheet

Opened from Change/Choose, or from a call's row. A bottom sheet:

- the call: name or number, direction, time, length;
- **Deal**: the candidates as radio rows (title, organisation, stage,
  status chip for won/lost, "call due" mark), the suggested one preselected;
  **Don't log**; "3 more deals: log this call in the web app" when the total
  exceeds them; a refreshed list for a call that waited (§7);
- **Outcome**: segmented chips: Reached, No answer, Voicemail, Wrong number,
  Call back, Not interested;
- **Next call**: off, or date (Oslo), time (optional), subject;
- **Log call** / **Private** buttons; after sending, **Make private** for a
  call not logged on a deal.

## Calls tab

A work call's row gets a small CRM mark in its details line:
"On Eksempel AS – nettside", "Choose a deal", "Not in CRM", "Private",
"Sending...", "Not sent". A chip **Choose a deal** filters to the calls
waiting for one (shown by the tab's existing rule). Tapping the mark opens
the call sheet.

## Prompts that need the seller

Notifications on the same channel: "Sign in to the Sales Engine again",
"Read the Sales Engine's new notice", "Update JemRec to keep logging calls";
each opens the Sales Engine settings.
