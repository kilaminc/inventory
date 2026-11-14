# HT Inventory pilot

Native Android inventory capture with a FastAPI backend, photo evidence, reviewed shelf counts, optional vision analysis, and optional Google Sheets sync.

## Data and credentials

This public repository contains application code only. The catalogue files are intentionally empty. Load your own catalogue through the authenticated `/api/items/{sku}` endpoint after deployment. The Android app downloads the catalogue when connected to the backend. Credentials belong in the server environment, never in source code.

The separately supplied internal pilot APK includes the business catalogue. An APK rebuilt from this public source starts with an empty catalogue until its first successful sync.

## Deploy on Render

Use `render.yaml` to create a Python web service with a 1 GB persistent disk. The disk is required to retain SQLite counts and evidence photos across restarts and deployments. Use one worker and one service instance.

Build: `pip install -r backend/requirements.txt`

Start: `cd backend && uvicorn app:app --host 0.0.0.0 --port $PORT --workers 1`

Set `DATA_DIR=/var/data` and a long random `DEVICE_TOKEN`. Configure `OPENAI_API_KEY`, `GOOGLE_SERVICE_ACCOUNT_JSON`, `GOOGLE_SHEET_ID` and `PUBLIC_BASE_URL` to enable the corresponding connections. The service remains usable for reviewed counts without the optional AI and Sheets connections.

`GET /health` is public. All inventory API routes require `Authorization: Bearer DEVICE_TOKEN`. The browser dashboard requests the same device code; employees do not need separate accounts.

## Android

Requires JDK 17 and Android SDK 35. In `android`, run `./gradlew assembleDebug testDebugUnitTest lintDebug`.

Install the APK on Android 8 or newer. Enter the HTTPS backend URL and shared device code in Setup. Start a shelf session, capture clear photos, review final quantities, and finish the shelf. Local CSV export works before server setup. Android's file picker can save the CSV to a supported OneDrive location; direct Microsoft workbook sync is not implemented.

## Verification

In `backend`, install `requirements.txt` and run `python -m unittest discover -s tests -v`.

The pilot preserves evidence and uses one reviewed final quantity per SKU and shelf. Repeat uploads do not add counts; unvisited items are not set to zero. Cloud AI, Google Sheets permissions and warehouse camera accuracy require live configuration and device testing. See `docs/API.md` for API details.

## Live video and photos in the browser (0.2.0)

Open `/capture` on the deployed HTTPS service and enter the existing device code. Live mode opens the rear camera and analyzes one changed frame at a time, with an eight-second gap after each result. It stops on errors, when the page is hidden, and when the operator starts review. This is sampled-frame AI over a continuous camera preview, not video recording or object tracking. Camera access and physical-device performance still require on-device verification.

Live previews do not create evidence or change stock. Choose **Review this view** to save the selected frame and review quantities. **Take / choose photo** uploads and analyzes a still photo immediately. Previously uploaded Android photos appear in **Uploaded photos** with retryable analysis errors. Empty AI results explicitly explain why no packages were counted.

Confirmed counts replace the same SKU at the same shelf; they are never summed across frames. A final confirmation is required before committing stock and syncing Sheets. Pending commit retries reuse the same ID and body. Draft final counts persist in this browser; preview images do not. Voice corrections depend on browser speech-recognition support and remain editable before confirmation.

The APK remains the existing 0.1.0 pilot; this release adds browser capture without requiring an APK reinstall.

Run backend tests with `cd backend && python -m unittest discover -s tests -v`. Run browser-logic tests with `cd backend && npm install && npm test` (Node 22 or newer). The JavaScript tests use simulated camera and network responses; they do not prove physical camera or live cloud accuracy.
