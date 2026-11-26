package za.co.madbot.stickermaker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Adds several picked images to a pack in one go, without the editor: each image is fitted to the 512x512 canvas
 * (same composition as the editor with no edits), encoded for the pack type and saved with the default emoji.
 * Edit any of them afterwards from the pack screen. Decision Q41 (Darryl): add automatically, edit later.
 */
object BatchAdd {
    class Result(val added: Int, val skippedFull: Int, val failed: List<String>, val cancelled: Boolean) {
        fun summary(): String = buildString {
            append(if (added == 1) "Added 1 sticker." else "Added $added stickers.")
            if (skippedFull > 0) append("\n$skippedFull not added: the pack holds at most ${PackStore.MAX_STICKERS} stickers.")
            if (cancelled) append("\nCancelled before the rest were added.")
            if (failed.isNotEmpty()) {
                append("\n\nCould not add:")
                failed.forEach { append("\n- $it") }
            }
            if (added > 0) append("\n\nTap a sticker to edit it or change its emoji.")
        }
    }

    suspend fun addAll(ctx: Context, store: PackStore, pack: StickerPack, uris: List<Uri>, progress: (String) -> Unit): Result {
        val room = (PackStore.MAX_STICKERS - pack.stickers.size).coerceAtLeast(0)
        val todo = uris.take(room)
        val skipped = uris.size - todo.size
        var added = 0
        val failed = ArrayList<String>()
        var cancelled = false
        try {
            todo.forEachIndexed { i, uri ->
                progress("Adding ${i + 1} of ${todo.size}...")
                try {
                    val (bytes, preview) = withContext(Dispatchers.Default) { encode(ctx, pack, uri) { m -> progress("${i + 1} of ${todo.size}: $m") } }
                    withContext(Dispatchers.IO) { store.addSticker(pack, bytes, preview, listOf(DEFAULT_EMOJI), null) }
                    added++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed.add("${uri.lastPathSegment ?: "image ${i + 1}"}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
        }
        return Result(added, skipped, failed, cancelled)
    }

    private suspend fun encode(ctx: Context, pack: StickerPack, uri: Uri, progress: (String) -> Unit): Pair<ByteArray, android.graphics.Bitmap> {
        val job = coroutineContext[Job]
        val f = MediaDecoder.load(ctx, uri)
        var fr = f.bitmaps
        var dl = f.delays
        if (!pack.animated) { fr = fr.take(1); dl = dl.take(1) } // a static pack takes the first frame
        var comp = fr.map { Composer.compose(it, false, emptyList()) }
        if (pack.animated && comp.size == 1) {
            comp = listOf(comp[0], FakeAnimation.nudged(comp[0]))
            dl = listOf(FakeAnimation.FRAME_DELAY_MS, FakeAnimation.FRAME_DELAY_MS)
        }
        val bytes = if (pack.animated) StickerEncoder.encodeAnimated(comp, dl, { job?.isCancelled == true }, progress)
        else StickerEncoder.encodeStatic(comp[0])
        return bytes to comp[0]
    }
}
