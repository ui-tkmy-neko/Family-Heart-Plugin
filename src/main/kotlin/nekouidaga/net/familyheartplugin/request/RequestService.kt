package nekouidaga.net.familyheartplugin.request

import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.RequestDao
import nekouidaga.net.familyheartplugin.database.tx
import nekouidaga.net.familyheartplugin.config.EconomyService
import nekouidaga.net.familyheartplugin.database.PlayerDao
import nekouidaga.net.familyheartplugin.config.Settings
import org.bukkit.Bukkit
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import java.sql.SQLIntegrityConstraintViolationException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class RequestService(
    private val db: DatabaseManager,
    private val dao: RequestDao,
    private val rel: RelationshipService,
    private val settings: Settings,
    private val economy: EconomyService,
    private val players: PlayerDao
) {
    private val expirationRunning = AtomicBoolean(false)
    fun create(a: UUID, b: UUID, type: RequestType, metadata: String? = null): CompletableFuture<Long> =
        CompletableFuture.supplyAsync({
            db.connection().use { c ->
                c.tx {
                    // バグ修正(7): 以前は重複チェックなしで単純INSERTしていたため、
                    // 同じ相手に同じ種類の申請を連投すると保留中の申請が際限なく増えていた。
                    if (dao.pendingBetween(c, a, b, type).isNotEmpty()) {
                        throw nekouidaga.net.familyheartplugin.service.RequestException(
                            nekouidaga.net.familyheartplugin.service.RequestError.DUPLICATE_PENDING
                        )
                    }
                    try {
                        dao.create(c, a, b, type, metadata)
                    } catch (e: Exception) {
                        // Xerial sqlite-jdbc は UNIQUE制約違反時に SQLIntegrityConstraintViolationException を
                        // 投げるとは限らない(バージョン依存)ため、型だけでなくSQLiteのエラーメッセージでも判定する。
                        val isUniqueViolation = e is SQLIntegrityConstraintViolationException ||
                            (e.message?.contains("UNIQUE constraint failed", ignoreCase = true) == true)
                        if (isUniqueViolation) {
                            throw nekouidaga.net.familyheartplugin.service.RequestException(
                                nekouidaga.net.familyheartplugin.service.RequestError.DUPLICATE_PENDING
                            )
                        }
                        throw e
                    }
                }
            }
        }, db.executor)

    fun findMcid(mcid: String): CompletableFuture<UUID?> = CompletableFuture.supplyAsync({
        db.connection().use { c -> players.byMcid(c, mcid) }
    }, db.executor)

    fun get(id: Long): CompletableFuture<RelationshipRequest?> = CompletableFuture.supplyAsync({
        db.connection().use { c -> dao.byId(c, id) }
    }, db.executor)

    fun pending(u: UUID): CompletableFuture<List<RelationshipRequest>> = CompletableFuture.supplyAsync({
        db.connection().use { c -> dao.pendingFor(c, u) }
    }, db.executor)

    /** GUI等、Serviceの外からも承認コストを参照できるようにする公開ラッパー。 */
    fun cost(type: RequestType): Double = acceptanceCost(type)

    private fun acceptanceCost(type: RequestType): Double {
        val key = when (type) {
            RequestType.MARRY -> "marriage"
            RequestType.DIVORCE -> "divorce"
            RequestType.CHILD_PARENT -> "child"
            RequestType.SEPARATION -> "separation"
            RequestType.SKINSHIP -> return 0.0
        }
        return if (settings.econEnabled()) settings.cost(key).coerceAtLeast(0.0) else 0.0
    }

    fun cancelFor(u: UUID): CompletableFuture<List<RelationshipRequest>> = CompletableFuture.supplyAsync({
        db.connection().use { c ->
            c.tx {
                val requests: List<RelationshipRequest> = dao.pendingInvolving(c, u)
                requests.forEach { request -> dao.update(c, request.id, RequestStatus.CANCELLED) }
                requests
            }
        }
    }, db.executor)

    /** Atomically claims any pending acceptance so concurrent clicks cannot double-process or double-charge. */
    fun claimAcceptance(actor: UUID, id: Long): CompletableFuture<RelationshipRequest> = CompletableFuture.supplyAsync({
        db.connection().use { c -> rel.withMutationLock {
            c.tx {
                val request = dao.byId(c, id, true) ?: throw IllegalArgumentException("request")
                if (request.target != actor || request.status != RequestStatus.PENDING) throw IllegalStateException("invalid")
                dao.update(c, id, RequestStatus.PROCESSING)
                request.copy(status = RequestStatus.PROCESSING, processingGuard = null)
            }
        } }
    }, db.executor)

    fun acceptSkinshipAfterAction(actor: UUID, id: Long): CompletableFuture<RelationshipRequest> = CompletableFuture.supplyAsync({
        db.connection().use { c -> rel.withMutationLock {
            c.tx {
                val request = dao.byId(c, id, true) ?: throw IllegalArgumentException("request")
                if (request.target != actor || request.status != RequestStatus.PROCESSING || request.type != RequestType.SKINSHIP || request.processingGuard != RequestProcessingGuard.SKINSHIP_EXECUTED) {
                    throw IllegalStateException("invalid")
                }
                dao.update(c, id, RequestStatus.ACCEPTED)
                request
            }
        } }
    }, db.executor)


    fun setProcessingGuard(actor: UUID, id: Long, guard: RequestProcessingGuard): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({
        db.connection().use { c -> rel.withMutationLock {
            c.tx {
                val request = dao.byId(c, id, true) ?: return@tx false
                if (request.target != actor || request.status != RequestStatus.PROCESSING) return@tx false
                dao.setProcessingGuard(c, id, guard)
                true
            }
        } }
    }, db.executor)

    fun releaseProcessing(actor: UUID, id: Long): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({
        db.connection().use { c -> rel.withMutationLock {
            c.tx {
                val request = dao.byId(c, id, true) ?: return@tx false
                if (request.target != actor || request.status != RequestStatus.PROCESSING) return@tx false
                dao.update(c, id, RequestStatus.PENDING)
                true
            }
        } }
    }, db.executor)

    fun decide(
        actor: UUID,
        id: Long,
        accept: Boolean,
        acceptedSpouseRole: SpouseRole? = null
    ): CompletableFuture<RelationshipRequest> {
        return CompletableFuture.supplyAsync({ db.connection().use { c -> dao.byId(c, id) } }, db.executor)
            .thenCompose { initial ->
                if (initial == null) return@thenCompose CompletableFuture.failedFuture<RelationshipRequest>(IllegalArgumentException("request"))
                if (initial.target != actor || initial.status != RequestStatus.PENDING) {
                    return@thenCompose CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("invalid"))
                }
                if (!accept) return@thenCompose completeDecision(actor, id, false, acceptedSpouseRole)
                if (initial.type == RequestType.SKINSHIP) {
                    return@thenCompose CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("skinship-use-acceptSkinshipAfterAction"))
                }
                claimAcceptance(actor, id).thenCompose { claimed ->
                    val cost = acceptanceCost(claimed.type)
                    if (cost <= 0.0) {
                        completeDecision(actor, id, true, acceptedSpouseRole)
                    } else if (!economy.available()) {
                        releaseProcessing(actor, id)
                            .thenCompose { CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("economy.insufficient")) }
                    } else {
                        economy.offlinePlayerAsync(claimed.requester).thenCompose { player: org.bukkit.OfflinePlayer ->
                            setProcessingGuard(actor, id, RequestProcessingGuard.ECONOMY_INTENT).thenCompose { guarded ->
                                if (!guarded) return@thenCompose CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("request.guard-failed"))
                                economy.canChargeAsync(player, cost).thenCompose { hasFunds ->
                                    if (!hasFunds) {
                                        releaseProcessing(actor, id).thenCompose { CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("economy.insufficient")) }
                                    } else economy.chargeAsync(player, cost).thenCompose { charged ->
                                        if (!charged) {
                                            releaseProcessing(actor, id).thenCompose { CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("economy.charge-failed")) }
                                        } else {
                                            setProcessingGuard(actor, id, RequestProcessingGuard.ECONOMY_CHARGED).thenCompose { chargedGuarded ->
                                                if (!chargedGuarded) {
                                                    // The money moved but the durable marker did not; never auto-retry.
                                                    CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("economy.reconciliation-required"))
                                                } else completeDecision(actor, id, true, acceptedSpouseRole)
                                            }.handle { result, error ->
                                                if (error == null) CompletableFuture.completedFuture(result)
                                                else economy.refundAsync(player, cost).thenCompose { refunded ->
                                                    if (!refunded) {
                                                        Bukkit.getLogger().severe("[FamilyHeart] Economy refund failed for request $id; request is intentionally kept in PROCESSING for manual reconciliation: ${error.message}")
                                                        CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("economy.refund-failed", error))
                                                    } else releaseProcessing(actor, id).thenCompose {
                                                        CompletableFuture.failedFuture<RelationshipRequest>(error)
                                                    }
                                                }
                                            }.thenCompose { it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun completeDecision(
        actor: UUID,
        id: Long,
        accept: Boolean,
        acceptedSpouseRole: SpouseRole?
    ): CompletableFuture<RelationshipRequest> = CompletableFuture.supplyAsync({
        db.connection().use { c ->
            rel.withMutationLock {
                var affected = emptySet<UUID>()
                try {
                    val result = c.tx {
                        val request = dao.byId(c, id, true) ?: throw IllegalArgumentException("request")
                        if (request.target != actor || request.status != if (accept) RequestStatus.PROCESSING else RequestStatus.PENDING) throw IllegalStateException("invalid")
                        if (!accept) {
                            dao.update(c, id, RequestStatus.DENIED)
                            return@tx request
                        }
                        if (request.type == RequestType.SKINSHIP) throw IllegalStateException("skinship-use-acceptSkinshipAfterAction")
                        affected = when (request.type) {
                            RequestType.MARRY -> {
                                val requesterRole = runCatching { SpouseRole.valueOf(request.metadata ?: error("marriage role missing")) }.getOrElse { throw IllegalStateException("invalid requester role") }
                                val targetRole = acceptedSpouseRole ?: throw IllegalArgumentException("/fh accept {husband|wife}")
                                if (requesterRole == targetRole) throw IllegalArgumentException("marriage.same-role")
                                rel.createWithinTransaction(c, request.requester, request.target, RelationshipType.SPOUSE, requesterRole.name, targetRole.name).second
                            }
                            RequestType.CHILD_PARENT -> {
                                val requesterRole = runCatching { ParentChildRole.valueOf(request.metadata ?: error("child role missing")) }.getOrElse { throw IllegalStateException("invalid requester role") }
                                if (requesterRole == ParentChildRole.PARENT)
                                    rel.createWithinTransaction(c, request.requester, request.target, RelationshipType.PARENT_CHILD, ParentChildRole.PARENT.name, ParentChildRole.CHILD.name).second
                                else
                                    rel.createWithinTransaction(c, request.target, request.requester, RelationshipType.PARENT_CHILD, ParentChildRole.PARENT.name, ParentChildRole.CHILD.name).second
                            }
                            RequestType.DIVORCE -> rel.removeWithinTransaction(c, request.requester, request.target, RelationshipType.SPOUSE)
                            RequestType.SEPARATION -> rel.removeWithinTransaction(c, request.requester, request.target, RelationshipType.PARENT_CHILD)
                            RequestType.SKINSHIP -> emptySet()
                        }
                        dao.update(c, id, RequestStatus.ACCEPTED)
                        request
                    }
                    // The transaction has committed here. Only now publish cache changes.
                    if (affected.isNotEmpty()) rel.refreshAffected(c, affected)
                    result
                } catch (e: Exception) {
                    // Economy charging happens outside the DB transaction; caller handles asynchronous refund.
                    throw e
                }
            }
        }
    }, db.executor)

    fun findLatestPendingMarriage(target: UUID): CompletableFuture<RelationshipRequest?> =
        pending(target).thenApply { requests -> requests.firstOrNull { it.type == RequestType.MARRY } }

    fun recoverProcessing(): CompletableFuture<Int> = CompletableFuture.supplyAsync({
        db.connection().use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM requests WHERE status='PROCESSING' AND updated_at < datetime('now','-2 minutes')").use { q ->
                val count = q.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                dao.recoverProcessing(c)
                count
            }
        }
    }, db.executor)

    fun expireAll() {
        if (!expirationRunning.compareAndSet(false, true)) return
        db.executor.submit {
            try {
                db.connection().use { c ->
                    c.prepareStatement(
                        "UPDATE requests SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP,pending_key=NULL " +
                            "WHERE status='PENDING' AND created_at < datetime('now','-10 minutes')"
                    ).use { st -> st.executeUpdate() }
                }
            } finally {
                expirationRunning.set(false)
            }
        }
    }
}
