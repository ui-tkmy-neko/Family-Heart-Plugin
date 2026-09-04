package nekouidaga.net.familyheartplugin.database

import nekouidaga.net.familyheartplugin.model.*
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PlayerDao {
    fun upsert(c: Connection, u: UUID, n: String) {
        val t = Timestamp.from(Instant.now())
        c.prepareStatement("INSERT INTO players(uuid,mcid,first_seen,last_seen) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET mcid=excluded.mcid,last_seen=excluded.last_seen").use {
            it.setString(1, u.toString()); it.setString(2, n); it.setTimestamp(3, t); it.setTimestamp(4, t); it.executeUpdate()
        }
    }
    fun byMcid(c: Connection, n: String): UUID? = c.prepareStatement("SELECT uuid FROM players WHERE lower(mcid)=lower(?) ORDER BY last_seen DESC LIMIT 1").use {
        it.setString(1, n.trim()); it.executeQuery().use { r -> if (r.next()) UUID.fromString(r.getString(1)) else null }
    }
}

class RelationshipDao {
    fun nextId(c: Connection): String {
        // SQLiteではDB書き込みが単一接続に直列化されるため、固定PKの行を更新して連番を進める。
        // 以前の実装ではPRIMARY KEY(id)自体をカウンタとして扱う誤りがあり、
        // WHERE句なしのUPDATEが重複キー例外を起こしていた。
        // PKは固定値(id=1)にし、カウンタは別カラム(next_value)に分離する。
        c.createStatement().use { it.executeUpdate("INSERT OR IGNORE INTO relationship_sequence(id,next_value) VALUES(1,1)") }
        c.prepareStatement("SELECT next_value FROM relationship_sequence WHERE id=1").use { p ->
            p.executeQuery().use { r ->
                r.next(); val n = r.getLong(1)
                c.prepareStatement("UPDATE relationship_sequence SET next_value=? WHERE id=1").use { u -> u.setLong(1, n + 1); u.executeUpdate() }
                return "REL-%06d".format(n)
            }
        }
    }
    fun insert(c: Connection, a: UUID, b: UUID, t: RelationshipType, ra: String, rb: String, auto: Boolean, sourceRelationshipId: String? = null): FamilyRelationship {
        val now = Instant.now(); val id = nextId(c)
        c.prepareStatement("INSERT INTO relationships(relationship_id,player_a,player_b,type,role_a,role_b,auto_added,auto_source_relationship_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,?)").use {
            it.setString(1, id); it.setString(2, a.toString()); it.setString(3, b.toString()); it.setString(4, t.name); it.setString(5, ra); it.setString(6, rb); it.setBoolean(7, auto); it.setString(8, sourceRelationshipId); it.setTimestamp(9, Timestamp.from(now)); it.setTimestamp(10, Timestamp.from(now)); it.executeUpdate()
            val internalId = c.createStatement().use { st ->
                st.executeQuery("SELECT last_insert_rowid()").use { r ->
                    if (!r.next()) throw IllegalStateException("relationship insert id missing")
                    r.getLong(1)
                }
            }
            return FamilyRelationship(internalId, id, a, b, t, ra, rb, auto, sourceRelationshipId, RelationshipStatus.ACTIVE, now, now)
        }
    }
    private fun map(r: ResultSet) = FamilyRelationship(r.getLong("internal_id"), r.getString("relationship_id"), UUID.fromString(r.getString("player_a")), UUID.fromString(r.getString("player_b")), RelationshipType.valueOf(r.getString("type")), r.getString("role_a"), r.getString("role_b"), r.getBoolean("auto_added"), r.getString("auto_source_relationship_id"), RelationshipStatus.valueOf(r.getString("status")), r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant())
    fun byPlayer(c: Connection, u: UUID, t: RelationshipType? = null, lock: Boolean = false): List<FamilyRelationship> {
        val sql = buildString {
            append("SELECT * FROM relationships WHERE (player_a=? OR player_b=?) AND status='ACTIVE'")
            if (t != null) append(" AND type=?")
            if (lock) append("")
        }
        c.prepareStatement(sql).use { st ->
            var i = 1
            st.setString(i++, u.toString())
            st.setString(i++, u.toString())
            if (t != null) st.setString(i++, t.name)
            return st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
        }
    }
    fun between(c: Connection, a: UUID, b: UUID, t: RelationshipType, lock: Boolean = false): List<FamilyRelationship> {
        val q = "SELECT * FROM relationships WHERE ((player_a=? AND player_b=?) OR (player_a=? AND player_b=?)) AND type=? AND status='ACTIVE'" + if (lock) "" else ""
        c.prepareStatement(q).use { it.setString(1, a.toString()); it.setString(2, b.toString()); it.setString(3, b.toString()); it.setString(4, a.toString()); it.setString(5, t.name); return it.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } } }
    }
    fun autoAddedParentRelationsForSource(c: Connection, sourceRelationshipId: String, lock: Boolean = false): List<FamilyRelationship> {
        val sql = "SELECT * FROM relationships WHERE auto_source_relationship_id=? AND type=? AND status='ACTIVE' AND auto_added=TRUE" + if (lock) "" else ""
        c.prepareStatement(sql).use { st ->
            st.setString(1, sourceRelationshipId)
            st.setString(2, RelationshipType.PARENT_CHILD.name)
            return st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
        }
    }

    fun allActive(c: Connection): List<FamilyRelationship> =
        c.prepareStatement("SELECT * FROM relationships WHERE status='ACTIVE' ORDER BY internal_id").use { st ->
            st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
        }

    fun byPlayers(c: Connection, players: Collection<UUID>): Map<UUID, List<FamilyRelationship>> {
        if (players.isEmpty()) return emptyMap()
        val ids = players.map(UUID::toString)
        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT * FROM relationships WHERE status='ACTIVE' AND (player_a IN ($placeholders) OR player_b IN ($placeholders))"
        val grouped = players.associateWith { mutableListOf<FamilyRelationship>() }
        c.prepareStatement(sql).use { st ->
            var i = 1
            ids.forEach { st.setString(i++, it) }
            ids.forEach { st.setString(i++, it) }
            st.executeQuery().use { r ->
                while (r.next()) {
                    val relation = map(r)
                    grouped[relation.playerA]?.add(relation)
                    grouped[relation.playerB]?.add(relation)
                }
            }
        }
        return grouped.mapValues { it.value.toList() }
    }

    fun byId(c: Connection, id: String, lock: Boolean = false) = c.prepareStatement("SELECT * FROM relationships WHERE relationship_id=? AND status='ACTIVE'" + if (lock) "" else "").use { it.setString(1, id); it.executeQuery().use { r -> if (r.next()) map(r) else null } }
    fun remove(c: Connection, id: Long) { c.prepareStatement("UPDATE relationships SET status='REMOVED',updated_at=? WHERE internal_id=?").use { it.setTimestamp(1, Timestamp.from(Instant.now())); it.setLong(2, id); it.executeUpdate() } }
    fun history(c: Connection, id: String, action: String, actor: UUID?, target: UUID?, reason: String?) { c.prepareStatement("INSERT INTO relationship_history(relationship_id,action,actor,target,reason,created_at) VALUES(?,?,?,?,?,?)").use { it.setString(1, id); it.setString(2, action); it.setString(3, actor?.toString()); it.setString(4, target?.toString()); it.setString(5, reason); it.setTimestamp(6, Timestamp.from(Instant.now())); it.executeUpdate() } }
}

