package com.example.multiscan.scan.track

import com.example.multiscan.scan.model.Detection
import com.example.multiscan.scan.model.PointF

/** 轴对齐包围盒（buffer 像素坐标，由 4 个角点计算）。 */
internal data class TrackBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    /** 交并比；退化框（面积 0）返回 0。 */
    fun iou(other: TrackBounds): Float {
        val interLeft = maxOf(left, other.left)
        val interTop = maxOf(top, other.top)
        val interRight = minOf(right, other.right)
        val interBottom = minOf(bottom, other.bottom)
        val interWidth = (interRight - interLeft).coerceAtLeast(0f)
        val interHeight = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = interWidth * interHeight
        val union = width * height + other.width * other.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        fun of(quad: List<PointF>): TrackBounds {
            if (quad.isEmpty()) return TrackBounds(0f, 0f, 0f, 0f)
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (point in quad) {
                if (point.x < left) left = point.x
                if (point.x > right) right = point.x
                if (point.y < top) top = point.y
                if (point.y > bottom) bottom = point.y
            }
            return TrackBounds(left, top, right, bottom)
        }
    }
}

/** 内部 track：保存最近一次匹配的四边形、稳定文本与确认证据。 */
internal data class ScanTrack(
    val id: Long,
    /** 当前身份文本；confirmed 后不再被同文本的后续帧覆盖（防抖动）。 */
    var text: String,
    /** 最近一次匹配检测的四边形（buffer 空间），让 UI 锚点跟随条码移动。 */
    var quad: List<PointF>,
    var lineCount: Int,
    var symbologyIdentifier: String?,
    /** 累计命中「与 [text] 相同文本」的帧数（证据数；漏帧不清零，文本变化重置为 1）。 */
    var sameTextEvidence: Int,
    var confirmed: Boolean,
    var lastSeenMs: Long,
    var bounds: TrackBounds,
) {
    fun toSnapshot(): TrackSnapshot = TrackSnapshot(
        id = id,
        text = text,
        quad = quad,
        lineCount = lineCount,
        symbologyIdentifier = symbologyIdentifier,
        confirmed = confirmed,
        lastSeenMs = lastSeenMs,
    )
}

/** 对外快照（不可变）：controller 用它做去重分类/叠加层更新。 */
internal data class TrackSnapshot(
    val id: Long,
    val text: String,
    val quad: List<PointF>,
    val lineCount: Int,
    val symbologyIdentifier: String?,
    val confirmed: Boolean,
    val lastSeenMs: Long,
)

/** 单帧更新结果。 */
internal data class TrackUpdate(
    /** 当前存活的所有 track（含未确认，controller 自行过滤）。 */
    val tracks: List<TrackSnapshot>,
    /** 本帧「刚变为 confirmed」的 track（每 track 只会出现一次）。 */
    val newlyConfirmed: List<TrackSnapshot>,
)

/**
 * 帧间跟踪（对应 `docs/CONTRACT.md` §5.1/§5.2，v2 §12 放宽确认逻辑）：
 *  - 当前帧 detection 与已有 track 匹配条件：**IoU > 0.3** 或 **中心距 < 0.5 × track 包围盒高**；
 *  - 未确认 track 累计 **2 次同文本证据** 即变 confirmed（**不要求连续帧**：漏帧不清零计数，
 *    只要两次证据间隔不超过 500ms TTL；文本变化立即重置证据数）；
 *  - `> 500ms` 未再匹配的 track 过期移除（确认计数随 track 一起丢弃，重新出现即新 track）；
 *  - confirmed track 保持稳定 text/lineCount/symbology（不随后续帧小抖动变化），
 *    但四边形随最近一次匹配更新，使叠加层跟随条码移动。
 *
 * 为什么不再要求连续帧：真机对屏幕条码解码概率低（摩尔纹/滚动快门/手抖/对焦），
 * 旧逻辑漏一帧就清零，导致很少能确认出框（v1 逻辑在真实照片仿真中确认概率约 4%）。
 *
 * 线程模型：由 CameraX 单线程分析 executor 独占调用；内部加 `@Synchronized` 兜底。
 * 时钟可注入（`clock: () -> Long`，毫秒单调时钟），JVM 单测用假时钟驱动过期/证据累计逻辑。
 */
