package nekouidaga.net.familyheartplugin.database

import nekouidaga.net.familyheartplugin.model.*
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PlayerDao {
    fun upsert(c: Connection, u: UUID, n: String) {
        val t = Timestamp.from(Instant.now())
        c.prepareStatement("INSERT INTO players(uuid,mcid,first_seen,last_seen) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE mcid=VALUES(mcid),last_seen=VALUES(last_seen)").use {
            it.setString(1, u.toString()); it.setString(2, n); it.setTimestamp(3, t); it.setTimestamp(4, t); it.executeUpdate()
        }
    }
    fun byMcid(c: Connection, n: String): UUID? = c.prepareStatement("SELECT uuid FROM players WHERE mcid=? ORDER BY last_seen DESC LIMIT 1").use {
        it.setString(1, n); it.executeQuery().use { r -> if (r.next()) UUID.fromString(r.getString(1)) else null }
    }
}

class RelationshipDao {
    fun nextId(c: Connection): String {
        c.createStatement().use { it.executeUpdate("INSERT INTO relationship_sequence(id) VALUES(0) ON DUPLICATE KEY UPDATE id=id") }
        c.prepareStatement("SELECT id FROM relationship_sequence FOR UPDATE").use { p ->
            p.executeQuery().use { r ->
                r.next(); val n = r.getLong(1) + 1
                c.prepareStatement("UPDATE relationship_sequence SET id=?").use { u -> u.setLong(1, n); u.executeUpdate() }
                return "REL-%06d".format(n)
            }
        }
    }
    fun insert(c: Connection, a: UUID, b: UUID, t: RelationshipType, ra: String, rb: String, auto: Boolean): FamilyRelationship {
        val now = Instant.now(); val id = nextId(c)
        c.prepareStatement("INSERT INTO relationships(relationship_id,player_a,player_b,type,role_a,role_b,auto_added,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?)", Statement.RETURN_GENERATED_KEYS).use {
            it.setString(1, id); it.setString(2, a.toString()); it.setString(3, b.toString()); it.setString(4, t.name); it.setString(5, ra); it.setString(6, rb); it.setBoolean(7, auto); it.setTimestamp(8, Timestamp.from(now)); it.setTimestamp(9, Timestamp.from(now)); it.executeUpdate()
            it.generatedKeys.use { r -> r.next(); return FamilyRelationship(r.getLong(1), id, a, b, t, ra, rb, auto, RelationshipStatus.ACTIVE, now, now) }
        }
    }
    private fun map(r: ResultSet) = FamilyRelationship(r.getLong("internal_id"), r.getString("relationship_id"), UUID.fromString(r.getString("player_a")), UUID.fromString(r.getString("player_b")), RelationshipType.valueOf(r.getString("type")), r.getString("role_a"), r.getString("role_b"), r.getBoolean("auto_added"), RelationshipStatus.valueOf(r.getString("status")), r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant())
    fun byPlayer(c: Connection, u: UUID, t: RelationshipType? = null, lock: Boolean = false): List<FamilyRelationship> {
        val sql = buildString {
            append("SELECT * FROM relationships WHERE (player_a=? OR player_b=?) AND status='ACTIVE'")
            if (t != null) append(" AND type=?")
            if (lock) append(" FOR UPDATE")
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
        val q = "SELECT * FROM relationships WHERE ((player_a=? AND player_b=?) OR (player_a=? AND player_b=?)) AND type=? AND status='ACTIVE'" + if (lock) " FOR UPDATE" else ""
        c.prepareStatement(q).use { it.setString(1, a.toString()); it.setString(2, b.toString()); it.setString(3, b.toString()); it.setString(4, a.toString()); it.setString(5, t.name); return it.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } } }
    }
    fun autoAddedParentRelationsForParent(c: Connection, parent: UUID, lock: Boolean = false): List<FamilyRelationship> {
        val sql = "SELECT * FROM relationships WHERE (player_a=? OR player_b=?) AND type=? AND status='ACTIVE' AND auto_added=TRUE" + if (lock) " FOR UPDATE" else ""
        c.prepareStatement(sql).use { st ->
            st.setString(1, parent.toString())
            st.setString(2, parent.toString())
            st.setString(3, RelationshipType.PARENT_CHILD.name)
            return st.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
        }
    }

    fun byId(c: Connection, id: String) = c.prepareStatement("SELECT * FROM relationships WHERE relationship_id=? AND status='ACTIVE'").use { it.setString(1, id); it.executeQuery().use { r -> if (r.next()) map(r) else null } }
    fun remove(c: Connection, id: Long) { c.prepareStatement("UPDATE relationships SET status='REMOVED',updated_at=? WHERE internal_id=?").use { it.setTimestamp(1, Timestamp.from(Instant.now())); it.setLong(2, id); it.executeUpdate() } }
    fun history(c: Connection, id: String, action: String, actor: UUID?, target: UUID?, reason: String?) { c.prepareStatement("INSERT INTO relationship_history(relationship_id,action,actor,target,reason,created_at) VALUES(?,?,?,?,?,?)").use { it.setString(1, id); it.setString(2, action); it.setString(3, actor?.toString()); it.setString(4, target?.toString()); it.setString(5, reason); it.setTimestamp(6, Timestamp.from(Instant.now())); it.executeUpdate() } }
}

