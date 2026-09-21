package app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 固定合成 fixture（确定性、无随机种子外依赖）。
 *
 * 场景：
 *  - 60-2 齿盘（58 齿，参考缺齿跨 3 个齿距，锚点为跨距中点，TDC 在锚点后 3°）
 *  - 8 转内转速 1200 -> 2200 rpm 近似线性上升（转速瞬变）
 *  - 发火上止点 1440° 所在参考缺齿（缺槽 1434°、1440°）前的两个连续齿
 *    （1422°、1428°）丢失，观测跨距 30° = 5 个齿距。内部 4 个缺槽由
 *    2 个参考缺槽 + 2 个连续故障缺槽组成，合法分解有两种：
 *    故障缺槽全在左（真锚点 1437°，TDC=1440°）或全在右（伪锚点 1419°），
 *    相差 18°（3 个齿距），缺齿模式不可唯一识别；该转不输出参考标记。
 *  - 紧随其后的循环（cycle 2，1440° 起）峰值燃烧触发压力饱和削顶。
 */
object FixtureGenerator {
    private const val THETA_START = 300.0
    private const val THETA_END = 2940.0
    private const val FIRING_TDCS = 6

    private const val N_REV = 8.0
    private const val RPM_START = 1200.0
    private const val RPM_END = 2200.0

    private const val SAMPLE_RATE_HZ = 25_000.0
    private const val SENSOR_OFFSET_BAR = 0.35
    private const val NOISE_AMPLITUDE_BAR = 0.035

    /** 每个循环燃烧凸起中心（CA ATDC）；cycle 1 最高并触发饱和 */
    private val COMBUSTION_AMPLITUDE = doubleArrayOf(55.0, 58.0, 96.0, 60.0)
    private val COMBUSTION_CENTER = doubleArrayOf(12.0, 13.0, 11.0, 12.5)
    private val COMBUSTION_SIGMA = doubleArrayOf(8.0, 8.0, 6.5, 8.0)

    fun generate(nowMs: Long = 1_750_000_000_000L): Run {
        // ---- 角度-时间表（积分得到，线性转速上升）----
        val thetaMs = buildThetaTable()

        fun tAt(thetaDeg: Double): Double {
            val x = thetaDeg
            var lo = 0
            var hi = thetaMs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (thetaMs[mid].first <= x) lo = mid else hi = mid
            }
            val (t0, ms0) = thetaMs[lo]
            val (t1, ms1) = thetaMs[hi]
            val f = (x - t0) / (t1 - t0)
            return ms0 + f * (ms1 - ms0)
        }

        // ---- 齿沿：6° 倍数处存在；60-2 参考缺齿为两个连续槽位（槽 59、0）----
        val edgeAngles = ArrayList<Double>()
        val firstK = Math.ceil(THETA_START / 6.0).toInt()
        val lastK = Math.floor(THETA_END / 6.0).toInt()
        val dropped = setOf(1422.0, 1428.0) // 故障：参考缺齿前两个连续齿丢失
        for (k in firstK..lastK) {
            val ang = k * 6.0
            val slot = ((k % 60) + 60) % 60
            if (slot == 59 || slot == 0) continue // 60-2 参考缺齿（3 齿距跨距）
            if (ang in dropped) continue          // 故障：连续两齿丢失
            edgeAngles.add(ang)
        }
        val edgeTimes = edgeAngles.map { tAt(it) }

        // ---- 标记通道 ----
        // 参考标记位于锚点（参考缺齿中点）= TDC - 3°；故障转不输出
        val refMarkers = ArrayList<Double>()
        var rev = 1
        while (360.0 * rev - Versions.TDC_OFFSET_FROM_ANCHOR_DEG <= THETA_END) {
            val anchorAngle = 360.0 * rev - Versions.TDC_OFFSET_FROM_ANCHOR_DEG
            val isFaultyRev = kotlin.math.abs(anchorAngle - 1437.0) < 1e-6
            if (anchorAngle >= THETA_START && !isFaultyRev) refMarkers.add(tAt(anchorAngle))
            rev++
        }
        val tdcMarkers = (0..FIRING_TDCS)
            .map { it * 720.0 }
            .filter { it in THETA_START..THETA_END }
            .map { tAt(it) }
        val uncertain = doubleArrayOf(tAt(1428.0))

