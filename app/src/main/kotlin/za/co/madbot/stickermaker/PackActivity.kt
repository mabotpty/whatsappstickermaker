package za.co.madbot.stickermaker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.io.File

class PackActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PACK = "pack_id"
    }

    private lateinit var store: PackStore
    private lateinit var pack: StickerPack
    private lateinit var grid: RecyclerView
    private lateinit var info: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) edit(uri, null)
    }
    private val addToWhatsApp = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val err = r.data?.getStringExtra("validation_error")
        if (err != null) showError("WhatsApp rejected the pack", err)
        else if (r.resultCode == RESULT_OK) Toast.makeText(this, "Added to WhatsApp", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PackStore(this)
        pack = store.get(intent.getStringExtra(EXTRA_PACK) ?: "") ?: run { finish(); return }

        val root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this)
        setSupportActionBar(bar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        col.addView(bar)
        info = TextView(this).apply { textSize = 13f; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        col.addView(info)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = Adapter()
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(96))
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col)
        root.addView(
            ExtendedFloatingActionButton(this).apply {
                text = "Add sticker"
                setOnClickListener {
                    if (pack.stickers.size >= PackStore.MAX_STICKERS) Toast.makeText(context, "Pack is full", Toast.LENGTH_SHORT).show()
                    else pickImage.launch("image/*")
                }
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) },
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        store.get(pack.id)?.let { pack = it } ?: run { finish(); return }
        supportActionBar?.title = pack.name
        info.text = (if (pack.animated) "Animated pack. " else "Static pack. ") +
            if (pack.isReady) "${pack.stickers.size}/${PackStore.MAX_STICKERS} stickers - ready to add to WhatsApp."
            else "${pack.stickers.size}/${PackStore.MAX_STICKERS} stickers - add at least ${PackStore.MIN_STICKERS} to enable WhatsApp."
        grid.adapter?.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val add = menu.add(0, 1, 0, "Add to WhatsApp")
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        add.isEnabled = pack.isReady
        menu.add(0, 2, 1, "Rename pack")
        menu.add(0, 3, 2, "Delete pack")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> {
                val intent = WhatsApp.buildIntent(this, pack) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                if (intent != null) addToWhatsApp.launch(intent)
            }
            2 -> rename()
            3 -> AlertDialog.Builder(this).setTitle("Delete \"${pack.name}\"?").setMessage("All its stickers will be removed.")
                .setPositiveButton("Delete") { _, _ -> store.delete(pack.id); finish() }
                .setNegativeButton("Cancel", null).show()
        }
        return true
    }

    private fun rename() {
        val input = EditText(this).apply { setText(pack.name); setSelection(text.length) }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("Rename pack").setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { pack.name = n.take(128); store.save(pack); onResume() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun edit(uri: Uri, replace: String?) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_PACK, pack.id).putExtra(EditorActivity.EXTRA_URI, uri.toString())
                .putExtra(EditorActivity.EXTRA_REPLACE, replace)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun stickerMenu(s: StickerItem) {
        AlertDialog.Builder(this).setTitle(s.emojis.joinToString(" "))
            .setItems(arrayOf("Edit", "Change emoji", "Use as tray icon", "Delete")) { _, which ->
                when (which) {
                    0 -> edit(Uri.fromFile(File(store.dir(pack.id), s.file)), s.file)
                    1 -> askEmojis(s.emojis) { store.updateEmojis(pack, s, it); onResume() }
                    2 -> { store.setTray(pack, s); Toast.makeText(this, "Tray icon updated", Toast.LENGTH_SHORT).show() }
                    3 -> { store.removeSticker(pack, s); onResume() }
                }
            }.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val size = (parent.measuredWidth.coerceAtLeast(resources.displayMetrics.widthPixels) - dp(16)) / 3
            val img = ImageView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(size, size)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setBackgroundColor(0x11888888)
            }
            return VH(img)
        }

        override fun getItemCount() = pack.stickers.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = pack.stickers[position]
            h.img.setImageBitmap(Thumbs.load(File(store.dir(pack.id), s.thumbFile)))
            h.img.setOnClickListener { stickerMenu(s) }
        }
    }
}
