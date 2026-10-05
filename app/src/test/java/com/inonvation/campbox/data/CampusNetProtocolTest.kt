package com.inonvation.campbox.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampusNetProtocolTest {
    @Test fun `截图中的 ret_code 8 应重试而不是等待放行`() {
        val reply = parseCampusReply("""dr1001({"result":0,"msg":"Portal协议认证超时！","ret_code":8});""")
        assertEquals(CampusReplyStatus.RETRYABLE, reply.status)
    }

    @Test fun `兼容空格 字符串数字和 unicode 消息`() {
        assertEquals(CampusReplyStatus.ACCEPTED,
            parseCampusReply("""dr1001({ "result" : 1, "msg": "ok" });""").status)
        assertEquals(CampusReplyStatus.RETRYABLE,
            parseCampusReply("""{"result":"0","ret_code":"8","msg":"timeout"}""").status)
        assertEquals("账号或密码错误，请核对后重试",
            parseCampusReply("""{"result":0,"msg":"\u5bc6\u7801\u9519\u8bef"}""").message)
    }

    @Test fun `在线响应仍需进入外网验证`() {
        assertEquals(CampusReplyStatus.ACCEPTED,
            parseCampusReply("""dr1001({"result":0,"msg":"终端IP已在线"});""").status)
    }

    @Test fun `明确拒绝和未知响应均不可自动重复提交`() {
        listOf("", "<html>维护中</html>", """{"result":10}""",
            """{"result":0,"msg":"账号停机"}""",
            """{"result":0,"msg":"密码错误"}""").forEach {
            assertEquals(CampusReplyStatus.REJECTED, parseCampusReply(it).status)
        }
    }

    @Test fun `重定向地址参数兼容大小写 下划线和编码`() {
        assertEquals(DiscoveredPortal("219.226.127.249", "10.158.1.2"),
            parseCampusPortal("https://drcom.tyut.edu.cn/?WLAN_AC_IP=219%2E226%2E127%2E249&amp;wlan_user_ip=10.158.1.2"))
    }

    @Test fun `只有用户 IP 时仍保留而 AC 使用默认值`() {
        assertEquals(DiscoveredPortal(null, "10.158.1.2"),
            parseCampusPortal("https://drcom.tyut.edu.cn/?wlanuserip=10.158.1.2"))
    }

    @Test fun `无效 IP 不进入认证参数`() {
        assertNull(parseCampusPortal("https://drcom.tyut.edu.cn/?wlanacip=999.1.2.3"))
        assertNull(parseCampusPortal("https://drcom.tyut.edu.cn/?wlanuserip=0.0.0.0"))
        assertNull(parseCampusPortal("https://drcom.tyut.edu.cn/?wlanacip=10.2"))
        assertNull(parseCampusPortal("not a url"))
    }
}
