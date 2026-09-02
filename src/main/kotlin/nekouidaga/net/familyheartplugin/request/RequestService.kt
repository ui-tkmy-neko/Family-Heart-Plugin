package nekouidaga.net.familyheartplugin.request

import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.RequestDao
import nekouidaga.net.familyheartplugin.database.tx
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RequestService(
    private val db: DatabaseManager,
    private val dao: RequestDao,
    private val rel: RelationshipService
) {
    fun create(a: UUID, b: UUID, type: RequestType, metadata: String? = null): CompletableFuture<Long> =
        CompletableFuture.supplyAsync({
            db.connection().use { c -> c.tx { dao.create(c, a, b, type, metadata) } }
        }, db.executor)

    fun get(id: Long): CompletableFuture<RelationshipRequest?> = CompletableFuture.supplyAsync({
        db.connection().use { c -> dao.byId(c, id) }
    }, db.executor)

    fun pending(u: UUID): CompletableFuture<List<RelationshipRequest>> = CompletableFuture.supplyAsync({
        db.connection().use { c -> dao.pendingFor(c, u) }
    }, db.executor)

    fun cancelFor(u: UUID): CompletableFuture<List<RelationshipRequest>> = CompletableFuture.supplyAsync({
        db.connection().use { c ->
            c.tx {
                val requests: List<RelationshipRequest> = dao.pendingInvolving(c, u)
                requests.forEach { request -> dao.update(c, request.id, RequestStatus.CANCELLED) }
                requests
            }
        }
    }, db.executor)

    fun decide(
        actor: UUID,
        id: Long,
        accept: Boolean,
        acceptedSpouseRole: SpouseRole? = null
    ): CompletableFuture<RelationshipRequest> = CompletableFuture.supplyAsync({
        db.connection().use { c ->
            val request: RelationshipRequest = dao.byId(c, id, true)
                ?: throw IllegalArgumentException("request")
            if (request.target != actor || request.status != RequestStatus.PENDING) {
                throw IllegalStateException("invalid")
            }

            if (!accept) {
                dao.update(c, id, RequestStatus.DENIED)
                return@supplyAsync request
            }

            when (request.type) {
                RequestType.MARRY -> {
                    val requesterRole = runCatching {
                        SpouseRole.valueOf(request.metadata ?: throw IllegalArgumentException("marriage role missing"))
                    }.getOrElse { throw IllegalStateException("invalid requester role") }
                    val targetRole = acceptedSpouseRole
                        ?: throw IllegalArgumentException("/fh accept {husband|wife}")

                    rel.create(
                        request.requester,
                        request.target,
                        RelationshipType.SPOUSE,
                        requesterRole.name,
                        targetRole.name
                    )
                }

                RequestType.CHILD_PARENT -> {
                    rel.create(
                        request.requester,
                        request.target,
                        RelationshipType.PARENT_CHILD,
                        ParentChildRole.PARENT.name,
                        ParentChildRole.CHILD.name
                    )
                }

                RequestType.DIVORCE -> rel.remove(request.requester, request.target, RelationshipType.SPOUSE).join()
                RequestType.SEPARATION -> rel.remove(request.requester, request.target, RelationshipType.PARENT_CHILD).join()
                RequestType.SKINSHIP -> Unit
            }

            dao.update(c, id, RequestStatus.ACCEPTED)
            request
        }
    }, db.executor)

    fun findLatestPendingMarriage(target: UUID): CompletableFuture<RelationshipRequest?> =
        pending(target).thenApply { requests -> requests.firstOrNull { it.type == RequestType.MARRY } }

    fun expireAll() {
        db.executor.submit {
            db.connection().use { c ->
                c.prepareStatement(
                    "UPDATE requests SET status='EXPIRED',updated_at=NOW() " +
                        "WHERE status='PENDING' AND created_at < DATE_SUB(NOW(),INTERVAL 10 MINUTE)"
                ).use { st -> st.executeUpdate() }
            }
        }
    }
}
