package nekouidaga.net.familyheartplugin.penalty

import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.PenaltyDao
import nekouidaga.net.familyheartplugin.database.tx
import nekouidaga.net.familyheartplugin.model.FamilyPenalty
import nekouidaga.net.familyheartplugin.model.PenaltyTargetType
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class PenaltyService(
    private val plugin: JavaPlugin,
    private val db: DatabaseManager,
    private val dao: PenaltyDao,
    private val relationshipService: RelationshipService
) {
    private val cache = ConcurrentHashMap<UUID, Map<String, Double>>()
    private val loaded = ConcurrentHashMap.newKeySet<UUID>()
    private val generations = ConcurrentHashMap<UUID, AtomicLong>()
    private val expirationRunning = AtomicBoolean(false)
    @Volatile private var affectedHandler: (Set<UUID>) -> Unit = {}

    fun setAffectedHandler(handler: (Set<UUID>) -> Unit) { affectedHandler = handler }

    fun refresh(u: UUID): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            try {
                refreshBlocking(u)
            } catch (t: Throwable) {
                invalidate(u)
                throw t
            }
        }, db.executor)

    fun refreshAsync(u: UUID, after: (() -> Unit)? = null): CompletableFuture<Void> {
        val result = CompletableFuture<Void>()
        db.executor.execute {
            try {
                refreshBlocking(u)
                if (after == null) {
                    result.complete(null)
                } else {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        try { after() } finally { result.complete(null) }
                    })
                }
            } catch (t: Throwable) {
                invalidate(u)
                result.completeExceptionally(t)
            }
        }
        return result
    }

    private fun refreshBlocking(u: UUID) {
        val generation = generations.computeIfAbsent(u) { AtomicLong(0L) }.incrementAndGet()
        val value = relationshipService.withMutationLock {
            db.connection().use { c -> loadEffective(c, u) }
        }
        val current = generations[u]?.get() ?: generation
        if (current == generation) {
            cache[u] = value
            loaded.add(u)
        }
    }

    private fun loadEffective(c: java.sql.Connection, u: UUID): Map<String, Double> {
        val direct = dao.forPlayer(c, u)
        val familyMembers = familyOf(u)
        val familyWide = dao.familyPenalties(c).filter { penalty ->
            val anchor = penalty.targetPlayer ?: return@filter false
            familyMembers.contains(anchor)
        }
        val relationshipIds = relationshipService.relationships(u).map { it.relationshipId }
        val relationshipWide = dao.relationshipPenalties(c, relationshipIds)
        return merge(direct + familyWide + relationshipWide)
    }

    private fun merge(penalties: List<FamilyPenalty>): Map<String, Double> =
        penalties.groupBy { it.effect.uppercase() }
            .mapValues { (_, list) -> list.fold(1.0) { acc, penalty -> acc * penalty.multiplier } }

    private fun familyOf(u: UUID): Set<UUID> {
        val set = mutableSetOf(u)
        fun walk(x: UUID) {
            relationshipService.relationships(x).forEach {
                val other = it.getOther(x)
                if (set.add(other)) walk(other)
            }
        }
        walk(u)
        return set
    }

    fun refreshAffectedAsync(ids: Set<UUID>): CompletableFuture<Unit> =
        if (ids.isEmpty()) CompletableFuture.completedFuture(Unit)
        else CompletableFuture.runAsync({ refreshIds(ids) }, db.executor).thenApply { Unit }

    private fun refreshIds(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        val generationsForRefresh = ids.associateWith { u -> generations.computeIfAbsent(u) { AtomicLong(0L) }.incrementAndGet() }
        relationshipService.withMutationLock {
            db.connection().use { c ->
                generationsForRefresh.forEach { (u, generation) ->
                    try {
                        val value = loadEffective(c, u)
                        if (generations[u]?.get() == generation) {
                            cache[u] = value
                            loaded.add(u)
                        }
                    } catch (t: Throwable) {
                        if (generations[u]?.get() == generation) {
                            cache.remove(u)
                            loaded.remove(u)
                        }
                        plugin.logger.warning("[FamilyHeart] Penalty cache refresh failed for $u after commit: ${t.message}")
                    }
                }
            }
        }
        affectedHandler(ids.toSet())
    }

    /** Snapshot online UUIDs without ever blocking a worker thread waiting for the server thread. */
    private fun onlineIdsSnapshot(): CompletableFuture<Set<UUID>> {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(Bukkit.getOnlinePlayers().mapTo(LinkedHashSet<UUID>()) { it.uniqueId })
        }
        val result = CompletableFuture<Set<UUID>>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                result.complete(Bukkit.getOnlinePlayers().mapTo(LinkedHashSet<UUID>()) { it.uniqueId })
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        })
        return result
    }

    /** Expire and refresh only players whose effective penalty set may have changed. */
    fun expireAndRefreshOnline(): CompletableFuture<Unit> {
        if (!expirationRunning.compareAndSet(false, true)) return CompletableFuture.completedFuture(Unit)
        return onlineIdsSnapshot().thenApplyAsync({ online ->
            try {
                val affected = db.connection().use { c ->
                    c.tx {
                        val expired = dao.expireReturning(c)
                        if (expired.isEmpty()) emptySet()
                        else online.filterTo(mutableSetOf()) { u -> expired.any { relationshipService.penaltyAffectsPlayerFromDatabase(c, it, u) } }
                    }
                }
                if (affected.isNotEmpty()) refreshIds(affected)
                Unit
            } finally {
                expirationRunning.set(false)
            }
        }, db.executor).exceptionally {
            expirationRunning.set(false)
            throw it
        }
    }

    fun add(
        type: PenaltyTargetType,
        p: UUID?,
        rel: String?,
        effect: String,
        value: Double,
        multiplier: Double,
        end: Instant?,
        removable: Boolean
    ): CompletableFuture<Long> =
        onlineIdsSnapshot().thenApplyAsync({ online ->
            val id = db.connection().use { c -> c.tx { dao.add(c, type, p, rel, effect, value, multiplier, end, removable) } }
            val created = FamilyPenalty(id, type, p, rel, effect, value, multiplier, Instant.now(), end, removable, true)
            val affected = online.filterTo(mutableSetOf()) { penaltyAffectsPlayer(created, it) }
            refreshIds(affected)
            id
        }, db.executor)

    fun remove(id: Long): CompletableFuture<Unit> =
        onlineIdsSnapshot().thenApplyAsync({ online ->
            val existingAndChanged: Pair<FamilyPenalty?, Boolean> = db.connection().use { c -> c.tx {
                val existing = dao.byId(c, id)
                val changed = existing != null && dao.remove(c, id)
                existing to changed
            } }
            val existing = existingAndChanged.first
            if (existing != null && existingAndChanged.second) {
                val affected = online.filterTo(mutableSetOf()) { penaltyAffectsPlayer(existing, it) }
                refreshIds(affected)
            }
            Unit
        }, db.executor)

    private fun penaltyAffectsPlayer(penalty: FamilyPenalty, player: UUID): Boolean = when (penalty.targetType) {
        PenaltyTargetType.PLAYER -> penalty.targetPlayer == player
        PenaltyTargetType.FAMILY -> penalty.targetPlayer != null && familyOf(player).contains(penalty.targetPlayer)
        PenaltyTargetType.RELATIONSHIP -> penalty.targetRelationship != null &&
            relationshipService.relationships(player).any { it.relationshipId == penalty.targetRelationship }
    }

    fun syncOnline() { /* Intentionally no-op: synchronization is event/write/join driven. */ }

    private fun invalidate(u: UUID) {
        generations.computeIfAbsent(u) { AtomicLong(0L) }.incrementAndGet()
        cache.remove(u)
        loaded.remove(u)
    }

    fun clear(u: UUID) = invalidate(u)

    fun multiplier(player: UUID, effect: String): Double = if (player in loaded) {
        cache[player]?.get(effect.uppercase()) ?: 1.0
    } else 1.0
}
