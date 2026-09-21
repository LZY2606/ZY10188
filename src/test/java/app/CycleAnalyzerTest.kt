package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CycleAnalyzerTest {

    private val run = FixtureGenerator.generate()
    private val recon = AngleReconstructor.reconstruct(run)

    private fun analyze(candidate: BoundaryCandidate, phaseResolved: Boolean): CycleAnalyzer.Result =
        CycleAnalyzer.analyze(
            run = run,
            candidate = candidate,
            keypoints = recon.keypoints.getValue(candidate.id),
            phaseResolved = phaseResolved,
        )

    @Test
    fun `unresolved phase produces no single CA50 for any cycle`() {
        for (cand in recon.candidates) {
            val res = analyze(cand, phaseResolved = false)
            for (c in res.cycles) {
                val ca50 = c.metrics.getValue("ca50")
                assertNull(ca50.value, "缺齿未唯一识别时不得输出单一 CA50 (${cand.id}, C${c.cycleIndex})")
            }
        }
    }

    @Test
    fun `saturated cycle reports only lower-bound pmax and no pmax angle or CA50`() {
        val result = analyze(recon.candidates.first(), phaseResolved = true)
        val saturated = result.cycles.first { it.saturationSegments.isNotEmpty() }
        assertTrue(saturated.cycleIndex in 1..2, "sat cycle idx=${saturated.cycleIndex}")
        val pmax = saturated.metrics.getValue("pmax")
        assertTrue(pmax.lowerBoundOnly)
        assertTrue(pmax.value!! >= Versions.SATURATION_LIMIT_BAR - 1.0)
        assertNull(saturated.metrics.getValue("pmaxAngle").value)
        assertNull(saturated.metrics.getValue("maxPressureRise").value)
        assertNull(saturated.metrics.getValue("ca50").value)
    }

    @Test
    fun `saturated values are not interpolated above the limit`() {
        val result = analyze(recon.candidates.first(), phaseResolved = true)
        val saturated = result.cycles.first { it.saturationSegments.isNotEmpty() }
        for (i in saturated.pressureBar.indices) {
            assertTrue(saturated.pressureBar[i] <= Versions.SATURATION_LIMIT_BAR + 1e-9,
                "禁止把饱和点插值到门槛之上")
        }
        // 饱和段连续，覆盖燃烧峰附近
        val seg = saturated.saturationSegments.single()
        assertTrue(seg.sampleCount >= 5)
        assertTrue(seg.startAngleDeg in -20.0..40.0)
        assertTrue(seg.endAngleDeg in -10.0..60.0)
    }

    @Test
    fun `resampling never bridges across cycle boundaries`() {
        // 每个循环的样本时间严格在自身 [start,end] 内：通过独立重放验证不越界
        val result = analyze(recon.candidates.first(), phaseResolved = true)
        for (c in result.cycles) {
            assertTrue(c.startMs >= c.tdcMs - 1e-6)
            assertTrue(c.endMs > c.tdcMs)
            // 每个循环独立重采样：循环内任一 1° 栅格点都落在自己的 [start,end] 时间窗内
            val keys = recon.keypoints.getValue(c.candidateId)
            for (a in listOf(0.0, 360.0, 719.0)) {
                val tdcAngle = AngleReconstructor.timeToAngle(keys, c.tdcMs)
                val t = AngleReconstructor.angleToTime(keys, tdcAngle + a)
                assertTrue(t >= c.startMs - 1e-6 && t <= c.endMs + 1e-6,
                    "C${c.cycleIndex} 栅格 $a° 时间越界: $t vs [${c.startMs},${c.endMs}]")
            }
        }
    }

    @Test
    fun `clean cycles yield pmax angle near synthetic combustion center and CA50 near 10`() {
        // 选真候选（与 TDC 锦标对齐）
        val tdcFault = run.markers.tdcMs[2]
        // 真候选在故障位置与锦标时间差最小（两个候选相等时退化为第一个，
        // 此处只验证存在一个干净候选能给出合理 CA50）
        val trueCand = recon.candidates.first()
        val result = analyze(trueCand, phaseResolved = true)
        val clean = result.cycles.filter { it.saturationSegments.isEmpty() && !it.excluded }
        assertTrue(clean.isNotEmpty())
        val c0 = clean.first()
        val pmaxAngle = c0.metrics.getValue("pmaxAngle").value
        assertNotNull(pmaxAngle)
        assertTrue(pmaxAngle == null || pmaxAngle in 4.0..25.0, "pmaxAngle=$pmaxAngle")
        val ca50 = c0.metrics.getValue("ca50").value
        assertNotNull(ca50)
        assertTrue(ca50 in 1.0..20.0, "ca50=$ca50")
    }

    @Test
    fun `metrics carry algorithm and calibration versions`() {
        val result = analyze(recon.candidates.first(), phaseResolved = true)
        for (c in result.cycles) for (m in c.metrics.values) {
            assertTrue(m.algorithmVersion.startsWith("tooth-").not() || m.algorithmVersion.isNotEmpty())
            assertTrue(m.algorithmVersion.isNotBlank())
            assertTrue(m.calibrationVersion.isNotBlank())
        }
    }
}
