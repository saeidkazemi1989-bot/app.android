package com.saeidkazemi.trader.analysis

/** پارامترهای مدیریت ریسک بر اساس سطح ریسک انتخابی کاربر. */
class RiskManager {

    data class Plan(
        val title: String,
        val maxPositions: Int,
        val positionPct: Double,
        val stopPct: Double,
        val tpPct: Double,
        val cashReservePct: Double,
        val minTradeUsd: Double
    )

    fun plan(level: String): Plan = when (level) {
        "LOW" -> Plan("کم‌ریسک", 6, 0.10, 0.04, 0.10, 0.10, 25.0)
        "HIGH" -> Plan("پرریسک", 4, 0.22, 0.08, 0.20, 0.05, 25.0)
        else -> Plan("متوسط", 5, 0.15, 0.06, 0.15, 0.08, 25.0)
    }
}
