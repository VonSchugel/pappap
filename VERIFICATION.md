# Verification record

## Executed on 22 November 2026

All 16 server and in-memory FastAPI tests passed. The checks cover authenticated requests, rejected invalid settings, timezone rollover, changing and pausing a schedule, concurrent and repeated generation, failure recovery, two versions of one project staying distinct, canonical-link merging, X outage, expiry and location matching of coastal evidence, closure precedence, tide timezone conversion and explicit Morocco eligibility.

The debug APK installed on an Android 15 (API 35) x86_64 emulator. The four instrumentation checks passed. The first wrote through the Compose journal with both network transports disabled. The test harness then force-stopped the app and the second check read the same draft in a new process. The third edited and paused a schedule, delivered the same reminder twice and found one notification and one delivery record. The fourth imported all 28 Markdown prompts. [Android build and device test log](https://github.com/VonSchugel/pappap/actions/runs/45026454196/job/137220437735)

A separate CI check ran FastAPI, imported the supplied workflow into a fresh n8n instance, configured an ephemeral header credential and published the workflow. The clock trigger generated a dated Tech Brief while no Android client was running. The test disabled external news sources to isolate the scheduled publication. [n8n integration test](https://github.com/VonSchugel/pappap/actions/runs/45026764015/job/137221380511)

The following public endpoints or rendered records were fetched for this build. Their responses were replayed through the production parsing functions. This verifies source reachability and parsing at the time of the check. Continuous operation requires a running deployment.

| Integration | Observed result |
| --- | --- |
| Open-Meteo weather | 24 hourly rows for Mohammedia, including requested planning variables and day bounds. |
| Open-Meteo Marine | Regional swell height, period, direction and sea-surface temperature. The returned grid point was offshore. |
| DGM general vigilance | A dated provincial bulletin matched Mohammadia, the spelling used by the feed. |
| DGM marine vigilance | A dated bulletin matched the Mohammedia–Cap Mazagan coastal stretch. |
| SHOM Mohammedia | The rendered harbor table supplied 27 high/low events over seven days and an explicit UTC offset. The parser produced the phase during the selected window. |
| GitHub Releases | Two LeRobot release records parsed. The llama.cpp release endpoint also returned a dated release with assets. |
| Hacker News | Live top and Show HN IDs and 11 linked item records were retrieved. A filtered edition retained three stories with X disconnected. |
| Devpost | Nine discovery listings retrieved. A reviewed remote event qualified using its organizer rules. |
| X | No bearer credential or followed account configured in this build. Missing-access and source-failure cases passed. |
| Water quality and closures | No current dated Mannesmann sample or automatic beach-closure feed verified. Reviewed-record ingestion and closure priority tested with fixtures. |

Continuous publication on your own host starts after configuring its credential and activating the workflow. The tests used disposable credentials and an emulator. The phone app opens its demo and journal immediately.

