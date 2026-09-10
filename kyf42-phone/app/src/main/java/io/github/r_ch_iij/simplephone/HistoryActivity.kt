package io.github.r_ch_iij.simplephone

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 発着信履歴の一覧表示（新しい順、最大30件）。
// 記録は SipConfig.addHistory が行う（応答成立時・不在着信時）。
class HistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val dateFormat = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
        val entries = SipConfig.getHistory(this)
        val lines = entries.map { entry ->
            "${dateFormat.format(Date(entry.epochMillis))} ${entry.direction} ${entry.number}"
        }

        val listView = findViewById<ListView>(R.id.historyList)
        val emptyView = findViewById<TextView>(R.id.historyEmpty)
        listView.emptyView = emptyView
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, lines
        )
    }
}
