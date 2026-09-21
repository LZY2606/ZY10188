package app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets

data class ConfirmPatternRequest(val candidateId: String)
data class TrustTdcRequest(val tdcIndex: Int)
data class OffsetRequest(val cycleIndex: Int, val offsetDeg: Double)
data class ExcludeRequest(val cycleIndex: Int, val excluded: Boolean)

data class ExportBundle(
    val run: Run,
    val state: AnalysisState,
    val actions: List<ActionDto>,
)

class AppServer(
    private val db: Database,
    private val service: AnalysisService,
    private val runLoader: () -> Run = { FixtureGenerator.generate() },
) {
    fun start(port: Int) {
        embeddedServer(Netty, port = port, host = "127.0.0.1") { configure() }.start(wait = true)
    }

    fun Application.configure() {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, JsonConverter())
        }
        routing {
            get("/api/health") {
                call.respond(JsonCodec.obj("status" to "ok", "name" to "缸压相位镜"))
            }

            get("/api/runs") {
                call.respond(JsonCodec.toJson(db.listRunIds()))
            }

            post("/api/runs/import") {
                val body = JsonCodec.parse(call.receiveText())
                val run = try { Codecs.runFromJson(body) } catch (e: Exception) {
                    return@post call.respond(io.ktor.http.content.TextContent(
                        JsonCodec.print(JsonCodec.obj("error" to (e.message ?: "parse error"))),
                        ContentType.Application.Json, HttpStatusCode.BadRequest))
                }
                db.upsertRun(run)
                db.saveState(service.defaultState(run.id))
                service.recordAction(run.id, "import", "run=${run.id}")
                call.respond(DtoJson.runSummary(service.summary(run)))
            }

            post("/api/runs/import-fixture") {
                val run = runLoader()
                db.upsertRun(run)
                if (db.loadState(run.id) == null) db.saveState(service.defaultState(run.id))
                service.recordAction(run.id, "import-fixture", "run=${run.id}")
                call.respond(DtoJson.runSummary(service.summary(run)))
            }

            get("/api/runs/{id}") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@get call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                call.respond(DtoJson.runSummary(service.summary(run)))
            }

            get("/api/runs/{id}/raw") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@get call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                call.respond(DtoJson.raw(service.raw(run)))
            }

            get("/api/runs/{id}/analysis") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@get call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                call.respond(DtoJson.analysis(service.analysisDto(run, service.getState(run.id), service.recon(run))))
            }

            post("/api/runs/{id}/confirm-pattern") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@post call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                val req = DtoJson.confirmPattern(JsonCodec.parse(call.receiveText()))
                val recon = service.recon(run)
                require(recon.candidates.any { it.id == req.candidateId }) { "候选不存在" }
                val state = service.getState(run.id).copy(
                    patternConfirmed = true,
                    patternCandidateId = req.candidateId,
                )
                service.setState(state)
                service.recordAction(run.id, "confirm-pattern", req.candidateId)
                call.respond(DtoJson.analysis(service.analysisDto(run, state, recon)))
            }

            post("/api/runs/{id}/trust-tdc") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@post call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                val req = DtoJson.trustTdc(JsonCodec.parse(call.receiveText()))
                require(req.tdcIndex in run.markers.tdcMs.indices) { "TDC 锦标索引越界" }
                val state = service.getState(run.id).copy(trustedTdcIndex = req.tdcIndex)
                service.setState(state)
                service.recordAction(run.id, "trust-tdc", "index=${req.tdcIndex}")
                call.respond(DtoJson.analysis(service.analysisDto(run, state, service.recon(run))))
            }

            post("/api/runs/{id}/cycle-offset") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@post call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                val req = DtoJson.offset(JsonCodec.parse(call.receiveText()))
                val state0 = service.getState(run.id)
                val offsets = state0.cycleOffsetsDeg.toMutableMap()
                offsets[req.cycleIndex] = req.offsetDeg
                val state = state0.copy(cycleOffsetsDeg = offsets)
                service.setState(state)
                service.recordAction(run.id, "cycle-offset",
                    "cycle=${req.cycleIndex},offsetDeg=${req.offsetDeg}")
                call.respond(DtoJson.analysis(service.analysisDto(run, state, service.recon(run))))
            }

            post("/api/runs/{id}/exclude-saturation") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@post call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                val req = DtoJson.exclude(JsonCodec.parse(call.receiveText()))
                val state0 = service.getState(run.id)
                val excluded = state0.excludedCycleIndexes.toMutableSet()
                if (req.excluded) excluded.add(req.cycleIndex) else excluded.remove(req.cycleIndex)
                val state = state0.copy(excludedCycleIndexes = excluded.sorted())
                service.setState(state)
                service.recordAction(run.id, "exclude-saturation",
                    "cycle=${req.cycleIndex},excluded=${req.excluded}")
                call.respond(DtoJson.analysis(service.analysisDto(run, state, service.recon(run))))
            }

            get("/api/runs/{id}/actions") {
                val runId = call.parameters["id"]!!
                call.respond(DtoJson.actions(db.listActions(runId)))
            }

            get("/api/runs/{id}/export") {
                val run = db.loadRun(call.parameters["id"] ?: "") ?: return@get call.respond(io.ktor.http.content.TextContent(JsonCodec.print(JsonCodec.obj("error" to "run not found")), ContentType.Application.Json, HttpStatusCode.NotFound))
                val bundle = ExportBundle(
                    run = run,
                    state = service.getState(run.id),
                    actions = db.listActions(run.id).map {
                        ActionDto(it.id, it.atMs, it.type, it.payload)
                    },
                )
                val text = JsonCodec.print(DtoJson.exportBundle(bundle))
                call.response.headers.append(
                    io.ktor.http.HttpHeaders.ContentDisposition,
                    "attachment; filename=\"run-${run.id}-export.json\""
                )
                call.respond(io.ktor.http.content.TextContent(
                    text, ContentType.Application.Json, HttpStatusCode.OK))
            }

            post("/api/runs/replay") {
                val bundle = try {
                    DtoJson.exportBundleFrom(JsonCodec.parse(call.receiveText()))
                } catch (e: Exception) {
                    return@post call.respond(io.ktor.http.content.TextContent(
                        JsonCodec.print(JsonCodec.obj("error" to (e.message ?: "parse error"), "type" to e::class.simpleName.orEmpty())),
                        ContentType.Application.Json, HttpStatusCode.BadRequest))
                }
                db.upsertRun(bundle.run)
                db.saveState(bundle.state)
                service.recordAction(bundle.run.id, "replay-import", "actions=${bundle.actions.size}")
                call.respond(DtoJson.runSummary(service.summary(bundle.run)))
            }

            post("/api/admin/clear") {
                db.clearAll()
                call.respond(JsonCodec.obj("cleared" to true))
            }

            get("/") {
                call.respondRedirect("/web/index.html")
            }

            get("/web/{static...}") {
                val rel = call.parameters.getAll("static")?.joinToString("/") ?: "index.html"
                val safe = rel.substringBefore("?").trimStart('/')
                val stream = javaClass.classLoader.getResourceAsStream("web/$safe")
                if (stream == null) {
                    call.respond(io.ktor.http.content.TextContent(
                        JsonCodec.print(JsonCodec.obj("error" to "not found")),
                        ContentType.Application.Json, HttpStatusCode.NotFound))
                } else {
                    val text = stream.bufferedReader(StandardCharsets.UTF_8).readText()
                    val ct = when {
                        safe.endsWith(".html") -> ContentType.Text.Html
                        safe.endsWith(".js") -> ContentType.Application.JavaScript
                        safe.endsWith(".css") -> ContentType.Text.CSS
                        else -> ContentType.Application.OctetStream
                    }
                    call.respond(io.ktor.http.content.TextContent(text, ct, HttpStatusCode.OK))
                }
            }
        }
    }
}
