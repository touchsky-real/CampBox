package com.inonvation.campbox.data

import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val CAMPUS_PORTAL_URL = "https://drcom.tyut.edu.cn/?wlanacip=219.226.127.249&url=http://www.msftconnecttest.com/redirect"

internal fun campusPortalUrl(acIp: String?, userIp: String?): String =
    CAMPUS_PORTAL_URL.toHttpUrlOrNull()!!.newBuilder().apply {
        if (!acIp.isNullOrBlank()) setQueryParameter("wlanacip", acIp)
        if (!userIp.isNullOrBlank()) setQueryParameter("wlanuserip", userIp)
    }.build().toString()

internal fun isOfficialCampusPortal(url: String?): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && parsed.host == "drcom.tyut.edu.cn" && parsed.port in setOf(443, 804) &&
        parsed.username.isEmpty() && parsed.password.isEmpty()
}

/** 只在官方 HTTPS 页面填入登录表单；不提交表单、不把密码拼进 URL。 */
internal fun campusPortalAutofillScript(username: String, password: String): String {
    val json = Moshi.Builder().build().adapter(String::class.java)
    val user = json.toJson(username)
    val pass = json.toJson(password)
    return """
        (function() {
          if (location.protocol !== 'https:' || location.hostname !== 'drcom.tyut.edu.cn' ||
              (location.port && location.port !== '443' && location.port !== '804')) return;
          if (window.__campboxFillObserver) window.__campboxFillObserver.disconnect();
          function fillInput(input, value) {
            if (!input || input.dataset.campboxFilled === '1' || input.disabled) return;
            var setter = Object.getOwnPropertyDescriptor(input.ownerDocument.defaultView.HTMLInputElement.prototype, 'value').set;
            setter.call(input, value);
            input.dataset.campboxFilled = '1';
            input.dispatchEvent(new Event('input', {bubbles:true}));
            input.dispatchEvent(new Event('change', {bubbles:true}));
          }
          function fill(doc, depth) {
            var pw = doc.querySelector('input[name="upass"], input[name="user_password"], input[type="password"]');
            if (pw && pw.autocomplete !== 'new-password') {
              var scope = pw.form || doc;
              var account = scope.querySelector('input[name="DDDDD"], input[name="user_account"], input[name="username"], input[autocomplete="username"]') ||
                  scope.querySelector('input[type="text"], input[type="tel"], input[type="email"]');
              if (account) {
                fillInput(account, $user);
                fillInput(pw, $pass);
              }
            }
            if (depth < 2) doc.querySelectorAll('iframe').forEach(function(frame) {
              try {
                var child = frame.contentWindow;
                if (child.location.origin === location.origin && frame.contentDocument) fill(frame.contentDocument, depth + 1);
              } catch (_) {}
            });
          }
          fill(document, 0);
          var observer = new MutationObserver(function() { fill(document, 0); });
          observer.observe(document.documentElement, {childList:true, subtree:true});
          window.__campboxFillObserver = observer;
          setTimeout(function() { observer.disconnect(); }, 20000);
        })();
    """.trimIndent()
}
