package com.saeidkazemi.trader.analysis

/** اندیکاتورهای تکنیکال پایه - توابع خالص روی آرایه قیمت. */
object Indicators {

    fun sma(v: DoubleArray, period: Int): Double? {
        if (v.size < period || period <= 0) return null
        var sum = 0.0
        for (i in v.size - period until v.size) sum += v[i]
        return sum / period
    }

    fun emaSeries(v: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (v.size < period || period <= 0) return out
        var seed = 0.0
        for (i in 0 until period) seed += v[i]
        out[period - 1] = seed / period
        val k = 2.0 / (period + 1)
        for (i in period until v.size) out[i] = v[i] * k + out[i - 1] * (1 - k)
        return out
    }

    /** RSI با هموارسازی وایلدر. */
    fun rsi(v: DoubleArray, period: Int = 14): Double? {
        if (v.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = v[i] - v[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until v.size) {
            val d = v[i] - v[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
        }
        if (avgLoss <= 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    data class MacdResult(val macd: Double, val signal: Double, val hist: Double)

    fun macd(v: DoubleArray): MacdResult? {
        if (v.size < 35) return null
        val e12 = emaSeries(v, 12)
        val e26 = emaSeries(v, 26)
        val valid = ArrayList<Double>()
        for (i in v.indices) {
            if (!e12[i].isNaN() && !e26[i].isNaN()) valid.add(e12[i] - e26[i])
        }
        if (valid.size < 10) return null
        val arr = valid.toDoubleArray()
        val sig = emaSeries(arr, 9)
        val lastSignal = sig[sig.size - 1]
        val lastMacd = arr[arr.size - 1]
        if (lastSignal.isNaN()) return null
        return MacdResult(lastMacd, lastSignal, lastMacd - lastSignal)
    }

    fun momentumPct(v: DoubleArray, lookback: Int): Double? {
        if (v.size <= lookback) return null
        val a = v[v.size - 1 - lookback]
        val b = v[v.size - 1]
        if (a <= 0 || !a.isFinite() || !b.isFinite()) return null
        return (b / a - 1.0) * 100.0
    }

    /** انحراف معیار بازده‌های روزانه به درصد. */
    fun dailyVolatilityPct(v: DoubleArray, lookback: Int = 30): Double? {
        if (v.size < lookback + 1 || lookback <= 1) return null
        val rets = DoubleArray(lookback)
        for (i in 0 until lookback) {
            val prev = v[v.size - 2 - i]
            val cur = v[v.size - 1 - i]
            if (prev <= 0) return null
            rets[i] = cur / prev - 1.0
        }
        val mean = rets.average()
        var ss = 0.0
        for (r in rets) ss += (r - mean) * (r - mean)
        val sd = Math.sqrt(ss / (lookback - 1))
        return sd * 100.0
    }

    /** فاصله درصدی میانگین متحرک نمایی کوتاه‌مدت از بلندمدت (نشانه روند). */
    fun trendPct(v: DoubleArray, shortPeriod: Int = 12, longPeriod: Int = 26): Double? {
        val s = emaSeries(v, shortPeriod)
        val l = emaSeries(v, longPeriod)
        val sv = s[s.size - 1]
        val lv = l[l.size - 1]
        if (sv.isNaN() || lv.isNaN() || lv <= 0) return null
        return (sv / lv - 1.0) * 100.0
    }
}
