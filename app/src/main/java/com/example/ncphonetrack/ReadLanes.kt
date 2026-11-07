package com.example.ncphonetrack

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ReadLanes {
    private const val COUNT = 10
    private val lanes = Array(COUNT) {
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.AbortPolicy())
    }
    private val clients = arrayOfNulls<OkHttpClient>(COUNT)
    private val cursor = AtomicInteger(0)
    private var pinStamp = ""

    fun waiting(): Int = lanes.sumOf { it.queue.size }

    fun execute(context: Context, block: (OkHttpClient) -> Unit): Boolean {
        val index = Math.floorMod(cursor.getAndIncrement(), COUNT)
        return try {
            lanes[index].execute {
                try {
                    block(client(context, index))
                } catch (e: Exception) {
                    LogManager.add("Read failed: ${e.message}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun client(context: Context, index: Int): OkHttpClient {
        val prefs = context.getSharedPreferences("nc_phonetrack", Context.MODE_PRIVATE)
        val stamp = prefs.getString("accepted_certs", "") ?: ""
        synchronized(clients) {
            if (stamp != pinStamp) {
                clients.fill(null)
                pinStamp = stamp
            }
            clients[index]?.let { return it }
            val built = CertHelper.buildClient(prefs).newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
                .build()
            built.dispatcher.maxRequests = 1
            built.dispatcher.maxRequestsPerHost = 1
            clients[index] = built
            return built
        }
    }
}