        // ---- 压力均匀采样 ----
        val tStart = tAt(0.0)
        val tEnd = tAt(2880.0)
        val dtMs = 1000.0 / SAMPLE_RATE_HZ
        val nSamples = Math.floor((tEnd - tStart) / dtMs).toInt() + 1
        val pressure = DoubleArray(nSamples)
        val saturated = BooleanArray(nSamples)
        val rng = Rng(0x51A7E1)
        for (i in 0 until nSamples) {
            val tMs = tStart + i * dtMs
            val theta = thetaAtTime(thetaMs, tMs)
            var p = cyclePressure(theta)
            val noise = (rng.nextDouble() - 0.5) * 2.0 * NOISE_AMPLITUDE_BAR
            p += SENSOR_OFFSET_BAR + noise
            if (p >= Versions.SATURATION_LIMIT_BAR - Versions.SATURATION_EPSILON) {
                p = Versions.SATURATION_LIMIT_BAR
                saturated[i] = true
            }
            pressure[i] = p
        }

        // ---- 工况通道：100 Hz 平均转速 ----
        val condT = ArrayList<Double>()
        val condRpm = ArrayList<Double>()
        var ct = tStart
        while (ct <= tEnd) {
            val a0 = thetaAtTime(thetaMs, ct)
            val a1 = thetaAtTime(thetaMs, ct + 10.0)
            val mid = (a0 + a1) / 2.0
            condT.add(ct)
            condRpm.add(rpmAt(mid))
            ct += 10.0
        }

