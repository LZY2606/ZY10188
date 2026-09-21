package app

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 角度重建。
 *
 * 齿盘几何（标定 wheel-60-2-cal-v1）：
 *  - 60 个槽位中槽 59、0 缺失，形成 3 个齿距（18°）的参考缺齿；
 *  - 参考锚点 = 缺齿跨距中点 = TDC 前 3°；锚点后沿齿沿 = TDC 后 9°。
 *
 * 解码规则：
 *  - 齿间隔按局部滚动中位（窗口 21）归一化，四舍五入为整数跨距 S；
 *  - S==3 为参考缺齿；S==2（孤立丢齿，无法放入 3 齿距参考）不可同步；
 *  - S>=4 的长跨距内部有 S-1 个缺槽 = 2 个参考缺槽 + (S-3) 个故障缺槽；
 *    在“连续两个齿丢失”的约束下，故障缺槽必须连续，因此只有两种合法分解：
 *    全部位于参考左侧（k1=0）或全部位于参考右侧（k1=S-3）。
 *    两种分解各自产生一个锚点（相差 (S-3)*6°），均保留为候选，不强行拼接。
 *
 * 角域映射只使用相邻齿沿的实际时间做分段线性插值；
 * 长跨距内部没有齿沿，跨距整段按时间线性拉伸（不假定每齿内转速恒定）。
 */
object AngleReconstructor {

    data class Reconstruction(
        val intervals: List<ToothInterval>,
        val candidates: List<BoundaryCandidate>,
        /** 每个候选：(时间ms, 全局曲轴角°) 的齿沿关键点 */
        val keypoints: Map<String, List<Pair<Double, Double>>>,
        val patternUnique: Boolean,
    )

    private const val MEDIAN_WINDOW = 21
    private const val EDGE_INDEX_PER_REV = 58

    fun reconstruct(run: Run): Reconstruction {
        val t = run.teeth.tMs
        val dts = DoubleArray(t.size - 1) { t[it + 1] - t[it] }

        val intervals = ArrayList<ToothInterval>(dts.size)
        for (i in dts.indices) {
            val from = maxOf(0, i - MEDIAN_WINDOW / 2)
            val to = minOf(dts.size, i + MEDIAN_WINDOW / 2 + 1)
            val window = dts.copyOfRange(from, to).sorted()
            val median = window[window.size / 2]
            val ratio = if (median > 0) dts[i] / median else 1.0
            val span = ratio.roundToInt().coerceAtLeast(1)
            intervals.add(ToothInterval(i, t[i], dts[i], span, span >= 2))
        }

        val fixedGaps = intervals.indices.filter { intervals[it].spanIntervals == 3 }
        val longGaps = intervals.indices.filter { intervals[it].spanIntervals >= 4 }
        val orphan = intervals.indices.any { intervals[it].spanIntervals == 2 }

        val candidates = ArrayList<BoundaryCandidate>()
        val keypoints = LinkedHashMap<String, List<Pair<Double, Double>>>()

        if (!orphan) {
            // S>=4：k1 ∈ {0, S-3}（故障缺槽连续，全在参考左侧或右侧）
            val choiceLists: List<List<Int>> = longGaps.map { g ->
                val s = intervals[g].spanIntervals
                val right = s - 3
                if (right == 0) listOf(0) else listOf(0, right)
            }
            for (combo in product(choiceLists)) {
                val anchors = ArrayList<Pair<Double, Int>>() // (锚点时刻ms, gapIntervalIndex)
                for (g in fixedGaps) {
                    val med = localMedian(intervals, g)
                    anchors.add(t[g + 1] - 1.5 * med to g)
                }
                for ((g, k1) in longGaps.zip(combo)) {
                    val med = localMedian(intervals, g)
                    anchors.add(t[g] + (k1 + 1.5) * med to g)
                }
                anchors.sortBy { it.first }
                if (!validateSpacing(anchors, longGaps.toSet())) continue
                if (!validateMarkers(anchors, run)) continue

                val anchorAngle = HashMap<Int, Double>()
                val anchorTime = HashMap<Int, Double>()
                anchors.forEachIndexed { idx, (at, g) ->
                    anchorAngle[g] = 360.0 * idx - 3.0
                    anchorTime[g] = at
                }
                val keys = buildKeypoints(t, intervals, anchorAngle, anchorTime)

                // 锚点 idx 位于 360*idx+357；idx 偶数时其后方 3° 即发火 TDC（360*(idx+1)）
                val firingTdc = ArrayList<Double>()
                for (idx in anchors.indices) {
                    if (idx % 2 == 0) firingTdc.add(angleToTime(keys, 360.0 * (idx + 1)))
                }

                val id = "cand-${candidates.size + 1}"
                val label = when {
                    longGaps.isEmpty() -> "唯一缺齿模式（60-2 参考）"
                    else -> "缺齿模式候选 ${candidates.size + 1}"
                }
                candidates.add(
                    BoundaryCandidate(
                        id = id,
                        label = label,
                        firingTdcMs = firingTdc.toDoubleArray(),
                        supportedByReferenceMarkers = true,
                    )
                )
                keypoints[id] = keys
            }
        }

        return Reconstruction(intervals, candidates, keypoints, candidates.size <= 1)
    }

