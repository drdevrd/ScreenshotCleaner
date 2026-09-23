# Screenshot Cleaner (com.drdevrd.screenshotcleaner)

On-device screenshot triage: labels, groups similar shots, and deletes in bulk
after you review and select. No network permission — OCR and image hashing
both run locally via ML Kit's bundled (offline) text recognizer.

## What it does
1. **Scan** — finds screenshots via MediaStore (folder name or `Screenshot_*` filename pattern).
2. **Label** — runs on-device OCR per screenshot and tags it:
   - OTP / Code
   - Payment / Receipt
   - Chat / Message
   - Article / Long text
   - Other
3. **Group** — computes a perceptual hash (average hash) per image and clusters
   visually similar screenshots together within each label (e.g. 5 near-identical
   app screens end up in one group).
4. **Review & delete** — grid view, grouped with headers. Tap thumbnails or use
   "all" per group, or the global Select All. Delete Selected triggers Android's
   native delete-confirmation dialog (`MediaStore.createDeleteRequest`) — the OS
   asks you to confirm, so nothing is removed silently.

## How to build
1. Open this folder in Android Studio (Giraffe or newer).
2. Let Gradle sync — it will pull the AndroidX / Material / ML Kit dependencies
   from Google's and Maven Central's repos (needs internet *only* at build time,
   not at runtime).
3. Run on a device/emulator with API 26+.

## Notes / next steps
- Currently scoped to **screenshots only**, per your last request. The baby-video
  face-match module discussed earlier is a separate feature — say the word and
  I'll add it as its own screen/flow in this same app.
- Grouping threshold (`maxDistance = 8` in `Analyzer.kt`) controls how strict
  "similar" is — lower it for stricter matches, raise it to group more loosely.
- Labeling is heuristic (regex over OCR text), not perfect — it's meant to sort
  the pile faster, not to make irreversible decisions for you. Always review
  before deleting.
