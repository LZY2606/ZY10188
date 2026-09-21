package app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 逐循环分析。
 *
 *  - 时间->角：仅在齿沿关键点之间按实际时间分段线性插值；
 *  - 重采样：每个循环独立在 0..720° 的 1° 栅格上取值，循环边界处不跨循环插值；
 *  - 归零：固定角域窗 [390°,450°)（半开，进气冲程），窗内压力均值作为偏移；
 *  - 饱和：等于饱和门槛的样本标记为削顶，仅报告峰压下界与饱和角段，
 *    不插值伪造峰压，也不跨循环填补；
 *  - CA50：缺齿模式未唯一确认或循环内含饱和时不输出单一放热中心。
 */
object CycleAnalyzer {

    private const val VD_M3 = 0.0005
    private val vc = VD_M3 / (Versions.COMPRESSION_RATIO - 1.0)
    private val crankRadius = Versions.STROKE_M / 2.0
    private val rodRatio = crankRadius / Versions.ROD_M

    data class Result(
        val cycles: List<CycleAnalysis>,
        val envelopeAngleDeg: DoubleArray,
        val envelopeMin: DoubleArray,
        val envelopeMax: DoubleArray,
        val envelopeMean: DoubleArray,
        val envelopeSaturated: BooleanArray,
    )

    fun analyze(
        run: Run,
        candidate: BoundaryCandidate,
        keypoints: List<Pair<Double, Double>>,
        tdcTrustShiftMs: Double = 0.0,
        userOffsetsDeg: Map<Int, Double> = emptyMap(),
        excludedCycles: Set<Int> = emptySet(),
        phaseResolved: Boolean,
    ): Result {
        val tdcs = candidate.firingTdcMs.map { it + tdcTrustShiftMs }
        val cycles = ArrayList<CycleAnalysis>()

        for (c in 0 until tdcs.size - 1) {
            cycles += analyzeCycle(run, c, tdcs[c], tdcs[c + 1], keypoints, userOffsetsDeg[c] ?: 0.0,
                excludedCycles.contains(c), phaseResolved).copy(candidateId = candidate.id)
        }

        val n = 721
        val mn = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val mx = DoubleArray(n) { Double.NEGATIVE_INFINITY }
        val sum = DoubleArray(n)
        val sat = BooleanArray(n)
        var included = 0
        for (cyc in cycles) {
            if (cyc.excluded) continue
            included++
            for (i in 0 until n) {
                val v = cyc.pressureBar[i]
                if (v.isNaN()) continue
                if (v < mn[i]) mn[i] = v
                if (v > mx[i]) mx[i] = v
                sum[i] += v
                if (cyc.saturatedMask[i]) sat[i] = true
            }
        }
        val mean = DoubleArray(n) { if (included > 0) sum[it] / included else Double.NaN }
        for (i in 0 until n) if (mn[i] == Double.POSITIVE_INFINITY) mn[i] = Double.NaN
        for (i in 0 until n) if (mx[i] == Double.NEGATIVE_INFINITY) mx[i] = Double.NaN

        return Result(
            cycles = cycles,
            envelopeAngleDeg = DoubleArray(n) { it.toDouble() },
            envelopeMin = mn,
            envelopeMax = mx,
            envelopeMean = mean,
            envelopeSaturated = sat,
        )
    }

