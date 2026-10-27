package za.co.madbot.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StickerItem(val file: String, val emojis: List<String>) {
    val thumbFile get() = file.removeSuffix(".webp") + ".png"
}

data class StickerPack(
    val id: String,
    var name: String,
    var publisher: String,
    val animated: Boolean,
    var version: Int = 1,
    var trayFile: String = "tray.png",
    val stickers: MutableList<StickerItem> = mutableListOf(),
    /** True once the user picked the pack icon themselves: adding stickers then no longer replaces it. */
    var trayCustom: Boolean = false,
) {
    val isReady get() = stickers.size >= PackStore.MIN_STICKERS
}

/** Packs live in filesDir/packs/<id>/{pack.json, tray.png, s*.webp, s*.png (thumbnails)}. */
class PackStore(private val ctx: Context) {
    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
        val ID_RE = Regex("[A-Za-z0-9_.-]+")
    }

    private val root = File(ctx.filesDir, "packs").apply { mkdirs() }

    fun dir(id: String) = File(root, id)

    fun list(): List<StickerPack> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
            .mapNotNull { read(it.name) }.sortedBy { it.id }

    fun get(id: String): StickerPack? = if (ID_RE.matches(id)) read(id) else null

    private fun read(id: String): StickerPack? {
        val f = File(dir(id), "pack.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val ss = o.getJSONArray("stickers")
            val items = (0 until ss.length()).map {
                val s = ss.getJSONObject(it)
                val em = s.getJSONArray("emojis")
                StickerItem(s.getString("file"), (0 until em.length()).map { i -> em.getString(i) })
            }
            StickerPack(
                id, o.getString("name"), o.getString("publisher"), o.getBoolean("animated"),
                o.getInt("version"), o.getString("tray"), items.toMutableList(), trayCustom = o.optBoolean("trayCustom", false),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Persists the pack and bumps its image data version so WhatsApp refreshes its cache. */
    fun save(p: StickerPack) {
        p.version++
        val o = JSONObject()
            .put("name", p.name).put("publisher", p.publisher).put("animated", p.animated)
            .put("version", p.version).put("tray", p.trayFile).put("trayCustom", p.trayCustom)
            .put("stickers", JSONArray().also { arr ->
                p.stickers.forEach { s ->
                    arr.put(JSONObject().put("file", s.file).put("emojis", JSONArray(s.emojis)))
                }
            })
        val d = dir(p.id).apply { mkdirs() }
        val tmp = File(d, "pack.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(d, "pack.json"))
        ctx.contentResolver.notifyChange(StickerContentProvider.baseUri(), null)
    }

    /** New packs are animated: a static image is saved as a 2-frame animation (docs/SPEC.md). */
    fun create(name: String, publisher: String, animated: Boolean = true): StickerPack {
        val p = StickerPack("pack_" + System.currentTimeMillis(), name, publisher, animated)
        save(p)
        return p
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        ctx.contentResolver.notifyChange(StickerContentProvider.baseUri(), null)
    }

    /** Adds a sticker, or replaces [replaceFile] in place. */
    fun addSticker(p: StickerPack, webp: ByteArray, preview: Bitmap, emojis: List<String>, replaceFile: String?) {
        val d = dir(p.id)
        val item = StickerItem("s${System.currentTimeMillis()}.webp", emojis)
        File(d, item.file).writeBytes(webp)
        File(d, item.thumbFile).outputStream().use {
            Bitmap.createScaledBitmap(preview, 256, 256, true).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val idx = p.stickers.indexOfFirst { it.file == replaceFile }
        if (idx >= 0) {
            val old = p.stickers[idx]
            File(d, old.file).delete()
            File(d, old.thumbFile).delete()
            p.stickers[idx] = item
        } else {
            p.stickers.add(item)
        }
        if (!p.trayCustom) writeTray(p, p.stickers.first())
        save(p)
    }

    /** Adds an already-valid WebP as-is (no re-encode). Call [finishImport] when done. */
    fun addImported(p: StickerPack, webp: ByteArray, preview: Bitmap, emojis: List<String>, index: Int) {
        val d = dir(p.id)
        val item = StickerItem("s${System.currentTimeMillis()}_$index.webp", emojis)
        File(d, item.file).writeBytes(webp)
        File(d, item.thumbFile).outputStream().use {
            Bitmap.createScaledBitmap(preview, 256, 256, true).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        p.stickers.add(item)
    }

    /** Uses [trayPng] as the tray icon if given, otherwise builds one from the first sticker. */
    fun finishImport(p: StickerPack, trayPng: ByteArray?) {
        if (trayPng != null) {
            File(dir(p.id), p.trayFile).writeBytes(trayPng)
            p.trayCustom = true
        } else if (!p.trayCustom) {
            p.stickers.firstOrNull()?.let { writeTray(p, it) }
        }
        save(p)
    }

    fun removeSticker(p: StickerPack, s: StickerItem) {
        val d = dir(p.id)
        File(d, s.file).delete()
        File(d, s.thumbFile).delete()
        p.stickers.remove(s)
        if (!p.trayCustom) p.stickers.firstOrNull()?.let { writeTray(p, it) }
        save(p)
    }

    fun setTray(p: StickerPack, s: StickerItem) {
        writeTray(p, s)
        p.trayCustom = true // an explicit choice: adding stickers later must not replace it
        save(p)
    }

    /** Pack icon from any image (first frame): fitted into 96x96 with transparent padding. */
    fun setTrayImage(p: StickerPack, src: Bitmap) {
        val size = 96
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = minOf(size / src.width.toFloat(), size / src.height.toFloat())
        val w = src.width * s
        val h = src.height * s
        android.graphics.Canvas(out).drawBitmap(
            src, null, android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG),
        )
        File(dir(p.id), p.trayFile).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        p.trayCustom = true
        save(p)
    }

    fun setTrayFromUri(p: StickerPack, uri: android.net.Uri) = setTrayImage(p, MediaDecoder.load(ctx, uri).bitmaps[0])

    fun update(p: StickerPack, name: String, publisher: String) {
        if (name.isNotBlank()) p.name = name.trim().take(128)
        if (publisher.isNotBlank()) p.publisher = publisher.trim().take(128)
        save(p)
    }

    fun updateEmojis(p: StickerPack, s: StickerItem, emojis: List<String>) {
        val i = p.stickers.indexOf(s)
        if (i >= 0) p.stickers[i] = s.copy(emojis = emojis)
        save(p)
    }

    /** WhatsApp wants a 96x96 PNG (or WebP) tray icon under 50 KB. */
    private fun writeTray(p: StickerPack, s: StickerItem) {
        val d = dir(p.id)
        val thumb = BitmapFactory.decodeFile(File(d, s.thumbFile).path) ?: return
        File(d, p.trayFile).outputStream().use {
            Bitmap.createScaledBitmap(thumb, 96, 96, true).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
