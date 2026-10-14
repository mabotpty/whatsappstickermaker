package za.co.madbot.stickermaker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

class EditorActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PACK = "pack_id"
        const val EXTRA_URI = "uri"
        const val EXTRA_REPLACE = "replace_file"
    }

    private class Snap(val frames: List<Bitmap>, val orig: List<Bitmap>, val delays: List<Int>)

    private lateinit var store: PackStore
    private lateinit var pack: StickerPack
    private var replaceFile: String? = null
    private lateinit var view: EditorView
    private lateinit var cropBar: View
    private lateinit var brushBar: View
    private lateinit var toolRow: View
    private lateinit var hint: TextView

    private var frames: List<Bitmap> = emptyList()
    private var orig: List<Bitmap> = emptyList()
    private var delays: List<Int> = listOf(100)
    private val undo = ArrayDeque<Snap>()
    private val texts = mutableListOf<TextOverlay>()
    private var outline = false
    private var composed: Array<Bitmap?> = arrayOfNulls(0)
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PackStore(this)
        pack = store.get(intent.getStringExtra(EXTRA_PACK) ?: "") ?: run { finish(); return }
        replaceFile = intent.getStringExtra(EXTRA_REPLACE)
        buildUi()

        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI))
        busy("Loading...", { _ ->
            val f = withContext(Dispatchers.IO) { MediaDecoder.load(this@EditorActivity, uri) }
            onLoaded(f)
        }, { ok -> if (!ok) finish() })
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply { title = if (replaceFile != null) "Edit sticker" else "New sticker" }
        setSupportActionBar(bar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        root.addView(bar)

        view = EditorView(this)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.onTextTap = { i -> editText(i) }
        view.onTextMove = { i, cx, cy ->
            texts[i] = texts[i].copy(cx = cx, cy = cy)
            textsChanged(rebuildBoxes = true, invalidateFrames = true)
        }
        view.onBeginStroke = {
            pushUndo()
            val copy = frames[0].copy(Bitmap.Config.ARGB_8888, true)
            frames = listOf(copy)
            composed = arrayOfNulls(1)
            view.image = copy
            copy
        }

        hint = TextView(this).apply { textSize = 12f; setPadding(dp(16), dp(4), dp(16), dp(4)); gravity = android.view.Gravity.CENTER }
        root.addView(hint)

        // sub-bars (only one visible at a time)
        val sub = FrameLayout(this)
        cropBar = row(btn("Cancel") { setMode(EditorView.Mode.PREVIEW) }, btn("Apply crop") { applyCrop() })
        val seek = SeekBar(this).apply {
            max = 100
            progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    view.brushFraction = 0.01f + 0.14f * p / 100f
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        view.brushFraction = 0.01f + 0.14f * 30 / 100f
        brushBar = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            addView(TextView(context).apply { text = "Brush" })
            addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(btn("Done") { setMode(EditorView.Mode.PREVIEW) })
        }
        sub.addView(cropBar); sub.addView(brushBar)
        cropBar.visibility = View.GONE; brushBar.visibility = View.GONE
        root.addView(sub)

        toolRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                LinearLayout(context).apply {
                    setPadding(dp(8), 0, dp(8), dp(8))
                    addView(btn("Crop") { enterCrop() })
                    addView(btn("Rotate") { geometry("Rotating...") { b -> rotate(b) } })
                    addView(btn("Flip") { geometry("Flipping...") { b -> flip(b) } })
                    addView(btn("Remove BG") { removeBackground() })
                    addView(btn("Erase") { enterBrush(EditorView.Mode.ERASE) })
                    addView(btn("Restore") { enterBrush(EditorView.Mode.RESTORE) })
                    addView(btn("Text") { editText(-1) })
                    addView(btn("Outline") { outline = !outline; textsChanged(false, true); toast(if (outline) "Outline on" else "Outline off") })
                    addView(btn("Undo") { undoLast() })
                }
            )
        }
        root.addView(toolRow)
        setContentView(root)
    }

    private fun btn(label: String, click: () -> Unit) = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setOnClickListener { if (loaded) click() }
    }

    private fun row(vararg children: View) = LinearLayout(this).apply {
        gravity = android.view.Gravity.END
        children.forEach { addView(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Save").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> if (loaded) save()
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ loading / preview

    private fun onLoaded(f: Frames) {
        var fr = f.bitmaps
        var dl = f.delays
        if (!pack.animated && f.animated) {
            fr = fr.take(1); dl = dl.take(1)
            toast("Static pack: using the first frame")
        }
        if (pack.animated && !f.animated) {
            showError("Animated pack", "This pack only takes animated stickers. Pick an animated GIF or WebP, or make a new static pack.")
            finish()
            return
        }
        frames = fr; orig = fr; delays = dl
        loaded = true
        refreshPreview()
        hint.text = if (pack.animated) "Animated pack - ${frames.size} frames" else "Tip: tap Remove BG, then Erase/Restore to touch up"
    }

    private fun refreshPreview() {
        composed = arrayOfNulls(frames.size)
        view.image = frames[0]
        view.orig = orig[0]
        view.setPreview(frames.size, delays) { i ->
            val idx = i.coerceIn(0, frames.size - 1)
            composed[idx] ?: Composer.compose(frames[idx], outline, texts).also { composed[idx] = it }
        }
        rebuildBoxes()
    }

    private fun textsChanged(rebuildBoxes: Boolean, invalidateFrames: Boolean) {
        if (invalidateFrames) composed = arrayOfNulls(frames.size)
        if (rebuildBoxes) rebuildBoxes()
        view.invalidate()
    }

    private fun rebuildBoxes() {
        view.textBoxes = texts.map { Composer.textBox(it) }
    }

    // ------------------------------------------------------------------ modes

    private fun setMode(m: EditorView.Mode) {
        view.mode = m
        cropBar.visibility = if (m == EditorView.Mode.CROP) View.VISIBLE else View.GONE
        brushBar.visibility = if (m == EditorView.Mode.ERASE || m == EditorView.Mode.RESTORE) View.VISIBLE else View.GONE
        toolRow.visibility = if (m == EditorView.Mode.PREVIEW) View.VISIBLE else View.GONE
        if (m == EditorView.Mode.PREVIEW) {
            composed = arrayOfNulls(frames.size)
            view.image = frames[0]
            view.orig = orig[0]
            view.invalidate()
        }
        hint.text = when (m) {
            EditorView.Mode.CROP -> "Drag the edges or corners, or drag inside to move"
            EditorView.Mode.ERASE -> "Paint over areas to erase"
            EditorView.Mode.RESTORE -> "Paint to bring back the original"
            else -> if (pack.animated) "Animated pack - ${frames.size} frames" else ""
        }
    }

    private fun enterCrop() {
        view.image = frames[0]
        setMode(EditorView.Mode.CROP)
    }

    private fun enterBrush(m: EditorView.Mode) {
        if (frames.size > 1) { toast("Eraser works on static stickers. Use Remove BG for animations."); return }
        view.image = frames[0]
        view.orig = orig[0]
        setMode(m)
    }

    // ------------------------------------------------------------------ edit operations

    private fun pushUndo() {
        undo.addLast(Snap(frames, orig, delays))
        if (undo.size > 6) undo.removeFirst()
    }

    private fun undoLast() {
        val s = undo.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        frames = s.frames; orig = s.orig; delays = s.delays
        refreshPreview()
    }

    /** Applies [op] to every frame (and the matching originals) off the main thread. */
    private fun geometry(msg: String, op: (Bitmap) -> Bitmap) {
        busy(msg, { _ ->
            val (nf, no) = withContext(Dispatchers.Default) {
                val cache = IdentityHashMap<Bitmap, Bitmap>()
                fun m(b: Bitmap) = cache.getOrPut(b) { op(b) }
                frames.map { m(it) } to orig.map { m(it) }
            }
            pushUndo()
            frames = nf; orig = no
            refreshPreview()
        })
    }

    private fun rotate(b: Bitmap): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)

    private fun flip(b: Bitmap): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { preScale(-1f, 1f) }, true)

    private fun applyCrop() {
        val r = view.getCropRect()
        val w = frames[0].width
        val h = frames[0].height
        val full = r.left <= 0 && r.top <= 0 && r.right >= w && r.bottom >= h
        setMode(EditorView.Mode.PREVIEW)
        if (full || r.width() < 8 || r.height() < 8) return
        geometry("Cropping...") { b -> Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()) }
    }

    private fun removeBackground() {
        busy("Removing background... (first use downloads a small model)", { update ->
            val out = withContext(Dispatchers.Default) {
                val seg = SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
                )
                try {
                    val cache = IdentityHashMap<Bitmap, Bitmap>()
                    frames.mapIndexed { i, b ->
                        runOnUiThread { update("Removing background ${i + 1}/${frames.size}...") }
                        cache.getOrPut(b) {
                            segment(seg, b) { runOnUiThread { update("Downloading background-removal model (first use only)...") } }
                        }
                    }
                } finally {
                    seg.close()
                }
            }
            pushUndo()
            frames = out
            refreshPreview()
        })
    }

    /** Google Play services fetches the model on first use; keep retrying while it downloads (up to ~2 min). */
    private fun segment(seg: com.google.mlkit.vision.segmentation.subject.SubjectSegmenter, b: Bitmap, waiting: () -> Unit): Bitmap {
        var attempts = 0
        while (true) {
            try {
                val res = Tasks.await(seg.process(InputImage.fromBitmap(b, 0)))
                return res.foregroundBitmap ?: b
            } catch (e: Exception) {
                val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
                if (!msg.contains("Waiting for the subject segmentation") && !msg.contains("module to be downloaded") || ++attempts > 40) throw e
                waiting()
                Thread.sleep(3000)
            }
        }
    }

    // ------------------------------------------------------------------ text

    private val palette = intArrayOf(Color.WHITE, Color.BLACK, Color.rgb(255, 214, 10), Color.rgb(255, 69, 58), Color.rgb(48, 209, 88), Color.rgb(10, 132, 255), Color.rgb(255, 105, 180))

    /** [index] = -1 adds new text, otherwise edits that overlay. */
    private fun editText(index: Int) {
        val cur = texts.getOrNull(index)
        var color = cur?.color ?: Color.WHITE
        val input = EditText(this).apply { setText(cur?.text ?: ""); hint = "Text"; setSelection(text.length) }
        val size = SeekBar(this).apply { max = 100; progress = (((cur?.size ?: 0.12f) - 0.05f) / 0.2f * 100).toInt().coerceIn(0, 100) }
        val colors = LinearLayout(this).apply {
            setPadding(0, dp(12), 0, dp(12))
            palette.forEach { c ->
                addView(View(context).apply {
                    setBackgroundColor(c)
                    setOnClickListener { color = c; (parent as LinearLayout).children().forEach { v -> v.alpha = 0.45f }; alpha = 1f }
                    alpha = if (c == color) 1f else 0.45f
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input); addView(colors); addView(TextView(context).apply { text = "Size" }); addView(size)
        }
        val b = AlertDialog.Builder(this).setTitle(if (cur == null) "Add text" else "Edit text").setView(box)
            .setPositiveButton("OK") { _, _ ->
                val s = input.text.toString()
                val t = TextOverlay(s, color, 0.05f + 0.2f * size.progress / 100f, cur?.cx ?: 0.5f, cur?.cy ?: 0.82f)
                if (cur == null) { if (s.isNotBlank()) texts.add(t) } else if (s.isBlank()) texts.removeAt(index) else texts[index] = t
                textsChanged(true, true)
            }
            .setNegativeButton("Cancel", null)
        if (cur != null) b.setNeutralButton("Delete") { _, _ -> texts.removeAt(index); textsChanged(true, true) }
        b.show()
    }

    private fun LinearLayout.children() = (0 until childCount).map { getChildAt(it) }

    // ------------------------------------------------------------------ save

    private fun save() {
        val existing = pack.stickers.firstOrNull { it.file == replaceFile }
        if (replaceFile == null && pack.stickers.size >= PackStore.MAX_STICKERS) {
            showError("Pack is full", "A pack holds at most ${PackStore.MAX_STICKERS} stickers.")
            return
        }
        // Keep the emoji of a sticker being edited; new stickers get the default (changeable from the pack screen).
        val emojis = existing?.emojis ?: listOf(DEFAULT_EMOJI)
        busy("Encoding sticker...", { update ->
            val (bytes, preview) = withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                val comp = frames.map { Composer.compose(it, outline, texts) }
                val bytes = if (pack.animated) {
                    StickerEncoder.encodeAnimated(comp, delays, { job?.isCancelled == true }) { m -> update(m) }
                } else {
                    StickerEncoder.encodeStatic(comp[0])
                }
                bytes to comp[0]
            }
            withContext(Dispatchers.IO) { store.addSticker(pack, bytes, preview, emojis, replaceFile) }
            toast("Saved (${bytes.size / 1024} KB)")
            setResult(RESULT_OK)
            finish()
        })
    }
}
