# LightLife 速查

面向太原理工大学的校园生活助手（胖乖生活开水 + 校园网认证 + 趣智校园淋浴），基于 [wzs0512/qiekj-android](https://github.com/wzs0512/qiekj-android) 重构。Jetpack Compose + Material3 UI，OkHttp 网络层，R8 全模式压缩。

## 首要原则

**任何发布、构建、提交、推送前，先向用户阐述方案，等用户明确同意。** 不自作主张。

## 构建命令

| 命令 | 用途 |
|------|------|
| `gradlew :app:compileDebugKotlin` | 快速编译检查（改代码后必跑） |
| `gradlew :app:assembleDebug` | Debug APK |
| `gradlew :app:assembleRelease` | Release APK（R8 优化） |
| `gradlew :app:installDebug` | 安装到手机 |
| `gradlew :app:installRelease` | 安装 Release 到手机 |
| `gradlew :app:testDebugUnitTest` | 单元测试 |
| `gradlew :app:lintDebug` | Lint 检查 |

**启动：** `adb shell monkey -p com.inonvation.campbox -c android.intent.category.LAUNCHER 1`

## 源码架构

**包名：** `com.inonvation.campbox`
**源码路径：** `app/src/main/java/com/inonvation/lightlife/`

| 路径 | 职责 |
|------|------|
| `MainActivity.kt` | 应用入口，组装依赖，首次定位权限申请 |
| `ui/AppUiState.kt` | 所有 UI 状态类型定义 |
| `ui/AppViewModel.kt` | 协调层，积分任务/校园网认证/解锁流程 |
| `ui/auth/AuthController.kt` | 登录/Token 管理 |
| `ui/qzxy/QzxyController.kt` | 趣智校园淋浴流程控制 |
| `ui/ShortcutUtils.kt` | 桌面快捷方式创建 |
| `ui/screen/` | 各页面组件，每个页面一个文件 |
| `ui/screen/Components.kt` | 跨页面共享组件 |
| `ui/screen/UnlockFlowCards.kt` | 解锁流程 4 种状态卡片 |
| `ui/theme/AppStyles.kt` | UI 间距/颜色常量 |
| `data/` | API 接口、Repository、Store、Model |
| `data/CampusNetRunner.kt` | 校园网 Dr.COM ePortal 自动认证 |
| `data/PointsTaskRunner.kt` | 积分任务执行核心（签到/首页浏览/任务列表） |
| `data/DeviceIdProvider.kt` | 全局稳定设备标识（模拟 OAID，设备风控用） |
| `data/WaterLocationProvider.kt` | 开水位置风控定位 |
| `data/qzxy/` | 趣智校园 API、蓝牙扫描、密码工具 |

## 代码规范

- 不要在 Compose 函数外使用 `remember`
- UI 间距/颜色优先用 `AppStyles.kt` 常量
- Kotlin 文件确保 UTF-8 编码
- 新增页面或功能按上述结构放置
- `UserPrefsStore` 的 SharedPreferences 文件名沿用历史名称 `points_task_state`，勿改（存量用户设置）

## 签名

Debug 和本地 Release 同用 `app/debug.keystore`（alias `androiddebugkey`，password `android`）。**不要删除或重新生成**，否则存量安装需卸载重装。

CI 配置了 `KEYSTORE_BASE64` 等 4 个 GitHub Secrets 时，Release 构建自动改用正式签名（见 `app/build.gradle.kts` 中 `ciRelease` 配置）。v3.1.0 起线上 APK 为正式签名。

## Commit 规范

格式：`<type>: <中文描述>`，如 `fix: 修复登录页面空指针崩溃`

| 前缀 | 出现在 Release Notes |
|------|:---:|
| `feat:` / `fix:` / `perf:` / `refactor:` | ✅ |
| `chore:` / `docs:` / `ci:` / `test:` / `build:` / `style:` / `revert:` | ❌ |

- 一个 commit 只做一件事，多个修复拆成多个 commit
- 每个 commit 必须编译通过
- 标题用非技术人员能看懂的语言，专业术语放正文

## 版本与发布

- 版本号来自 Git Tag：CI 用 `-PbuildVersionName` / `-PbuildVersionCode` 注入（versionCode = 主版本×10000 + 次版本×100 + 补丁版本）
- 本地构建默认 `versionName = 3.1.0` / `versionCode = 12`
- 发布：更新 build.gradle.kts 默认版本 → commit → `git push origin main` → `git tag vX.Y.Z` → `git push origin v*`（或 `git push --tags`）
- 发布工作流（`.github/workflows/release-apk.yml`）：推送 `v*` tag 自动云端编译、签名并挂载 APK 到 Release
- CI（`.github/workflows/ci.yml`）：PR 到 main 时运行 lint → test → assemble

## 注意事项

- `ApiConfig` 中 `ANDROID_SECRET` / `ALIPAY_SECRET` 是接口签名密钥
- 任务系接口（`task/*`）做设备维度风控：所有请求统一带 `DeviceIdProvider` 生成的稳定 deviceId，登录也带（否则任务接口报「未登录」）；积分任务 UA 必须用 `ApiConfig.POINTS_USER_AGENT`（QEUser 格式）
- 不要上传个人 Token、抓包文件、签名密钥到公开仓库
- Lint 禁用了 `NullSafeMutableLiveData`、`RememberInComposition`、`FrequentlyChangingValue`、`AutoboxingStateCreation`
