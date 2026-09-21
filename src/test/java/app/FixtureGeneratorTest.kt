package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureGeneratorTest {

    private val run = FixtureGenerator.generate()

    @Test
    fun `fixture is deterministic`() {
        val a = FixtureGenerator.generate(nowMs = 1)
        val b = FixtureGenerator.generate(nowMs = 2)
        a.pressure.bar.indices.forEach { i ->
            assertEquals(a.pressure.bar[i], b.pressure.bar[i], 1e-12)
        }
        a.teeth.tMs.indices.forEach { i ->
            assertEquals(a.teeth.tMs[i], b.teeth.tMs[i], 1e-12)
        }
    }

    @Test
    fun `rpm ramps upward and covers range`() {
        val rpms = run.condition.rpm
        assertTrue(rpms.first() < 1300.0, "start rpm=${rpms.first()}")
        assertTrue(rpms.last() > 2100.0, "end rpm=${rpms.last()}")
    }

    @Test
    fun `exactly two consecutive teeth are dropped producing a 5-interval long gap`() {
        val recon = AngleReconstructor.reconstruct(run)
        val spans = recon.intervals.map { it.spanIntervals }
        // 一个 5 齿距长跨距 + 其余参考跨距都是 3，无孤立 2
        assertEquals(1, spans.count { it >= 4 })
        assertEquals(5, spans.first { it >= 4 })
        assertTrue(spans.none { it == 2 })
    }

    @Test
    fun `faulty revolution emits no reference marker`() {
        val refs = run.markers.referenceGapMs
        // 转边界锚点 357°,717°,...,2877° 共 8 个；故障转（1437°）缺失 -> 7 个
        assertEquals(7, refs.size)
        // 存在不确定跨距提示
        assertEquals(1, run.markers.uncertainGapMs.size)
    }

    @Test
    fun `one later cycle contains saturated pressure samples`() {
        val satCount = run.pressure.saturated.count { it }
        assertTrue(satCount >= 10, "satCount=$satCount")
        // 饱和样本时间应落在 1440° 之后的燃烧循环（约 60ms 后，依据转速）
        val firstSat = run.pressure.saturated.indexOfFirst { it }
        assertTrue(firstSat > 0)
    }
}
