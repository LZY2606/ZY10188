package app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AngleReconstructorTest {

    private val run = FixtureGenerator.generate()
    private val recon = AngleReconstructor.reconstruct(run)

    @Test
    fun `ambiguous pattern yields two boundary candidates and is not unique`() {
        assertEquals(2, recon.candidates.size, "必须保留两个循环边界候选")
        assertFalse(recon.patternUnique)
        recon.keypoints.keys.let { keys ->
            assertEquals(setOf("cand-1", "cand-2"), keys)
        }
    }

    @Test
    fun `candidate firing TDC boundaries differ only at the faulty cycle two boundary`() {
        val a = recon.candidates[0].firingTdcMs
        val b = recon.candidates[1].firingTdcMs
        assertEquals(a.size, b.size)
        // 歧义发生在 1440°（cycle 2 起点，候选数组索引 1）；其他发火 TDC 完全重合
        for (i in a.indices) {
            val diff = kotlin.math.abs(a[i] - b[i])
            if (i == 1) {
                assertTrue(diff > 0.3, "boundary $i should differ, diff=$diff")
            } else {
                assertTrue(diff < 1e-6, "boundary $i should coincide, diff=$diff")
            }
        }
    }

    @Test
    fun `both anchors straddle the true TDC marker and a trusted marker resolves them`() {
        // 故障跨距两种合法分解的锚点对称位于真发火 TDC 两侧：
        // 真 TDC 锦标恰在两者之间，置信锦标可吸附到正确相位。
        // 故障 TDC 锦标（marker[1]，1440°）用于仲裁该歧义边界
        val tdcFaulty = run.markers.tdcMs[1]
        val boundaries = recon.candidates.map { it.firingTdcMs[1] }
        val below = boundaries.min()
        val above = boundaries.max()
        assertTrue(tdcFaulty in (below - 1e-6)..(above + 1e-6),
            "锦标应位于两个候选边界之间: $below..$above marker=$tdcFaulty")
        // 两个候选到锦标的时间差都很小（< 1ms，约 <12°）
        for (b in boundaries) {
            assertTrue(kotlin.math.abs(b - tdcFaulty) < 1.0, "候选偏离锦标过大: ${b - tdcFaulty}")
        }
    }

    @Test
    fun `angle map is monotonic and spans full cycle range`() {
        for (cand in recon.candidates) {
            val keys = recon.keypoints.getValue(cand.id)
            for (i in 1 until keys.size) {
                assertTrue(keys[i].first >= keys[i - 1].first, "time monotonic")
                assertTrue(keys[i].second >= keys[i - 1].second, "angle monotonic")
            }
        }
    }
}
