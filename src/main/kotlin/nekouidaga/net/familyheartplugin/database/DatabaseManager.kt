package nekouidaga.net.familyheartplugin.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.file.FileConfiguration
import java.sql.Connection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * SQLite-backed persistence for FamilyHeart.
 *
 * SQLite is intentionally used with a single Hikari connection. SQLite supports
 * concurrent readers, but only one writer at a time; serialising JDBC work here
 * avoids lock storms while all plugin-side writes remain asynchronous.
 */
class DatabaseManager(private val log: Logger) {
    private lateinit var ds: HikariDataSource
    // SQLiteはHikariの単一コネクション(maximumPoolSize=1)で書き込みを直列化している。
    // ワーカースレッド数を無条件に8とすると、その全てが同時にDB処理を要求した場合、
    // 単一コネクションの取得待ちで connectionTimeout に近づくレイテンシが発生し得る
    // (機能上のバグではないが、負荷が高い環境向けに調整可能にしておく)。
    private lateinit var executorImpl: ExecutorService
    val executor: ExecutorService get() = executorImpl

    fun connect(c: FileConfiguration) {
        val file = c.getString("database.sqlite.file", "familyheart.db") ?: "familyheart.db"
        val busyTimeout = c.getLong("database.sqlite.busy-timeout-ms", 10_000L)
        val maximumPool = 1
        // SQLite書き込みはmaximumPool=1で直列化されるため、ワーカースレッドを増やしても
        // 実際のDBスループットは上がらず、逆に単一コネクション待ちで詰まるスレッドが増えるだけ。
        // デフォルトはmaximumPoolに合わせて1にし、必要であれば設定で増やせるようにする。
        val workerThreads = c.getInt("database.sqlite.worker-threads", maximumPool).coerceIn(1, 8)
        executorImpl = Executors.newFixedThreadPool(workerThreads) {
            Thread(it, "FamilyHeart-DB").apply { isDaemon = true }
        }

        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            maximumPoolSize = maximumPool
            minimumIdle = 1
            connectionTimeout = c.getLong("database.sqlite.connection-timeout-ms", 10_000L)
            poolName = "FamilyHeart-SQLite-Pool"
            connectionInitSql = "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=$busyTimeout;"
        })

        initSchema()
        migrateSQLiteColumns()
        log.info("[FamilyHeart] SQLite database ready: $file")
    }

    fun connection(): Connection = ds.connection

    private fun migrateSQLiteColumns() {
        connection().use { conn ->
            ensureColumn(conn, "relationships", "auto_source_relationship_id", "VARCHAR(32)")
            ensureColumn(conn, "requests", "pending_key", "VARCHAR(160)")
            ensureColumn(conn, "requests", "processing_guard", "VARCHAR(32)")
            ensureColumn(conn, "actions", "state", "VARCHAR(16) NOT NULL DEFAULT 'EXECUTED'")
            ensureColumn(conn, "actions", "request_id", "INTEGER")

            // Existing SQLite databases are expected to have been created by this
            // plugin, so these indexes are safe to create idempotently.
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auto_source ON relationships(auto_source_relationship_id)")
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_request ON requests(pending_key)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_req_expire ON requests(status,created_at)")
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_actions_request ON actions(request_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_actions_state ON actions(state)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pen_expire ON family_penalties(active,ends_at)")
            }
        }
    }

    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        val exists = conn.prepareStatement("PRAGMA table_info($table)").use { ps ->
            ps.executeQuery().use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) {
                        found = true
                        break
                    }
                }
                found
            }
        }
        if (!exists) conn.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
    }

    private fun initSchema() {
        val statements = listOf(
            """CREATE TABLE IF NOT EXISTS players(
                uuid VARCHAR(36) PRIMARY KEY,
                mcid VARCHAR(32) NOT NULL,
                first_seen TIMESTAMP NOT NULL,
                last_seen TIMESTAMP NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS relationship_sequence(
                id INTEGER PRIMARY KEY,
                next_value BIGINT NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS relationships(
                internal_id INTEGER PRIMARY KEY AUTOINCREMENT,
                relationship_id VARCHAR(32) UNIQUE NOT NULL,
                player_a VARCHAR(36) NOT NULL,
                player_b VARCHAR(36) NOT NULL,
                type VARCHAR(32) NOT NULL,
                role_a VARCHAR(32) NOT NULL,
                role_b VARCHAR(32) NOT NULL,
                auto_added BOOLEAN NOT NULL DEFAULT FALSE,
                auto_source_relationship_id VARCHAR(32),
                status VARCHAR(16) NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS relationship_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                relationship_id VARCHAR(32) NOT NULL,
                action VARCHAR(64) NOT NULL,
                actor VARCHAR(36),
                target VARCHAR(36),
                reason TEXT,
                created_at TIMESTAMP NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS requests(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                requester VARCHAR(36) NOT NULL,
                target VARCHAR(36) NOT NULL,
                type VARCHAR(32) NOT NULL,
                metadata TEXT,
                status VARCHAR(16) NOT NULL,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL,
                pending_key VARCHAR(160),
                processing_guard VARCHAR(32)
            )""",
            """CREATE TABLE IF NOT EXISTS actions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                actor VARCHAR(36) NOT NULL,
                target VARCHAR(36) NOT NULL,
                action VARCHAR(32) NOT NULL,
                created_at TIMESTAMP NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'EXECUTED',
                request_id INTEGER
            )""",
            """CREATE TABLE IF NOT EXISTS family_penalties(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_type VARCHAR(16) NOT NULL,
                target_player VARCHAR(36),
                target_relationship VARCHAR(32),
                effect VARCHAR(64) NOT NULL,
                value DOUBLE NOT NULL,
                multiplier DOUBLE NOT NULL DEFAULT 1,
                started_at TIMESTAMP NOT NULL,
                ends_at TIMESTAMP,
                removable BOOLEAN NOT NULL DEFAULT TRUE,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )""",
            """CREATE TABLE IF NOT EXISTS audit_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                actor VARCHAR(36),
                action VARCHAR(64) NOT NULL,
                target_player VARCHAR(36),
                relationship_id VARCHAR(32),
                result VARCHAR(16) NOT NULL,
                reason TEXT,
                created_at TIMESTAMP NOT NULL
            )"""
        )

        connection().use { conn ->
            conn.createStatement().use { st -> statements.forEach(st::executeUpdate) }
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mcid ON players(mcid)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_a ON relationships(player_a,status)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_b ON relationships(player_b,status)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auto_source ON relationships(auto_source_relationship_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_hist ON relationship_history(relationship_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_req_target ON requests(target,status)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_req_processing ON requests(status,processing_guard,updated_at)")
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_request ON requests(pending_key)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_actions ON actions(actor,created_at)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_actions_state ON actions(state)")
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_actions_request ON actions(request_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pen_player ON family_penalties(target_player,active)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pen_expire ON family_penalties(active,ends_at)")
            }
            conn.prepareStatement("INSERT OR IGNORE INTO relationship_sequence(id,next_value) VALUES(1,1)").use { it.executeUpdate() }
        }
    }

    /** Recover durable request states after an abnormal shutdown. */
    fun recoverProcessingBlocking() {
        connection().use { c ->
            c.tx {
                c.createStatement().use { st ->
                    st.executeUpdate("""
                        UPDATE requests
                        SET status='ACCEPTED', processing_guard=NULL, pending_key=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE status='PROCESSING' AND type='SKINSHIP'
                          AND processing_guard IN ('SKINSHIP_INTENT','SKINSHIP_EXECUTED')
                          AND id IN (SELECT request_id FROM actions WHERE state='EXECUTED' AND request_id IS NOT NULL)
                    """.trimIndent())

                    st.executeUpdate("""
                        UPDATE requests
                        SET status='CANCELLED', processing_guard=NULL, pending_key=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE status='PROCESSING' AND type='SKINSHIP'
                          AND processing_guard IN ('SKINSHIP_INTENT','SKINSHIP_EXECUTED')
                          AND updated_at < datetime('now','-2 minutes')
                          AND NOT EXISTS (SELECT 1 FROM actions a WHERE a.request_id=requests.id AND a.state='EXECUTED')
                    """.trimIndent())

                    // The legacy custom-item feature was removed. RequestType no longer contains
                    // CUSTOM_ITEM, so old rows must not survive into RequestDao.valueOf(). Remove
                    // their request-linked action records first, then the obsolete request rows.
                    st.executeUpdate("DELETE FROM actions WHERE request_id IN (SELECT id FROM requests WHERE type='CUSTOM_ITEM')")
                    st.executeUpdate("DELETE FROM requests WHERE type='CUSTOM_ITEM'")

                    st.executeUpdate("""
                        UPDATE requests
                        SET status='PENDING', updated_at=CURRENT_TIMESTAMP
                        WHERE status='PROCESSING' AND processing_guard IS NULL
                          AND updated_at < datetime('now','-2 minutes')
                    """.trimIndent())
                }
            }
        }
    }

    fun shutdown() {
        if (::ds.isInitialized) ds.close()
        if (::executorImpl.isInitialized) executorImpl.shutdownNow()
    }
}

inline fun <T> Connection.tx(block: (Connection) -> T): T {
    val ac = autoCommit
    autoCommit = false
    return try {
        val value = block(this)
        commit()
        value
    } catch (e: Exception) {
        rollback()
        throw e
    } finally {
        autoCommit = ac
    }
}
