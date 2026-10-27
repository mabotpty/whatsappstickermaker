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
| Pack icon | The 96x96 tray image. Chosen when creating a pack (optional) or later; without a choice it follows the first sticker. A chosen icon is never replaced by adding stickers (`trayCustom` in pack.json) |
| Pack type | All static or all animated. Never mixed (WhatsApp rejects the whole pack). A static image in an animated pack is stored as a 2-frame animation, see below |

## Pack data (identical JSON on both devices)
`packs/<id>/pack.json`:
`{ "name", "publisher", "animated", "version", "tray": "tray.png", "trayCustom": false, "stickers": [ { "file": "s123.webp", "emojis": ["⚫"] } ] }`
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

## Remove background sensitivity
After Remove BG a slider shows from -100 (keep less) to +100 (keep more); 0 is the model output. The model's mask is soft, so thin
text and fine edges can end up semi-transparent; "keep more" fixes that. The result is always rebuilt from the ORIGINAL pixels and the
model's alpha mask (colours never degrade), with the mask passed through this curve (a = mask alpha 0..255):
v > 0: alpha = 0 if a < 6, else min(255, a * (1 + v/100 * 4)).  v < 0: alpha = max(0, (a - t) * 255 / (255 - t)) with t = -v/100 * 200.
For plain backgrounds there is a second signal, because the model sometimes scores thin text as pure background (alpha ~0), which no
amount of gain can recover. Colour mask: take the median RGB of the outer 3 px ring as the background colour; if fewer than 85% of ring
pixels are within 24 (max channel difference) of it, the border is not flat and the colour mask is off (photos, busy backgrounds).
Otherwise cm = 0 when a pixel's max channel difference d <= 24, 255 when d >= 72, linear in between. For v > 0 the final alpha is
`max(curve(mask), cm * min(1, v/50))`, so "keep more" brings back text and fine detail that differs from the flat background.
The slider goes away with Done or as soon as another edit (crop, rotate, brush, undo) is made. Original transparency is kept.

## Static images in an animated pack
WhatsApp only checks that every sticker in an animated pack has more than one frame, so a static image is saved as two frames:
frame 1 is the sticker, frame 2 is frame 1 with 8 pixels near the top-left corner (x = 4, 7 ... 25, y = 4) nudged by one level
(transparent pixel: alpha 1; otherwise blue minus 1, or 1 if it was 0). Identical frames would be merged back into one by the encoder.
Frame durations 1000 ms + 1000 ms. The result is about 2 KB and looks static.
When loading, an animation of exactly 2 frames whose mean absolute channel difference (RGBA, on a 64x64 copy) is <= 4 is treated as a
static image, so these stickers (and ones made by other apps) can be edited with the static-only tools and are re-saved the same way.
Static packs still use the first frame of an animated source.

## Importing media
Static images are limited to 1536 px on the long side, animated to 512 px and 120 frames (extra frames are merged into the
previous one's delay). Delays <= 10 ms become 100 ms (browser convention). A static pack takes the first frame of an animated
source; an animated pack takes a static source and saves it as a 2-frame animation (see above).

## Encoding (same strategy)
Static: lossy WebP, quality 95 then 85 ... 25 (steps of 10) until <= 100 KB.
Animated: lossy only (no mixed, no exhaustive size search: both make libwebp very slow), at most 60 frames to start
(halve by merging delays), quality 70 / 45 / 25, then halve the frame count and repeat (at most 3 rounds, stop when under 8 frames).
Fail with a clear message if it still does not fit. The user can cancel at any time.

## UX conventions
All new packs are animated; there is no static/animated choice (static images become 2-frame animations). Old static packs and imported static
packs still work and are labelled "static". Name and author can be edited afterwards (Edit pack); the last author is the default for new packs.
New pack: a top-bar action plus a button in the empty state, never a floating button. The dialog has a large filled primary
"Create pack" button and an inline error when the name is empty. No prompts while saving a sticker; the emoji is changed
afterwards from the sticker's menu (Edit, Change emoji, Use as tray icon, Delete). Progress dialogs always have a Cancel button.
Desktop only: drag and drop (images onto the pack, a visible drop tile, drag stickers to reorder). Android has no reorder yet.
Adding to WhatsApp happens on the phone only: the desktop app exports `.wastickers`, the Android app imports it.
