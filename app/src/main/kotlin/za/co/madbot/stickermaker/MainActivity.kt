package za.co.madbot.stickermaker

import android.content.Intent
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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import android.widget.FrameLayout
import java.io.File

/** Lists sticker packs. Also receives images shared from other apps ("Share > Sticker Maker"). */
class MainActivity : AppCompatActivity() {
    private lateinit var store: PackStore
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private var packs: List<StickerPack> = emptyList()
    private var pendingUri: Uri? = null

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
            setPadding(0, 0, 0, dp(96))
        }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col)
        empty = TextView(this).apply {
            text = "No packs yet.\nTap New pack to create one."
            gravity = Gravity.CENTER
            textSize = 16f
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            ExtendedFloatingActionButton(this).apply {
                text = "New pack"
                setOnClickListener { newPackDialog() }
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) },
        )
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        if (pendingUri != null) refresh()
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("image/") == true) {
            pendingUri = i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        packs = store.list()
        list.adapter?.notifyDataSetChanged()
        empty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        supportActionBar?.subtitle = if (pendingUri != null) "Pick a pack for the shared image" else null
    }

    private fun newPackDialog() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val name = EditText(this).apply { hint = "Pack name" }
        val pub = EditText(this).apply { hint = "Author"; setText(prefs.getString("publisher", "")) }
        val anim = Switch(this).apply { text = "Animated pack"; setPadding(0, dp(12), 0, 0) }
        val note = TextView(this).apply {
            text = "WhatsApp packs are either all static or all animated. Add 3 to 30 stickers."
            textSize = 12f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(name); addView(pub); addView(anim); addView(note)
        }
        AlertDialog.Builder(this).setTitle("New pack").setView(box)
            .setPositiveButton("Create") { _, _ ->
                val n = name.text.toString().trim()
                val p = pub.text.toString().trim().ifEmpty { "Me" }
                if (n.isEmpty()) { Toast.makeText(this, "Give the pack a name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                prefs.edit().putString("publisher", p).apply()
                val pack = store.create(n.take(128), p.take(128), anim.isChecked)
                open(pack)
            }
            .setNegativeButton("Cancel", null).show()
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
