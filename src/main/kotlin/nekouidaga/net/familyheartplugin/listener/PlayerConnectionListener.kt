package nekouidaga.net.familyheartplugin.listener

import nekouidaga.net.familyheartplugin.buff.BuffService
import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.PlayerDao
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.message.Messages
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

class PlayerConnectionListener(
    private val p: JavaPlugin,
    private val db: DatabaseManager,
    private val players: PlayerDao,
    private val rel: RelationshipService,
    private val req: RequestService,
    private val buffs: BuffService,
    private val msg: Messages
) : Listener {
    @EventHandler
    fun join(e: PlayerJoinEvent) {
        val x = e.player
        db.executor.submit { db.connection().use { players.upsert(it, x.uniqueId, x.name) } }
        rel.load(x.uniqueId)
        Bukkit.getScheduler().runTaskLater(p, Runnable { buffs.recompute(x) }, 1)
    }

    @EventHandler
    fun quit(e: PlayerQuitEvent) {
        req.cancelFor(e.player.uniqueId).thenAccept { requests ->
            Bukkit.getScheduler().runTask(p, Runnable {
                requests.forEach { r ->
                    val other = if (r.requester == e.player.uniqueId) r.target else r.requester
                    Bukkit.getPlayer(other)?.sendMessage(msg.get("request.offline-cancel", mapOf("player" to e.player.name)))
                }
                e.player.sendMessage(msg.get("request.offline-cancel", mapOf("player" to e.player.name)))
            })
        }
    }
}
