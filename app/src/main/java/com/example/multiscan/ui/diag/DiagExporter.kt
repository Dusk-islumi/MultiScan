package com.example.multiscan.ui.diag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.multiscan.AppContainer
import com.example.multiscan.scan.model.FrameGeometry
import com.example.multiscan.scan.model.ScanDebugStats
import com.example.multiscan.ui.debug.ScanDebugBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断导出：在 `cacheDir/diagnostics/` 生成 zip（不需要任何存储权限），再用 FileProvider 分享。
 *
 * zip 内容（契约 §7）：
 *  - `logs.txt`     ：`DiagLog.snapshot()` 最近 200 条（已由 DiagLog 做 token 兜底打码）
 *  - `device.json`  ：型号/厂商/SDK/系统版本/ABI/App 版本
 *  - `params.json`  ：相机分辨率（来自 ScanDebugBus 的最近一帧几何）、reader/tracker 固定参数、会话统计快照
 *
 * 即使从未开始过扫描（ScanDebugBus 为空值）也能正常导出，不会抛异常。
 */
object DiagExporter {

    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val ZIP_MIME_TYPE = "application/zip"
    private const val SEPARATOR = "（暂无日志）"

    /** 生成诊断 zip；IO 较重，调用方需放到后台线程（DebugViewModel 用 Dispatchers.IO 包裹）。 */
    fun export(context: Context, container: AppContainer): File {
        val appContext = context.applicationContext
        val directory = File(appContext.cacheDir, DIAGNOSTICS_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建诊断目录")
        }
        // 只保留最新一份，避免 cacheDir 无限增长。
        directory.listFiles()?.forEach { old -> old.delete() }

        val zipFile = File(directory, "multiscan-diagnostics-${timestamp()}.zip")
        val logs = container.diagLog.snapshot()
        val logsText = if (logs.isEmpty()) SEPARATOR else logs.joinToString(separator = "\n")
        val stats = ScanDebugBus.stats.value
        val geometry = ScanDebugBus.geometry.value
        val appVersion = resolveAppVersion(appContext)

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            writeEntry(zip, "logs.txt", logsText)
            writeEntry(zip, "device.json", buildDeviceJson(appContext, appVersion))
            writeEntry(zip, "params.json", buildParamsJson(geometry, stats))
        }
        return zipFile
    }

    /**
     * 分享诊断 zip（`ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`）。
     *
     * @return null = 已成功拉起分享；非空 = 失败原因（中文），调用方展示，不崩溃。
     */
    fun share(context: Context, file: File): String? = runCatching {
        val authority = context.packageName + FILE_PROVIDER_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = ZIP_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "多码扫描诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "分享诊断包").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
        null
    }.getOrElse { throwable -> throwable.message ?: "分享失败" }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildDeviceJson(context: Context, appVersion: String): String {
        val json = JSONObject()
        json.put("model", Build.MODEL)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("brand", Build.BRAND)
        json.put("device", Build.DEVICE)
        json.put("sdk", Build.VERSION.SDK_INT)
        json.put("release", Build.VERSION.RELEASE)
        json.put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        json.put("appVersion", appVersion)
        json.put("packageName", context.packageName)
        json.put("generatedAt", Instant.now().toString())
        return json.toString(2)
    }

    private fun buildParamsJson(geometry: FrameGeometry?, stats: ScanDebugStats): String {
        val root = JSONObject()
        root.put(
            "camera",
            JSONObject().apply {
                put("analyzedWidth", geometry?.imageWidth ?: 0)
                put("analyzedHeight", geometry?.imageHeight ?: 0)
                put("rotationDegrees", geometry?.rotationDegrees ?: 0)
                put("requestedAnalysisSize", "1920x1080")
                put("resolutionFallbackRule", "CLOSEST_LOWER_THEN_HIGHER")
                put("outputImageFormat", "YUV_420_888")
                put("backpressureStrategy", "KEEP_ONLY_LATEST")
                put("cameraResolutionLabel", stats.cameraResolution)
                put("analysisResolutionLabel", stats.analysisResolution)
            },
        )
        root.put(
            "readerOptions",
            JSONObject().apply {
                put("library", "io.github.zxing-cpp:android:3.1.1")
                put("formats", JSONArray(listOf("Code128")))
                put(
                    "pass1",
                    JSONObject().apply {
                        put("minLineCount", 3)
                        put("tryHarder", true)
                        put("tryRotate", true)
                        put("tryInvert", false)
                        put("tryDownscale", false)
                        put("binarizer", "LOCAL_AVERAGE")
                    },
                )
                put(
                    "pass2",
                    JSONObject().apply {
                        put("minLineCount", 2)
                        put("tryHarder", true)
                        put("tryRotate", true)
                        put("tryInvert", true)
                        put("tryDenoise", true)
                        put("binarizer", "GLOBAL_HISTOGRAM")
                    },
                )
                put("pass2Trigger", "仅当 pass1 无有效结果")
                put("note", "契约 §12.2 固定配置；reader 实例只创建一次且运行期不改 options")
            },
        )
        root.put(
            "tracker",
            JSONObject().apply {
                put("iouThreshold", 0.3)
                put("confirmDetections", 2)
                put("missedFramesDoNotReset", true)
                put("maxMissMs", 500)
                put("note", "契约 §12.3 固定配置（累计 2 次同文本证据即确认、漏帧不清零、TTL 500ms）")
            },
        )
        root.put(
            "sessionStats",
            JSONObject().apply {
                put("fps", stats.fps.toDouble())
                put("lastDecodeMs", stats.lastDecodeMs)
                put("avgDecodeMs", stats.avgDecodeMs)
                put("activeTracks", stats.activeTracks)
                put("droppedFrames", stats.droppedFrames)
                put("analyzedFrames", stats.analyzedFrames)
                put("decodedFrames", stats.decodedFrames)
                put("decodeHitRate", stats.decodeHitRate.toDouble())
                put("analysisResolution", stats.analysisResolution)
                put("lastDecodePass", stats.lastDecodePass)
                put("cameraResolution", stats.cameraResolution)
            },
        )
        root.put("generatedAt", Instant.now().toString())
        return root.toString(2)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    @Suppress("DEPRECATION")
    private fun resolveAppVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")
}
