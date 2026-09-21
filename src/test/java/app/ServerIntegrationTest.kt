package app

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerIntegrationTest {

    private fun newDb(): Database {
        val db = Database(java.nio.file.Paths.get("jdbc:sqlite::memory:"))
        db.init()
        val run = FixtureGenerator.generate()
        db.upsertRun(run)
        db.saveState(AnalysisService(db).defaultState(run.id))
        return db
    }

    @Test
    fun `health and page contain the product name`() = testApplication {
        val db = newDb()
        application { AppServer(db, AnalysisService(db)).run { configure() } }
        val health = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("缸压相位镜"))
        val page = client.get("/web/index.html")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("缸压相位镜"))
    }

    @Test
    fun `full workflow keeps candidates until confirmation then resolves CA50`() = testApplication {
        val db = newDb()
        val service = AnalysisService(db)
        application { AppServer(db, service).run { configure() } }

        val initial = client.get("/api/runs/synthetic-crank-v1/analysis")
        assertEquals(HttpStatusCode.OK, initial.status)
        val initialDto = DtoJson.analysisFrom(JsonCodec.parse(initial.bodyAsText()))
        assertEquals(2, initialDto.candidates.size)
        assertTrue(initialDto.cycles.all { c ->
            c.metrics.first { it.key == "ca50" }.value == null
        })

        client.post("/api/runs/synthetic-crank-v1/confirm-pattern") {
            contentType(ContentType.Application.Json)
            setBody("""{"candidateId":"cand-1"}""")
        }
        var dto = DtoJson.analysisFrom(
            JsonCodec.parse(client.get("/api/runs/synthetic-crank-v1/analysis").bodyAsText()))
        assertTrue(dto.cycles.all { it.metrics.first { m -> m.key == "ca50" }.value == null })

        client.post("/api/runs/synthetic-crank-v1/trust-tdc") {
            contentType(ContentType.Application.Json)
            setBody("""{"tdcIndex":2}""")
        }
        dto = DtoJson.analysisFrom(
            JsonCodec.parse(client.get("/api/runs/synthetic-crank-v1/analysis").bodyAsText()))
        val clean = dto.cycles.filter { it.saturationSegments.isEmpty() }
        assertTrue(clean.any { it.metrics.first { m -> m.key == "ca50" }.value != null })
        val saturated = dto.cycles.first { it.saturationSegments.isNotEmpty() }
        assertTrue(saturated.metrics.first { it.key == "pmax" }.lowerBoundOnly)
    }
}
