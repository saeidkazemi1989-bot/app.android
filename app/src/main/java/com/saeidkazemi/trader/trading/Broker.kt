package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.model.AccountState

interface Broker {
    val mode: String
    fun account(): AccountState
}
