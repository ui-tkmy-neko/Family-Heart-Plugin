package nekouidaga.net.familyheartplugin.listener

import nekouidaga.net.familyheartplugin.buff.BuffService
import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.PlayerDao
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.message.Messages
import nekouidaga.net.familyheartplugin.penalty.PenaltyService
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
    private val msg: Messages,
    private val penalties: PenaltyService
) : Listener {
    @EventHandler
    fun join(e: PlayerJoinEvent) {
        val x = e.player
        db.executor.submit {
            try {
                db.connection().use { c -> players.upsert(c, x.uniqueId, x.name) }
            } catch (t: Throwable) {
                // 失敗を握りつぶすとplayersテーブル未反映のままMCID検索(findMcid)が
                // 継続的に外れる原因になるため、必ずログに残す。
                p.logger.warning("[FamilyHeart] Failed to upsert player ${x.name} on join: ${t.message}")
            }
        }
        // Join時のDB処理はすべて非同期。Penalty cache更新完了後、Buff適用だけMain Threadで行う。
        rel.load(x.uniqueId) {
            penalties.refreshAsync(x.uniqueId) {
                Bukkit.getPlayer(x.uniqueId)?.let(buffs::recompute)
            }
        }
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
