# Marqicci

Marqicci is a single-user native Android daybook for Mohammedia. Today, Schedule, Tech Brief and Journal share a local calendar date. The first launch creates one labeled example day. Select **Continue journal**, type a paragraph, and wait for **Saved on this device**. Use the date button to reopen an earlier page. Journal drafts, observations, bookmarks, passages and essays live in Room on the phone.

## Reading and writing in version 0.3.1

Today shows the next scheduled item in local time and offers unfinished work above the agenda. Tap an agenda row for the full report. On the weather page, expand **Hourly forecast** for the detailed readings. In Schedule, tap a recurring item to change it or check its next run.

Tech Brief remembers the reading position for each topic and date on this device. In Journal, **Focus writing** opens one section at a time. **All sections** returns to the complete entry. Both views edit the same offline draft. Search opens the archive, and the overflow menu contains questions, essays, curriculum and Markdown export.

## Install the debug app

Android 8.0 (API 26) or later is required. Download **app-debug.apk** under Artifacts on a successful Android CI run while signed in to GitHub. Open the APK on the phone, or use `adb install -r app-debug.apk`. A build on a different machine may use a different debug signing key. Export existing entries before uninstalling an earlier build. Android clears local journal data on uninstall.

To build from source, open this directory in Android Studio with JDK 17 and Android SDK Platform 35 installed. Use **Run → Run 'app'**, or run the wrapper below. The first build downloads Gradle and Maven dependencies.

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Linux or macOS use `bash gradlew :app:assembleDebug`. The project includes a checksum-pinned Gradle 8.9 wrapper. Gradle caches and Android SDK files are external dependencies.

## Publish while the phone is closed

Use an always-on host with Docker and Docker Compose. If the host sleeps, the next tick checks for missed publications on the current local date. Previously published dates remain in the server archive.

