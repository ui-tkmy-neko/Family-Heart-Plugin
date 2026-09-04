package nekouidaga.net.familyheartplugin.request

import nekouidaga.net.familyheartplugin.config.EconomyService
import nekouidaga.net.familyheartplugin.config.Settings
import nekouidaga.net.familyheartplugin.database.DatabaseManager
import nekouidaga.net.familyheartplugin.database.PlayerDao
import nekouidaga.net.familyheartplugin.database.tx
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory request registry. Requests are intentionally transient: they expire after a short
 * period or when either participant disconnects. SQLite is reserved for durable domain data and
 * request audit history is written asynchronously to logs/latest.log.
 */
class RequestService(
    private val db: DatabaseManager,
    private val rel: RelationshipService,
    private val settings: Settings,
    private val economy: EconomyService,
    private val players: PlayerDao,
    private val requestLog: RequestLogService
) {
    private val requests = ConcurrentHashMap<Long, RelationshipRequest>()
    private val online = ConcurrentHashMap.newKeySet<UUID>()
    private val mcids = ConcurrentHashMap<UUID, String>()
    private val idSequence = AtomicLong(0)
    private val lock = Any()

    fun create(a: UUID, b: UUID, type: RequestType, metadata: String? = null, requesterLabel: String? = null, targetLabel: String? = null): CompletableFuture<Long> {
        synchronized(lock) {
            if (requesterLabel != null) mcids[a] = canonicalMcid(requesterLabel)
            if (targetLabel != null) mcids[b] = canonicalMcid(targetLabel)
            if (a == b) return CompletableFuture.failedFuture(IllegalArgumentException("self"))
            if (requests.values.any { it.status == RequestStatus.PENDING || it.status == RequestStatus.PROCESSING } &&
                requests.values.any { it.status.let { st -> st == RequestStatus.PENDING || st == RequestStatus.PROCESSING } && it.type == type &&
                    ((it.requester == a && it.target == b) || (it.requester == b && it.target == a)) }) {
                return CompletableFuture.failedFuture(nekouidaga.net.familyheartplugin.service.RequestException(
                    nekouidaga.net.familyheartplugin.service.RequestError.DUPLICATE_PENDING
                ))
            }
            val id = idSequence.incrementAndGet()
            val now = java.time.Instant.now()
            requests[id] = RelationshipRequest(id, a, b, type, metadata, RequestStatus.PENDING, now, now, null)
            requestLog.log("${type.name.lowercase()} request send from ${identity(a)} to ${identity(b)} #$id")
            return CompletableFuture.completedFuture(id)
        }
    }

    fun markOnline(uuid: UUID, mcid: String) {
        online.add(uuid)
        mcids[uuid] = canonicalMcid(mcid)
    }
    fun markOffline(uuid: UUID) { online.remove(uuid) }

    private fun canonicalMcid(value: String): String = value.trim().ifEmpty { "unknown" }

    private fun identity(uuid: UUID): String {
        val mcid = mcids[uuid] ?: Bukkit.getPlayer(uuid)?.name?.let(::canonicalMcid) ?: "unknown"
        return "$mcid($uuid)"
    }

    fun findMcid(mcid: String): CompletableFuture<UUID?> = CompletableFuture.supplyAsync({
        db.connection().use { c -> players.byMcid(c, mcid) }
    }, db.executor)

    fun mcid(uuid: UUID): CompletableFuture<String?> {
        mcids[uuid]?.let { return CompletableFuture.completedFuture(it) }
        Bukkit.getPlayer(uuid)?.name?.let { name ->
            val value = name
            mcids[uuid] = value
            return CompletableFuture.completedFuture(value)
        }
        return CompletableFuture.supplyAsync({ db.connection().use { c -> players.mcidByUuid(c, uuid) } }, db.executor)
            .thenApply { value -> value?.also { mcids[uuid] = it } }
    }

    fun pending(u: UUID): CompletableFuture<List<RelationshipRequest>> = CompletableFuture.completedFuture(
        synchronized(lock) { requests.values.filter { it.target == u && it.status == RequestStatus.PENDING }.sortedByDescending { it.id } }
    )

    fun get(id: Long): CompletableFuture<RelationshipRequest?> = CompletableFuture.completedFuture(requests[id]?.takeIf { it.status != RequestStatus.EXPIRED && it.status != RequestStatus.CANCELLED })

    fun latestPending(u: UUID): CompletableFuture<RelationshipRequest?> = CompletableFuture.completedFuture(
        synchronized(lock) { requests.values.filter { it.target == u && it.status == RequestStatus.PENDING }.maxByOrNull { it.id } }
    )

    fun latestPendingMarriage(u: UUID): CompletableFuture<RelationshipRequest?> = CompletableFuture.completedFuture(
        synchronized(lock) { requests.values.filter { it.target == u && it.type == RequestType.MARRY && it.status == RequestStatus.PENDING }.maxByOrNull { it.id } }
    )

    fun cost(type: RequestType): Double = acceptanceCost(type)

    private fun acceptanceCost(type: RequestType): Double = when (type) {
        RequestType.MARRY -> if (settings.econEnabled()) settings.cost("marriage").coerceAtLeast(0.0) else 0.0
        RequestType.DIVORCE -> if (settings.econEnabled()) settings.cost("divorce").coerceAtLeast(0.0) else 0.0
        RequestType.CHILD_PARENT -> if (settings.econEnabled()) settings.cost("child").coerceAtLeast(0.0) else 0.0
        RequestType.SEPARATION -> if (settings.econEnabled()) settings.cost("separation").coerceAtLeast(0.0) else 0.0
        RequestType.SKINSHIP -> 0.0
    }

    fun cancelFor(u: UUID): CompletableFuture<List<RelationshipRequest>> {
        val cancelled = synchronized(lock) {
            requests.values.filter { it.status == RequestStatus.PENDING && (it.requester == u || it.target == u) }.also { list ->
                list.forEach { it.status = RequestStatus.CANCELLED; it.updatedAt = java.time.Instant.now(); requests.remove(it.id) }
            }
        }
        cancelled.forEach { requestLog.log("${it.type.name.lowercase()} request cancel by disconnect ${identity(u)} #${it.id}") }
        return CompletableFuture.completedFuture(cancelled)
    }

    fun claimAcceptance(actor: UUID, id: Long, actorLabel: String? = null): CompletableFuture<RelationshipRequest> {
        synchronized(lock) {
            val request = requests[id] ?: return CompletableFuture.failedFuture(IllegalArgumentException("request"))
            if (request.target != actor || request.status != RequestStatus.PENDING) {
                return CompletableFuture.failedFuture(IllegalStateException("invalid"))
            }
            request.status = RequestStatus.PROCESSING
            request.updatedAt = java.time.Instant.now()
            if (actorLabel != null) mcids[actor] = canonicalMcid(actorLabel)
            requestLog.log("${request.type.name.lowercase()} request processing by ${identity(actor)} #$id")
            return CompletableFuture.completedFuture(request)
        }
    }

    fun setProcessingGuard(actor: UUID, id: Long, guard: RequestProcessingGuard): CompletableFuture<Boolean> = CompletableFuture.completedFuture(
        synchronized(lock) {
            val r = requests[id]
            if (r == null || r.target != actor || r.status != RequestStatus.PROCESSING) false
            else { r.processingGuard = guard; r.updatedAt = java.time.Instant.now(); true }
        }
    )

    fun releaseProcessing(actor: UUID, id: Long): CompletableFuture<Boolean> = CompletableFuture.completedFuture(
        synchronized(lock) {
            val r = requests[id]
            if (r == null || r.target != actor || r.status != RequestStatus.PROCESSING) false
            else { r.status = RequestStatus.PENDING; r.processingGuard = null; r.updatedAt = java.time.Instant.now(); requestLog.log("${r.type.name.lowercase()} request returned to pending #$id"); true }
        }
    )

    fun acceptSkinshipAfterAction(actor: UUID, id: Long, actorLabel: String? = null): CompletableFuture<RelationshipRequest> {
        synchronized(lock) {
            val r = requests[id] ?: return CompletableFuture.failedFuture(IllegalArgumentException("request"))
            if (r.target != actor || r.status != RequestStatus.PROCESSING || r.type != RequestType.SKINSHIP) {
                return CompletableFuture.failedFuture(IllegalStateException("invalid"))
            }
            r.status = RequestStatus.ACCEPTED
            r.updatedAt = java.time.Instant.now()
            requests.remove(id)
            if (actorLabel != null) mcids[actor] = canonicalMcid(actorLabel)
            requestLog.log("${r.type.name.lowercase()} request accept by ${identity(actor)} #$id")
            return CompletableFuture.completedFuture(r)
        }
    }

    fun decide(actor: UUID, id: Long, accept: Boolean, acceptedSpouseRole: SpouseRole? = null, actorLabel: String? = null): CompletableFuture<RelationshipRequest> {
        val initial = requests[id]
        if (initial == null) return CompletableFuture.failedFuture(IllegalArgumentException("request"))
        if (initial.target != actor || initial.status != RequestStatus.PENDING) return CompletableFuture.failedFuture(IllegalStateException("invalid"))
        if (initial.type == RequestType.MARRY && acceptedSpouseRole == null && accept) return CompletableFuture.failedFuture(IllegalArgumentException("marriage-role-required"))
        if (initial.type != RequestType.MARRY && acceptedSpouseRole != null) return CompletableFuture.failedFuture(IllegalArgumentException("spouse-role-not-applicable"))
        if (initial.type == RequestType.SKINSHIP && accept) return CompletableFuture.failedFuture(IllegalStateException("skinship-use-acceptSkinshipAfterAction"))
        if (!accept) {
            synchronized(lock) {
                val r = requests.remove(id) ?: return CompletableFuture.failedFuture(IllegalArgumentException("request"))
                if (r.target != actor || r.status != RequestStatus.PENDING) { requests[id] = r; return CompletableFuture.failedFuture(IllegalStateException("invalid")) }
                r.status = RequestStatus.DENIED; r.updatedAt = java.time.Instant.now()
                requestLog.log("${r.type.name.lowercase()} request deny by ${identity(actor)} #$id")
                return CompletableFuture.completedFuture(r)
            }
        }
        return claimAcceptance(actor, id, null).thenCompose { claimed ->
            val cost = acceptanceCost(claimed.type)
            if (cost <= 0.0) {
                completeDecision(actor, claimed, acceptedSpouseRole, actorLabel).handle { result, error ->
                    if (error == null) {
                        CompletableFuture.completedFuture(result)
                    } else {
                        releaseProcessing(actor, id).thenCompose {
                            CompletableFuture.failedFuture<RelationshipRequest>(error)
                        }
                    }
                }.thenCompose { it }
            } else if (!economy.available()) {
                releaseProcessing(actor, id).thenCompose { CompletableFuture.failedFuture(IllegalStateException("economy.insufficient")) }
            } else economy.canChargeAsync(claimed.requester, cost).thenCompose { hasFunds ->
                if (!hasFunds) releaseProcessing(actor, id).thenCompose { CompletableFuture.failedFuture(IllegalStateException("economy.insufficient")) }
                else economy.chargeAsync(claimed.requester, cost).thenCompose { charged ->
                    if (!charged) releaseProcessing(actor, id).thenCompose { CompletableFuture.failedFuture(IllegalStateException("economy.charge-failed")) }
                    else {
                        setProcessingGuard(actor, id, RequestProcessingGuard.ECONOMY_CHARGED).thenCompose { guarded ->
                            if (!guarded) economy.refundAsync(claimed.requester, cost).thenCompose { releaseProcessing(actor, id) }.thenCompose { CompletableFuture.failedFuture(IllegalStateException("request.guard-failed")) }
                            else completeDecision(actor, claimed, acceptedSpouseRole, actorLabel).handle { result, error ->
                                if (error == null) CompletableFuture.completedFuture(result)
                                else economy.refundAsync(claimed.requester, cost).thenCompose { refunded ->
                                    if (!refunded) {
                                        Bukkit.getLogger().severe("[FamilyHeart] Economy refund failed for transient request $id; manual reconciliation required")
                                        CompletableFuture.failedFuture<RelationshipRequest>(IllegalStateException("economy.refund-failed", error))
                                    } else releaseProcessing(actor, id).thenCompose { CompletableFuture.failedFuture<RelationshipRequest>(error) }
                                }
                            }.thenCompose { it }
                        }
                    }
                }
            }
        }
    }

    private fun completeDecision(actor: UUID, request: RelationshipRequest, acceptedSpouseRole: SpouseRole?, actorLabel: String? = null): CompletableFuture<RelationshipRequest> = CompletableFuture.supplyAsync({
        synchronized(lock) {
            val live = requests[request.id] ?: throw IllegalArgumentException("request")
            if (live.target != actor || live.status != RequestStatus.PROCESSING) throw IllegalStateException("invalid")
            if (!online.contains(live.target)) throw IllegalStateException("offline")
        }
        db.connection().use { c ->
            rel.withMutationLock {
                val (result, affected) = c.tx {
                    when (request.type) {
                        RequestType.MARRY -> {
                            val requesterRole = runCatching { SpouseRole.valueOf(request.metadata ?: error("marriage role missing")) }.getOrElse { throw IllegalStateException("invalid requester role") }
                            val targetRole = acceptedSpouseRole ?: throw IllegalArgumentException("marriage-role-required")
                            // Same-sex marriage is supported. WIFE/WIFE and HUSBAND/HUSBAND are valid role combinations;
                            // SpouseRole is a relationship-role label and is not a gender restriction.
                            rel.createWithinTransaction(c, request.requester, request.target, RelationshipType.SPOUSE, requesterRole.name, targetRole.name).let { it.first to it.second }
                        }
                        RequestType.CHILD_PARENT -> {
                            val requesterRole = runCatching { ParentChildRole.valueOf(request.metadata ?: error("child role missing")) }.getOrElse { throw IllegalStateException("invalid requester role") }
                            if (requesterRole == ParentChildRole.PARENT) rel.createWithinTransaction(c, request.requester, request.target, RelationshipType.PARENT_CHILD, ParentChildRole.PARENT.name, ParentChildRole.CHILD.name).let { it.first to it.second }
                            else rel.createWithinTransaction(c, request.target, request.requester, RelationshipType.PARENT_CHILD, ParentChildRole.PARENT.name, ParentChildRole.CHILD.name).let { it.first to it.second }
                        }
                        RequestType.DIVORCE -> null to rel.removeWithinTransaction(c, request.requester, request.target, RelationshipType.SPOUSE)
                        RequestType.SEPARATION -> null to rel.removeWithinTransaction(c, request.requester, request.target, RelationshipType.PARENT_CHILD)
                        RequestType.SKINSHIP -> throw IllegalStateException("skinship-use-acceptSkinshipAfterAction")
                    }
                }
                rel.refreshAffected(c, affected)
                synchronized(lock) {
                    val live = requests.remove(request.id) ?: throw IllegalStateException("request")
                    live.status = RequestStatus.ACCEPTED
                    live.updatedAt = java.time.Instant.now()
                    live.processingGuard = null
                    requestLog.log("${live.type.name.lowercase()} request accept by ${identity(actor)} #${live.id}")
                    live
                }
            }
        }
    })

    fun findLatestPendingMarriage(target: UUID): CompletableFuture<RelationshipRequest?> = latestPendingMarriage(target)

    fun recoverEconomyCharged(): CompletableFuture<Int> = CompletableFuture.completedFuture(0)
    fun recoverProcessing(): CompletableFuture<Int> = CompletableFuture.completedFuture(0)

    // バグ修正(監査): messages.yml の request.expired は定義済みだが、以前はこの関数が
    // ログ出力のみ行い、戻り値も無いため呼び出し元(FamilyHeartPlugin.onEnable の定期タスク)
    // には期限切れになった申請が一切伝わらず、当事者への通知が不可能だった。
    // 期限切れになったRequestの一覧を返し、通知は呼び出し元(Messagesを持つ側)に委ねる。
    fun expireAll(): List<RelationshipRequest> {
        val now = java.time.Instant.now()
        val expired = synchronized(lock) {
            requests.values.filter { it.status == RequestStatus.PENDING && now.epochSecond - it.createdAt.epochSecond >= settings.requestExpireSeconds() }.also { list ->
                list.forEach { it.status = RequestStatus.EXPIRED; it.updatedAt = now; requests.remove(it.id) }
            }
        }
        expired.forEach { requestLog.log("${it.type.name.lowercase()} request expire between ${identity(it.requester)} and ${identity(it.target)} #${it.id}") }
        return expired
    }

    fun shutdown() { requestLog.shutdown() }
}
