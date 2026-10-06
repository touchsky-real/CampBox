package com.inonvation.campbox.data

import android.content.Context
import com.squareup.moshi.Types

class OrderHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("order_history", Context.MODE_PRIVATE)
    private val adapter = MoshiProvider.instance
        .adapter<List<OrderHistoryItem>>(
            Types.newParameterizedType(List::class.java, OrderHistoryItem::class.java),
        )

    fun list(): List<OrderHistoryItem> {
        val raw = prefs.getString(KEY_ORDERS, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw).orEmpty() }.getOrDefault(emptyList())
    }

    fun add(item: OrderHistoryItem) {
        val previous = list()
        val totals = totals().replace(previous.firstOrNull { it.orderNo == item.orderNo }, item)
        val next = (previous.filterNot { it.orderNo == item.orderNo } + item)
            .sortedByDescending { it.completedAt }
            .take(MAX_HISTORY)
        prefs.edit().putString(KEY_ORDERS, adapter.toJson(next))
            .putInt(KEY_TOTAL_COUNT, totals.confirmedCount)
            .putString(KEY_TOTAL_SPENDING, totals.spending.toPlainString()).apply()
    }

    fun totals(): WaterHistoryTotals {
        val spending = prefs.getString(KEY_TOTAL_SPENDING, null)?.toBigDecimalOrNull()
            ?: return WaterHistoryTotals.from(list())
        return WaterHistoryTotals(prefs.getInt(KEY_TOTAL_COUNT, 0), spending)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ORDERS = "orders"
        const val KEY_TOTAL_COUNT = "total_confirmed_count"
        const val KEY_TOTAL_SPENDING = "total_spending"
        const val MAX_HISTORY = 50
    }
}
