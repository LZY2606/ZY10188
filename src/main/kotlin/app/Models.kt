package app


/** 缸压高速采样：均匀时间栅格 */
data class PressureSignal(
    val t0Ms: Double,
    val dtMs: Double,
    /** bar；等于饱和门槛即削顶样本 */
    val bar: DoubleArray,
    /** 与 bar 等长：true 表示传感器饱和（削顶）样本 */
    val saturated: BooleanArray,
) {
    fun timeMs(i: Int) = t0Ms + i * dtMs
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** 齿沿事件（数字沿时刻） */
data class ToothEdges(val tMs: DoubleArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * 锦标/标记通道：
 * - referenceGap：同步段 60-2 参考缺齿中点标记（每转一个）；丢齿发生的一转不输出
 * - tdc：凸轮轴发火上止点锦标（每 720° 一个），可被用户“置信”
 * - uncertainGap：无法唯一识别的缺齿跨距位置（提示用，不可当锚点）
 */
data class MarkerEvents(
    val referenceGapMs: DoubleArray,
    val tdcMs: DoubleArray,
    val uncertainGapMs: DoubleArray = DoubleArray(0),
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** 工况通道（100 Hz 平均转速） */
data class ConditionChannel(val tMs: DoubleArray, val rpm: DoubleArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class Run(
    val id: String,
    val fixtureId: String,
    val fixtureParams: Map<String, Double>,
    val createdAtMs: Long,
    val pressure: PressureSignal,
    val teeth: ToothEdges,
    val markers: MarkerEvents,
    val condition: ConditionChannel,
)

/** 单个观测齿间隔 */
data class ToothInterval(
    val index: Int,
    val tMs: Double,
    val dtMs: Double,
    /** 相对局部滚动中位间隔的跨距数（四舍五入） */
    val spanIntervals: Int,
    /** spanIntervals>=2 时表示缺齿候选 */
    val gap: Boolean,
)

/** 循环边界候选：各循环发火上止点时刻（ms）。patternUnique=false 时通常有两组候选 */
data class BoundaryCandidate(
    val id: String,
    val label: String,
    val firingTdcMs: DoubleArray,
    val supportedByReferenceMarkers: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class CycleAnalysis(
    val cycleIndex: Int,
    val candidateId: String,
    val tdcMs: Double,
    val startMs: Double,
    val endMs: Double,
    val userOffsetDeg: Double,
    /** 1° 栅格角域压力（bar，已归零），长度 721 */
    val angleDeg: DoubleArray,
    val pressureBar: DoubleArray,
    val saturatedMask: BooleanArray,
    val longSpanInterpolation: Boolean,
    val peggingWindow: ClosedRange<Double>,
    val peggingOffsetBar: Double,
    val metrics: Map<String, MetricResult>,
    val saturationSegments: List<SaturationSegment>,
    val excluded: Boolean,
)

data class SaturationSegment(val startAngleDeg: Double, val endAngleDeg: Double, val sampleCount: Int)

data class MetricResult(
    val key: String,
    val value: Double?,
    val unit: String,
    val phaseResolved: Boolean,
    val lowerBoundOnly: Boolean,
    val note: String,
    val algorithmVersion: String,
    val calibrationVersion: String,
)

data class ActionRecord(
    val id: Long = 0,
    val runId: String,
    val atMs: Long,
    val type: String,
    val payload: String,
)

data class AnalysisState(
    val runId: String,
    val patternConfirmed: Boolean,
    val patternCandidateId: String?,
    val trustedTdcIndex: Int?,
    val excludedCycleIndexes: List<Int>,
    val cycleOffsetsDeg: Map<Int, Double>,
)
