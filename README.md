# LightLife

LightLife 是一款校园生活助手 App，集成**胖乖生活**开水、签到与**趣智校园**淋浴控制等功能。基于 [wzs0512/qiekj-android](https://github.com/wzs0512/qiekj-android) 重构，Jetpack Compose + Material3，安装包约 2MB。

> **🏫 适用学校：太原理工大学**
>
> 本 App 对接的是部署在太原理工大学的胖乖生活饮水机与趣智校园热水系统。其他学校若使用相同平台，理论上也可用，但未经验证。

## 下载

前往 [Releases](https://github.com/touchsky-real/light-life/releases) 下载最新 APK。

## 功能

- **登录**：手机验证码登录、Token 登录
- **开水**：历史设备一键开水，支持积分抵扣，订单详情自动保存
- **淋浴**：趣智校园热水器蓝牙连接、一键洗澡、钱包余额查询
- **每日签到**：打开 App 自动签到（可在设置中关闭），也可手动签到
- **余额查询**：实时查询积分余额、可抵扣金额、剩余小票
- **数据统计**：累计开水次数、订单记录
- **快捷方式**：自定义快捷链接、添加到桌面、设备快捷方式
- **主题与触感**：深色模式、主题配色、触感反馈

## 使用

1. 打开 App，手机号验证码登录，或粘贴 Token 登录
2. 首页选择饮水机设备，点击「开水」
3. 洗澡功能在趣智校园卡片中登录后使用
4. 每日签到在打开 App 时自动执行（可在设置中关闭），也可手动点击签到

## 自动发布（CI/CD）

通过 GitHub Actions 工作流，每次推送 Git Tag（如 `v3.1.0`）时，自动在云端完成 Gradle 编译并挂载 APK 至 Release。

### 1. 配置 Keystore 密钥（GitHub Secrets）

将本地 keystore 转为 Base64 并复制到剪贴板（PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("你的密钥路径.jks")) | Set-Clipboard
```

进入 GitHub 仓库 **Settings → Secrets and variables → Actions**，添加以下 Repository secrets：

| Secret 名称 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上一步复制的 Base64 字符串 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

### 2. 发布新版本

```bash
git tag v3.1.0
git push origin v3.1.0
```

推送 Tag 后自动触发 [`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml)：云端编译 → 签名 → 创建 GitHub Release 并挂载 APK。若未配置上述 Secrets，工作流自动回退到仓库内置 keystore 签名，发布流程不会中断（但与已配置正式签名的版本不互通升级）。

## 免责声明

本项目为个人兴趣开发，仅供学习和测试使用。自动签到、开水与淋浴功能模拟正常用户操作流程，可能违反相关平台服务条款。请自行承担账号、设备、接口变更和平台规则风险，可能面临积分清零、无法使用积分甚至封号的风险。

## 开发者说明

v3.0.0 删除了刷分、备份、日志、喝水提醒等模块。完整旧代码保留在 git 历史 `v2.0.0` tag，如需恢复可执行：

```bash
git checkout v2.0.0 -- <文件路径>
```

本地构建：`gradlew :app:assembleDebug`（Debug/Release 使用 `app/debug.keystore` 签名，alias `androiddebugkey`，password `android`，勿删除或重新生成，否则存量安装需卸载重装）。
