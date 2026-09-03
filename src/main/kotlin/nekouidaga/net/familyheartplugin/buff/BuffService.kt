package nekouidaga.net.familyheartplugin.buff

import nekouidaga.net.familyheartplugin.config.Settings
import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.penalty.PenaltyService
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionEffect
import java.util.UUID

class BuffService(private val p: JavaPlugin, private val db: DatabaseManager, private val actionService: nekouidaga.net.familyheartplugin.action.ActionService, private val rel: RelationshipService, private val penalty: PenaltyService, private val settings: Settings) {
    private data class AppliedBuff(val type: PotionEffectType, val amplifier: Int, val previous: PotionEffect?)
    private val applied = HashMap<UUID, MutableMap<String, AppliedBuff>>()

    fun recompute(player: Player) {
        if (!settings.features("family-buffs")) { clear(player); return }
        val defs = settings.buffs()
        val family = family(player.uniqueId)
        val seen = mutableSetOf<String>()
        defs.forEach { d ->
            if (!d.enabled) return@forEach
            val effect = runCatching { PotionEffectType.getByName(d.effect) }.getOrNull() ?: return@forEach
            val id = "fh-${d.key}"
            seen += id
            val ok = condition(player, family, d)
            if (ok) {
                val mult = penalty.multiplier(player.uniqueId, d.effect)
                val amp = (d.amplifier * mult).toInt().coerceAtLeast(0)
                val current = player.getPotionEffect(effect)
                val entry = applied[player.uniqueId]?.get(id)
                if (entry == null) {
                    val previous = current
                    player.addPotionEffect(PotionEffect(effect, d.duration * 20, amp, true, false, true))
                    applied.computeIfAbsent(player.uniqueId) { mutableMapOf() }[id] = AppliedBuff(effect, amp, previous)
                } else if (current == null || current.amplifier == entry.amplifier) {
                    player.addPotionEffect(PotionEffect(effect, d.duration * 20, amp, true, false, true))
                    applied.computeIfAbsent(player.uniqueId) { mutableMapOf() }[id] = AppliedBuff(effect, amp, entry.previous)
                }
            } else {
                removeApplied(player, id)
            }
        }
        applied[player.uniqueId]?.keys?.filterNot { it in seen }?.toList()?.forEach { removeApplied(player, it) }
        if (applied[player.uniqueId]?.isEmpty() == true) applied.remove(player.uniqueId)
    }

    private fun removeApplied(player: Player, id: String) {
        val entry = applied[player.uniqueId]?.remove(id) ?: return
        val current = player.getPotionEffect(entry.type)
        if (current != null && current.amplifier == entry.amplifier) {
            if (entry.previous != null) player.addPotionEffect(entry.previous)
            else player.removePotionEffect(entry.type)
        }
    }

    private fun family(u: UUID): Set<UUID> { val set = mutableSetOf(u); fun walk(x: UUID) { rel.relationships(x).forEach { val o = it.getOther(x); if (set.add(o)) walk(o) } }; walk(u); return set }
    private fun condition(p: Player, f: Set<UUID>, d: BuffDefinition): Boolean = when (d.condition) {
        "FAMILY_MEMBERS_ONLINE" -> f.count { Bukkit.getPlayer(it) != null } >= d.required
        "FAMILY_SIZE" -> f.size >= d.required
        "SPOUSE_ONLINE" -> rel.spouse(p.uniqueId)?.let { Bukkit.getPlayer(it) != null } == true
        "PARENTS_ONLINE" -> rel.parents(p.uniqueId).count { Bukkit.getPlayer(it) != null } >= d.required
        "CHILDREN_ONLINE" -> rel.children(p.uniqueId).count { Bukkit.getPlayer(it) != null } >= d.required
        "RELATIONSHIP_COUNT" -> rel.relationships(p.uniqueId).size >= d.required
        "FAMILY_DISTANCE" -> f.any { Bukkit.getPlayer(it)?.takeIf { x: Player -> x.world.uid == p.world.uid }?.let { x: Player -> x.location.distanceSquared(p.location) <= d.required * d.required } == true }
        "FAMILY_ACTION_COUNT" -> actionService.actionCount(p.uniqueId) >= d.required
        else -> false
    }

    fun clear(player: Player) {
        val entries = applied.remove(player.uniqueId)?.values?.toList() ?: return
        entries.forEach { entry ->
            val current = player.getPotionEffect(entry.type)
            if (current != null && current.amplifier == entry.amplifier) {
                if (entry.previous != null) player.addPotionEffect(entry.previous) else player.removePotionEffect(entry.type)
            }
        }
    }
}
