package app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseReplayTest {

    private fun tempDb(): Database {
        val file = Files.createTempFile("ppm-test-", ".sqlite")
        Files.deleteIfExists(file)
        return Database(file)
    }

    @Test
    fun `run and analysis state survive clear and reimport replay`() {
        val db = tempDb()
        db.init()
        val service = AnalysisService(db)
        val run = FixtureGenerator.generate()
        db.upsertRun(run)
        val state0 = AnalysisState(
            runId = run.id,
            patternConfirmed = true,
            patternCandidateId = "cand-1",
            trustedTdcIndex = 2,
            excludedCycleIndexes = listOf(2),
            cycleOffsetsDeg = mapOf(0 to 1.5),
        )
        db.saveState(state0)
        service.recordAction(run.id, "confirm-pattern", "cand-1")

        val bundle = ExportBundle(
            run = db.loadRun(run.id)!!,
            state = db.loadState(run.id)!!,
            actions = db.listActions(run.id).map { ActionDto(it.id, it.atMs, it.type, it.payload) },
        )
        val json = JsonCodec.print(DtoJson.exportBundle(bundle))

        // 清空后重新导入复核
        db.clearAll()
        assertEquals(emptyList(), db.listRunIds())
        val restored = DtoJson.exportBundleFrom(JsonCodec.parse(json))
        db.upsertRun(restored.run)
        db.saveState(restored.state)

        val state2 = db.loadState(run.id)!!
        assertEquals(true, state2.patternConfirmed)
        assertEquals("cand-1", state2.patternCandidateId)
        assertEquals(2, state2.trustedTdcIndex)
        assertEquals(listOf(2), state2.excludedCycleIndexes)
        assertEquals(1.5, state2.cycleOffsetsDeg[0])

        val rerun = db.loadRun(run.id)!!
        assertEquals(run.pressure.bar.size, rerun.pressure.bar.size)
        run.pressure.bar.indices.forEach { i ->
            assertEquals(run.pressure.bar[i], rerun.pressure.bar[i], 1e-12)
        }

        // 重放后分析结果可复现
        val recon = service.recon(rerun)
        val dto = service.analysisDto(rerun, state2, recon)
        assertEquals(2, dto.candidates.size)
        val c2 = dto.cycles.first { it.cycleIndex == 2 }
        assertTrue(c2.excluded)
        val saturated = dto.cycles.first { it.saturationSegments.isNotEmpty() }
        assertEquals(1, saturated.cycleIndex)
    }
}
