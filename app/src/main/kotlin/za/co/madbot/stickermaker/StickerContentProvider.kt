package za.co.madbot.stickermaker

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves sticker packs to WhatsApp. The column names and URI layout are fixed by WhatsApp's
 * contract and must not be changed.
 */
class StickerContentProvider : ContentProvider() {
    companion object {
        fun baseUri(): Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
            .authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY).build()
    }

    private val store by lazy { PackStore(context!!) }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
        val seg = uri.pathSegments
        return when (seg.firstOrNull()) {
            "metadata" ->
                if (seg.size == 1) packInfo(uri, store.list().filter { it.isReady })
                else packInfo(uri, listOfNotNull(store.get(seg[1])))
            "stickers" -> stickers(uri, seg.getOrNull(1))
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun packInfo(uri: Uri, packs: List<StickerPack>): Cursor {
        val c = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version",
                "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            )
        )
        packs.forEach {
            c.addRow(
                arrayOf<Any>(
                    it.id, it.name, it.publisher, it.trayFile, "", "", "", "", "", "",
                    it.version.toString(), 0, if (it.animated) 1 else 0,
                )
            )
        }
        c.setNotificationUri(context!!.contentResolver, uri)
        return c
    }

    private fun stickers(uri: Uri, id: String?): Cursor {
        val c = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        id?.let { store.get(it) }?.stickers?.forEach {
            c.addRow(arrayOf<Any>(it.file, it.emojis.joinToString(","), ""))
        }
        c.setNotificationUri(context!!.contentResolver, uri)
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val seg = uri.pathSegments
        if (seg.size != 3 || seg[0] != "stickers_asset") return null
        val id = seg[1]
        val name = seg[2]
        if (!PackStore.ID_RE.matches(name)) return null
        val pack = store.get(id) ?: return null
        if (name != pack.trayFile && pack.stickers.none { it.file == name }) return null
        val f = File(store.dir(id), name)
        if (!f.exists()) return null
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    override fun getType(uri: Uri): String {
        val a = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        val seg = uri.pathSegments
        return when (seg.firstOrNull()) {
            "metadata" ->
                if (seg.size == 1) "vnd.android.cursor.dir/vnd.$a.metadata"
                else "vnd.android.cursor.item/vnd.$a.metadata"
            "stickers" -> "vnd.android.cursor.dir/vnd.$a.stickers"
            "stickers_asset" -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int =
        throw UnsupportedOperationException()
}
