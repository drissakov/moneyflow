package com.moneyflow.app.data.remote

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiFactory {
    fun create(baseUrl: String, token: () -> String?): MoneyFlowApi {
        val client = OkHttpClient.Builder()
            // The API closes idle sockets after 60 seconds. Evict them sooner
            // so GET requests cannot select a stale socket while retries are off.
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header("Accept", "application/json")
                if (chain.request().header("Authorization") == null) {
                    token()?.let { request.header("Authorization", "Bearer $it") }
                }
                chain.proceed(request.build())
            }
            .build()
        return Retrofit.Builder().baseUrl(baseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(MoneyFlowApi::class.java)
    }
}
