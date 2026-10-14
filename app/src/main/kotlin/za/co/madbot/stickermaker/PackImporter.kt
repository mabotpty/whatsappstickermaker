package za.co.madbot.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

class ImportResult(val pack: StickerPack, val skipped: List<String>)

/**
 * Imports `.wastickers` files: a zip holding title.txt, author.txt, a 96x96 tray png and the sticker webp files.
 * The format carries no emoji, so imported stickers get [DEFAULT_EMOJI].
 * Entry names are reduced to their file name and nothing is extracted by path, so a hostile archive cannot write elsewhere.
 */
object PackImporter {
    private const val MAX_ENTRIES = 300
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 40 * 1024 * 1024

    fun import(ctx: Context, uri: Uri, store: PackStore): ImportResult {
        var title = ""
        var author = ""
        var tray: ByteArray? = null
        val webps = ArrayList<Pair<String, ByteArray>>()

        // Android 14+ rejects zip entry names like "/1234.webp", which .wastickers files use. Names are
        // reduced to a bare file name below and nothing is written by path, so the check is not needed here.
        if (android.os.Build.VERSION.SDK_INT >= 34) dalvik.system.ZipPathValidator.clearCallback()

        val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
        input.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entries = 0
                var total = 0L
                var e = zip.nextEntry
                while (e != null) {
                    if (++entries > MAX_ENTRIES) error("This archive has too many files")
                    if (!e.isDirectory) {
                        val name = e.name.substringAfterLast('/').substringAfterLast('\\').lowercase()
                        val data = readLimited(zip)
                        total += data.size
                        if (total > MAX_TOTAL_BYTES) error("This archive is too large")
                        when {
                            name == "title.txt" -> title = String(data, Charsets.UTF_8).trim()
                            name == "author.txt" -> author = String(data, Charsets.UTF_8).trim()
                            name.endsWith(".webp") -> webps.add(name to data)
                            name.endsWith(".png") && tray == null -> tray = data
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        if (webps.isEmpty()) error("No stickers found. Is this a .wastickers file?")
        webps.sortBy { it.first }

        val skipped = ArrayList<String>()
        val valid = webps.filter { (name, data) ->
            val problem = problemWith(data)
            if (problem != null) skipped.add("$name: $problem")
            problem == null
        }
        if (valid.isEmpty()) error("None of the stickers are valid WhatsApp stickers")

        // A pack is all-static or all-animated; keep the larger group.
        val animatedOnes = valid.filter { isAnimated(it.second) }
        val animated = animatedOnes.size * 2 >= valid.size
        val keep = (if (animated) animatedOnes else valid.filter { !isAnimated(it.second) })
        valid.filter { it !in keep }.forEach { skipped.add("${it.first}: ${if (animated) "static sticker in an animated pack" else "animated sticker in a static pack"}") }
        val chosen = keep.take(PackStore.MAX_STICKERS)
        keep.drop(PackStore.MAX_STICKERS).forEach { skipped.add("${it.first}: pack is limited to ${PackStore.MAX_STICKERS} stickers") }

        val pack = store.create(title.ifBlank { "Imported pack" }.take(128), author.ifBlank { "Me" }.take(128), animated)
        try {
            chosen.forEachIndexed { i, (_, data) ->
                store.addImported(pack, data, firstFrame(data), listOf(DEFAULT_EMOJI), i)
            }
            store.finishImport(pack, tray?.takeIf { isValidTray(it) })
        } catch (e: Exception) {
            store.delete(pack.id) // do not leave a half-imported pack behind
            throw e
        }
        return ImportResult(pack, skipped)
    }

    private fun readLimited(zip: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_ENTRY_BYTES) error("A file inside the archive is too large")
        }
        return out.toByteArray()
    }

    private fun tag(b: ByteArray, o: Int, s: String) = b.size >= o + s.length && s.indices.all { b[o + it] == s[it].code.toByte() }

    private fun isAnimated(b: ByteArray) = b.size > 20 && tag(b, 12, "VP8X") && (b[20].toInt() and 0x02) != 0

    /** Returns why a sticker cannot be used, or null if it meets WhatsApp's limits. */
    private fun problemWith(b: ByteArray): String? {
        if (!(tag(b, 0, "RIFF") && tag(b, 8, "WEBP"))) return "not a WebP image"
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth != Composer.SIZE || o.outHeight != Composer.SIZE) return "must be 512x512 (is ${o.outWidth}x${o.outHeight})"
        val limit = if (isAnimated(b)) StickerEncoder.ANIMATED_MAX else StickerEncoder.STATIC_MAX
        if (b.size > limit) return "${b.size / 1024} KB is over the ${limit / 1024} KB limit"
        return null
    }

    private fun isValidTray(b: ByteArray): Boolean {
        if (b.size > 50 * 1024) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth == 96 && o.outHeight == 96
    }

    private fun firstFrame(b: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
}