    private fun analyzeCycle(
        run: Run,
        cycleIndex: Int,
        tdcMs: Double,
        nextTdcMs: Double,
        keypoints: List<Pair<Double, Double>>,
        userOffsetDeg: Double,
        excluded: Boolean,
        phaseResolved: Boolean,
    ): CycleAnalysis {
        val p = run.pressure
        val gridN = (Versions.CYCLE_DEG / Versions.GRID_STEP_DEG).toInt() + 1
        val angle = DoubleArray(gridN) { it * Versions.GRID_STEP_DEG }
        val grid = DoubleArray(gridN)
        val satGrid = BooleanArray(gridN)
        var longSpan = false

        // 每个 1° 栅格点反查时间（角 = 发火TDC角 + a），仅落在本循环内取值；
        // 时间落在两个齿沿之间的长跨距（S>=4）内时标记 longSpan。
        for (i in 0 until gridN) {
            // 栅格点时间：先在齿沿关键点上反查本循环 TDC(0°)/下一 TDC(720°) 的时刻，
            // 再按栅格角比例在两时刻间分段线性定位（严格不跨出本循环）
            val a = angle[i] - userOffsetDeg
            val tdcAngle = AngleReconstructor.timeToAngle(keypoints, tdcMs)
            val targetMs = AngleReconstructor.angleToTime(keypoints, tdcAngle + a)
            if (targetMs < p.t0Ms - 1e-9 || targetMs > p.timeMs(p.bar.size - 1) + 1e-9) {
                grid[i] = Double.NaN
                continue
            }
            val pos = (targetMs - p.t0Ms) / p.dtMs
            val i0 = Math.floor(pos).toInt().coerceIn(0, p.bar.size - 1)
            val i1 = (i0 + 1).coerceAtMost(p.bar.size - 1)
            val frac = (pos - i0).coerceIn(0.0, 1.0)
            // 饱和样本不插值：端点饱和即标记，禁止伪造峰压
            val saturated = p.saturated[i0] || (frac > 0.0 && p.saturated[i1])
            val raw = if (p.saturated[i0] && p.saturated[i1]) {
                Versions.SATURATION_LIMIT_BAR
            } else if (p.saturated[i0] || p.saturated[i1]) {
                Versions.SATURATION_LIMIT_BAR
            } else {
                p.bar[i0] + frac * (p.bar[i1] - p.bar[i0])
            }
            grid[i] = raw
            satGrid[i] = saturated
            longSpan = longSpan || withinLongSpan(keypoints, targetMs)
        }

        // ---- 固定角域归零窗 [390,450) ----
        val pegStart = (Versions.PEG_START_DEG / Versions.GRID_STEP_DEG).toInt()
        val pegEnd = (Versions.PEG_END_DEG / Versions.GRID_STEP_DEG).toInt() // 半开
        var pegSum = 0.0
        var pegCount = 0
        for (i in pegStart until pegEnd) {
            if (!grid[i].isNaN() && !satGrid[i]) {
                pegSum += grid[i]
                pegCount++
            }
        }
        val pegOffset = if (pegCount > 0) pegSum / pegCount else 0.0
        for (i in grid.indices) if (!grid[i].isNaN()) grid[i] -= pegOffset
        // 归零后的饱和样本仍表示“真实压力 >= 门槛”，保留门槛值用于下界展示
        val segments = findSegments(angle, satGrid)

        val anySat = satGrid.any { it }
        val metrics = LinkedHashMap<String, MetricResult>()
        metrics["pmax"] = pmaxMetric(grid, satGrid, anySat)
        metrics["pmaxAngle"] = pmaxAngleMetric(grid, satGrid, anySat, phaseResolved)
        metrics["maxPressureRise"] = riseRateMetric(grid, satGrid, anySat, phaseResolved)
        metrics["ca50"] = ca50Metric(grid, satGrid, anySat, phaseResolved)

        return CycleAnalysis(
            cycleIndex = cycleIndex,
            candidateId = "",
            tdcMs = tdcMs,
            startMs = tdcMs,
            endMs = nextTdcMs,
            userOffsetDeg = userOffsetDeg,
            angleDeg = angle,
            pressureBar = grid,
            saturatedMask = satGrid,
            longSpanInterpolation = longSpan,
            peggingWindow = Versions.PEG_START_DEG..Versions.PEG_END_DEG,
            peggingOffsetBar = pegOffset,
            metrics = metrics,
            saturationSegments = segments,
            excluded = excluded,
        )
    }

