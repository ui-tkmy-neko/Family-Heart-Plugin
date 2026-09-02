package nekouidaga.net.familyheartplugin.buff
import nekouidaga.net.familyheartplugin.config.Settings
import nekouidaga.net.familyheartplugin.database.*
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.penalty.PenaltyService
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
class BuffService(private val p:JavaPlugin,private val db:DatabaseManager,private val actionService:nekouidaga.net.familyheartplugin.action.ActionService,private val rel:RelationshipService,private val penalty:PenaltyService,private val settings:Settings){private val applied=HashMap<UUID,MutableSet<String>>()
fun recompute(player:Player){penalty.refresh(player.uniqueId);if(!settings.features("family-buffs"))return;val defs=settings.buffs();val family=family(player.uniqueId);defs.forEach{d->if(!d.enabled)return@forEach;val ok=condition(player,family,d);val id="fh-${d.key}";val effect=runCatching{org.bukkit.potion.PotionEffectType.getByName(d.effect)}.getOrNull()?:return@forEach;if(ok){val mult=penalty.multiplier(player.uniqueId,d.effect);val amp=(d.amplifier*mult).toInt().coerceAtLeast(0);player.addPotionEffect(org.bukkit.potion.PotionEffect(effect,d.duration*20,amp,true,false,true));applied.computeIfAbsent(player.uniqueId){mutableSetOf()}.add(id)}else if(applied[player.uniqueId]?.remove(id)==true)player.removePotionEffect(effect)}}
private fun family(u:UUID):Set<UUID>{val set=mutableSetOf(u);fun walk(x:UUID){rel.relationships(x).map{it.getOther(x)}.forEach{if(set.add(it))walk(it)}};walk(u);return set}
private fun condition(p:Player,f:Set<UUID>,d:BuffDefinition):Boolean=when(d.condition){"FAMILY_MEMBERS_ONLINE"->f.count{Bukkit.getPlayer(it)!=null}>=d.required;"FAMILY_SIZE"->f.size>=d.required;"SPOUSE_ONLINE"->rel.spouse(p.uniqueId)?.let{Bukkit.getPlayer(it)!=null}==true;"PARENTS_ONLINE"->rel.parents(p.uniqueId).count{Bukkit.getPlayer(it)!=null}>=d.required;"CHILDREN_ONLINE"->rel.children(p.uniqueId).count{Bukkit.getPlayer(it)!=null}>=d.required;"RELATIONSHIP_COUNT"->rel.relationships(p.uniqueId).size>=d.required;"FAMILY_DISTANCE"->f.any{Bukkit.getPlayer(it)?.takeIf{x->x.world.uid==p.world.uid}?.let{x->x.location.distanceSquared(p.location)<=d.required*d.required}==true};"FAMILY_ACTION_COUNT"->actionService.actionCount(p.uniqueId)>=d.required;else->false}
fun clear(p:Player){applied.remove(p.uniqueId)?.forEach{}}
}
