package za.co.madbot.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.showError(title: String, message: String) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
}

/** Splits user input into at most three emoji (grapheme clusters). Falls back to a smiley. */
fun parseEmojis(s: String): List<String> {
    val bi = android.icu.text.BreakIterator.getCharacterInstance()
    bi.setText(s.trim())
    val out = ArrayList<String>()
    var start = bi.first()
    var end = bi.next()
    while (end != android.icu.text.BreakIterator.DONE) {
        val g = s.trim().substring(start, end)
        if (g.isNotBlank() && !g[0].isLetterOrDigit()) out.add(g)
        start = end
        end = bi.next()
    }
    return out.take(3).ifEmpty { listOf("😀") }
}

fun Context.askEmojis(current: List<String>, onDone: (List<String>) -> Unit) {
    val input = EditText(this).apply {
        setText(current.joinToString(""))
        hint = "1 to 3 emoji"
        textSize = 24f
        setSelection(text.length)
    }
    val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
    AlertDialog.Builder(this).setTitle("Emoji for this sticker").setView(box)
        .setPositiveButton("OK") { _, _ -> onDone(parseEmojis(input.text.toString())) }
        .setNegativeButton("Cancel", null).show()
}

/** Runs [block] behind a non-cancellable progress dialog; shows an error dialog and returns null on failure. */
fun AppCompatActivity.busy(initial: String, block: suspend ((String) -> Unit) -> Unit, onDone: (Boolean) -> Unit = {}) {
    val label = TextView(this).apply { text = initial; textSize = 16f; setPadding(dp(16), 0, 0, 0) }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        addView(ProgressBar(context).apply { isIndeterminate = true })
        addView(label)
    }
    val dlg = AlertDialog.Builder(this).setView(row).setCancelable(false).create()
    dlg.show()
    lifecycleScope.launch {
        var ok = true
        try {
            block { msg -> runOnUiThread { label.text = msg } }
        } catch (e: Throwable) {
            ok = false
            showError("Something went wrong", e.message ?: e.toString())
        } finally {
            dlg.dismiss()
        }
        onDone(ok)
    }
}

object Thumbs {
    private val cache = LruCache<String, Bitmap>(64)
    fun load(f: File): Bitmap? {
        val key = f.path + f.lastModified()
        cache.get(key)?.let { return it }
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        cache.put(key, b)
        return b
    }
}

