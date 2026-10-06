package com.inonvation.campbox.data.qzxy

import java.security.MessageDigest

/** 与 linyu SignUtils 一致：短信签名由手机号本地计算，验证码由服务端校验。 */
object QzxySms {
    fun secret(phone: String): String {
        require(Regex("^1[3-9][0-9]{9}$").matches(phone)) { "请输入正确格式的手机号" }
        return MessageDigest.getInstance("MD5")
            .digest((phone.take(3) + phone.takeLast(4) + "klcx").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
