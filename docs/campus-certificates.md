# 校园网证书兼容

2026-10-05 检查 `drcom.tyut.edu.cn:804`：证书为太原理工大学 `*.tyut.edu.cn`，签发者为 GlobalSign RSA OV SSL CA 2018，上级为 GlobalSign Root CA R3。当前站点证书有效期为 2026-01-30 至 2027-03-03。

为兼容设备缺少根证书或网关未发送完整中间证书的情况，只在 `drcom.tyut.edu.cn` 域名下补充以下 CA，保留系统信任、域名验证和 HTTPS：

- Root R3：https://secure.globalsign.com/cacert/root-r3.crt
  - SHA-256：CBB522D7B7F127AD6A0113865BDF1CD4102E7D0759AF635A7CF4720DC963C53B
  - 有效期至 2029-03-18。
- OV 2018：https://secure.globalsign.com/cacert/gsrsaovsslca2018.crt
  - 使用上面的 Root R3 验证通过，有效期至 2028-11-21。

从官方 HTTPS 地址下载 DER 后转为 PEM，保存于 `app/src/main/res/raw`。后续学校更换签发链时需重新检查，不能通过关闭证书校验处理。

HTTP 放行仅用于指定的校园网连通性探针和网关发现；账号认证仍使用 HTTPS。

## 官方网页备用入口

默认使用用户提供的地址：
`https://drcom.tyut.edu.cn/?wlanacip=219.226.127.249&url=http://www.msftconnecttest.com/redirect`

如果自动认证阶段发现当前接入点的 AC/IP 参数，优先带入发现值。手动连接失败后打开备用页；启动时的静默自动连接失败不弹出网页，用户也可直接点击「官方网页登录」。

WebView 在 `:campus_portal` 独立进程内绑定 Wi-Fi，关闭时恢复该进程先前的绑定，主进程继续遵循系统路由。账号密码仅通过显式内部 Intent 传递，限定在 `drcom.tyut.edu.cn` 的 HTTPS 443/804 页面中填写，不拼进 URL、不自动提交、不绕过证书错误。填写逻辑等待动态表单出现，限制同源 iframe；返回 App 后只探测 Wi-Fi 连通性，不重复认证。

自动填充依赖官方表单结构，仍需校园网真机验证；页面结构变更时可在官方页手动填写。

官方 `a41.js` 的 `getTermType()` 根据 Android/Mobile 标识选择手机页面。备用 WebView 使用本机 Chromium 的手机 UA，并启用宽视口、整页适配和双指缩放，兼容官方固定 640px 的 viewport；不猜测或替换所谓「手机版」网址。
