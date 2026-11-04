Every JSON body of LANARS's Sales Engine API document of 2026-11-03
(specs/002-sales-engine-call-logging/reference/), one file per example, named
after what it shows. The document says a contract test on the server compares
each with the live answer, so these are what the client is tested against.
Written by a script from the document, not by hand, except:

- notice.json: the document cuts the notice at "…"; the items and the second
  section here are invented, in the shape §3.1 describes.
- The 2026-11-03 document's new examples (no_match, the deal search) were
  written in by hand from it: call-no-match, deal-found-request,
  deal-found-logged, search-request, search. The Make private examples went
  with that endpoint.
- error-session-*.json: §2.2 names these `detail` values in a table rather
  than as bodies.
