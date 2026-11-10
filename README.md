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
