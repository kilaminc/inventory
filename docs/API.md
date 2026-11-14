# Pilot API

All `/api/*` routes require `Authorization: Bearer DEVICE_TOKEN`. No employee account is required.

| Method / path | Purpose |
|---|---|
| GET `/health` | Public service health, no inventory data |
| GET `/api/status` | AI/Sheets configuration flags and pending sheet commit count |
| GET `/api/items` | Full catalogue |
| PUT `/api/items/{sku}` | Teach/update a catalogue item; barcodes must be unique |
| POST `/api/captures` | Upload evidence and metadata using a stable capture UUID |
| POST `/api/captures/{id}/analyze` | Retrieve/cache reviewed-later AI suggestions; returns explicit pending state without an API key |
| GET `/api/evidence/{id}` | Authenticated JPEG evidence |
| POST `/api/commits` | Save a reviewed shelf count atomically using a stable commit UUID |
| GET `/api/stock` | Latest stock per location and SKU |
| GET `/api/export.csv` | Excel-compatible current stock export |
| POST `/api/sheets/sync` | Rebuild only the four owned HT tabs |

## Capture

Fields: `id`, `session_id` (UUIDs), `location`, `operator`, `captured_at` (ISO timestamp), `image_base64`, `ocr`, `barcodes`.

Uploads are idempotent by capture ID. Reusing an ID for different content returns 409. JPEG evidence is normalized and limited to 2048 pixels per side for storage and cloud analysis. The phone retains the original local capture.

## Commit

Fields: `id`, `session_id` (UUIDs), `location`, `operator`, `counted_at` (timestamp with timezone), `review_confirmed: true`, `lines`.

Each line has `sku`, `quantity`, `unit`, `source`, `evidence_ids` and `note`. `source` is one of `visual_confirmed`, `operator_reported`, `scale_measured`. One final line per SKU is allowed. The entire commit fails if any line is invalid. Evidence must already be uploaded and belong to the same session and shelf. All quantities must be finite and non-negative.

Retry the same commit ID with identical content after uncertain network outcomes. A changed commit needs a new ID. A genuinely newer recount replaces stock; a stale or simultaneous count returns 409 and must be reviewed. No quantity is added merely because another camera frame arrived.

## Deployment limits

This is a single-process SQLite/persistent-disk pilot. For multiple concurrent sites/devices at scale, migrate to Postgres, object storage and an explicit conflict-resolution UI. The shared device token is sufficient for a small controlled pilot; it does not provide individual access revocation or staff identity verification.


## Browser capture (0.2.0)
- `GET /capture` and `GET /capture.js`: public UI shell; no business data or credentials embedded.
- `GET /api/captures?limit=30`: authenticated recent photo metadata, analyses, and sanitized analysis errors (limit 1–100).
- `POST /api/vision/preview`: authenticated `{image_base64}` request. Returns a proposed count from a sampled live frame without saving evidence or changing stock.
- `POST /api/captures/{id}/analyze`: retains existing behavior, with actionable errors for authentication, quota, model access, timeout and malformed results. Failures remain retryable.
- `GET /api/status`: adds uploaded/analyzed photo totals and the latest unresolved sanitized analysis error. Configured flags indicate key presence, not a successful external API call.

The bounded 120-second in-memory analysis cache stores results only. Selecting the same live frame as saved photo evidence can reuse its result. The cache is scoped by image, catalogue identity fields, OCR/barcodes and model. It does not bypass count review.
