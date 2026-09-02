package nekouidaga.net.familyheartplugin.action

import nekouidaga.net.familyheartplugin.config.Settings
import nekouidaga.net.familyheartplugin.database.ActionDao
import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.model.RequestType
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ActionService(
    private val p: JavaPlugin,
    private val db: DatabaseManager,
    private val dao: ActionDao,
    private val rel: RelationshipService,
    private val requests: RequestService,
    private val settings: Settings
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
            future.thenAccept { id -> onApproval(id) }
            return Result(false, "approval")
        }

        doIt(actor, target, action)
        return Result(true, "ok")
    }

    private fun doIt(actor: Player, target: Player, action: String) {
        cooldown["${actor.uniqueId}:$action"] = System.currentTimeMillis() + settings.actionCooldown(action) * 1000L
        counts.computeIfAbsent(actor.uniqueId) { AtomicInteger(0) }.incrementAndGet()
        counts.computeIfAbsent(target.uniqueId) { AtomicInteger(0) }.incrementAndGet()

        db.executor.submit {
            db.connection().use { c -> dao.record(c, actor.uniqueId, target.uniqueId, action) }
        }

        Bukkit.getScheduler().runTask(p, Runnable {
            val particle = runCatching {
                Particle.valueOf(p.config.getString("actions.$action.particle", "HEART") ?: "HEART")
            }.getOrDefault(Particle.HEART)
            val sound = runCatching {
                Sound.valueOf(p.config.getString("actions.$action.sound", "ENTITY_PLAYER_LEVELUP") ?: "ENTITY_PLAYER_LEVELUP")
            }.getOrDefault(Sound.ENTITY_PLAYER_LEVELUP)

            if (p.config.getBoolean("skinship.particles", true)) {
                target.world.spawnParticle(
                    particle,
                    target.location.add(0.0, 1.0, 0.0),
                    12,
                    0.3,
                    0.5,
                    0.3,
                    0.05
                )
            }
            if (p.config.getBoolean("skinship.sounds", true)) {
                actor.playSound(actor.location, sound, 1f, 1f)
                target.playSound(target.location, sound, 1f, 1f)
            }
            if (action.equals("feed", ignoreCase = true)) {
                target.foodLevel = minOf(20, target.foodLevel + 4)
            }
        })
    }

    fun approved(actor: Player, target: Player, action: String) = doIt(actor, target, action)

    fun actionCount(uuid: UUID): Int = counts[uuid]?.get() ?: 0

    data class Result(val success: Boolean, val reason: String)
}
