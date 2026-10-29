# Windows 构建说明（中文 / 非 ASCII 路径）

本项目在 Linux CI（`.github/workflows/android.yml`）上使用默认配置即可构建。
如果你的 Windows 工作区路径包含**中文或其他非 ASCII 字符**（例如 `C:\Users\me\桌面\条码识别项目\MultiScan\app`），
JDK 17 + Gradle 8.14.x 组合有两个已知问题，需要在本机做一次性覆盖：

## 1. Gradle 单元测试全部 `ClassNotFoundException`

**现象**：`testDebugUnitTest` 里所有测试类都报
`initializationError ... ClassNotFoundException`，但 `.class` 文件确实存在。

**原因**：测试运行时 classpath 很长，Gradle 把它写进 `@argfile`；JDK 17 的 Windows
启动器用系统 ANSI 编码（如 GBK/CP936）解析 `@argfile`，而 Gradle 守护进程按
`file.encoding=UTF-8` 写文件 → 中文路径变成乱码 → 测试类加载失败。

**修复**：在本机用户级 `%USERPROFILE%\.gradle\gradle.properties`（注意不是仓库里的文件）加入：

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=GBK
```

这样 argfile 用 GBK 写出、GBK 读回，可正确往返；源码仍按 UTF-8 编译（`build.gradle.kts`
里已为 `JavaCompile` 设置 UTF-8）。

## 2. AGP 拒绝非 ASCII 项目路径

**现象**：配置阶段报 `Your project path contains non-ASCII characters`。

**修复**：同样在用户级 `~/.gradle/gradle.properties` 加入：

```properties
android.overridePathCheck=true
```

## 其他

- 放到纯 ASCII 路径（例如 `C:\work\multiscan`）下构建则不需要以上任何覆盖。
- `app/` 下的 `gradlew(*.bat)` 由 `.gitattributes` 保持 LF/可执行位；Windows 下直接用
  `.\gradlew.bat` 即可。
