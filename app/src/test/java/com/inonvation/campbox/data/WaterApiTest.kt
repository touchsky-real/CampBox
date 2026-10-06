package com.inonvation.campbox.data

import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** 基于 pgdecode 的接口字段验证完整请求编码，全部响应由本地提供。 */
class WaterApiTest {
    private lateinit var request: Request
    private var responseJson = ""
    private val client = OkHttpClient.Builder()
        .addInterceptor(HeaderInterceptor({ "test-token" }))
        .addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType())).build()
        }.build()
    private val api = Retrofit.Builder().baseUrl("https://example.test/").client(client)
        .addConverterFactory(MoshiConverterFactory.create(MoshiProvider.instance)).build()
        .create(DeviceApi::class.java)

    @After fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun form(): Map<String, String> {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8().split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    @Test fun `开启积分时实际请求包含官方正八抵扣项且未被编码破坏`() = runTest {
        responseJson = """{"code":0,"data":{"orderNo":"mine","msgId":"message"}}"""
        api.unlockWater(emptyMap(), "sku", AppRepository.promotions(true), "test-token")
        val promotions = form().getValue("promotions")
        assertEquals("/goods/water/unlock", request.url.encodedPath)
        assertTrue(promotions.contains("\"promotionType\":\"8\""))
        assertFalse(promotions.contains("\"promotionType\":\"-8\""))
        assertEquals(AppRepository.promotions(true), promotions)
        assertEquals("test-token", form()["token"])
    }

    @Test fun `关闭积分时显式传负八`() = runTest {
        responseJson = """{"code":0,"data":{"orderNo":"mine"}}"""
        api.unlockWater(emptyMap(), "sku", AppRepository.promotions(false), "test-token")
        assertTrue(form().getValue("promotions").contains("\"promotionType\":\"-8\""))
    }

    @Test fun `积分充足且平台允许时规则不会取消抵扣`() = runTest {
        responseJson = """{"code":0,"data":{"integralTotal":1000,"minUsageCount":100,"isUserIntegral":true}}"""
        val rule = api.integralLimitRule("test-token").requireData()
        assertEquals("/userIntegral/limitRule", request.url.encodedPath)
        assertNull(rule.unusedReason())
    }

    @Test fun `积分门槛来自服务端而非客户端猜测`() = runTest {
        responseJson = """{"code":0,"data":{"integralTotal":99,"minUsageCount":100,"isUserIntegral":false}}"""
        assertEquals("平台要求积分满 100 才可使用（当前 99）", api.integralLimitRule("test-token").requireData().unusedReason())
        assertNull(IntegralLimitRule().unusedReason())
    }

    @Test fun `风控返回假不能误判为未实名认证`() = runTest {
        responseJson = """{"code":0,"data":false}"""
        assertEquals(false, api.useIntergral("test-token").requireData())
    }

    @Test fun `真实接口使用userIntegral字段且三十二积分未满四十`() = runTest {
        responseJson = """{"code":0,"data":{"integralTotal":32,"minUsageCount":40,"userIntegral":false}}"""
        assertEquals("平台要求积分满 40 才可使用（当前 32）",
            api.integralLimitRule("test-token").requireData().unusedReason())
    }

    @Test fun `结束后设备状态可能为空金额且不影响独立读取账单`() = runTest {
        responseJson = """{"code":0,"data":{"status":6,"workStatus":1,"amount":null,"identify":null}}"""
        val status = api.syncWater("sku", "test-token").requireData()
        assertEquals(6, status.status)
        assertNull(status.amount)
    }
}
