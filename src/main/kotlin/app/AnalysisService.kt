package app

import java.util.concurrent.ConcurrentHashMap

/** 对外/对前端的数据传输对象（角域曲线已按固定抽稀） */
data class RunSummaryDto(
    val id: String,
    val fixtureId: String,
    val fixtureParams: Map<String, Double>,
    val sampleRateHz: Double,
    val sampleCount: Int,
    val durationMs: Double,
    val startTimeMs: Double,
)

data class RawSeriesPoint(val tMs: Double, val values: List<Double>)

data class RawSignalsDto(
    /** 抽稀后的压力时域点 [tMs, bar, saturatedFlag(0/1)] */
    val pressure: List<List<Double>>,
    /** 齿沿时刻（全量） */
    val toothEdgesMs: List<Double>,
    val referenceMarkersMs: List<Double>,
    val tdcMarkersMs: List<Double>,
    val uncertainGapMs: List<Double>,
    val condition: List<List<Double>>,
)

data class ToothIntervalDto(
    val tMs: Double,
    val dtMs: Double,
    val span: Int,
    val gap: Boolean,
    val rpmInstant: Double,
)

data class CandidateDto(
    val id: String,
    val label: String,
    val firingTdcMs: List<Double>,
)

data class MetricDto(
    val key: String,
    val value: Double?,
    val unit: String,
    val phaseResolved: Boolean,
    val lowerBoundOnly: Boolean,
    val note: String,
    val algorithmVersion: String,
    val calibrationVersion: String,
)

data class SegmentDto(val startAngleDeg: Double, val endAngleDeg: Double, val sampleCount: Int)

data class CycleDto(
    val cycleIndex: Int,
    val candidateId: String,
    val tdcMs: Double,
    val startMs: Double,
    val endMs: Double,
    val userOffsetDeg: Double,
    /** [angle, pressureBar]，已归零，饱和点压力为门槛值（下界展示） */
    val trace: List<List<Double>>,
    val saturatedAngles: List<Double>,
    val longSpanInterpolation: Boolean,
    val peggingWindow: List<Double>,
    val peggingOffsetBar: Double,
    val metrics: List<MetricDto>,
    val saturationSegments: List<SegmentDto>,
    val excluded: Boolean,
)

data class EnvelopeDto(
    val angle: List<Double>,
    val min: List<Double>,
    val max: List<Double>,
    val mean: List<Double>,
    val saturated: List<Boolean>,
)

data class AnalysisDto(
    val run: RunSummaryDto,
    val state: AnalysisState,
    val toothIntervals: List<ToothIntervalDto>,
    val candidates: List<CandidateDto>,
    val patternUnique: Boolean,
    val cycles: List<CycleDto>,
    val envelope: EnvelopeDto,
    val versions: Map<String, String>,
)

data class ActionDto(val id: Long, val atMs: Long, val type: String, val payload: String)

class AnalysisService(private val db: Database) {
    private val reconCache = ConcurrentHashMap<String, AngleReconstructor.Reconstruction>()

    fun defaultState(runId: String): AnalysisState =
        AnalysisState(runId, false, null, null, emptyList(), emptyMap())

    fun getState(runId: String): AnalysisState = db.loadState(runId) ?: defaultState(runId)

    fun recon(run: Run): AngleReconstructor.Reconstruction =
        reconCache.getOrPut(run.id) { AngleReconstructor.reconstruct(run) }

    fun recordAction(runId: String, type: String, payload: String) {
        db.addAction(ActionRecord(runId = runId, atMs = System.currentTimeMillis(), type = type, payload = payload))
    }

    fun setState(state: AnalysisState) {
        db.saveState(state)
    }

    fun summary(run: Run): RunSummaryDto = RunSummaryDto(
        id = run.id,
        fixtureId = run.fixtureId,
        fixtureParams = run.fixtureParams,
        sampleRateHz = 1000.0 / run.pressure.dtMs,
        sampleCount = run.pressure.bar.size,
        durationMs = run.pressure.dtMs * (run.pressure.bar.size - 1),
        startTimeMs = run.pressure.t0Ms,
    )

    fun raw(run: Run, maxPoints: Int = 1800): RawSignalsDto {
        val p = run.pressure
        val stride = maxOf(1, p.bar.size / maxPoints)
        val pts = ArrayList<List<Double>>()
        var i = 0
        while (i < p.bar.size) {
            pts.add(listOf(p.timeMs(i), p.bar[i], if (p.saturated[i]) 1.0 else 0.0))
            i += stride
        }
        if ((i - stride) != p.bar.size - 1) {
            pts.add(listOf(p.timeMs(p.bar.size - 1), p.bar.last(), if (p.saturated.last()) 1.0 else 0.0))
        }
        return RawSignalsDto(
            pressure = pts,
            toothEdgesMs = run.teeth.tMs.toList(),
            referenceMarkersMs = run.markers.referenceGapMs.toList(),
            tdcMarkersMs = run.markers.tdcMs.toList(),
            uncertainGapMs = run.markers.uncertainGapMs.toList(),
            condition = run.condition.tMs.indices.map {
                listOf(run.condition.tMs[it], run.condition.rpm[it])
            },
        )
    }

