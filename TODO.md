# TODO

## Bugs (first)
- [x] Animated sticker save seemed stuck on "Encoding 68 frames (quality 75)" (S25 Ultra). Cause: libwebp encoder ran with minimize_size + allow_mixed (very slow) and up to 9 retries. Fixed: lossy-only, no exhaustive search, start at <=60 frames, quality 70/45/25. Needs confirming on the phone.
- [x] Progress dialogs now have a Cancel button (encode, crop, remove BG...). Native work already running finishes first, then its result is discarded.

Waiting on more phone testing (S25 Ultra, WhatsApp + WhatsApp Business).

To check on the phone:
- [ ] Add to WhatsApp / WhatsApp Business end to end (never run on a device)
- [ ] Remove BG: first-use model download, then static and animated
- [ ] Animated pack accepted by WhatsApp (500 KB / 10 s / 512x512)

## UX
- [x] New pack: the floating button is gone; "New pack" is now in the top bar (and a button in the empty state). The dialog has a big filled "Create pack" button and an inline error if the name is empty.
- [x] Emoji prompt on save removed: new stickers get the default emoji (black circle). Change it per sticker from the pack screen. Editing a sticker keeps its emoji.

## Features
- [x] Import `.wastickers` packs (zip of webp stickers + title/author/tray): main screen menu > Import pack, or open the file from a file manager / share it to the app. Stickers are copied as-is (no re-encode) and get the default emoji; invalid ones are skipped and listed.
- [x] Export a pack as `.wastickers`: pack screen menu > Export. Saved wherever you choose (round-trip with import checked: identical stickers).
- [ ] Pinch-zoom in the eraser / restore brush
- [ ] Video input (MP4 to animated sticker, with trim)
- [ ] Bundled offline background removal as a fallback (people only)
- [ ] Batch import (pick several images at once)
- [x] Signed release build: `./gradlew.bat assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`, copied to `dist/StickerMaker-release.apk`. Key: `release.keystore` + `keystore.properties` in the project root (git-ignored). BACK THESE UP: without them future updates can't install over this version.

## Parity with the desktop app (`whatsappstickermaker-desktop`, see docs/SPEC.md)
Shared: limits, pack format, `.wastickers` import/export, editing tools, encoding strategy, UX conventions.
Only on desktop for now: multi-file add (batch), eraser zoom/pan (= the two Features above), drag stickers to reorder, faster encodes.
- [ ] Reorder stickers in a pack (desktop has it: WhatsApp shows them in this order)
Only on Android: Add to WhatsApp / WhatsApp Business, share-sheet import.
Neither has video input yet.
Done on both: static images in an animated pack are saved as 2-frame animations (docs/SPEC.md).
When a rule in docs/SPEC.md changes, change it in both repos.
