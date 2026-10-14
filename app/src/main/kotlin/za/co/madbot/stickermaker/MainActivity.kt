package za.co.madbot.stickermaker

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import java.io.File

/** Lists sticker packs. Also receives images shared from other apps ("Share > Sticker Maker"). */
class MainActivity : AppCompatActivity() {
    private lateinit var store: PackStore
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private var packs: List<StickerPack> = emptyList()
    private var pendingUri: Uri? = null
    private var pendingImport: Uri? = null

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPack(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PackStore(this)
        handleShare(intent)

        val root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = MaterialToolbar(this).apply { title = "Sticker Maker" }
        setSupportActionBar(bar)
        col.addView(bar)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Adapter()
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col)
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply { text = "No packs yet"; textSize = 16f; gravity = Gravity.CENTER })
            addView(
                MaterialButton(context).apply {
                    text = "Create your first pack"
                    setOnClickListener { newPackDialog() }
                },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) },
            )
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "New pack").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Import pack (.wastickers)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) newPackDialog()
        if (item.itemId == 2) importPicker.launch(arrayOf("*/*"))
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        if (pendingUri != null) refresh()
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        val type = i?.type ?: return
        if (i.action == Intent.ACTION_SEND && type.startsWith("image/")) {
            pendingUri = i.getParcelableExtra(Intent.EXTRA_STREAM)
        } else if (i.action == Intent.ACTION_SEND) {
            pendingImport = i.getParcelableExtra(Intent.EXTRA_STREAM)
        } else if (i.action == Intent.ACTION_VIEW) {
            pendingImport = i.data
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        pendingImport?.let { pendingImport = null; importPack(it) }
    }

    private fun importPack(uri: Uri) {
        var result: ImportResult? = null
        busy("Importing pack...", { _ ->
            result = withContext(Dispatchers.IO) { PackImporter.import(this@MainActivity, uri, store) }
        }, { ok ->
            val r = result
            if (ok && r != null) {
                refresh()
                if (r.skipped.isEmpty()) {
                    Toast.makeText(this, "Imported ${r.pack.stickers.size} stickers", Toast.LENGTH_SHORT).show()
                    open(r.pack)
                } else {
                    AlertDialog.Builder(this).setTitle("Imported ${r.pack.stickers.size} stickers")
                        .setMessage("Skipped ${r.skipped.size}:\n" + r.skipped.joinToString("\n"))
                        .setPositiveButton("Open pack") { _, _ -> open(r.pack) }.show()
                }
            }
        })
    }

    private fun refresh() {
        packs = store.list()
        list.adapter?.notifyDataSetChanged()
        empty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        supportActionBar?.subtitle = if (pendingUri != null) "Pick a pack for the shared image" else null
    }

    /** Custom dialog: the primary action is a big filled button, so it cannot be mistaken for anything behind it. */
    private fun newPackDialog() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val name = EditText(this).apply { hint = "Pack name"; setSingleLine() }
        val pub = EditText(this).apply { hint = "Author"; setText(prefs.getString("publisher", "")); setSingleLine() }
        val anim = Switch(this).apply { text = "Animated pack"; setPadding(0, dp(12), 0, 0) }
        val note = TextView(this).apply {
            text = "WhatsApp packs are either all static or all animated. Add 3 to 30 stickers."
            textSize = 12f
        }
        val title = TextView(this).apply { text = "New sticker pack"; textSize = 20f; setPadding(0, 0, 0, dp(8)) }
        lateinit var dlg: AlertDialog
        val create = MaterialButton(this).apply {
            text = "Create pack"
            setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) { name.error = "Give the pack a name"; return@setOnClickListener }
                val p = pub.text.toString().trim().ifEmpty { "Me" }
                prefs.edit().putString("publisher", p).apply()
                val pack = store.create(n.take(128), p.take(128), anim.isChecked)
                dlg.dismiss()
                open(pack)
            }
        }
        val cancel = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setOnClickListener { dlg.dismiss() }
        }
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
            addView(cancel)
            addView(create, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
            addView(title); addView(name); addView(pub); addView(anim); addView(note); addView(buttons)
        }
        dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.show()
    }

    private fun open(p: StickerPack) {
        val uri = pendingUri
        if (uri != null) {
            pendingUri = null
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_PACK, p.id).putExtra(EditorActivity.EXTRA_URI, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } else {
            startActivity(Intent(this, PackActivity::class.java).putExtra(PackActivity.EXTRA_PACK, p.id))
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val row: LinearLayout, val img: ImageView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val title = TextView(parent.context).apply { textSize = 17f }
            val sub = TextView(parent.context).apply { textSize = 13f; alpha = 0.7f }
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(sub)
            }
            val row = LinearLayout(parent.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                addView(img, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            }
            return VH(row, img, title, sub)
        }

        override fun getItemCount() = packs.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = packs[position]
            h.title.text = p.name
            h.sub.text = "${p.stickers.size}/${PackStore.MAX_STICKERS} stickers" +
                (if (p.animated) " - animated" else "") + (if (!p.isReady) " - needs ${PackStore.MIN_STICKERS - p.stickers.size} more" else "")
            val tray = File(store.dir(p.id), p.trayFile)
            h.img.setImageBitmap(if (tray.exists()) Thumbs.load(tray) else null)
            h.row.setOnClickListener { open(p) }
        }
    }
}
