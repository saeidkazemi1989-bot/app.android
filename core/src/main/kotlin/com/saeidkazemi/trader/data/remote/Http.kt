package com.saeidkazemi.trader.data.remote

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Http {

    /** نوبیتکس توصیه می‌کند بات‌ها خود را با الگوی TraderBot/<name-version> معرفی کنند. */
    const val USER_AGENT = "TraderBot/MoameleYar-1.2.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") == null) {
                    chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
    }

    val gson: Gson by lazy { Gson() }

    fun <T> retrofit(baseUrl: String, service: Class<T>): T {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(service)
    }
}
