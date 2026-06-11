package com.nyora.windows.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NyoraHttpClient(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl$path").get().build()
        http.newCall(req).execute().use { it.body!!.string() }
    }

    suspend fun post(path: String, body: String = "{}"): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { it.body!!.string() }
    }

    suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl$path").delete().build()
        http.newCall(req).execute().use { it.body!!.string() }
    }

    inline fun <reified T> parse(body: String): T = json.decodeFromString(body)
}