class ActionDao {
    fun record(c: Connection, a: UUID, t: UUID, x: String) {
        c.prepareStatement("INSERT INTO actions(actor,target,action,created_at,state,request_id) VALUES(?,?,?,?,'EXECUTED',NULL)").use {
            it.setString(1, a.toString()); it.setString(2, t.toString()); it.setString(3, x); it.setTimestamp(4, Timestamp.from(Instant.now())); it.executeUpdate()
        }
    }
    fun beginRequestAction(c: Connection, requestId: Long, a: UUID, t: UUID, x: String): ActionExecutionState {
        c.prepareStatement("INSERT OR IGNORE INTO actions(actor,target,action,created_at,state,request_id) VALUES(?,?,?,?,'INTENT',?)").use {
            it.setString(1, a.toString()); it.setString(2, t.toString()); it.setString(3, x); it.setTimestamp(4, Timestamp.from(Instant.now())); it.setLong(5, requestId); it.executeUpdate()
        }
        return c.prepareStatement("SELECT state FROM actions WHERE request_id=?").use {
            it.setLong(1, requestId); it.executeQuery().use { r ->
                if (!r.next()) throw IllegalStateException("action intent missing")
                ActionExecutionState.valueOf(r.getString(1))
            }
        }
    }
    fun markRequestActionExecuted(c: Connection, requestId: Long): Boolean =
        c.prepareStatement("UPDATE actions SET state='EXECUTED' WHERE request_id=? AND state='INTENT'").use { it.setLong(1, requestId); it.executeUpdate() > 0 }
    fun requestActionState(c: Connection, requestId: Long): ActionExecutionState? =
        c.prepareStatement("SELECT state FROM actions WHERE request_id=?").use { it.setLong(1, requestId); it.executeQuery().use { r -> if (r.next()) ActionExecutionState.valueOf(r.getString(1)) else null } }
}

