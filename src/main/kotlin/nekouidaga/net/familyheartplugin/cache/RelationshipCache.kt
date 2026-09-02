package nekouidaga.net.familyheartplugin.cache
import nekouidaga.net.familyheartplugin.model.FamilyRelationship
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
class RelationshipCache{private val m=ConcurrentHashMap<UUID,List<FamilyRelationship>>();fun get(u:UUID)=m[u]?:emptyList();fun replace(u:UUID,v:List<FamilyRelationship>){m[u]=v};fun clear(u:UUID){m.remove(u)};fun clearAll(){m.clear()};fun add(r:FamilyRelationship){listOf(r.playerA,r.playerB).forEach{m.compute(it){_,v->(v?:emptyList())+r}}};fun remove(r:FamilyRelationship){listOf(r.playerA,r.playerB).forEach{u->m.computeIfPresent(u){_,v->v.filterNot{it.internalId==r.internalId}}}}}