    private fun withinLongSpan(keys: List<Pair<Double, Double>>, tMs: Double): Boolean {
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].first <= tMs) lo = mid else hi = mid
        }
        // 常规参考缺齿为 3 齿距（18°）；只有故障长跨距（>=4 齿距）才标记线性拉伸
        return keys[hi].second - keys[lo].second > 18.0 + 1e-9
    }

    private fun findSegments(angle: DoubleArray, sat: BooleanArray): List<SaturationSegment> {
        val out = ArrayList<SaturationSegment>()
        var i = 0
        while (i < sat.size) {
            if (!sat[i]) {
                i++
                continue
            }
            val start = i
            while (i < sat.size && sat[i]) i++
            out.add(SaturationSegment(angle[start], angle[minOf(i, sat.size - 1)], i - start))
        }
        return out
    }

    private fun pmaxMetric(grid: DoubleArray, sat: BooleanArray, anySat: Boolean): MetricResult {
        var cleanMax = Double.NEGATIVE_INFINITY
        for (idx in grid.indices) if (!sat[idx] && !grid[idx].isNaN()) cleanMax = maxOf(cleanMax, grid[idx])
        return if (anySat) {
            // 饱和门槛在归零后要减去归零偏移，但“真实峰压 >= 物理门槛 - 偏移”恒成立；
            // 下界取非饱和最大压力与（门槛-偏移）二者较大者
            MetricResult("pmax", maxOf(cleanMax, Versions.SATURATION_LIMIT_BAR), "bar", true, true,
                "存在饱和削顶样本，仅报告峰压下界；未对饱和点插值",
                Versions.ALG_PMAX, Versions.CAL_ENGINE)
        } else {
            MetricResult("pmax", grid.filter { !it.isNaN() }.maxOrNull(), "bar", true, false,
                "角域 1° 栅格最大缸压", Versions.ALG_PMAX, Versions.CAL_ENGINE)
        }
    }

    private fun pmaxAngleMetric(
        grid: DoubleArray, sat: BooleanArray, anySat: Boolean, phaseResolved: Boolean,
    ): MetricResult {
        if (anySat) {
            return MetricResult("pmaxAngle", null, "degATDC", phaseResolved, true,
                "峰值落在饱和段内，峰压角不可信，不给单一数值",
                Versions.ALG_PMAX, Versions.CAL_ENGINE)
        }
        var idx = 0
        for (i in grid.indices) if (grid[i] > grid[idx]) idx = i
        return MetricResult("pmaxAngle", idx.toDouble(), "degATDC", phaseResolved, false,
            "最大缸压对应的曲轴角", Versions.ALG_PMAX, Versions.CAL_ENGINE)
    }

    private fun riseRateMetric(
        grid: DoubleArray, sat: BooleanArray, anySat: Boolean, phaseResolved: Boolean,
    ): MetricResult {
        if (anySat) {
            return MetricResult("maxPressureRise", null, "bar/deg", phaseResolved, true,
                "压升率受饱和段影响，不给单一数值", Versions.ALG_RPR, Versions.CAL_ENGINE)
        }
        var max = Double.NEGATIVE_INFINITY
        for (i in 1 until grid.size) {
            val r = grid[i] - grid[i - 1]
            if (r > max) max = r
        }
        return MetricResult("maxPressureRise", max, "bar/deg", phaseResolved, false,
            "1° 栅格一阶差分最大压升率", Versions.ALG_RPR, Versions.CAL_ENGINE)
    }

    private fun ca50Metric(
        grid: DoubleArray, sat: BooleanArray, anySat: Boolean, phaseResolved: Boolean,
    ): MetricResult {
        if (!phaseResolved) {
            return MetricResult("ca50", null, "degATDC", false, false,
                "缺齿模式未唯一识别/上止点未置信，相位存在歧义，不输出单一放热中心",
                Versions.ALG_HRR, Versions.CAL_ENGINE)
        }
        if (anySat) {
            return MetricResult("ca50", null, "degATDC", true, true,
                "循环内含压力饱和段，放热中心不可靠，不给单一数值",
                Versions.ALG_HRR, Versions.CAL_ENGINE)
        }
        val ca50 = computeCa50(grid)
            ?: return MetricResult("ca50", null, "degATDC", true, false,
                "净放热未过 50%，无法定义 CA50", Versions.ALG_HRR, Versions.CAL_ENGINE)
        return MetricResult("ca50", ca50, "degATDC", true, false,
            "dQ=g/(g-1)pdV+1/(g-1)Vdp，5 点滑动平滑，净放热 50% 位置",
            Versions.ALG_HRR, Versions.CAL_ENGINE)
    }

    /** 气缸容积 m^3，a 为相对发火 TDC 的曲柄角（度） */
    private fun volume(a: Double): Double {
        val rad = Math.toRadians(a)
        val displacement = crankRadius * (1.0 - cos(rad)) +
            Versions.ROD_M * (1.0 - sqrt(1.0 - rodRatio * rodRatio * sin(rad) * sin(rad)))
        return vc + displacement / Versions.STROKE_M * VD_M3
    }

    internal fun computeCa50(grid: DoubleArray): Double? {
        // 角度索引：-40..100° ATDC（680..720 对应 -40..0，0..100 对应 0..100）
        val idx = (680 until 721).toList() + (1..100).toList()
        val m = idx.size
        val dq = DoubleArray(m)
        val pa = 1e5 // bar -> Pa
        // 单向（后向）差分，避免中心差分在 dv 变号（TDC）处引入假脉冲
        for (k in 1 until m) {
            val i = idx[k]
            val iPrev = idx[k - 1]
            val a = angleOf(i)
            val p = grid[i] * pa
            val dp = (grid[i] - grid[iPrev]) * pa
            val dv = volume(a) - volume(angleOf(iPrev))
            val g = Versions.GAMMA_HEAT_RELEASE
            dq[k] = g / (g - 1.0) * p * dv + 1.0 / (g - 1.0) * volume(a) * dp
        }
        // 5 点滑动平均
        val smooth = DoubleArray(m)
        for (k in 0 until m) {
            var sum = 0.0
            var cnt = 0
            for (off in -2..2) {
                val j = k + off
                if (j in 0 until m) { sum += dq[j]; cnt++ }
            }
            smooth[k] = sum / cnt
        }
        // 净累积放热（保留压缩段负值，燃烧前取最小为基线）
        val cum = DoubleArray(m)
        for (k in 1 until m) cum[k] = cum[k - 1] + smooth[k]
        val tdcK = 40 // idx[40]=720 -> 0°
        val endK = (tdcK until m).maxByOrNull { cum[it] }!!
        // 以 TDC 累积值为基线（燃烧主要发生在 TDC 后），50% 净放热在 TDC 后搜索
        val base = cum[tdcK]
        val total = cum[endK] - base
       
        if (total <= 0.0) return null
        val half = base + total * 0.5
        for (k in (tdcK + 1)..endK) {
            if (cum[k - 1] <= half && cum[k] >= half) {
                val f = if (cum[k] == cum[k - 1]) 0.0
                else (half - cum[k - 1]) / (cum[k] - cum[k - 1])
                return angleOf(idx[k - 1]) + f * (angleOf(idx[k]) - angleOf(idx[k - 1]))
            }
        }
        return null
    }

    internal fun angleOf(index: Int): Double = if (index >= 360) index - 720.0 else index.toDouble()
}