1. Copy `.env.example` to `.env`. Generate a private token with `python -c "import secrets; print(secrets.token_urlsafe(32))"` and use it as `APP_TOKEN`. Optional GitHub and X credentials belong on the server.
2. Run `docker compose up -d --build`. The first pull includes a browser for reading the official SHOM tide table. The API listens on the host's loopback port 8000. The n8n editor listens on loopback port 5678.
3. Open [n8n](http://localhost:5678), complete its local owner setup, and choose **Import from File**. Select `n8n/Marqicci-Daily.json`.
4. In **Publish due reports**, create a **Header Auth** credential. Use header name `Authorization` and header value `Bearer YOUR_APP_TOKEN`. Save the credential and **publish/activate the workflow**. Its once-per-minute trigger calls `http://api:8000/v1/tick`. The API reads current preferences on every tick.
5. For a USB-connected phone, run `adb reverse tcp:8000 tcp:8000`. In **Schedule → Server connection**, enter `http://127.0.0.1:8000` and the token. For a standard Android emulator use `http://10.0.2.2:8000`. For untethered access expose the API behind an HTTPS reverse proxy and enter its HTTPS URL. The n8n editor can stay private.
6. Open a report and tap **Refresh report** to generate it immediately. Tech Brief has **Refresh edition**. The next scheduled tick publishes due items regardless of whether the phone is running. WorkManager downloads reports when Android permits background work. The app also syncs on opening a day.

The default timezone is `Africa/Casablanca`. The app bundles IANA clock rules so an older phone can schedule with the same local dates as the server. Rebuild the server and update the app when the rules change. Weather and swimming publish at 06:00, Tech Brief at 07:30, and hackathons at 09:00. The optional 19:00 journal reminder starts paused. Reports start with notifications off. **Schedule** exposes publication time, pause, local notifications and next run separately. Android may defer notifications during Doze. Device-local notifications announce scheduled activities. They do not certify successful server publication. A disabled background process after Android's explicit **Force stop** resumes after the app is opened.

One phone owns the preferences in this single-user deployment. Offline edits are applied to the device immediately and queued for the next successful server sync. The journal is available from midnight and never requires the server. Use the same app token for n8n and the phone. Rotate it in both places if changed on the host.

## Reading and writing

### Reusable activities

In **Schedule → Add recurring activity**, choose **Study**, **Project**, **Writing**, **Review**, or **Custom**. Name the activity, choose a time and weekdays, then edit its prompts. Each occurrence opens a separate page in Today. Typing your first answer or choosing **Start session** saves a dated copy of the prompts and starts the session. **Mark complete** closes that date’s work. **Reopen session** lets you revise it.

For a controls lab, choose **Project**, name it “Motor step response”, and replace the prompts with “Prediction”, “Measured response” and “Next experiment”. For a seminar, use **Writing** and make the prompts “Claim”, “Strongest objection” and “Revision”. Both activities can run on the same day and keep separate notes. An unfinished page from an earlier date appears under **Today → Pick up unfinished work**. Opening it resumes that original date. Saved sessions retain their title and prompts through later schedule edits.

Session notes work offline and appear in the day’s journal Markdown export. Preferences sync to FastAPI, including weekdays for scheduled reports. Update the server with `docker compose up -d --build` to apply weekday changes to server publications.

CI supplies two signed builds. **app-debug.apk** uses the original application ID. **app-preview.apk** installs as **Marqicci Activities** alongside the November 22 build and starts with a separate local notebook. Use the preview if you already have valuable entries and Android rejects an in-place debug update because the signing keys differ.

### Journal, newsletter and events

- **Tech Brief** groups stories by topic after the lead. Browse editions by date, filter by topic, bookmark a story, or save its headline and link to the selected day's journal. Edit the source toggles, follow lists and maximum edition length under **Preferences**.
- **Journal** autosaves edits to Room. Long-press a passage, adjust the selection, and choose **Work with selected passage**. Offline prompts cover premises, inferences, objections and follow-up readings. An annotation retains the quote, selection offsets and original section revision. Edits continue in the same draft.
- **Import or edit curriculum** accepts Markdown with `Day 1` or `Jour 1` headings, one prompt per line, a JSON array, or `{"days":[{"title":"Day 1","prompt":"Your actual reading prompt"}]}`. Import the original 28-day plan, inspect the count, set its start date and number of calendar days per prompt, then save. A two-day pace spans 56 calendar days for 28 prompts. No reconstructed personal curriculum is bundled.
- Use **Questions** to carry an unresolved question to a date. **Link another entry** creates a bidirectional link. **Essay workshops** keeps a separate working argument and its linked journal dates. Open a workshop on another date and choose **Link this entry**. **Export Markdown** writes the displayed entry, its annotations and linked workshop text through the Android file picker.
- Hackathons stay under **Today**. A bookmark appears in **Schedule**. **Add deadline** creates a date-specific reminder when the registration deadline and clock time are known.

## Coast and hackathon evidence

The initial swim pin marks Mannesmann Beach, not a surveyed point inside the pool. Inspect and edit it under **Schedule → Beach pin and swim window**. Forecasts identify the offshore model grid. The tide adapter reads the official Mohammedia harbor predictions and the displayed UTC offset, then converts them to the configured calendar zone. It uses adjacent extrema to describe phase. On a station or date mismatch, insufficient data, or a moved pin outside the area, the app shows unavailable or unverified fields.

DGM's official notices can apply to a province or a stretch of coast. The report identifies that area. A current, reviewed closure precedes the model assessment. The default installation has no continuous beach-closure or laboratory-result feed. A current water-quality result must carry a sample date, source and station. A region-wide bulletin or an old sample cannot establish conditions between the rocks.

For reviewed local evidence, add records to `server/data/coast.json`. Each record has `kind` (closure, sample, marine, weather or tides), `text`, `source` (official HTTPS URL), `issued` (ISO date/timestamp), `lat`, `lon` and `radiusDegrees` (coordinate matching tolerance). Include inclusive `from` and `through` dates for time-limited records. A sample's `issued` is its collection date. For a tide record include `extrema` containing local ISO-minute `at` and `type` of high or low, including adjacent dates for phase calculation. Only enter records that cover the selected station and dates. A manual refresh reads changed files from the mounted directory.

Devpost supplies discovery. Local listings are filtered for Moroccan locations. For online events the digest checks organizer rules for explicit worldwide or Moroccan eligibility. Ambiguous listings stay out of the digest. To add a reviewed event, use the example in `server/data/events.json`. Include `remote`, `moroccoEligible`, `rules`, eligibility text and validity dates. Use `deadlineAt` with an explicit UTC offset for registration deadlines. A submission cutoff alone does not create an application reminder. Hybrid events can be added with `country: "MA"` and a hybrid mode label.

## Tests and extension points

```bash
python -m pip install -r server/requirements.txt httpx
PYTHONTZPATH="" python -m unittest discover -s server -p 'test_*.py' -v
bash gradlew :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest
bash scripts/device-checks.sh
```

Run the device checks on a fresh disposable emulator. Use a Google APIs image that allows `adb root` for Android 8 (API 26). The script installs the standard app, its test APK and the side-by-side preview. It changes radio and display settings, verifies the device is offline, reopens a journal draft after a force-stop, and connects to a local FastAPI server over ADB for the sync recovery test. The Python dependencies in `server/requirements.txt` must be installed on the test host. [Full test results and live source checks](VERIFICATION.md)

The backend registers newsletter sources in `NEWS` and report producers in `REPORTS` in `server/sources.py`. Each report has a kind, title, date, summary, blocks and links. Add its default schedule entry in `app/src/main/assets/defaults.json`. Today renders that shared shape. Source failures are isolated. SQLite uses a unique day/kind and an expiring generation lease for concurrent ticks. Android uses a date/activity delivery ledger. Add a personal recurring activity directly in Schedule.

Primary references: [Open-Meteo weather](https://open-meteo.com/en/docs), [Open-Meteo marine](https://open-meteo.com/en/docs/marine-weather-api), [DGM](https://marocmeteo.ma/fr/vigilance/marine), [SHOM](https://maree.shom.fr/harbor/MOHAMMEDIA), [national bathing-water laboratory](https://labo.environnement.gov.ma/), [official HN API](https://github.com/HackerNews/API), [GitHub release API](https://docs.github.com/en/rest/releases/releases), [X API](https://docs.x.com/x-api), [n8n Schedule Trigger](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.scheduletrigger/).

