package nekouidaga.net.familyheartplugin.relationship

import nekouidaga.net.familyheartplugin.cache.RelationshipCache
import nekouidaga.net.familyheartplugin.database.*
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.service.*
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RelationshipService(
    private val p: JavaPlugin,
    private val db: DatabaseManager,
    private val dao: RelationshipDao,
    private val playerDao: PlayerDao,
    private val cache: RelationshipCache
) {
    private val maxParents get() = p.config.getInt("relationship-limits.max-parents", 2)
    private val mutationLock = ReentrantLock(true)
    @Volatile private var affectedHandler: (Set<UUID>) -> Unit = {}

    fun setAffectedHandler(handler: (Set<UUID>) -> Unit) { affectedHandler = handler }

    /**
     * Server-startup snapshot. Called before commands/listeners are registered so that
     * there is no window where a player can be treated as having no relationships merely
     * because the cache has not loaded yet.
     */
    fun preloadAllBlocking() {
        mutationLock.withLock {
            db.connection().use { c ->
                val grouped = linkedMapOf<UUID, MutableList<FamilyRelationship>>()
                dao.allActive(c).forEach { r ->
                    grouped.computeIfAbsent(r.playerA) { mutableListOf() }.add(r)
                    grouped.computeIfAbsent(r.playerB) { mutableListOf() }.add(r)
                }
                // バグ修正: 関係が1件も無いプレイヤーはgroupedに一切現れず、cacheへ登録されない。
                // 通常のサーバー起動時はまだ誰もオンラインでないため問題化しないが、/reload等
                // プラグインが再度onEnable()されたタイミングで既にオンラインのプレイヤーがいる場合、
                // 関係0件のそのプレイヤーはisLoaded()がfalseのまま固まり、再ログインするまで
                // GUI/コマンド/スキンシップ等のisLoaded()ガードにブロックされ続けていた。
                // オンライン中の全プレイヤーを(関係が無くても)明示的に登録する。
                Bukkit.getOnlinePlayers().forEach { player ->
                    grouped.computeIfAbsent(player.uniqueId) { mutableListOf() }
                }
                cache.clearAll()
                cache.replaceMany(grouped.mapValues { it.value.toList() })
            }
        }
    }

    /** Reload one player immediately after join. */
    fun load(u: UUID, after: (() -> Unit)? = null) {
        val loadGeneration = cache.markLoading(u)
        db.executor.submit {
            try {
                mutationLock.withLock {
                    db.connection().use { c -> cache.replace(u, dao.byPlayer(c, u), loadGeneration) }
                }
                if (after != null) Bukkit.getScheduler().runTask(p, Runnable { after() })
            } catch (t: Throwable) {
                cache.markFailed(u, loadGeneration)
                p.logger.warning("[FamilyHeart] Relationship load failed for $u: ${t.message}")
            }
        }
    }

    /** Explicit DB->cache refresh for a set of affected players. */
    fun refreshAffected(c: Connection, affected: Set<UUID>) {
        if (affected.isEmpty()) return
        val refreshed = mutableSetOf<UUID>()
        affected.forEach { u ->
            val refreshGeneration = cache.markLoading(u)
            try {
                cache.replace(u, dao.byPlayer(c, u), refreshGeneration)
                refreshed += u
            } catch (t: Throwable) {
                // The mutation transaction has already committed. Never propagate a
                // post-commit cache-read failure back to the caller, because doing so
                // could make a successful DB mutation look failed (and trigger e.g.
                // an Economy refund). Mark only this refresh generation as failed so
                // a newer load cannot be invalidated by an older failure.
                cache.markFailed(u, refreshGeneration)
                p.logger.warning("[FamilyHeart] Relationship cache refresh failed for $u after commit: ${t.message}")
            }
        }
        if (refreshed.isNotEmpty()) affectedHandler(refreshed)
    }

    fun relationships(u: UUID) = cache.get(u)
    fun isLoaded(u: UUID): Boolean = cache.isLoaded(u)
    fun isFamily(a: UUID, b: UUID): Boolean = cache.isLoaded(a) && cache.get(a).any { it.involves(b) }

    /** DB-authoritative family graph for maintenance/recovery paths where cache freshness cannot be assumed. */
    fun familyFromDatabase(c: Connection, u: UUID): Set<UUID> {
        val set = mutableSetOf(u)
        fun walk(x: UUID) {
            dao.byPlayer(c, x).forEach { relation ->
                val other = relation.getOther(x)
                if (set.add(other)) walk(other)
            }
        }
        walk(u)
        return set
    }

    /** DB-authoritative test used by penalty expiration to avoid stale-cache target detection. */
    fun penaltyAffectsPlayerFromDatabase(c: Connection, penalty: FamilyPenalty, player: UUID): Boolean = when (penalty.targetType) {
        PenaltyTargetType.PLAYER -> penalty.targetPlayer == player
        PenaltyTargetType.FAMILY -> penalty.targetPlayer != null && familyFromDatabase(c, player).contains(penalty.targetPlayer)
        PenaltyTargetType.RELATIONSHIP -> penalty.targetRelationship != null && dao.byPlayer(c, player).any { it.relationshipId == penalty.targetRelationship }
    }
    fun <T> withMutationLock(block: () -> T): T = mutationLock.withLock(block)

    /** Execute relationship creation inside an existing JDBC transaction. Caller must hold mutationLock. */
    fun createWithinTransaction(c: Connection, a: UUID, b: UUID, t: RelationshipType, ra: String, rb: String): Pair<FamilyRelationship, Set<UUID>> {
        if (a == b) throw RelationshipException(RelationshipError.SELF_TARGET)
        if (t == RelationshipType.SPOUSE) {
            if (dao.byPlayer(c, a, RelationshipType.SPOUSE, true).isNotEmpty()) throw RelationshipException(RelationshipError.ALREADY_HAS_SPOUSE)
            if (dao.byPlayer(c, b, RelationshipType.SPOUSE, true).isNotEmpty()) throw RelationshipException(RelationshipError.TARGET_ALREADY_HAS_SPOUSE)
        } else {
            val childParents = dao.byPlayer(c, b, RelationshipType.PARENT_CHILD, true).count { it.roleOf(b) == ParentChildRole.CHILD.name }
            if (childParents >= maxParents) throw RelationshipException(RelationshipError.ALREADY_MAX_PARENTS)
            if (dao.between(c, a, b, t, true).isNotEmpty()) throw RelationshipException(RelationshipError.DUPLICATE_RELATIONSHIP)
        }
        val primary = dao.insert(c, a, b, t, ra, rb, false)
        dao.history(c, primary.relationshipId, "CREATE", a, b, null)
        val created = mutableListOf(primary)
        if (t == RelationshipType.SPOUSE && p.config.getBoolean("features.auto-parent", true)) {
            listOf(a to b, b to a).forEach { (parent, spouse) ->
                val children = dao.byPlayer(c, parent, RelationshipType.PARENT_CHILD, true).filter { it.roleOf(parent) == ParentChildRole.PARENT.name }
                children.forEach { parentChild ->
                    val child = parentChild.getOther(parent)
                    val existing = dao.byPlayer(c, child, RelationshipType.PARENT_CHILD, true)
                    val childParentCount = existing.count { it.roleOf(child) == ParentChildRole.CHILD.name }
                    val spouseAlreadyParent = existing.any { it.involves(spouse) && it.roleOf(spouse) == ParentChildRole.PARENT.name }
                    if (childParentCount < maxParents && !spouseAlreadyParent) {
                        val auto = dao.insert(c, spouse, child, RelationshipType.PARENT_CHILD, ParentChildRole.PARENT.name, ParentChildRole.CHILD.name, true, primary.relationshipId)
                        created += auto
                        dao.history(c, auto.relationshipId, "CREATE", spouse, child, "auto-parent")
                    }
                }
            }
        }
        return primary to created.flatMap { listOf(it.playerA, it.playerB) }.toSet()
    }

    /** Execute relationship removal inside an existing JDBC transaction. Caller must hold mutationLock. */
    fun removeWithinTransaction(c: Connection, a: UUID, b: UUID, type: RelationshipType): Set<UUID> {
        val rs = dao.between(c, a, b, type, true)
        if (rs.isEmpty()) throw RelationshipException(RelationshipError.RELATIONSHIP_NOT_FOUND)
        val affected = mutableSetOf(a, b)
        affected += rs.flatMap { listOf(it.playerA, it.playerB) }
        rs.forEach { relation ->
            dao.remove(c, relation.internalId)
            dao.history(c, relation.relationshipId, "REMOVE", a, b, null)
            if (type == RelationshipType.SPOUSE && p.config.getBoolean("features.auto-parent", true)) {
                dao.autoAddedParentRelationsForSource(c, relation.relationshipId, true).forEach { auto ->
                    dao.remove(c, auto.internalId)
                    dao.history(c, auto.relationshipId, "REMOVE", auto.playerA, auto.playerB, "auto-parent-source-removed")
                    affected += auto.playerA
                    affected += auto.playerB
                }
            }
        }
        return affected
    }

    fun spouse(u: UUID) = relationships(u).firstOrNull { it.type == RelationshipType.SPOUSE }?.getOther(u)
    fun parents(u: UUID) = relationships(u).filter { it.type == RelationshipType.PARENT_CHILD && it.roleOf(u) == ParentChildRole.CHILD.name }.map { it.getOther(u) }
    fun children(u: UUID) = relationships(u).filter { it.type == RelationshipType.PARENT_CHILD && it.roleOf(u) == ParentChildRole.PARENT.name }.map { it.getOther(u) }

    fun create(a: UUID, b: UUID, t: RelationshipType, ra: String, rb: String): FamilyRelationship {
        return mutationLock.withLock {
            db.connection().use { c ->
                val result = c.tx { createWithinTransaction(c, a, b, t, ra, rb) }
                refreshAffected(c, result.second)
                result.first
            }
        }
    }

    fun removeSync(a: UUID, b: UUID, type: RelationshipType) {
        mutationLock.withLock {
            db.connection().use { c ->
                val affected = c.tx { removeWithinTransaction(c, a, b, type) }
                refreshAffected(c, affected)
            }
        }
    }

    fun remove(a: UUID, b: UUID, type: RelationshipType): CompletableFuture<Unit> = CompletableFuture.supplyAsync({ removeSync(a, b, type) }, db.executor)

    fun removeById(id: String): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({
        mutationLock.withLock {
            db.connection().use { c ->
                var affected = emptySet<UUID>()
                val success = c.tx {
                    val r = dao.byId(c, id, true) ?: return@tx false
                    dao.remove(c, r.internalId)
                    dao.history(c, r.relationshipId, "FORCE_REMOVE", null, null, "admin")
                    affected = setOf(r.playerA, r.playerB)
                    if (r.type == RelationshipType.SPOUSE) dao.autoAddedParentRelationsForSource(c, r.relationshipId, true).forEach { auto ->
                        dao.remove(c, auto.internalId)
                        dao.history(c, auto.relationshipId, "REMOVE", auto.playerA, auto.playerB, "auto-parent-source-removed")
                        affected += auto.playerA; affected += auto.playerB
                    }
                    true
                }
                if (success) refreshAffected(c, affected)
                success
            }
        }
    }, db.executor)

    fun resetPair(a: UUID, b: UUID): CompletableFuture<Int> = CompletableFuture.supplyAsync({
        mutationLock.withLock {
            db.connection().use { c ->
                val count = c.tx {
                    val rs = dao.byPlayer(c, a, lock = true).filter { it.involves(b) }
                    val affected = mutableSetOf(a, b)
                    rs.forEach { relation ->
                        dao.remove(c, relation.internalId)
                        dao.history(c, relation.relationshipId, "FORCE_RESET", null, null, "admin")
                        affected += relation.playerA
                        affected += relation.playerB
                        if (relation.type == RelationshipType.SPOUSE) {
                            dao.autoAddedParentRelationsForSource(c, relation.relationshipId, true).forEach { auto ->
                                dao.remove(c, auto.internalId)
                                dao.history(c, auto.relationshipId, "REMOVE", auto.playerA, auto.playerB, "auto-parent-source-removed")
                                affected += auto.playerA
                                affected += auto.playerB
                            }
                        }
                    }
                    affected to rs.size
                }
                refreshAffected(c, count.first)
                count.second
            }
        }
    }, db.executor)

    fun info(id: String) = CompletableFuture.supplyAsync({ db.connection().use { dao.byId(it, id) } }, db.executor)
    fun resolveMcid(c: String): CompletableFuture<UUID?> {
        val online = CompletableFuture<UUID?>()
        Bukkit.getScheduler().runTask(p, Runnable {
            online.complete(Bukkit.getPlayerExact(c)?.uniqueId)
        })
        return online.thenCompose { uuid ->
            if (uuid != null) CompletableFuture.completedFuture(uuid)
            else CompletableFuture.supplyAsync({ db.connection().use { playerDao.byMcid(it, c) } }, db.executor)
        }
    }
}