class TrackManager(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val tracks = ArrayList<ScanTrack>()
    private var nextTrackId = 1L

    /**
     * 输入当前帧（一次分析/解码）的全部 detection，返回合并后的 track 快照。
     * 同一帧内一个 track 最多匹配一个 detection；未匹配上的 detection 会创建新 track。
     */
    @Synchronized
    internal fun update(detections: List<Detection>): TrackUpdate {
        val now = clock()

        // 1) 过期：超过 TTL 未再出现的 track 直接移除（confirmed 也移除）。
        tracks.removeAll { now - it.lastSeenMs > TRACK_TTL_MS }

        val newlyConfirmed = ArrayList<TrackSnapshot>(2)
        val matchedThisFrame = HashSet<Long>(detections.size)

        // 2) 逐个 detection 贪心匹配（同文本优先 → IoU 高优先 → 中心距小优先）。
        for (detection in detections) {
            val bounds = TrackBounds.of(detection.cornersInBufferSpace)
            val matched = findBestMatch(detection, bounds, matchedThisFrame)
            if (matched == null) {
                val track = ScanTrack(
                    id = nextTrackId++,
                    text = detection.text,
                    quad = detection.cornersInBufferSpace,
                    lineCount = detection.lineCount,
                    symbologyIdentifier = detection.symbologyIdentifier,
                    sameTextEvidence = 1,
                    confirmed = false,
                    lastSeenMs = now,
                    bounds = bounds,
                )
                tracks += track
                matchedThisFrame += track.id
            } else {
                matchedThisFrame += matched.id
                applyDetection(matched, detection, bounds, now, newlyConfirmed)
            }
        }

        // 3) 本帧没匹配上的 track **不清零证据计数**（v2：容忍丢帧，累计 2 次证据即可确认）。
        //    未被匹配的 track 会随 500ms TTL 过期；confirmed track 也不因偶尔漏帧而撤销。

        val snapshots = ArrayList<TrackSnapshot>(tracks.size)
        for (track in tracks) snapshots += track.toSnapshot()
        return TrackUpdate(tracks = snapshots, newlyConfirmed = newlyConfirmed)
    }

    /** 当前存活 track 数（调试用）。 */
    @Synchronized
    internal fun activeTrackCount(): Int = tracks.size

    private fun applyDetection(
        track: ScanTrack,
        detection: Detection,
        bounds: TrackBounds,
        now: Long,
        newlyConfirmed: MutableList<TrackSnapshot>,
    ) {
        track.lastSeenMs = now
        track.quad = detection.cornersInBufferSpace
        track.bounds = bounds

        if (detection.text != track.text) {
            // 身份变了（同一位置换了一条码）：此前证据作废，重新从 1 开始累计。
            track.text = detection.text
            track.lineCount = detection.lineCount
            track.symbologyIdentifier = detection.symbologyIdentifier
            track.sameTextEvidence = 1
            track.confirmed = false
            return
        }

        if (!track.confirmed) {
            track.sameTextEvidence += 1
            if (track.sameTextEvidence >= CONFIRM_EVIDENCE_COUNT) {
                track.confirmed = true
                newlyConfirmed += track.toSnapshot()
            }
        }
        // confirmed 且文本相同：保持既有 text/lineCount/symbology 稳定，仅更新四边与时间戳。
    }

    private fun findBestMatch(
        detection: Detection,
        bounds: TrackBounds,
        matchedThisFrame: Set<Long>,
    ): ScanTrack? {
        var best: ScanTrack? = null
        var bestSameText = false
        var bestIou = -1f
        var bestCenterDistance = Float.MAX_VALUE

        for (track in tracks) {
            if (track.id in matchedThisFrame) continue
            val iou = bounds.iou(track.bounds)
            val centerDistance = kotlin.math.hypot(
                (bounds.centerX - track.bounds.centerX).toDouble(),
                (bounds.centerY - track.bounds.centerY).toDouble(),
            ).toFloat()
            val matchedByCenter = centerDistance < track.bounds.height * CENTER_DISTANCE_RATIO
            if (iou <= IOU_THRESHOLD && !matchedByCenter) continue

            val sameText = track.text == detection.text
            val better = when {
                best == null -> true
                sameText != bestSameText -> sameText
                iou > bestIou -> true
                iou < bestIou -> false
                else -> centerDistance < bestCenterDistance
            }
            if (better) {
                best = track
                bestSameText = sameText
                bestIou = iou
                bestCenterDistance = centerDistance
            }
        }
        return best
    }

    companion object {
        /** 累计多少次同文本证据后确认（CONTRACT §12：2 次证据，不要求连续帧）。 */
        const val CONFIRM_EVIDENCE_COUNT: Int = 2

        /** 未再匹配的存活时长；严格大于该值才过期移除（两次证据必须落在该 TTL 内）。 */
        const val TRACK_TTL_MS: Long = 500L

        /** 匹配条件一：IoU 阈值（严格大于）。 */
        const val IOU_THRESHOLD: Float = 0.3f

        /** 匹配条件二：中心距 < 该系数 × track 包围盒高度。 */
        const val CENTER_DISTANCE_RATIO: Float = 0.5f
    }
}
