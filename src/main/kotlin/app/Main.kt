package app

import java.nio.file.Path
import java.nio.file.Paths

fun main(args: Array<String>) {
    var port = 5528
    var dbPath: Path = Paths.get("data", "pressure-phase-mirror.sqlite")
    var seed = true

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(i + 1)?.toIntOrNull() ?: port; i += 2 }
            "--db" -> { dbPath = Paths.get(args[i + 1]); i += 2 }
            "--no-seed" -> { seed = false; i += 1 }
            else -> i += 1
        }
    }

    val db = Database(dbPath)
    db.init()
    val service = AnalysisService(db)

    if (seed) {
        val run = FixtureGenerator.generate()
        if (db.loadRun(run.id) == null) {
            db.upsertRun(run)
            db.saveState(service.defaultState(run.id))
            db.addAction(
                ActionRecord(
                    runId = run.id,
                    atMs = System.currentTimeMillis(),
                    type = "seed-fixture",
                    payload = "run=${run.id}",
                )
            )
        }
    }

    println("缸压相位镜 已启动：http://127.0.0.1:$port")
    AppServer(db, service) { FixtureGenerator.generate() }.start(port)
}