class PenaltyDao {
    fun add(c: Connection, t: PenaltyTargetType, p: UUID?, rel: String?, e: String, v: Double, m: Double, end: Instant?, rem: Boolean): Long { c.prepareStatement("INSERT INTO family_penalties(target_type,target_player,target_relationship,effect,value,multiplier,started_at,ends_at,removable,active) VALUES(?,?,?,?,?,?,?,?,?,TRUE)").use { it.setString(1, t.name); it.setString(2, p?.toString()); it.setString(3, rel); it.setString(4, e); it.setDouble(5, v); it.setDouble(6, m); it.setTimestamp(7, Timestamp.from(Instant.now())); it.setTimestamp(8, end?.let(Timestamp::from)); it.setBoolean(9, rem); it.executeUpdate()
        return c.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { r ->
                if (!r.next()) throw IllegalStateException("penalty insert id missing")
                r.getLong(1)
            }
        } } }
    fun byId(c: Connection, id: Long): FamilyPenalty? = c.prepareStatement("SELECT * FROM family_penalties WHERE id=?").use {
        it.setLong(1, id)
        it.executeQuery().use { r -> if (r.next()) mapPenalty(r) else null }
    }
    fun remove(c: Connection, id: Long): Boolean { c.prepareStatement("UPDATE family_penalties SET active=FALSE WHERE id=? AND removable=TRUE AND active=TRUE").use { it.setLong(1, id); return it.executeUpdate() > 0 } }
    private fun mapPenalty(r: ResultSet) = FamilyPenalty(r.getLong("id"), PenaltyTargetType.valueOf(r.getString("target_type")), r.getString("target_player")?.let(UUID::fromString), r.getString("target_relationship"), r.getString("effect"), r.getDouble("value"), r.getDouble("multiplier"), r.getTimestamp("started_at").toInstant(), r.getTimestamp("ends_at")?.toInstant(), r.getBoolean("removable"), r.getBoolean("active"))
    // バグ修正(⑥): 以前はtarget_type='PLAYER' AND target_player=? のみ取得しており、
    // FAMILY(家族全体)・RELATIONSHIP(特定の関係)向けペナルティが常に無視されていた。
    // ここではプレイヤー個人向けのみ返す。FAMILY/RELATIONSHIP分はPenaltyServiceが
    // familyPenalties()/relationshipPenalties()と組み合わせて判定する。
    fun forPlayer(c: Connection, u: UUID): List<FamilyPenalty> = c.prepareStatement("SELECT * FROM family_penalties WHERE active=TRUE AND target_type='PLAYER' AND target_player=?").use { it.setString(1, u.toString()); it.executeQuery().use { r -> buildList { while (r.next()) add(mapPenalty(r)) } } }
    /** FAMILY型のペナルティ全件(対象家族はtarget_playerを起点とした家族グラフで判定する)。 */
    fun familyPenalties(c: Connection): List<FamilyPenalty> = c.prepareStatement("SELECT * FROM family_penalties WHERE active=TRUE AND target_type='FAMILY'").use { it.executeQuery().use { r -> buildList { while (r.next()) add(mapPenalty(r)) } } }
    /** RELATIONSHIP型のペナルティのうち、指定されたRelationship IDに一致するもの。 */
    fun relationshipPenalties(c: Connection, relationshipIds: Collection<String>): List<FamilyPenalty> {
        if (relationshipIds.isEmpty()) return emptyList()
        val placeholders = relationshipIds.joinToString(",") { "?" }
        return c.prepareStatement("SELECT * FROM family_penalties WHERE active=TRUE AND target_type='RELATIONSHIP' AND target_relationship IN ($placeholders)").use { ps ->
            relationshipIds.forEachIndexed { i, id -> ps.setString(i + 1, id) }
            ps.executeQuery().use { r -> buildList { while (r.next()) add(mapPenalty(r)) } }
        }
    }
    fun expireReturning(c: Connection): List<FamilyPenalty> {
        val expired = c.prepareStatement("SELECT * FROM family_penalties WHERE active=TRUE AND ends_at IS NOT NULL AND ends_at<=CURRENT_TIMESTAMP").use {
            it.executeQuery().use { r -> buildList { while (r.next()) add(mapPenalty(r)) } }
        }
        if (expired.isNotEmpty()) {
            c.prepareStatement("UPDATE family_penalties SET active=FALSE WHERE active=TRUE AND ends_at IS NOT NULL AND ends_at<=CURRENT_TIMESTAMP").use { it.executeUpdate() }
        }
        return expired
    }
    fun expire(c: Connection): Int = expireReturning(c).size
}
class AuditDao { fun log(c: Connection, a: UUID?, x: String, t: UUID?, rel: String?, res: String, reason: String?) { c.prepareStatement("INSERT INTO audit_log(actor,action,target_player,relationship_id,result,reason,created_at) VALUES(?,?,?,?,?,?,?)").use { it.setString(1, a?.toString()); it.setString(2, x); it.setString(3, t?.toString()); it.setString(4, rel); it.setString(5, res); it.setString(6, reason); it.setTimestamp(7, Timestamp.from(Instant.now())); it.executeUpdate() } } }
