package nekouidaga.net.familyheartplugin.penalty
import nekouidaga.net.familyheartplugin.database.*
import nekouidaga.net.familyheartplugin.model.PenaltyTargetType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
class PenaltyService(private val db:DatabaseManager,private val dao:PenaltyDao){private val cache=ConcurrentHashMap<UUID,Double>()
fun refresh(u:UUID){db.executor.submit{val v=db.connection().use{dao.expire(it);dao.forPlayer(it,u).fold(1.0){a,x->if(x.targetType==PenaltyTargetType.PLAYER||x.targetType==PenaltyTargetType.FAMILY)a*x.multiplier else a}};cache[u]=v}}
fun add(type:PenaltyTargetType,p:UUID?,rel:String?,effect:String,value:Double,multiplier:Double,end:Instant?,removable:Boolean)=CompletableFuture.supplyAsync({db.connection().use{dao.add(it,type,p,rel,effect,value,multiplier,end,removable)}},db.executor).also{it.thenAccept{p?.let(::refresh)}}
fun remove(id:Long)=CompletableFuture.runAsync({db.connection().use{dao.remove(it,id)}},db.executor)
fun multiplier(player:UUID,effect:String)=cache[player]?:1.0}
