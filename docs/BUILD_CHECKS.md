# Pilot verification

Android APK build, 5 quantity-parser tests, and Android lint passed. Backend has 12 automated tests for evidence, reviewed counts, retries, conflicts, vision-output sanitation and mocked Sheets behavior. Physical-device and live cloud verification remain necessary. Public-source tests use synthetic catalogue fixtures.


0.2.0 browser capture: 18 backend tests and 7 browser-logic tests pass. Checks cover preview isolation, authentication, selected-frame result reuse, sanitized billing errors and retry recovery, repeated-SKU replacement, zero counts, explicit confirmation, stable commit retries, unit conversion, and voice quantity parsing. Camera/network responses are simulated in the browser-logic tests. The uploaded production test photo was analyzed successfully and returned no countable inventory packages. No new Android APK was built. Physical-device live-camera behavior and deployment of 0.2.0 still require verification.
