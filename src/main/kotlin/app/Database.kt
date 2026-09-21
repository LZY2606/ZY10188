package app

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class Database(private val path: Path) {

    private val inMemory = path.toString().let { it == ":memory:" || it == "jdbc:sqlite::memory:" }
    private val persistent: Connection =
        DriverManager.getConnection(if (inMemory) "jdbc:sqlite::memory:" else null.let {
            val raw = path.toString()
            if (raw.startsWith("jdbc:sqlite:")) raw else "jdbc:sqlite:$raw"
        }).apply {
            createStatement().use { it.executeUpdate("PRAGMA journal_mode=WAL") }
        }

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        if (inMemory) return block(persistent)
        DriverManager.getConnection("jdbc:sqlite:$path").use { return block(it) }
    }

    fun close() {
        if (inMemory) persistent.close()
    }

    fun init() {
        if (path.parent != null) Files.createDirectories(path.parent)
        withConnection { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS runs (
                        id TEXT PRIMARY KEY,
                        payload TEXT NOT NULL,
                        imported_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS state (
                        run_id TEXT PRIMARY KEY,
                        payload TEXT NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS actions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id TEXT NOT NULL,
                        at_ms INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    fun upsertRun(run: Run) {
        withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO runs(id, payload, imported_at_ms) VALUES (?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, imported_at_ms=excluded.imported_at_ms
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, run.id)
                ps.setString(2, JsonCodec.print(Codecs.runToJson(run)))
                ps.setLong(3, run.createdAtMs)
                ps.executeUpdate()
            }
        }
    }

    fun loadRun(id: String): Run? = withConnection { c ->
        c.prepareStatement("SELECT payload FROM runs WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) Codecs.runFromJson(JsonCodec.parse(rs.getString(1))) else null
            }
        }
    }

    fun listRunIds(): List<String> = withConnection { c ->
        c.createStatement().executeQuery("SELECT id FROM runs ORDER BY id").use { rs ->
            val out = ArrayList<String>()
            while (rs.next()) out.add(rs.getString(1))
            out
        }
    }

    fun saveState(state: AnalysisState) {
        withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO state(run_id, payload, updated_at_ms) VALUES (?, ?, ?)
                ON CONFLICT(run_id) DO UPDATE SET payload=excluded.payload, updated_at_ms=excluded.updated_at_ms
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, state.runId)
                ps.setString(2, JsonCodec.print(Codecs.stateToJson(state)))
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    fun loadState(runId: String): AnalysisState? = withConnection { c ->
        c.prepareStatement("SELECT payload FROM state WHERE run_id = ?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                if (rs.next()) Codecs.stateFromJson(JsonCodec.parse(rs.getString(1))) else null
            }
        }
    }

    fun addAction(action: ActionRecord) {
        withConnection { c ->
            c.prepareStatement(
                "INSERT INTO actions(run_id, at_ms, type, payload) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, action.runId)
                ps.setLong(2, action.atMs)
                ps.setString(3, action.type)
                ps.setString(4, action.payload)
                ps.executeUpdate()
            }
        }
    }

    fun listActions(runId: String): List<ActionRecord> = withConnection { c ->
        c.prepareStatement("SELECT id, run_id, at_ms, type, payload FROM actions WHERE run_id = ? ORDER BY id")
            .use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<ActionRecord>()
                    while (rs.next()) {
                        out.add(
                            ActionRecord(
                                id = rs.getLong(1),
                                runId = rs.getString(2),
                                atMs = rs.getLong(3),
                                type = rs.getString(4),
                                payload = rs.getString(5),
                            )
                        )
                    }
                    out
                }
            }
    }

    fun clearAll() {
        withConnection { c ->
            c.createStatement().use { st ->
                st.executeUpdate("DELETE FROM actions")
                st.executeUpdate("DELETE FROM state")
                st.executeUpdate("DELETE FROM runs")
            }
        }
    }
}
