package nekouidaga.net.familyheartplugin.relationship

import nekouidaga.net.familyheartplugin.cache.RelationshipCache
import nekouidaga.net.familyheartplugin.database.*
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.service.*
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RelationshipService(
    private val p: JavaPlugin,
    private val db: DatabaseManager,
    private val dao: RelationshipDao,
    private val playerDao: PlayerDao,
    private val cache: RelationshipCache
) {
    private val maxParents get() = p.config.getInt("relationship-limits.max-parents", 2)

    fun load(u: UUID) {
        db.executor.submit { db.connection().use { c -> cache.replace(u, dao.byPlayer(c, u)) } }
    }

    fun relationships(u: UUID) = cache.get(u)

    fun isFamily(a: UUID, b: UUID) = relationships(a).any { it.involves(b) }

    fun spouse(u: UUID) = relationships(u)
        .firstOrNull { it.type == RelationshipType.SPOUSE }
        ?.getOther(u)

    fun parents(u: UUID) = relationships(u)
        .filter { it.type == RelationshipType.PARENT_CHILD && it.roleOf(u) == ParentChildRole.CHILD.name }
        .map { it.getOther(u) }

    fun children(u: UUID) = relationships(u)
        .filter { it.type == RelationshipType.PARENT_CHILD && it.roleOf(u) == ParentChildRole.PARENT.name }
        .map { it.getOther(u) }

    fun create(a: UUID, b: UUID, t: RelationshipType, ra: String, rb: String): FamilyRelationship {
        if (a == b) throw RelationshipException(RelationshipError.SELF_TARGET)

        val affected = mutableSetOf(a, b)
        val created = db.connection().use { c ->
            c.tx {
                if (t == RelationshipType.SPOUSE) {
                    if (dao.byPlayer(c, a, RelationshipType.SPOUSE, true).isNotEmpty()) {
                        throw RelationshipException(RelationshipError.ALREADY_HAS_SPOUSE)
                    }
                    if (dao.byPlayer(c, b, RelationshipType.SPOUSE, true).isNotEmpty()) {
                        throw RelationshipException(RelationshipError.TARGET_ALREADY_HAS_SPOUSE)
                    }
                } else {
                    val childParents = dao.byPlayer(c, b, RelationshipType.PARENT_CHILD, true)
                        .count { it.roleOf(b) == ParentChildRole.CHILD.name }
                    if (childParents >= maxParents) {
                        throw RelationshipException(RelationshipError.ALREADY_MAX_PARENTS)
                    }
                    if (dao.between(c, a, b, t, true).isNotEmpty()) {
                        throw RelationshipException(RelationshipError.DUPLICATE_RELATIONSHIP)
                    }
                }

                val result = mutableListOf(dao.insert(c, a, b, t, ra, rb, false))
                dao.history(c, result.first().relationshipId, "CREATE", a, b, null)

                if (t == RelationshipType.SPOUSE && p.config.getBoolean("features.auto-parent", true)) {
                    // For each spouse, add the other spouse as parent of that spouse's children.
                    listOf(a to b, b to a).forEach { (parent, spouse) ->
                        val children = dao.byPlayer(c, parent, RelationshipType.PARENT_CHILD, true)
                            .filter { it.roleOf(parent) == ParentChildRole.PARENT.name }
                        children.forEach { parentChild ->
                            val child = parentChild.getOther(parent)
                            val existing = dao.byPlayer(c, child, RelationshipType.PARENT_CHILD, true)
                            val childParentCount = existing.count { it.roleOf(child) == ParentChildRole.CHILD.name }
                            val spouseAlreadyParent = existing.any {
                                it.involves(spouse) && it.roleOf(spouse) == ParentChildRole.PARENT.name
                            }
                            if (childParentCount < maxParents && !spouseAlreadyParent) {
                                val auto = dao.insert(
                                    c, spouse, child, RelationshipType.PARENT_CHILD,
                                    ParentChildRole.PARENT.name, ParentChildRole.CHILD.name, true
                                )
                                result += auto
                                dao.history(c, auto.relationshipId, "CREATE", spouse, child, "auto-parent")
                                affected += child
                            }
                        }
                    }
                }
                result
            }
        }

        created.forEach { affected += it.playerA; affected += it.playerB }
        affected.forEach(::load)
        return created.first()
    }

    fun remove(a: UUID, b: UUID, type: RelationshipType): CompletableFuture<Unit> =
        CompletableFuture.supplyAsync({
            val affected = mutableSetOf(a, b)
            db.connection().use { c ->
                c.tx {
                    val rs = dao.between(c, a, b, type, true)
                    if (rs.isEmpty()) throw RelationshipException(RelationshipError.RELATIONSHIP_NOT_FOUND)

                    rs.forEach { relation ->
                        dao.remove(c, relation.internalId)
                        dao.history(c, relation.relationshipId, "REMOVE", a, b, null)
                        affected += relation.playerA
                        affected += relation.playerB
                    }

                    if (type == RelationshipType.SPOUSE && p.config.getBoolean("features.auto-parent", true)) {
                        val formerSpouses = rs.flatMap { listOf(it.playerA, it.playerB) }.distinct()
                        val autoRelations = formerSpouses
                            .flatMap { dao.autoAddedParentRelationsForParent(c, it, true) }
                            .distinctBy { it.internalId }
                        autoRelations.forEach { relation ->
                            dao.remove(c, relation.internalId)
                            val parent = if (relation.roleOf(relation.playerA) == ParentChildRole.PARENT.name) relation.playerA else relation.playerB
                            val child = relation.getOther(parent)
                            dao.history(c, relation.relationshipId, "REMOVE", parent, child, "auto-parent-recompute")
                            affected += relation.playerA
                            affected += relation.playerB
                        }
                    }
                }
            }
            affected.forEach(::load)
        }, db.executor).thenApply { }

    fun removeById(id: String): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            val affected = db.connection().use { c ->
                c.tx {
                    val r = dao.byId(c, id) ?: return@tx emptySet<UUID>()
                    dao.remove(c, r.internalId)
                    dao.history(c, r.relationshipId, "FORCE_REMOVE", null, null, "admin")
                    setOf(r.playerA, r.playerB)
                }
            }
            affected.forEach(::load)
            affected.isNotEmpty()
        }, db.executor)

    fun resetPair(a: UUID, b: UUID): CompletableFuture<Int> =
        CompletableFuture.supplyAsync({
            db.connection().use { c ->
                c.tx {
                    val rs = dao.byPlayer(c, a, lock = true).filter { it.involves(b) }
                    rs.forEach {
                        dao.remove(c, it.internalId)
                        dao.history(c, it.relationshipId, "FORCE_RESET", null, null, "admin")
                    }
                    rs.size
                }
            }
        }, db.executor).also { it.thenRun { load(a); load(b) } }

    fun info(id: String) = CompletableFuture.supplyAsync({
        db.connection().use { dao.byId(it, id) }
    }, db.executor)

    fun resolveMcid(c: String): UUID? = Bukkit.getPlayerExact(c)?.uniqueId
        ?: db.connection().use { playerDao.byMcid(it, c) }
}
