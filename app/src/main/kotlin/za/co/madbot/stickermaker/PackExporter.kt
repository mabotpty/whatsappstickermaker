package za.co.madbot.stickermaker

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a pack as a `.wastickers` zip (title.txt, author.txt, tray.png, sticker webp files). */
object PackExporter {
    fun suggestedName(pack: StickerPack): String =
        pack.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "stickers" } + ".wastickers"

    fun export(ctx: Context, store: PackStore, pack: StickerPack, uri: Uri) {
        val dir = store.dir(pack.id)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write to the chosen location")
        out.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put("title.txt", (pack.name + "\n").toByteArray(Charsets.UTF_8))
                put("author.txt", (pack.publisher + "\n").toByteArray(Charsets.UTF_8))
                File(dir, pack.trayFile).takeIf { it.exists() }?.let { put("tray.png", it.readBytes()) }
                pack.stickers.forEach { put(it.file, File(dir, it.file).readBytes()) }
            }
        }
    }
}
