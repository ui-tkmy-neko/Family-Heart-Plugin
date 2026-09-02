package nekouidaga.net.familyheartplugin.audit
import nekouidaga.net.familyheartplugin.database.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
class AuditService(private val db:DatabaseManager,private val dao:AuditDao){fun log(a:UUID?,action:String,target:UUID?,rel:String?,result:String,reason:String?){db.executor.submit{db.connection().use{dao.log(it,a,action,target,rel,result,reason)}}}}
