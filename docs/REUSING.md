# 复用指南（把本项目用作开源/内部模板）

本项目目前是一个**单模块 Android App**，可以直接作为同类项目（"多码扫描 + 本地上传"）的起点。
本文说明复用时的关键改动点、注意事项与工作量估计。

## 1. 直接复用为另一个 App

改这些地方即可（按重要性排序）：

1. **包名 / 应用标识**：`com.example.multiscan` 出现在约 70 个文件与目录路径中。
   用 IDE 的 Rename Package（或 `git grep -l com.example.multiscan | xargs sed -i`）整体替换，
   并同步修改 `app/build.gradle.kts` 的 `namespace` / `applicationId`、
   `AndroidManifest.xml` 的 `android:name`、`res/xml/file_paths.xml` 引用（FileProvider authority 使用 `${applicationId}`，通常无需改）。
2. **应用名 / 文案**：`app/src/main/res/values/strings.xml`（中文 UI）、`values-night/colors.xml`、`theme`。
   若要国际化，新增 `values-en/strings.xml` 并逐条翻译（当前只有中文）。
3. **服务端地址默认值**：`SettingsSnapshot.DEFAULT_SERVER_URL`（当前 `http://10.0.2.2:8000`，模拟器用）。
4. **服务端契约**：`data/remote/MultiScanApi.kt`、DTO、`SyncEngine` 状态机、`docs/API.md`。
   换后端时必须同步改 DTO 字段名与错误码处理（401/413/422/5xx 的语义在 `SyncEngine` 中硬编码）。
5. **码制**：当前固定 `Format.CODE_128`（`BarcodeDecoder` 两处 + `FORMAT_CODE128` 常量 + Room/上传默认值 + 诊断 JSON）。
   支持 Code 39/EAN/QR 等需修改 `Options.formats` 并调整 UI 文案/服务端 `format` 字段。

## 2. 只复用扫描能力（嵌入到已有 App）

复制以下内容：

- `app/src/main/java/com/example/multiscan/scan/**`（扫描、解码、跟踪、坐标映射、去重索引）
- `app/src/main/java/com/example/multiscan/core/**`（`Utc`、`DiagLog`、`ScanSessionStore` 契约、`Code128CValidator`）

然后：

1. 在宿主 App 实现 `ScanSessionStore`（`startSession` / `registerCode` / `finishSession`），接到你自己的存储；
2. 加入依赖：CameraX 1.6.x（core/camera2/lifecycle/view）+ `io.github.zxing-cpp:android:3.1.1`；
3. 用 `ScanController(context, store, diagLog)` + Compose `PreviewView` + `Canvas` 叠加层（参考 `ui/scan/ScanScreen.kt`）；
4. 相机权限、`FILL_CENTER`、分析流 1920×1080（16:9）等设置保持默认即可；
5. 若不需要 Compose，可只取 `scan/**` 并在 View 体系里自行绑定 `PreviewView`。

工作量：约半天到一天（含真机调通）。

## 3. 抽成独立 Gradle 模块 / 对外 SDK

需要做的事：

- 拆模块：`:core`（纯 Kotlin）、`:scan`（依赖 CameraX + zxing-cpp）、`:data`（Room/Retrofit/WorkManager）、`:app`（Compose UI）；
- `scan` 暴露稳定公开 API（目前 `TrackManager`/`BarcodeDecoder` 为 `internal`，只有 `ScanController` + `model` 对外）；
- 把码制、分析分辨率、确认帧数/超时、pass2 参数做成可配置项（当前部分写死）；
- 移除 UI 对 `AppContainer` 的具体依赖（面向接口的 ViewModel 层）；
- 版本号/变更日志/发布脚本。

工作量：约 1–2 周。

## 4. 复用时不要踩的坑

1. **不要把 `local.properties`、`*.keystore`、`build/` 提交**（`.gitignore` 已覆盖）。
2. **`gradlew` 必须可执行**：`.gitattributes` 已保证 LF；git index 权限为 `100755`（已设置）。Windows 上克隆后若丢失可执行位，
   在 Linux/macOS 执行 `chmod +x app/gradlew` 或 `git update-index --chmod=+x app/gradlew`。
3. **AGP / compileSdk 36 的版本钉死**：Compose 1.12+ / core-ktx 1.19+ / lifecycle 2.11+ / OkHttp 5.5+ 需要 compileSdk 37；
   升级 compileSdk 后可在 `app/gradle/libs.versions.toml` 放开。
4. **中文/非 ASCII Windows 路径**：见 [WINDOWS_NOTES.md](WINDOWS_NOTES.md)。
5. **明文 HTTP**：`AndroidManifest.xml` 有 `usesCleartextTraffic="true"`（因为契约是 `http://<host>:8000`）。
   生产环境建议改 HTTPS，并移除该属性或改用 `networkSecurityConfig` 白名单。
6. **token 安全**：token 只以 Keystore AES/GCM 密文落盘；日志有二次掩码。换存储实现时不要破坏这两点。
7. **单向同步语义**：本 App 只上传、不拉取；若你的业务需要"服务器→手机"下发，需要另行实现并明确冲突策略，
   不要偷偷修改 `SyncEngine` 的方向。

## 5. 复用清单（checklist）

- [ ] 改包名 + applicationId
- [ ] 改应用名/主题/图标/中文文案（或加多语言）
- [ ] 改默认服务器地址与 `docs/API.md` 契约（若后端不同）
- [ ] 确认码制（Code128 only？还是多码制）
- [ ] 跑 `cd app && ./gradlew assembleDebug testDebugUnitTest lintDebug`
- [ ] 真机验证：相机权限、扫描出框、绿/灰、杀进程恢复、同步按钮、仅 Wi-Fi 约束
- [ ] 补 LICENSE / NOTICE / 隐私说明（若公开发布）
