# CampBox

面向太原理工大学校园生活的 Android 工具，集成喝水、洗澡、校园网认证和常用快捷方式。使用 Kotlin、Jetpack Compose 和 Material 3 开发。

[下载最新版](https://github.com/touchsky-real/CampBox/releases/latest) · [反馈问题](https://github.com/touchsky-real/CampBox/issues)

支持 Android 8.0 及以上系统，当前构建面向 `arm64-v8a` 设备。

## 主要功能

| 功能 | 支持内容 |
| --- | --- |
| 喝水 · 胖乖生活 | 手机号验证码或 token 登录；扫码选择饮水机、历史设备开水、积分抵扣、余额和本地订单记录 |
| 每日签到 | 手动签到、启动时自动签到 |
| 洗澡 · 趣智校园 | 独立账号验证码或密码登录、附近设备扫描、手动输入 MAC 绑定、联网或蓝牙设备开关阀、使用结算、钱包余额查询及使用码管理 |
| 校园网 | 太原理工大学 Dr.COM 认证、启动时自动连接、官方网页登录备用入口 |
| 快捷方式与外观 | 自定义链接、排序和图标、添加桌面快捷方式；深浅色模式、主题配色、触感反馈、检查更新 |

## 使用

**喝水**：登录胖乖生活账号，在首页选择历史设备，或点击「扫码喝水」扫描饮水机上的二维码。确认设备后再点「开水」。扫码本身不会启动设备；实际开始、暂停和结束取水以机身按钮及设备提示为准。

开水卡会显示使用状态和结算结果。出现「状态待确认」时，应检查设备和官方账单，不能当作已经停止出水。当前扫码开水支持普通联网饮水机，胖乖蓝牙饮水机请使用官方 App。

**洗澡**：单独登录趣智校园账号，默认短信验证码登录，也可切换密码登录；未注册的手机号经验证码验证后自动注册。扫描附近设备或输入 MAC 地址进行绑定，再使用开关阀功能。已接入联网和蓝牙两类控制流程，具体可用性取决于学校及设备型号。

**趣智钱包与使用码**：在首页洗澡区或设置的趣智校园分组打开「查询余额」「使用码」，不需要先绑定设备。钱包只显示趣智账户余额。使用码支持查询、开关与领取；生成候选码后须在 3 分钟内点「确定领取」才会替换原码。

**校园网**：先在系统设置中连接校园 Wi-Fi，再填写学号 / 上网账号和密码，点击「连接」。连接失败时可使用「官方网页登录」；网页会尝试填入已保存的账号，需要自行确认并提交登录。

**签到**：在设置中开启「启动时自动签到」，打开 App 时执行当日签到。也可以在首页点击「签到」手动执行。

胖乖生活、趣智校园和校园网账号相互独立。相机、定位和蓝牙权限按对应功能的提示授予。

## 本地开发

需要 JDK 17、Android SDK 35，使用仓库自带的 Gradle Wrapper：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

APK 输出到 `app/build/outputs/apk/`。升级安装需沿用原有签名，不要删除或重新生成 `app/debug.keystore`。

源码位于 `app/src/main/java/com/inonvation/campbox/`，其中 `data/` 负责接口与存储，`ui/` 负责页面和状态管理，两个目录下的 `qzxy/` 为趣智校园模块。测试位于 `app/src/test/`，协议和适配说明见 [docs](docs/)。

推送 `v*` 标签后，GitHub Actions 会自动构建并发布 Release APK；版本名取自标签。流程见 [release-apk.yml](.github/workflows/release-apk.yml)。

## 项目说明

本项目是非官方第三方工具，与学校及相关服务提供方无隶属关系。校园网配置面向太原理工大学，其他平台功能的兼容性受学校、设备及接口变化影响。

项目基于 [Inonvation/light-life](https://github.com/Inonvation/light-life) 与 [wzs0512/qiekj-android](https://github.com/wzs0512/qiekj-android)，趣智校园实现参考 [yehu-imei/linyu](https://github.com/yehu-imei/linyu)。采用 [MIT 许可证](LICENSE)。请勿提交个人账号、密码、token 或抓包文件。
