package com.inonvation.campbox.data

import com.inonvation.campbox.data.qzxy.QzxyApi
import com.inonvation.campbox.data.qzxy.QzxyApiException
import com.inonvation.campbox.data.qzxy.QzxyEnvelope
import com.inonvation.campbox.data.qzxy.QzxySession
import com.inonvation.campbox.data.qzxy.QzxySms
import com.inonvation.campbox.data.qzxy.QzxySessionExpiredException
import com.inonvation.campbox.data.qzxy.QzxyUseCodeData
import com.inonvation.campbox.data.qzxy.QzxyWalletData
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

/** 使用公开文档样例与本地响应验证协议，不连接真实账号或热水器。 */
class QzxyAccountApiTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val body = when (request.url.encodedPath) {
            "/user/registerAndLogin" -> """{"success":true,"data":{"loginCode":"sms-session","userId":1,"userAccount":{"accountId":2,"projectId":3,"name":"测试"}}}"""
            "/account/wallet" -> """{"success":true,"data":{"money":"55.460","accountRealMoney":55460}}"""
            "/account/useCode/new" -> """{"success":true,"data":{"useCode":"00740364","useCodeStatus":0,"resetAvailability":0}}"""
            "/account/useCode/new/generate" -> """{"success":true,"data":{"useCode":"00123456","remainTimes":19}}"""
            else -> """{"success":true,"data":null}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val api = Retrofit.Builder().baseUrl("https://example.test/").client(client)
        .addConverterFactory(MoshiConverterFactory.create(MoshiProvider.instance)).build().create(QzxyApi::class.java)
    private val session = QzxySession("test-session", "1", "2", "3", "13800000000", "测试")

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun wallet_usesYuanTotalAndQueriesOnlyWallet() = runTest {
        val wallet = api.getWallet(session.queryFields()).throwIfFailed().data!!
        assertEquals("¥55.46", wallet.balanceText)
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/account/wallet", request.url.encodedPath)
        assertEquals(session.loginCode, request.url.queryParameter("loginCode"))
    }

    @Test
    fun wallet_unknownAmountsAreNotZeroOrThousandTimesTooLarge() {
        assertNull(QzxyWalletData().balanceText)
        assertNull(QzxyWalletData(accountRealMoney = 55460.0, accountGivenMoney = 0.0).balanceText)
        assertNull(QzxyWalletData(money = "unknown").balanceText)
        assertEquals("¥0.00", QzxyWalletData(money = "0.000").balanceText)
        assertEquals("¥12.35", QzxyWalletData(money = "12.345").balanceText)
    }

    @Test
    fun wallet_acceptsNumericTotalWithoutChangingUnits() {
        val wallet = MoshiProvider.instance.adapter(QzxyWalletData::class.java).fromJson("""{"money":12.34}""")!!
        assertEquals("¥12.34", wallet.balanceText)
    }

    @Test
    fun disabledCode_preservesLeadingZerosAndDailyClaimLimit() = runTest {
        val data = api.getUseCode(session.queryFields()).throwIfFailed().data!!
        assertEquals("00740364", data.code)
        assertFalse(data.enabled)
        assertFalse(data.canClaim)
        assertEquals("GET", requests.single().method)
    }

    @Test
    fun unclaimedCode_isACompletedResponseWithNoCode() {
        val data = MoshiProvider.instance.adapter(QzxyUseCodeData::class.java)
            .fromJson("""{"useCode":null,"useCodeStatus":0,"resetAvailability":1}""")!!
        assertNull(data.code)
        assertFalse(data.enabled)
        assertTrue(data.canClaim)
    }

    @Test
    fun generationAndClaim_areSeparateRequestsWithBothPhoneFields() = runTest {
        val generated = api.generateUseCode(session.authFields()).throwIfFailed().data!!
        assertEquals("00123456", generated.useCode)
        assertEquals(19, generated.remainTimes)
        assertEquals(1, requests.size)
        assertEquals("/account/useCode/new/generate", requests[0].url.encodedPath)
        val generationForm = requests[0].body as FormBody
        assertEquals("13800000000", generationForm.field("telephone"))
        assertEquals("13800000000", generationForm.field("telPhone"))
        assertNull(generationForm.field("useCode"))

        api.setUseCode(generated.useCode!!, session.authFields()).throwIfFailed()
        assertEquals("/account/useCode/new/set", requests[1].url.encodedPath)
        assertEquals("POST", requests[1].method)
        assertEquals("00123456", (requests[1].body as FormBody).field("useCode"))
    }

    @Test
    fun codeSwitch_sendsPlatformStatusValues() = runTest {
        api.updateUseCodeStatus(1, session.authFields()).throwIfFailed()
        api.updateUseCodeStatus(0, session.authFields()).throwIfFailed()
        assertEquals("/account/useCode/new/status/update", requests[0].url.encodedPath)
        assertEquals("1", (requests[0].body as FormBody).field("useCodeStatus"))
        assertEquals("0", (requests[1].body as FormBody).field("useCodeStatus"))
    }

    @Test(expected = QzxyApiException::class)
    fun businessFailure_isNotTreatedAsSuccessfulEmptyData() {
        QzxyEnvelope<QzxyUseCodeData>(success = false, errorCode = 1, errorMessage = "今日领取次数已用完")
            .throwIfFailed()
    }

    @Test(expected = QzxySessionExpiredException::class)
    fun expiredSession_isNotShownAsMissingBalance() {
        QzxyEnvelope<QzxyWalletData>(success = false, errorCode = 401).throwIfFailed()
    }

    @Test
    fun smsRequest_usesLoginPurposeAndNoExistingSession() = runTest {
        api.sendLoginSms("13800138000", QzxySms.secret("13800138000")).throwIfFailed()
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/user/verification/code/get", request.url.encodedPath)
        assertEquals("13800138000", request.url.queryParameter("telephone"))
        assertEquals("3", request.url.queryParameter("typeId"))
        assertEquals("1", request.url.queryParameter("platform"))
        assertEquals(QzxySms.secret("13800138000"), request.url.queryParameter("secret"))
        assertNull(request.url.queryParameter("loginCode"))
    }

    @Test
    fun smsLogin_preservesLeadingZeroCodeAndReadsSameSessionFields() = runTest {
        val response = api.loginWithSms("13800138000", "001234").throwIfFailed().data!!
        assertEquals("sms-session", response.loginCode)
        assertEquals(3L, response.userAccount?.projectId)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/user/registerAndLogin", request.url.encodedPath)
        val form = request.body as FormBody
        assertEquals("001234", form.field("smsCode"))
        assertEquals("13800138000", form.field("telephone"))
        assertEquals("5", form.field("type"))
        assertEquals("android", form.field("phoneSystem"))
        assertNull(form.field("password"))
        assertNull(form.field("loginCode"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun smsSecret_rejectsMalformedPhone() {
        QzxySms.secret("138abcdefgh")
    }

    @Test
    fun smsSecret_matchesReferenceAndDependsOnPhone() {
        assertEquals("f000554d9cb90c44bd0ae2ded9a847aa", QzxySms.secret("13800138000"))
        assertEquals("146b65d10da146268dba138a264e26bf", QzxySms.secret("13900138000"))
    }

    private fun FormBody.field(name: String): String? =
        (0 until size).firstOrNull { this.name(it) == name }?.let { value(it) }
}
