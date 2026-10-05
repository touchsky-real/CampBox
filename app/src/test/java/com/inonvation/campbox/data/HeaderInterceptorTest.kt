package com.inonvation.campbox.data

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeaderInterceptorTest {
    @Test fun `扫码请求包含完整客户端版本 正确签名并自动解压 gzip`() {
        val json = """{"code":0,"data":{"tokenCoin":"100"}}"""
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(json.toByteArray()) }
        }.toByteArray()
        val executor = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor({ "old-token" })).build()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val received = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers += line
                        }
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n" +
                                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                        headers
                    }
                }
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/goods/scan/v2")
                    .header("token", "candidate-token").build()
                client.newCall(request).execute().use { assertEquals(json, it.body?.string()) }
                val headers = received.get(5, TimeUnit.SECONDS)
                assertTrue(headers.any { it.equals("Accept-Encoding: gzip", ignoreCase = true) })
                assertTrue(headers.any { it.equals("Authorization: candidate-token", ignoreCase = true) })
                assertTrue(headers.any { it.equals("token: candidate-token", ignoreCase = true) })
                fun header(name: String) = headers.first { it.startsWith("$name:", ignoreCase = true) }
                    .substringAfter(':').trim()
                assertEquals("1.142.0", header("Version"))
                assertEquals("QEUser/1.142.0 (com.qiekj.user; build:279; Android 14; userChannel:android_app; version:1.142.0) OkHttp/4.12.0",
                    header("User-Agent"))
                // 按官方格式校验，路径前的 & 不能省略。
                val signingText = "appSecret=${ApiConfig.ANDROID_SECRET}&channel=android_app" +
                    "&timestamp=${header("timestamp")}&token=candidate-token&version=1.142.0&/goods/scan/v2"
                val expectedSign = MessageDigest.getInstance("SHA-256").digest(signingText.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expectedSign, header("sign"))
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            executor.shutdownNow()
        }
    }
}
