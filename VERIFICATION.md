# Verification record

## Version 0.3.1 · 26 November 2026

All 23 Android instrumentation checks passed on each of two emulators, Android 8 (API 26) and Android 15 (API 35). All 29 server tests passed. Both APK signatures verified, and the preview launched alongside the standard app. The n8n clock published a Tech Brief with the Android client closed. [Final build and artifacts](https://github.com/VonSchugel/pappap/actions/runs/45808976958) · [Initial run with failing regressions](https://github.com/VonSchugel/pappap/actions/runs/45745673175)

| Area | What the tests exercised |
| --- | --- |
| Journal and unfinished work | With both network transports off, reopen a draft after a force-stop, switch between focused and full-entry editing, and resume a previous date’s session. Rapid edits across two dates stayed with their entries. An essay-only date appeared in archive search. Markdown retained a multiline passage and its follow-up reading link. |
| Agenda and notifications | Pause a recurrence in Schedule and see the change in Today. Repeated delivery produced one notification record. Deadlines with a known time use the configured zone. An incomplete deadline cannot schedule an alarm. A malformed reminder from an earlier version leaves the other alarms and agenda usable. |
| Reading and sync | Reopen a filtered edition at its previous reading position after a process restart. Recover from a rejected server token without losing a pending settings change or cached edition. Coverage of one release merged with its Hacker News discussion, distinct versions stayed separate, and publishing continued when X was unavailable. |
| Publication under load | Sixteen workers made 768 generation attempts for 12 date/report pairs. Each pair ran once and all 12 results survived a database reopen. A slow worker whose lease expired returned the winning publication. Across 1,095 scheduler ticks in 2026, a Monday/Friday schedule published exactly 104 editions. |

The Android and server deadline checks agree on both sides of Morocco’s September clock change. A previous test incorrectly expected 23:30 UTC on November 26 to fall on November 27 in Casablanca. The corrected expectation is November 26 at 23:30. [IANA time-zone rules for Africa](https://data.iana.org/time-zones/tzdb/africa)

### Live integration results for November 26

| Source | Observed result |
| --- | --- |
| Open-Meteo Weather and Marine | 24 hourly temperature readings and 24 offshore swell-height readings for the requested date. |
| SHOM | Four dated high or low tide predictions at the Mohammedia harbor station. A separate Chromium fixture verified that independently delayed rows and UTC labels are read before parsing. These are harbor predictions, and exposure inside the rocks remains unverified. |
| GitHub and Hacker News | Four GitHub project or release records and 20 Hacker News discoveries with discussion links. |
| Devpost | Nine discovery listings before Morocco eligibility checks. |
| DGM weather and marine notices | Both bulletins responded. Neither listed Mohammedia. Local warning coverage remained unknown. |
| X and beach-specific evidence | Live X access was unconfigured. The outage path was exercised with other sources active. No current Mannesmann water-quality sample or beach-specific closure status was verified. |

[Android 8 log](https://github.com/VonSchugel/pappap/actions/runs/45808976958/job/139871532108) · [Android 15 log](https://github.com/VonSchugel/pappap/actions/runs/45808976958/job/139871531670) · [Server and n8n log](https://github.com/VonSchugel/pappap/actions/runs/45808976958/job/139871532000) · [Live source log](https://github.com/VonSchugel/pappap/actions/runs/45808976958/job/139871531881)

## Version 0.3 · 26 November 2026

The signed debug and side-by-side preview APKs installed on an Android 15 emulator. All 13 Android checks and 17 server tests passed. The n8n clock published one Tech Brief while no Android client was running.

The emulator reopened an offline journal draft after a force-stop and edited the same draft in focused and full-entry views. It completed a session on an earlier date and verified that the current date stayed open. It expanded schedule controls and hourly weather, and restored the Robotics edition at the end of its article after tab switching, activity recreation and a force-stop. The screenshot archive includes all four tabs, focused writing, and Today in dark mode at 150% system font size.

[Verified run and downloads](https://github.com/VonSchugel/pappap/actions/runs/45743045946) · [Screen previews](https://github.com/VonSchugel/pappap/actions/runs/45743045946/artifacts/15082242069)

## Reusable activities · 25 November 2026

Version 0.2 passed seven Android 15 instrumentation checks and 17 backend tests. With networking disabled, the emulator created a project from the template picker, selected its repeat days, typed measurements and force-stopped the app. After restart it recovered the measurements. A dated session retained its original prompts after the activity was renamed and paused. Completing the older session removed it from unfinished work and left the other date open. Its Markdown export contained the original measurements.

The backend accepted custom prompts and weekdays, rejected invalid definitions, and published according to the local weekday across a UTC date boundary. The n8n clock also published a Tech Brief without an Android client. Both APK signatures passed verification. The separate preview installed and launched alongside the standard app.

[Build, device checks and downloadable APKs](https://github.com/VonSchugel/pappap/actions/runs/45680148466)

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