    fun analysisDto(run: Run, state: AnalysisState, recon: AngleReconstructor.Reconstruction): AnalysisDto {
        val chosen = chooseCandidate(recon, state)
        val keys = recon.keypoints[chosen.id]
            ?: error("候选 ${chosen.id} 的角度关键点缺失")

        // 上止点锦标：把候选发火上止点吸附到被信锦标（同一事件），得到时间平移
        val tdcShift = if (state.trustedTdcIndex != null && state.trustedTdcIndex < run.markers.tdcMs.size) {
            val markerMs = run.markers.tdcMs[state.trustedTdcIndex]
            val nearest = chosen.firingTdcMs.minByOrNull { kotlin.math.abs(it - markerMs) }
            if (nearest != null) markerMs - nearest else 0.0
        } else 0.0

        val phaseResolved = if (recon.candidates.size > 1) {
            state.patternConfirmed && state.trustedTdcIndex != null
        } else {
            state.trustedTdcIndex != null
        }

        val result = CycleAnalyzer.analyze(
            run = run,
            candidate = chosen,
            keypoints = keys,
            tdcTrustShiftMs = tdcShift,
            userOffsetsDeg = state.cycleOffsetsDeg,
            excludedCycles = state.excludedCycleIndexes.toSet(),
            phaseResolved = phaseResolved,
        )

        val cycles = result.cycles.map { c ->
            val trace = c.angleDeg.indices.map { listOf(c.angleDeg[it], c.pressureBar[it]) }
            CycleDto(
                cycleIndex = c.cycleIndex,
                candidateId = chosen.id,
                tdcMs = c.tdcMs + tdcShift,
                startMs = c.startMs + tdcShift,
                endMs = c.endMs + tdcShift,
                userOffsetDeg = c.userOffsetDeg,
                trace = trace,
                saturatedAngles = c.angleDeg.indices.filter { c.saturatedMask[it] }.map { c.angleDeg[it] },
                longSpanInterpolation = c.longSpanInterpolation,
                peggingWindow = listOf(c.peggingWindow.start, c.peggingWindow.endInclusive),
                peggingOffsetBar = c.peggingOffsetBar,
                metrics = c.metrics.values.map {
                    MetricDto(it.key, it.value, it.unit, it.phaseResolved, it.lowerBoundOnly,
                        it.note, it.algorithmVersion, it.calibrationVersion)
                },
                saturationSegments = c.saturationSegments.map {
                    SegmentDto(it.startAngleDeg, it.endAngleDeg, it.sampleCount)
                },
                excluded = c.excluded,
            )
        }

        // 单个齿距 6° = 1/60 转，转速 rpm = 60000/dtMs/60 = 1000/dtMs
        val intervals = recon.intervals.map { iv ->
            val rpm = if (iv.dtMs > 0) 1000.0 / iv.dtMs else 0.0
            ToothIntervalDto(
                tMs = iv.tMs,
                dtMs = iv.dtMs,
                span = iv.spanIntervals,
                gap = iv.gap,
                rpmInstant = rpm,
            )
        }

        val versions = linkedMapOf(
            "tooth" to Versions.ALG_TOOTH,
            "angleMap" to Versions.ALG_ANGLE_MAP,
            "pegging" to Versions.ALG_PEGGING,
            "pmax" to Versions.ALG_PMAX,
            "pressureRise" to Versions.ALG_RPR,
            "heatRelease" to Versions.ALG_HRR,
            "wheelCalibration" to Versions.CAL_WHEEL,
            "engineCalibration" to Versions.CAL_ENGINE,
            "fixture" to Versions.FIXTURE_ID,
        )

        return AnalysisDto(
            run = summary(run),
            state = state,
            toothIntervals = intervals,
            candidates = recon.candidates.map {
                CandidateDto(it.id, it.label, it.firingTdcMs.toList())
            },
            patternUnique = recon.patternUnique,
            cycles = cycles,
            envelope = EnvelopeDto(
                angle = result.envelopeAngleDeg.toList(),
                min = result.envelopeMin.toList(),
                max = result.envelopeMax.toList(),
                mean = result.envelopeMean.toList(),
                saturated = result.envelopeSaturated.toList(),
            ),
            versions = versions,
        )
    }

    private fun chooseCandidate(
        recon: AngleReconstructor.Reconstruction,
        state: AnalysisState,
    ): BoundaryCandidate {
        val byId = recon.candidates.associateBy { it.id }
        if (state.patternConfirmed && state.patternCandidateId != null) {
            byId[state.patternCandidateId]?.let { return it }
        }
        return recon.candidates.first()
    }
}
