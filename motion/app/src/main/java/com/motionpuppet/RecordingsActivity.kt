package com.motionpuppet

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Saved movements. Tap to open on the stage; long-press to delete. */
class RecordingsActivity : ComponentActivity() {

    private var items: List<RecordingInfo> = emptyList()
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var d = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1116.toInt())
            setPadding(px(12), px(12), px(12), px(12))
        }
        root.addView(TextView(this).apply {
            text = "Recordings"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, px(4))
        })
        root.addView(TextView(this).apply {
            text = "Tap to put a character on it · long-press to delete"
            textSize = 13f
            setTextColor(0xFF9AA3AF.toInt())
            setPadding(0, 0, 0, px(8))
        })
        empty = TextView(this).apply {
            text = "No recordings yet. Go back and tap ⏺ Record."
            textSize = 15f
            setTextColor(0xFF9AA3AF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, px(40), 0, 0)
        }
        root.addView(empty)
        list = ListView(this).apply {
            divider = null
            dividerHeight = px(6)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, StageActivity::class.java).putExtra(StageActivity.EXTRA_FILE, items[pos].file.name))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val it = items[pos]
            AlertDialog.Builder(this)
                .setTitle("Delete this recording?")
                .setMessage(it.name)
                .setPositiveButton("Delete") { _, _ -> it.file.delete(); reload() }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items = RecordingStore.list(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = items[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1D222B.toInt())
                setPadding(px(14), px(12), px(14), px(12))
                addView(TextView(context).apply { textSize = 16f; setTextColor(Color.WHITE) })
                addView(TextView(context).apply { textSize = 13f; setTextColor(0xFF9AA3AF.toInt()) })
            }
            (row.getChildAt(0) as TextView).text = r.name
            val s = r.durationMs / 1000
            (row.getChildAt(1) as TextView).text =
                "${s / 60}:${"%02d".format(s % 60)} · ${r.people} ${if (r.people == 1) "person" else "people"}"
            return row
        }
    }

    private fun px(v: Int) = (v * d).toInt()
}