    private fun localMedian(intervals: List<ToothInterval>, gapIdx: Int): Double {
        val nearby = (gapIdx - 10..gapIdx + 10)
            .filter { it in intervals.indices && !intervals[it].gap }
            .map { intervals[it].dtMs }.sorted()
        require(nearby.isNotEmpty()) { "锚点附近缺少正常齿距，无法定位缺齿中点" }
        return nearby[nearby.size / 2]
    }

    /**
     * 相邻参考锚点后沿的间隔索引差：
     *  - 常规：58（每转 58 个齿沿间隔）
     *  - 与故障长跨距相邻的两个转对：一个为 56（故障跨距前一对），
     *    另一个为 58（后一对，在同一索引处补回）。
     */
    private fun validateSpacing(
        anchors: List<Pair<Double, Int>>,
        longGapIndexes: Set<Int>,
    ): Boolean {
        for (i in 1 until anchors.size) {
            val diff = anchors[i].second - anchors[i - 1].second
            val adjacentToLong =
                anchors[i].second in longGapIndexes || anchors[i - 1].second in longGapIndexes
            val allowed = if (adjacentToLong)
                setOf(EDGE_INDEX_PER_REV, EDGE_INDEX_PER_REV - 2)
            else setOf(EDGE_INDEX_PER_REV)
            if (diff !in allowed) return false
        }
        return true
    }

    /** 同步段参考标记（故障转缺失）必须与锚点时刻吻合（±2 ms） */
    private fun validateMarkers(anchors: List<Pair<Double, Int>>, run: Run): Boolean {
        val refs = run.markers.referenceGapMs
        if (refs.isEmpty()) return true
        var matched = 0
        for ((at, _) in anchors) {
            if (refs.any { abs(it - at) < 2.0 }) matched++
        }
        return matched >= refs.size - 1
    }

    /**
     * 构造全局 (时间ms, 曲轴角°) 关键点。
     * 锚点角 = 360*revIdx - 3；锚点后沿齿沿角 = 锚点 + 9° = 360*revIdx + 6。
     * gap 内整段按齿距数（6°*S）沿实际时间线性分布。
     */
    /**
     * 构造 (时间ms, 曲轴角°) 关键点。
     *  - 普通齿沿：相邻齿沿间角度 = 6°*span，按实际时间线性插值；
     *  - 每个参考锚点（含长跨距内部锚点时刻）作为显式关键点加入，
     *    使两种缺齿分解候选在长跨距内产生不同的角度-时间映射；
     *  - 长跨距内无齿沿的部分整段线性拉伸（不假定每齿转速恒定）。
     */
    private fun buildKeypoints(
        t: DoubleArray,
        intervals: List<ToothInterval>,
        anchorAngle: Map<Int, Double>,
        anchorTime: Map<Int, Double> = emptyMap(),
    ): List<Pair<Double, Double>> {
        val edgeAngle = DoubleArray(t.size)
        val firstGap = anchorAngle.keys.minOrNull()!!
        // 后沿齿沿 = 锚点 + 9°；前沿齿沿 = 后沿 - 6°*S
        edgeAngle[firstGap + 1] = anchorAngle.getValue(firstGap) + 9.0
        edgeAngle[firstGap] = edgeAngle[firstGap + 1] -
            6.0 * intervals[firstGap].spanIntervals
        for (i in (firstGap - 1) downTo 0) {
            edgeAngle[i] = edgeAngle[i + 1] - 6.0 * intervals[i].spanIntervals
        }
        for (i in (firstGap + 1) until intervals.size) {
            edgeAngle[i + 1] = if (i in anchorAngle) {
                anchorAngle.getValue(i) + 9.0
            } else {
                edgeAngle[i] + 6.0 * intervals[i].spanIntervals
            }
        }
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in t.indices) pts.add(t[i] to edgeAngle[i])
        for ((g, at) in anchorTime) {
            pts.add(at to anchorAngle.getValue(g))
        }
        return pts.sortedBy { it.first }
    }

    fun angleToTime(keys: List<Pair<Double, Double>>, angle: Double): Double {
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].second <= angle) lo = mid else hi = mid
        }
        val (t0, a0) = keys[lo]
        val (t1, a1) = keys[hi]
        if (a1 == a0) return t0
        return t0 + (angle - a0) / (a1 - a0) * (t1 - t0)
    }

    fun timeToAngle(keys: List<Pair<Double, Double>>, timeMs: Double): Double {
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].first <= timeMs) lo = mid else hi = mid
        }
        val (t0, a0) = keys[lo]
        val (t1, a1) = keys[hi]
        if (t1 == t0) return a0
        return a0 + (timeMs - t0) / (t1 - t0) * (a1 - a0)
    }

    private fun <T> product(lists: List<List<T>>): List<List<T>> {
        if (lists.isEmpty()) return listOf(emptyList())
        var acc = listOf<List<T>>(emptyList())
        for (list in lists) {
            val next = ArrayList<List<T>>()
            for (prefix in acc) for (item in list) next.add(prefix + item)
            acc = next
        }
        return acc
    }
}
