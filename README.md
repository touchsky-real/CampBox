# LightLife

LightLife 是面向太原理工大学校园生活场景的 Android 应用，集成胖乖生活开水、每日签到、校园网登录、余额与订单查询、快捷入口等功能。项目使用 Kotlin、Jetpack Compose 与 Material 3 开发。

> 本项目为非官方第三方工具，仅适用于太原理工大学当前接入的相关校园服务。校园平台、接口或认证规则变化后，部分功能可能失效。

## 主要功能

- 胖乖生活手机号验证码或 Token 登录
- 历史设备一键开水、积分抵扣和订单记录
- 每日自动签到及手动签到
- 积分余额、可抵扣金额和使用统计
- 太原理工大学校园网快捷登录
- 趣智校园淋浴设备支持
- 自定义网页与设备桌面快捷方式
- 深色模式、主题配色和触感反馈

## 下载与安装

前往项目的 GitHub Releases 页面下载最新版 APK：

https://github.com/touchsky-real/light-life/releases

Android 8.0（API 26）及以上系统可安装，当前仅构建 `arm64-v8a` 架构版本。升级安装必须使用与旧版本相同的签名。

## 基本使用

1. 打开 App，使用手机号验证码或 Token 登录。
2. 根据系统提示授予网络、蓝牙或定位权限。
3. 在首页选择需要使用的开水、校园网、淋浴或快捷入口功能。
4. 自动签到可在设置中开启或关闭。

## 本地开发

环境要求：

- Android Studio 或 JDK 17
- Android SDK 35
- Gradle Wrapper（项目已包含）

常用命令：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

默认本地 Release 构建沿用项目现有调试签名，便于兼容历史安装。正式发布建议通过 GitHub Actions 注入独立 Keystore。

## 自动发布

推送格式为 `v*` 的 Git Tag 后，`.github/workflows/release-apk.yml` 会自动：

1. 配置 JDK 17 和 Gradle 缓存。
2. 从 GitHub Secrets 恢复 Release Keystore（未配置时回退仓库内置调试签名，发布不中断）。
3. 使用 Tag 作为 `versionName` 编译 Release APK，`versionCode` 由 Tag 版本号计算。
4. 自动生成版本更新说明。
5. 创建 GitHub Release 并上传 APK。

### 配置 GitHub Secrets

先在 PowerShell 中将 Keystore 转为 Base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks")) | Set-Clipboard
```

进入 GitHub 仓库的 `Settings > Secrets and variables > Actions`，添加以下 Repository secrets：

- `KEYSTORE_BASE64`：Keystore 文件的 Base64 内容
- `KEYSTORE_PASSWORD`：Keystore 密码
- `KEY_ALIAS`：签名密钥别名
- `KEY_PASSWORD`：签名密钥密码

### 发布新版本

确保版本提交已推送到 `main`，再创建并推送 Tag：

```bash
git push origin main
git tag v3.1.0
git push origin v3.1.0
```

`versionName` 取 Tag 去掉 `v` 后的内容，`versionCode` 按「主版本×10000 + 次版本×100 + 补丁版本」计算。

## 项目结构

- `app/src/main/java/com/inonvation/lightlife/data`：接口、数据模型、存储与任务执行
- `app/src/main/java/com/inonvation/lightlife/ui`：状态管理和 Compose 界面
- `.github/workflows`：持续集成与自动发布
- `docs`：协议和设计说明

## 免责声明

本项目仅供学习、研究和个人测试使用，与太原理工大学、胖乖生活及其他服务提供方无隶属或授权关系。自动签到、校园网络认证及设备控制功能可能受到服务条款、接口调整和校园管理规定限制。使用者应自行评估并承担账号、设备、数据和服务可用性风险。

请勿提交个人 Token、账号密码、抓包文件或签名密钥。发现敏感信息意外进入 Git 历史时，应立即吊销并更换相关凭据。

## 许可证

项目许可证见 `LICENSE`。
