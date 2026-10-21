# SpendSnap

Personal, offline expense tracker for a Pixel 9 on Android 17. It captures the amount and
description of Google Pay / PhonePe payments. No LLM, no cloud, no internet permission.

## How payments get in

| Source | How | Counted automatically? |
|---|---|---|
| Accessibility (screen text) | Reads visible text inside GPay/PhonePe only | Only if a success screen follows that app's amount-entry screen within 10 min; otherwise sent to Review |
| Notification listener | Reads GPay/PhonePe notifications only | Yes for success notifications; also upgrades a matching screen capture to "corroborated" |
| Screenshot share (fallback) | Share a screenshot to SpendSnap, or "Import screenshot", then on-device ML Kit OCR | After you confirm on the review screen |
| Manual | Add manually | Yes |

Pending, unverified, conflicting and possibly-duplicate entries are **not** counted in totals until reviewed.
MediaProjection is deliberately not used: the share-screenshot flow needs no extra permission or
foreground service and is always user-initiated.

## Build & install

Requirements: Android SDK with platform 37, JDK 17+ (Android Studio's bundled JBR works).

```sh
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and Run.

### First-run setup on the phone
1. If Android blocks the Accessibility / Notification access switch ("Restricted setting"), allow it:
   Settings → Apps → SpendSnap → ⋮ → **Allow restricted settings**. On the Pixel 9 / Android 17 test device
   that menu was not shown, so use adb instead:
   `adb shell appops set com.spendsnap ACCESS_RESTRICTED_SETTINGS allow`
2. In SpendSnap: Settings → Permissions & detection setup → enable **Accessibility** and **Notification access**.

## Phase 0: verify what the apps actually expose (do this first)

Detection rules were written from synthetic fixtures. Real GPay/PhonePe text must be checked on this device:

1. SpendSnap → Settings → turn on **Diagnostic screen dump**.
2. On the laptop: `adb logcat -c && adb logcat -s SpendSnapDump`
3. Make a ₹1 payment in Google Pay, then in PhonePe. Also open each app's history once.
4. Save the log, **redact names, UPI IDs, phone numbers and reference numbers**, and use it to:
   - fill in the table below,
   - add real fixtures to `app/src/test/resources/fixtures/` and adjust rules in `parsing/`.
5. Turn the diagnostic dump off.

PIN/OTP screens are dropped before the dump, so they never reach logcat.

### What is accessible on Android 17 (fill in from Phase 0)

| Screen | Google Pay | PhonePe |
|---|---|---|
| Amount-entry screen text (amount, note) | ? | ? |
| Success screen: amount | ? | ? |
| Success screen: note / payee | ? | ? |
| Success screen: UPI ref / transaction ID | ? | ? |
| Failure / pending wording | ? | ? |
| Payment notification text | ? | ? |
| Screenshot allowed on success screen (not FLAG_SECURE) | ? | ? |

Known platform limits: apps can hide views from non-accessibility-tool services
(`accessibilityDataSensitive`), Flutter screens may expose partial text, secure screens show
black in screenshots, and the UPI PIN pad (NPCI library) is always secure. Where text is not
available, use screenshot import or manual entry.

## Code map

```
domain/     Models, Transaction (Room entity), Inr (paise math), Totals, DuplicateDetector, CsvExporter
parsing/    AmountParser, DescriptionParser, StatusClassifier, ReferenceParser, SensitiveContent, PaymentExtractor
data/       Room database/DAO, TransactionRepository (ingest = duplicate-aware insert)
detection/  PaymentAccessibilityService, PaymentNotificationListener, DiagnosticLog
ocr/        OcrProcessor (ML Kit bundled Latin model)
ui/         Compose screens: dashboard, history, review, editor, setup, settings, privacy
```
