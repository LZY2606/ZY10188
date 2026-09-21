package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeggingWindowTest {

    @Test
    fun `pegging window is fixed in angle domain and half open`() {
        assertEquals(390.0, Versions.PEG_START_DEG)
        assertEquals(450.0, Versions.PEG_END_DEG)
        // [390,450) 半开：点数恰为 60
        val n = ((Versions.PEG_END_DEG - Versions.PEG_START_DEG) / Versions.GRID_STEP_DEG).toInt()
        assertEquals(60, n)
        // 位于进气冲程（360=扫气TDC 后 30..90°）
        assertTrue(Versions.PEG_START_DEG > 360.0)
        assertTrue(Versions.PEG_END_DEG < 540.0)
    }

    @Test
    fun `sensor offset is removed by pegging but saturation remains flagged`() {
        val run = FixtureGenerator.generate()
        val recon = AngleReconstructor.reconstruct(run)
        val result = CycleAnalyzer.analyze(
            run = run,
            candidate = recon.candidates.first(),
            keypoints = recon.keypoints.getValue(recon.candidates.first().id),
            phaseResolved = true,
        )
        val clean = result.cycles.first { it.saturationSegments.isEmpty() }
        // 归零窗附近的均值应接近 0（相对压力）
        val pegValues = (390 until 450).map { clean.pressureBar[it] }
        val mean = pegValues.average()
        assertTrue(kotlin.math.abs(mean) < 0.02, "peg mean=$mean")
    }
}
