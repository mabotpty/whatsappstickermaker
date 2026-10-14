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
                o.getInt("version"), o.getString("tray"), items.toMutableList(),
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
            .put("version", p.version).put("tray", p.trayFile)
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

    fun create(name: String, publisher: String, animated: Boolean): StickerPack {
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
        writeTray(p, p.stickers.first())
        save(p)
    }

    fun removeSticker(p: StickerPack, s: StickerItem) {
        val d = dir(p.id)
        File(d, s.file).delete()
        File(d, s.thumbFile).delete()
        p.stickers.remove(s)
        p.stickers.firstOrNull()?.let { writeTray(p, it) }
        save(p)
    }

    fun setTray(p: StickerPack, s: StickerItem) {
        writeTray(p, s)
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
