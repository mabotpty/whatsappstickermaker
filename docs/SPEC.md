# Shared behaviour (Android app and desktop app)

Both apps follow this spec, so a pack made on either device looks and behaves the same. If you change a rule here,
change it in both repos (`whatsappstickermaker` for Android, `whatsappstickermaker-desktop` for the desktop app).
The desktop constants live in `src/shared/limits.js`; the Android ones are in `Composer.kt` / `Models.kt`.

## WhatsApp limits
| Thing | Rule |
|---|---|
| Sticker size | 512x512 WebP |
| Static sticker | <= 100 KB |
| Animated sticker | <= 500 KB, total duration <= 10 s (we cut at 9.5 s), every frame >= 8 ms (we clamp to >= 20 ms) |
| Tray icon | 96x96 PNG, <= 50 KB |
| Emoji | 1 to 3 per sticker. Default for new/imported stickers: black circle U+26AB |
| Pack size | 3 to 30 stickers (a pack with fewer than 3 is "not ready") |
| Pack type | All static or all animated. Never mixed (WhatsApp rejects the whole pack) |

## Pack data (identical JSON on both devices)
`packs/<id>/pack.json`:
`{ "name", "publisher", "animated", "version", "tray": "tray.png", "stickers": [ { "file": "s123.webp", "emojis": ["⚫"] } ] }`
plus `tray.png`, the sticker `.webp` files and a 256px `.png` thumbnail next to each (`s123.png`).
`version` goes up on every change (WhatsApp caches by it). Ids look like `pack_<millis>`.

## `.wastickers` exchange format
A zip with `title.txt`, `author.txt`, one 96x96 tray `.png` and the sticker `.webp` files (entry names may have a leading `/`;
readers use only the bare file name). No emoji data. Import rules: skip stickers that are not 512x512 WebP or are over the size
limit, keep the larger group when static and animated are mixed, cap at 30, give each sticker the default emoji.
Stickers are copied as-is, never re-encoded.

## Editing (same tools, same order of operations)
Tools: crop, rotate 90, flip, remove background, erase/restore brush (static only), text, white outline, undo (6 steps).
Rendering each frame: fit into the 512 canvas with 4 px padding (14 px with outline), draw the outline first
(white silhouette at 24 angles, radii 10 px and 5 px), then the image, then text.
Text: bold, size = fraction of 512, centred on (cx, cy) fractions, wrapped at 90% of the canvas, stroke 16% of the size in black or white
(whichever contrasts with the fill), then the fill.

## Importing media
Static images are limited to 1536 px on the long side, animated to 512 px and 120 frames (extra frames are merged into the
previous one's delay). Delays <= 10 ms become 100 ms (browser convention). A static pack takes the first frame of an animated
source; an animated pack refuses a static source.

## Encoding (same strategy)
Static: lossy WebP, quality 95 then 85 ... 25 (steps of 10) until <= 100 KB.
Animated: lossy only (no mixed, no exhaustive size search: both make libwebp very slow), at most 60 frames to start
(halve by merging delays), quality 70 / 45 / 25, then halve the frame count and repeat (at most 3 rounds, stop when under 8 frames).
Fail with a clear message if it still does not fit. The user can cancel at any time.

## UX conventions
New pack: a top-bar action plus a button in the empty state, never a floating button. The dialog has a large filled primary
"Create pack" button and an inline error when the name is empty. No prompts while saving a sticker; the emoji is changed
afterwards from the sticker's menu (Edit, Change emoji, Use as tray icon, Delete). Progress dialogs always have a Cancel button.
Adding to WhatsApp happens on the phone only: the desktop app exports `.wastickers`, the Android app imports it.
