package nekouidaga.net.familyheartplugin.action

import nekouidaga.net.familyheartplugin.config.Settings
import nekouidaga.net.familyheartplugin.database.ActionDao
import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.tx
import nekouidaga.net.familyheartplugin.model.RequestType
import nekouidaga.net.familyheartplugin.model.ActionExecutionState
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.message.Messages
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ActionService(
    private val p: JavaPlugin,
    private val db: DatabaseManager,
    private val dao: ActionDao,
    private val rel: RelationshipService,
    private val requests: RequestService,
    private val settings: Settings,
    private val messages: Messages
) {
    private val cooldown = ConcurrentHashMap<String, Long>()
    private val counts = ConcurrentHashMap<UUID, AtomicInteger>()

    fun execute(
        actor: Player,
        target: Player,
        action: String,
        onApproval: (Long) -> Unit
    ): Result {
        if (actor.uniqueId == target.uniqueId) return Result(false, "self")
        if (!rel.isLoaded(actor.uniqueId) || !rel.isLoaded(target.uniqueId)) return Result(false, "loading")
        if (actor.world.uid != target.world.uid) return Result(false, "distance")
        if (actor.location.distanceSquared(target.location) > settings.range() * settings.range()) {
            return Result(false, "distance")
        }

        val key = "${actor.uniqueId}:$action"
        val wait = (cooldown[key] ?: 0L) - System.currentTimeMillis()
        if (wait > 0L) return Result(false, "cooldown:${wait / 1000L}")

        if (!rel.isFamily(actor.uniqueId, target.uniqueId)) {
            val future: CompletableFuture<Long> = requests.create(
                actor.uniqueId,
                target.uniqueId,
                RequestType.SKINSHIP,
                action
            )
            future.thenAccept { id ->
                Bukkit.getScheduler().runTask(p, Runnable { onApproval(id) })
            }.exceptionally { t ->
                p.logger.warning("[FamilyHeart] Failed to create skinship request: ${t.message}")
                null
            }
            return Result(false, "approval")
        }

        // 永続化Futureを捨てずに監視する。ゲーム内演出は既に発生済みで取り消せないため、
        // DB書き込みが失敗した場合は監査ログが欠落した旨をactorへ通知する
        // (バグ修正: 以前はFutureを破棄しており、失敗はサーバーログにしか残らず
        // プレイヤーにもactionCount()等の内部状態にも一切伝わらなかった)。
        doIt(actor, target, action).exceptionally { _ ->
            Bukkit.getScheduler().runTask(p, Runnable {
                actor.sendMessage(messages.get("action.persist-failed", mapOf("action" to action)))
            })
            null
        }
        return Result(true, "ok")
    }

    private fun doIt(actor: Player, target: Player, action: String, requestId: Long? = null): CompletableFuture<Void> {
        check(Bukkit.isPrimaryThread) { "ActionService.doIt must run on the main thread" }
        cooldown["${actor.uniqueId}:$action"] = System.currentTimeMillis() + settings.actionCooldown(action) * 1000L
        counts.computeIfAbsent(actor.uniqueId) { AtomicInteger(0) }.incrementAndGet()
        counts.computeIfAbsent(target.uniqueId) { AtomicInteger(0) }.incrementAndGet()

        val persistence = CompletableFuture.runAsync({
            db.connection().use { c -> c.tx { if (requestId == null) dao.record(c, actor.uniqueId, target.uniqueId, action) else dao.markRequestActionExecuted(c, requestId) } }
        }, db.executor).exceptionally { t ->
            p.logger.severe("[FamilyHeart] Failed to persist action $action${requestId?.let { " for request $it" } ?: ""}: ${t.message}")
            throw CompletionException(t)
        }

        val particle = runCatching {
            Particle.valueOf(p.config.getString("actions.$action.particle", "HEART") ?: "HEART")
        }.getOrDefault(Particle.HEART)
        val sound = runCatching {
            Sound.valueOf(p.config.getString("actions.$action.sound", "ENTITY_PLAYER_LEVELUP") ?: "ENTITY_PLAYER_LEVELUP")
        }.getOrDefault(Sound.ENTITY_PLAYER_LEVELUP)

        if (p.config.getBoolean("skinship.particles", true)) {
            target.world.spawnParticle(particle, target.location.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.5, 0.3, 0.05)
        }
        if (p.config.getBoolean("skinship.sounds", true)) {
            actor.playSound(actor.location, sound, 1f, 1f)
            target.playSound(target.location, sound, 1f, 1f)
        }
        if (action.equals("feed", ignoreCase = true)) {
            target.foodLevel = minOf(20, target.foodLevel + 4)
        }
        return persistence
    }

    fun approved(actor: Player, target: Player, action: String): Result {
        // バグ修正(3): 以前はGUIでの承認時にdoIt()を直接呼んでおり、
        // execute()が行っているワールド一致・距離チェックを一切通っていなかった。
        // 「申請時は近くにいたが、承認時には相手が遠くへ移動していた」場合でも
        // そのままスキンシップが成立してしまっていたため、承認時にも同じ条件を確認する。
        if (actor.world.uid != target.world.uid) return Result(false, "distance")
        if (actor.location.distanceSquared(target.location) > settings.range() * settings.range()) {
            return Result(false, "distance")
        }
        doIt(actor, target, action)
        return Result(true, "ok")
    }

    /** Persist the idempotency intent before any SKINSHIP side effect is executed. */
    fun prepareRequestAction(requestId: Long, actor: UUID, target: UUID, action: String): CompletableFuture<ActionExecutionState> =
        CompletableFuture.supplyAsync({
            db.connection().use { c -> c.tx { dao.beginRequestAction(c, requestId, actor, target, action) } }
        }, db.executor)

    /** Main-thread validation + execution for a SKINSHIP request. The request action must already have a durable INTENT. */
    fun approvedPersistent(actor: Player, target: Player, action: String, requestId: Long): CompletableFuture<Result> {
        check(Bukkit.isPrimaryThread) { "ActionService.approvedPersistent must run on the main thread" }
        if (actor.uniqueId == target.uniqueId) return CompletableFuture.completedFuture(Result(false, "self"))
        if (actor.world.uid != target.world.uid) return CompletableFuture.completedFuture(Result(false, "distance"))
        if (actor.location.distanceSquared(target.location) > settings.range() * settings.range()) return CompletableFuture.completedFuture(Result(false, "distance"))
        return doIt(actor, target, action, requestId).thenApply { Result(true, "ok") }
    }

    fun actionCount(uuid: UUID): Int = counts[uuid]?.get() ?: 0

    data class Result(val success: Boolean, val reason: String)
}
