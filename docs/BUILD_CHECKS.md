# Pilot verification

Android APK build, 5 quantity-parser tests, and Android lint passed. Backend has 12 automated tests for evidence, reviewed counts, retries, conflicts, vision-output sanitation and mocked Sheets behavior. Physical-device and live cloud verification remain necessary. Public-source tests use synthetic catalogue fixtures.


0.2.0 browser capture: 18 backend tests and 7 browser-logic tests pass. Checks cover preview isolation, authentication, selected-frame result reuse, sanitized billing errors and retry recovery, repeated-SKU replacement, zero counts, explicit confirmation, stable commit retries, unit conversion, and voice quantity parsing. Camera/network responses are simulated in the browser-logic tests. The uploaded production test photo was analyzed successfully and returned no countable inventory packages. No new Android APK was built. Physical-device live-camera behavior and deployment of 0.2.0 still require verification.


0.2.1 photo deletion: 24 backend tests and 14 simulated browser-logic tests pass. Added coverage checks authentication, file removal, idempotent deletion, blocked offline resurrection, preserved stock/commit retries, reference-photo cleanup, concurrent analysis completion, storage failure recovery, Sheets links/review cleanup, confirmation/cancellation, draft cleanup, sync failure, pending commits, rejected-count recovery, and stale history responses. Existing live/photo/voice tests also pass. Physical-phone verification is not covered by the simulated browser tests.


0.2.2 missing-item workflow: 29 backend tests and 22 simulated browser-logic tests pass. Coverage includes unknown-AI prompts, fuzzy name ranking, explicit match selection, unit preservation, stale-selection clearing, cancel/required-field behavior, create-only/idempotent catalogue writes, name/barcode duplicate conflicts, missing reference photos, concurrent-click/retry behavior, new-item Sheets snapshots, separate reviewed stock commits, and Sheets failure recovery. Existing live/photo/voice/deletion tests continue to pass. Physical-phone camera/microphone behavior is outside these tests.
