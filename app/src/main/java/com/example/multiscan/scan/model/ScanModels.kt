package com.example.multiscan.scan.model

/**
 * 扫描模块（`scan`）对 UI 暴露的数据模型 —— 严格对应 `docs/CONTRACT.md` §5。
 *
 * 坐标约定（全模块统一）：
 *  - [PointF] / `ScanAnchor.quad` / `Detection.cornersInBufferSpace` 一律是
 *    **ImageProxy 原始 buffer 像素坐标（未旋转）**，尺寸 = `ImageProxy.width/height`。
 *  - zxing-cpp 返回的 `Result.position` 是「旋转后的 crop 空间」坐标（见
 *    `.coord/research/zxing-api.md` §9），由 `scan.decode.BarcodeDecoder` 负责逆旋转回
 *    buffer 空间；`scan.mapper.CoordinateMapper` 再统一做「正旋转 + FILL_CENTER 缩放」，
 *    两处只各自旋转一次，避免重复变换导致锚点错位。
 */

/** 二维坐标点（Float 精度，像素）。 */
data class PointF(val x: Float, val y: Float)

/**
 * 一帧分析图的几何信息。
 *
 * @param imageWidth      ImageProxy buffer 宽度（未旋转，即传感器输出宽）
 * @param imageHeight     ImageProxy buffer 高度（未旋转）
 * @param rotationDegrees CameraX `ImageInfo.rotationDegrees`，取值 0/90/180/270
 */
data class FrameGeometry(
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
)

/**
 * 一个已确认的叠加锚点（只包含 confirmed track；绿=新入库，灰=已存在）。
 *
 * @param quad  zxing 提供的 4 个角点，顺序 TL,TR,BR,BL，**buffer 未旋转坐标**
 * @param isNew true=绿（本次会话首次扫到并入库），false=灰（之前扫过）
 */
data class ScanAnchor(
    val text: String,
    val quad: List<PointF>,
    val isNew: Boolean,
)

/** 扫描页 UI 状态（由 `ScanController` 节流到 10–15Hz 更新）。 */
data class ScanUiState(
    val sessionId: String? = null,
    /** 只含 confirmed；绿=新入库，灰=已存在。 */
    val anchors: List<ScanAnchor> = emptyList(),
    /** 本次会话确认过的不同码数量。 */
    val totalDetected: Int = 0,
    /** 本次会话新入库数量。 */
    val newDetected: Int = 0,
    val torchOn: Boolean = false,
    val torchAvailable: Boolean = false,
    /** 最近一帧的分析图几何信息。 */
    val geometry: FrameGeometry? = null,
    val cameraReady: Boolean = false,
    /** 相机/会话初始化失败的简短错误文案，null 表示正常。 */
    val error: String? = null,
)

/** 调试面板实时统计（同样节流更新）。 */
data class ScanDebugStats(
    val fps: Float = 0f,
    val lastDecodeMs: Long = 0,
    val avgDecodeMs: Long = 0,
    val activeTracks: Int = 0,
    val droppedFrames: Long = 0,
    val analyzedFrames: Long = 0,
    val cameraResolution: String = "",
)

/** `finishSession()` 返回的会话汇总。 */
data class ScanSessionSummary(
    val sessionId: String,
    val total: Int,
    val newCount: Int,
)

/**
 * 单帧解码结果（scan 模块内部类型，不对 UI 暴露）。
 *
 * @param cornersInBufferSpace 4 个角点 TL,TR,BR,BL，已逆旋转回 buffer 空间
 */
internal data class Detection(
    val text: String,
    val format: String,
    val symbologyIdentifier: String?,
    val lineCount: Int,
    val cornersInBufferSpace: List<PointF>,
)
