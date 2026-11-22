# Verification record

## Executed on 22 November 2026

All 15 server and in-memory FastAPI tests passed. The checks cover authenticated requests, rejected invalid settings, timezone rollover, changing and pausing a schedule, concurrent and repeated generation, failure recovery, two versions of one project staying distinct, canonical-link merging, X outage, expiry and location matching of coastal evidence, closure precedence, tide timezone conversion and Morocco eligibility filtering.

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

No personal draft or credential is included in the repository. Android compilation, emulator results and APK availability are recorded by the **Marqicci Android** workflow. A workflow definition alone is not evidence of a passed device test. The importable n8n workflow was checked structurally. Continuous hosted execution depends on supplying its header credential and activating it.