        val params = linkedMapOf(
            "rpmStart" to RPM_START,
            "rpmEnd" to RPM_END,
            "revolutions" to N_REV,
            "sampleRateHz" to SAMPLE_RATE_HZ,
            "saturationLimitBar" to Versions.SATURATION_LIMIT_BAR,
            "missingTeethConsecutive" to 2.0,
            "missingTeethAtDeg" to 1425.0,
        )
        return Run(
            id = Versions.FIXTURE_ID,
            fixtureId = Versions.FIXTURE_ID,
            fixtureParams = params,
            createdAtMs = nowMs,
            pressure = PressureSignal(tStart, dtMs, pressure, saturated),
            teeth = ToothEdges(edgeTimes.toDoubleArray()),
            markers = MarkerEvents(refMarkers.toDoubleArray(), tdcMarkers.toDoubleArray(), uncertain),
            condition = ConditionChannel(condT.toDoubleArray(), condRpm.toDoubleArray()),
        )
    }

    /** 0.1° 步长的 (thetaDeg, tMs) 表，t=0 定义在 theta=360°（发火上止点） */
    private fun buildThetaTable(): List<Pair<Double, Double>> {
        val step = 0.1
        val n = ((THETA_END - THETA_START) / step).toInt() + 1
        val list = ArrayList<Pair<Double, Double>>(n)
        for (i in 0 until n) {
            val theta = THETA_START + i * step
            val ms = (integrateTime(theta) - integrateTime(360.0)) * 1000.0
            list.add(theta to ms)
        }
        return list
    }

    /** theta 处的瞬时转速（线性上升） */
    private fun rpmAt(thetaDeg: Double): Double {
        val f = thetaDeg / (360.0 * N_REV)
        return RPM_START + (RPM_END - RPM_START) * f
    }

    /** ∫ dθ/ω，θ 用弧度，ω=rad/s，返回秒（相对常数） */
    private fun integrateTime(thetaDeg: Double): Double {
        val a = RPM_START * 2.0 * PI / 60.0
        val b = (RPM_END - RPM_START) * 2.0 * PI / 60.0 / (2.0 * PI * N_REV)
        val theta = Math.toRadians(thetaDeg)
        return Math.log(a + b * theta) / b
    }

    private fun thetaAtTime(table: List<Pair<Double, Double>>, tMs: Double): Double {
        var lo = 0
        var hi = table.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (table[mid].second <= tMs) lo = mid else hi = mid
        }
        val (t0, ms0) = table[lo]
        val (t1, ms1) = table[hi]
        val f = (tMs - ms0) / (ms1 - ms0)
        return t0 + f * (t1 - t0)
    }

    // ---- 气缸几何（单缸，排量按曲柄-连杆几何从 TDC 的 Vc 线性分配）----
    private const val VD_CM3 = 500.0
    private val vc: Double = VD_CM3 / (Versions.COMPRESSION_RATIO - 1.0)
    private val crankRadius: Double = Versions.STROKE_M / 2.0
    private val rodRatio: Double = crankRadius / Versions.ROD_M

    /** 相对发火上止点曲柄角 a（度，0=TDC，±180=BDC）处的气缸容积 cm^3 */
    private fun volumeAt(a: Double): Double {
        val rad = Math.toRadians(a)
        val displacement = crankRadius * (1.0 - cos(rad)) +
            Versions.ROD_M * (1.0 - sqrt(1.0 - rodRatio * rodRatio * sin(rad) * sin(rad)))
        return vc + displacement / Versions.STROKE_M * VD_CM3
    }

    /**
     * 循环压力模型（theta 为全局曲轴角，发火上止点在 0,720,...）。
     * u ∈ [0,720) 为循环内相对角：0 发火 TDC；0-180 膨胀；180-360 排气；
     * 360 扫气 TDC；360-540 进气；540-720 压缩。
     */
    private fun cyclePressure(theta: Double): Double {
        val firing = Math.floor(theta / 720.0).toInt()
        var u = theta - firing * 720.0
        if (u < 0) u += 720.0
        val cycleIdx = ((firing % 4) + 4) % 4
        val vIvc = volumeAt(-180.0)
        return when {
            u < 50.0 -> {
                val a = u // 0..50 ATDC
                val mot = 1.0 * Math.pow(vIvc / volumeAt(a), Versions.GAMMA_COMPRESSION)
                val bump = COMBUSTION_AMPLITUDE[cycleIdx] *
                    exp(-0.5 * ((a - COMBUSTION_CENTER[cycleIdx]) / COMBUSTION_SIGMA[cycleIdx]) *
                        ((a - COMBUSTION_CENTER[cycleIdx]) / COMBUSTION_SIGMA[cycleIdx]))
                mot + bump
            }
            u < 180.0 -> {
                val p50 = 1.0 * Math.pow(vIvc / volumeAt(50.0), Versions.GAMMA_COMPRESSION) +
                    COMBUSTION_AMPLITUDE[cycleIdx] *
                    exp(-0.5 * ((50.0 - COMBUSTION_CENTER[cycleIdx]) / COMBUSTION_SIGMA[cycleIdx]) *
                        ((50.0 - COMBUSTION_CENTER[cycleIdx]) / COMBUSTION_SIGMA[cycleIdx]))
                p50 * Math.pow(volumeAt(50.0) / volumeAt(u), 1.25)
            }
            u < 360.0 -> 1.15 - 0.1 * (u - 180.0) / 180.0
            u < 540.0 -> 0.9 + 0.05 * Math.sin(Math.toRadians(u - 360.0) * 2.0)
            else -> {
                val a = u - 720.0 // -180..0（压缩行程）
                1.0 * Math.pow(vIvc / volumeAt(a), Versions.GAMMA_COMPRESSION)
            }
        }
    }

    private class Rng(private var state: Long) {
        fun nextDouble(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble()) / (1L shl 53).toDouble()
        }
    }
}