class RequestDao {
    fun create(c: Connection, a: UUID, t: UUID, ty: RequestType, m: String?): Long {
        c.prepareStatement("INSERT INTO requests(requester,target,type,metadata,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS).use {
            val n = Timestamp.from(Instant.now()); it.setString(1, a.toString()); it.setString(2, t.toString()); it.setString(3, ty.name); it.setString(4, m); it.setString(5, RequestStatus.PENDING.name); it.setTimestamp(6, n); it.setTimestamp(7, n); it.executeUpdate(); it.generatedKeys.use { r -> r.next(); return r.getLong(1) }
        }
    }
    private fun map(r: ResultSet) = RelationshipRequest(r.getLong("id"), UUID.fromString(r.getString("requester")), UUID.fromString(r.getString("target")), RequestType.valueOf(r.getString("type")), r.getString("metadata"), RequestStatus.valueOf(r.getString("status")), r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant())
    fun byId(c: Connection, id: Long, lock: Boolean = false) = c.prepareStatement("SELECT * FROM requests WHERE id=?" + if (lock) " FOR UPDATE" else "").use { it.setLong(1, id); it.executeQuery().use { r -> if (r.next()) map(r) else null } }
    fun pendingFor(c: Connection, u: UUID) = c.prepareStatement("SELECT * FROM requests WHERE target=? AND status='PENDING' ORDER BY id DESC").use { it.setString(1, u.toString()); it.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } } }
    fun pendingInvolving(c: Connection, u: UUID) = c.prepareStatement("SELECT * FROM requests WHERE status='PENDING' AND (requester=? OR target=?) ORDER BY id DESC").use { it.setString(1, u.toString()); it.setString(2, u.toString()); it.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } } }
    fun update(c: Connection, id: Long, s: RequestStatus) { c.prepareStatement("UPDATE requests SET status=?,updated_at=? WHERE id=?").use { it.setString(1, s.name); it.setTimestamp(2, Timestamp.from(Instant.now())); it.setLong(3, id); it.executeUpdate() } }
    fun cancelFor(c: Connection, u: UUID) { c.prepareStatement("UPDATE requests SET status='CANCELLED',updated_at=? WHERE status='PENDING' AND (requester=? OR target=?)").use { it.setTimestamp(1, Timestamp.from(Instant.now())); it.setString(2, u.toString()); it.setString(3, u.toString()); it.executeUpdate() } }
}

class ActionDao { fun record(c: Connection, a: UUID, t: UUID, x: String) { c.prepareStatement("INSERT INTO actions(actor,target,action,created_at) VALUES(?,?,?,?)").use { it.setString(1, a.toString()); it.setString(2, t.toString()); it.setString(3, x); it.setTimestamp(4, Timestamp.from(Instant.now())); it.executeUpdate() } } }

class PenaltyDao {
    fun add(c: Connection, t: PenaltyTargetType, p: UUID?, rel: String?, e: String, v: Double, m: Double, end: Instant?, rem: Boolean): Long { c.prepareStatement("INSERT INTO family_penalties(target_type,target_player,target_relationship,effect,value,multiplier,started_at,ends_at,removable,active) VALUES(?,?,?,?,?,?,?,?,?,TRUE)", Statement.RETURN_GENERATED_KEYS).use { it.setString(1, t.name); it.setString(2, p?.toString()); it.setString(3, rel); it.setString(4, e); it.setDouble(5, v); it.setDouble(6, m); it.setTimestamp(7, Timestamp.from(Instant.now())); it.setTimestamp(8, end?.let(Timestamp::from)); it.setBoolean(9, rem); it.executeUpdate(); it.generatedKeys.use { r -> r.next(); return r.getLong(1) } } }
    fun remove(c: Connection, id: Long) { c.prepareStatement("UPDATE family_penalties SET active=FALSE WHERE id=? AND removable=TRUE").use { it.setLong(1, id); it.executeUpdate() } }
    fun forPlayer(c: Connection, u: UUID): List<FamilyPenalty> = c.prepareStatement("SELECT * FROM family_penalties WHERE active=TRUE AND (target_player=? OR (ends_at IS NOT NULL AND ends_at>NOW()))").use { it.setString(1, u.toString()); it.executeQuery().use { r -> buildList { while (r.next()) add(FamilyPenalty(r.getLong("id"), PenaltyTargetType.valueOf(r.getString("target_type")), r.getString("target_player")?.let(UUID::fromString), r.getString("target_relationship"), r.getString("effect"), r.getDouble("value"), r.getDouble("multiplier"), r.getTimestamp("started_at").toInstant(), r.getTimestamp("ends_at")?.toInstant(), r.getBoolean("removable"), r.getBoolean("active"))) } } }
    fun expire(c: Connection) { c.createStatement().use { it.executeUpdate("UPDATE family_penalties SET active=FALSE WHERE active=TRUE AND ends_at IS NOT NULL AND ends_at<=NOW()") } }
}
class AuditDao { fun log(c: Connection, a: UUID?, x: String, t: UUID?, rel: String?, res: String, reason: String?) { c.prepareStatement("INSERT INTO audit_log(actor,action,target_player,relationship_id,result,reason,created_at) VALUES(?,?,?,?,?,?,?)").use { it.setString(1, a?.toString()); it.setString(2, x); it.setString(3, t?.toString()); it.setString(4, rel); it.setString(5, res); it.setString(6, reason); it.setTimestamp(7, Timestamp.from(Instant.now())); it.executeUpdate() } } }
