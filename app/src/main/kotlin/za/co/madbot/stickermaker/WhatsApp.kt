package za.co.madbot.stickermaker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object WhatsApp {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    /**
     * Experiment switch. false: keep the same identifier and always send the intent, hoping WhatsApp overwrites the pack
     * (image_data_version is bumped on every save). true: give each saved version a new identifier so it is added as a new pack.
     */
    const val VERSIONED_ID = false

    private fun installed(pm: PackageManager, pkg: String) =
        try {
            pm.getApplicationInfo(pkg, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun isWhitelisted(ctx: Context, pkg: String, id: String): Boolean {
        val auth = "$pkg.provider.sticker_whitelist_check"
        if (ctx.packageManager.resolveContentProvider(auth, 0) == null) return false
        val uri = Uri.Builder().scheme("content").authority(auth).appendPath("is_whitelisted")
            .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendQueryParameter("identifier", id).build()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use {
                it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow("result")) == 1
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the intent that enables [pack], or null after calling [why] with the reason. */
    fun buildIntent(ctx: Context, pack: StickerPack, why: (String) -> Unit): Intent? {
        val pm = ctx.packageManager
        val targets = listOf(CONSUMER, BUSINESS).filter { installed(pm, it) }
        if (targets.isEmpty()) {
            why("WhatsApp is not installed.")
            return null
        }
        val pending = if (VERSIONED_ID) targets.filter { !isWhitelisted(ctx, it, pack.waId) } else targets
        if (pending.isEmpty()) {
            why("This pack is already added to WhatsApp.")
            return null
        }
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")
            .putExtra("sticker_pack_id", pack.waId)
            .putExtra("sticker_pack_authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .putExtra("sticker_pack_name", pack.name)
        return if (pending.size == 1) intent.setPackage(pending[0])
        else Intent.createChooser(intent, "Add to WhatsApp")
    }
}
