# Changelog

按 App 交付迭代记录（非严格 SemVer），日期为本地时区。

## v3 - 同步按钮 + 同步设置（2026-10-23）

- 首页同步状态卡升级：服务器地址、最近同步时间、状态（从未同步/同步中/成功/失败/登录失效）、待同步条数
- 首页新增「立即同步」按钮：绕过退避立即上传 pending 批次，结果 Snackbar 提示（开始/无待同步/需注册/错误）
- 设置页新增「同步设置」卡片：
  - 单向同步说明（只上传、不下载/覆盖本地）
  - 「自动同步」开关（扫描完成是否自动上传）
  - 「仅 Wi-Fi 同步」开关（WorkManager 约束 UNMETERED/CONNECTED，切换后立即重排唯一任务）
  - 待同步数/最近同步时间/状态行 + 「立即同步」按钮
- 同步状态可见：last_sync_status=syncing（进行中）-> success/failed/auth_error
- data：SettingsSnapshot.autoSync/syncWifiOnly、SettingsStore 新 key、SyncTrigger 增加 wifiOnly 参数、
  SyncScheduler.reapplyConstraints、ScanRepository.requestManualSync() 与 ManualSyncResult
- 测试：+12（ManualSync 6、SyncScheduling 6）-> 81/81

## v2 - 实机识别率优化（2026-10-23）

- 分析/预览分辨率 1280x720 -> 1920x1080（16:9，CLOSEST_LOWER_THEN_HIGHER 兜底）
- 双 pass 解码：pass1 规格参数；pass1 无结果才走宽松 pass2（minLineCount=2、tryDenoise、tryInvert、GlobalHistogram）
- 确认逻辑容忍丢帧：500ms 内累计 2 次同文本证据即确认（不再"漏一帧清零"）
- 点击对焦 + 双击 1x/2x 切换 + 双指缩放；扫描页倍率显示与手势提示
- 调试面板新增解码命中率/已解码帧/最近解码 pass/分析分辨率
- 测试：+3（TrackManager 8->11）-> 69/69

## v1 - 初始版本（2026-10-23）

- 相机实时多 Code128 识别（CameraX + zxing-cpp，本地解码、无 ML Kit/闭源 SDK）
- 绿/灰锚点：绿=首次入库，灰=已扫过；scan_items.code UNIQUE + 内存去重
- 帧间跟踪（IoU/中心距匹配、2 帧确认、500ms 过期）；坐标映射 4 旋转 x FILL_CENTER
- Room（sessions/scan_items/outbox）+ DataStore + Android Keystore token 加密
- WorkManager 唯一任务同步（退避 30s->2min->10min->30min；401 不重试、413 拆批、422 标记失败、5xx 重试）
- Compose UI：Home / Scan / Records / Settings / Debug（含诊断 zip + FileProvider 分享）
- 工具：tools/dev-server 契约复刻（/admin、CSV）、tools/verify/check_server.py、CI workflow
- 测试：66/66，lint 0 errors；APK 可直接安装（debug 签名）

## 2026-10-23 开源准备补充

- MIT LICENSE、THIRD-PARTY-NOTICES.md、.gitattributes
- docs/REUSING.md（复用指南）、docs/WINDOWS_NOTES.md（Windows 中文路径说明）
- docs/API.md 示例 token 脱敏（契约接口未变）
- gradlew 置为可执行（git index 100755）；CI 增加 SDK 安装与 chmod +x gradlew
