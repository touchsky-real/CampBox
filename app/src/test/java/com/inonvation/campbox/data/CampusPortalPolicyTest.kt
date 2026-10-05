package com.inonvation.campbox.data

import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CampusPortalPolicyTest {
    @Test fun `保留用户提供的接入控制器和登录返回地址`() {
        val url = campusPortalUrl(null, null).toHttpUrl()
        assertEquals("drcom.tyut.edu.cn", url.host)
        assertEquals("219.226.127.249", url.queryParameter("wlanacip"))
        assertEquals("http://www.msftconnecttest.com/redirect", url.queryParameter("url"))
        assertTrue(isOfficialCampusPortal(url.toString()))
    }

    @Test fun `已发现的会话参数优先于默认 AC`() {
        val url = campusPortalUrl("10.1.1.1", "10.2.2.2").toHttpUrl()
        assertEquals("10.1.1.1", url.queryParameter("wlanacip"))
        assertEquals("10.2.2.2", url.queryParameter("wlanuserip"))
    }

    @Test fun `只在官方 HTTPS 源自动填充`() {
        assertTrue(isOfficialCampusPortal("https://drcom.tyut.edu.cn:804/eportal/portal/index"))
        listOf("http://drcom.tyut.edu.cn/", "https://drcom.tyut.edu.cn.attacker.test/",
            "https://drcom.tyut.edu.cn@attacker.test/", "https://user@drcom.tyut.edu.cn/",
            "https://drcom.tyut.edu.cn:801/", "javascript:alert(1)", "file:///login.html")
            .forEach { assertFalse(isOfficialCampusPortal(it), it) }
    }

    @Test fun `特殊字符通过 JSON 编码 填充不自动提交`() {
        val password = "quote\"';\\\n</script>"
        val script = campusPortalAutofillScript("test-user", password)
        val encoded = Moshi.Builder().build().adapter(String::class.java).toJson(password)
        assertTrue(script.contains("fillInput(pw, $encoded)"))
        assertFalse(script.contains(".submit("))
        assertFalse(script.contains(".click("))
        assertTrue(script.contains("frame.contentDocument"))
        assertTrue(script.contains("child.location.origin === location.origin"))
    }
}
