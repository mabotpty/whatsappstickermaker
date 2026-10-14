# TODO

## Bugs (first)
- [x] Animated sticker save seemed stuck on "Encoding 68 frames (quality 75)" (S25 Ultra). Cause: libwebp encoder ran with minimize_size + allow_mixed (very slow) and up to 9 retries. Fixed: lossy-only, no exhaustive search, start at <=60 frames, quality 70/45/25. Needs confirming on the phone.
- [ ] No way to cancel an encode; the progress dialog is not cancellable

Waiting on more phone testing (S25 Ultra, WhatsApp + WhatsApp Business).

To check on the phone:
- [ ] Add to WhatsApp / WhatsApp Business end to end (never run on a device)
- [ ] Remove BG: first-use model download, then static and animated
- [ ] Animated pack accepted by WhatsApp (500 KB / 10 s / 512x512)

## UX
- [ ] New pack dialog: make it clearer. The "Create" button text is hard to see, and the "New pack" button is too prominent: it gets tapped by mistake when the user means to confirm the dialog. Make Create a clearly styled primary button, tone down the New pack FAB (or move/shrink it), and consider a visual cue for the dialog's primary action.

## Features
- [ ] Pinch-zoom in the eraser / restore brush
- [ ] Video input (MP4 to animated sticker, with trim)
- [ ] Bundled offline background removal as a fallback (people only)
- [ ] Batch import (pick several images at once)
- [ ] Signed release build
