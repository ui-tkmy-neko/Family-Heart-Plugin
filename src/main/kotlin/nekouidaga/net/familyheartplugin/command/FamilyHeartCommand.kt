package nekouidaga.net.familyheartplugin.command

import nekouidaga.net.familyheartplugin.action.ActionService
import nekouidaga.net.familyheartplugin.audit.AuditService
import nekouidaga.net.familyheartplugin.buff.BuffService
import nekouidaga.net.familyheartplugin.config.EconomyService
import nekouidaga.net.familyheartplugin.config.Settings
import nekouidaga.net.familyheartplugin.gui.GuiManager
import nekouidaga.net.familyheartplugin.message.Messages
import nekouidaga.net.familyheartplugin.model.*
import nekouidaga.net.familyheartplugin.penalty.PenaltyService
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class FamilyHeartCommand(
    private val p: JavaPlugin,
    private val rel: RelationshipService,
    private val req: RequestService,
    private val act: ActionService,
    private val gui: GuiManager,
    private val msg: Messages,
    private val settings: Settings,
    private val economy: EconomyService,
    private val penalty: PenaltyService,
    private val buff: BuffService,
    private val audit: AuditService
) : CommandExecutor, TabCompleter {
    private fun ok(s: CommandSender, node: String) = s.hasPermission(node)

    private fun send(s: Player, key: String, vars: Map<String, String> = emptyMap()) {
        s.sendMessage(msg.get(key, vars))
    }

    private fun target(pl: Player, args: List<String>, index: Int = 1): Player? =
        if (args.size > index) Bukkit.getPlayerExact(args[index])
        else Bukkit.getOnlinePlayers()
            .filter { it != pl && it.world.uid == pl.world.uid }
            .minByOrNull { it.location.distanceSquared(pl.location) }

    private fun request(pl: Player, target: Player, type: RequestType, meta: String? = null) {
        req.create(pl.uniqueId, target.uniqueId, type, meta)
            .thenAccept { id ->
                Bukkit.getScheduler().runTask(p, Runnable {
                    send(pl, "request.created", mapOf("request_id" to id.toString()))
                    send(target, "request.received", mapOf("player" to pl.name, "request_id" to id.toString()))
                })
            }
            .exceptionally { ex ->
                Bukkit.getScheduler().runTask(p, Runnable {
                    // バグ修正(7): 以前は重複申請チェックが無く、常にdatabase-errorとして
                    // 一括りにしていた。RequestException(DUPLICATE_PENDING)は専用メッセージにする。
                    val cause = ex.cause ?: ex
                    if (cause is nekouidaga.net.familyheartplugin.service.RequestException &&
                        cause.error == nekouidaga.net.familyheartplugin.service.RequestError.DUPLICATE_PENDING
                    ) {
                        send(pl, "request.duplicate-pending")
                    } else {
                        send(pl, "general.database-error")
                    }
                })
                null
            }
    }

    override fun onCommand(s: CommandSender, c: Command, label: String, args: Array<String>): Boolean {
        if (s !is Player) {
            s.sendMessage(msg.get("general.player-only"))
            return true
        }
        if (!ok(s, "familyheart.use")) {
            send(s, "general.no-permission")
            return true
        }
        if (args.isEmpty() || args[0].equals("gui", true)) {
            gui.openMain(s)
            return true
        }

        if (args[0].equals(settings.customItemCommand(), ignoreCase = false)) {
            if (args.size != 2) {
                send(s, "custom-item.usage")
                return true
            }
            val mcid = args[1]
            if (settings.customItem(mcid) == null) {
                send(s, "custom-item.not-configured")
                return true
            }
            req.findMcid(mcid).thenAccept { targetUuid ->
                Bukkit.getScheduler().runTask(p, Runnable {
                    if (targetUuid == null) {
                        send(s, "custom-item.target-not-found", mapOf("mcid" to mcid))
                        return@Runnable
                    }
                    val target = Bukkit.getPlayer(targetUuid)
                    if (target == null) {
                        send(s, "custom-item.offline", mapOf("mcid" to mcid))
                        return@Runnable
                    }
                    req.createCustomItemRequest(s.uniqueId, target.uniqueId, mcid).thenAccept { id ->
                        Bukkit.getScheduler().runTask(p, Runnable {
                            send(s, "custom-item.request-sent", mapOf("mcid" to mcid, "request_id" to id.toString()))
                            send(target, "custom-item.request-received", mapOf("player" to s.name, "request_id" to id.toString()))
                        })
                    }.exceptionally { ex ->
                        Bukkit.getScheduler().runTask(p, Runnable {
                            val cause = ex.cause ?: ex
                            if (cause is nekouidaga.net.familyheartplugin.service.RequestException &&
                                cause.error == nekouidaga.net.familyheartplugin.service.RequestError.DUPLICATE_PENDING) {
                                send(s, "request.duplicate-pending")
                            } else send(s, "general.database-error")
                        })
                        null
                    }
                })
            }.exceptionally {
                Bukkit.getScheduler().runTask(p, Runnable { send(s, "general.database-error") })
                null
            }
            return true
        }

        when (args[0].lowercase()) {
            "family" -> if (ok(s, "familyheart.family")) gui.openFamily(s)
            "requests" -> if (ok(s, "familyheart.requests")) gui.openRequests(s)

            "marry" -> {
                if (!ok(s, "familyheart.marry")) {
                    send(s, "general.no-permission")
                    return true
                }
                // /fh marry [MCID] {husband|wife}
                val target = target(s, args.toList(), 1) ?: run {
                    send(s, "general.target-not-found")
                    return true
                }
                val role = args.getOrNull(2)?.uppercase()?.let {
                    runCatching { SpouseRole.valueOf(it) }.getOrNull()
                } ?: run {
                    send(s, "general.invalid-input")
                    return true
                }
                if (target == s) {
                    send(s, "relationship.self")
                    return true
                }
                request(s, target, RequestType.MARRY, role.name)
            }

            "accept" -> {
                if (!ok(s, "familyheart.requests")) {
                    send(s, "general.no-permission")
                    return true
                }
                // /fh accept {husband|wife}
                val role = args.getOrNull(1)?.uppercase()?.let {
                    runCatching { SpouseRole.valueOf(it) }.getOrNull()
                } ?: run {
                    send(s, "general.invalid-input")
                    return true
                }
                req.findLatestPendingMarriage(s.uniqueId).thenAccept { request ->
                    Bukkit.getScheduler().runTask(p, Runnable {
                        if (request == null) {
                            send(s, "relationship.not-found")
                            return@Runnable
                        }
                        req.decide(s.uniqueId, request.id, true, role)
                            .thenAccept { result ->
                                Bukkit.getScheduler().runTask(p, Runnable {
                                    val requester = Bukkit.getPlayer(result.requester)
                                    send(s, "request.accepted")
                                    requester?.let {
                                        send(it, "request.accepted")
                                        send(it, "relationship.created", mapOf(
                                            "relationship_id" to (rel.relationships(s.uniqueId)
                                                .firstOrNull { r -> r.type == RelationshipType.SPOUSE && r.involves(result.requester) }
                                                ?.relationshipId ?: "")
                                        ))
                                    }
                                })
                            }
                            .exceptionally { ex ->
                                Bukkit.getScheduler().runTask(p, Runnable {
                                    val cause = ex.cause ?: ex
                                    when (cause.message) {
                                        "economy.insufficient" -> send(s, "economy.insufficient-funds", mapOf("cost" to settings.cost("marriage").toString()))
                                        "economy.charge-failed" -> send(s, "economy.unavailable")
                                        else -> send(s, "general.database-error")
                                    }
                                })
                                null
                            }
                    })
                }
            }

            "divorce" -> {
                if (!ok(s, "familyheart.divorce")) {
                    send(s, "general.no-permission")
                    return true
                }
                if (!rel.isLoaded(s.uniqueId)) {
                    send(s, "general.database-error")
                    return true
                }
                val sp = rel.spouse(s.uniqueId)?.let(Bukkit::getPlayer) ?: run {
                    send(s, "relationship.not-found")
                    return true
                }
                request(s, sp, RequestType.DIVORCE)
            }

            "child" -> {
                if (!ok(s, "familyheart.child")) {
                    send(s, "general.no-permission")
                    return true
                }
                // /fh child {parent|child} [MCID]
                val mode = args.getOrNull(1)?.lowercase()
                if (mode != "parent" && mode != "child") {
                    send(s, "general.invalid-input")
                    return true
                }
                val target = target(s, args.toList(), 2) ?: run {
                    send(s, "general.target-not-found")
                    return true
                }
                if (target == s) {
                    send(s, "relationship.self")
                    return true
                }
                // バグ修正: 以前は mode=="child" のとき request(target, s, ...) と
                // requester/targetを入れ替えていたため、DB上のtarget(承認者)が
                // 実行者自身になり、相手の同意なしに自己承認できてしまっていた。
                // 常に「実行者=requester、相手=target(承認者)」を保ち、
                // 実行者が名乗った役割(PARENT/CHILD)はmetadataに保存して
                // RequestService側で判定する(MARRYのSpouseRoleと同じ方式)。
                val role = if (mode == "parent") ParentChildRole.PARENT else ParentChildRole.CHILD
                request(s, target, RequestType.CHILD_PARENT, role.name)
            }

            "separation" -> {
                if (!ok(s, "familyheart.separation")) {
                    send(s, "general.no-permission")
                    return true
                }
                if (!rel.isLoaded(s.uniqueId)) {
                    send(s, "general.database-error")
                    return true
                }
                val rs = rel.relationships(s.uniqueId).filter { it.type == RelationshipType.PARENT_CHILD }
                val t = args.getOrNull(1)?.let(Bukkit::getPlayerExact)
                    ?: rs.firstOrNull()?.getOther(s.uniqueId)?.let(Bukkit::getPlayer)
                if (t == null) {
                    send(s, "general.no-target")
                    return true
                }
                request(s, t, RequestType.SEPARATION)
            }

            "hug", "kiss", "feed" -> {
                val node = "familyheart.action.${args[0].lowercase()}"
                if (!ok(s, node)) {
                    send(s, "general.no-permission")
                    return true
                }
                val t = target(s, args.toList(), 1) ?: run {
                    send(s, "general.no-target")
                    return true
                }
                val r = act.execute(s, t, args[0].lowercase()) { id ->
                    send(s, "request.created", mapOf("request_id" to id.toString()))
                }
                when {
                    r.success -> send(s, "action.executed", mapOf("action" to args[0]))
                    r.reason == "distance" -> send(s, "general.distance")
                    r.reason.startsWith("cooldown:") -> send(s, "action.cooldown", mapOf("duration" to r.reason.substringAfter(":")))
                    r.reason == "self" -> send(s, "relationship.self")
                    r.reason == "approval" -> {} // 承認待ち: request.createdはonApprovalコールバックで送信済み/送信予定
                }
            }

            "admin" -> admin(s, args.drop(1))
            else -> send(s, "general.invalid-input")
        }
        return true
    }

    private fun admin(s: Player, a: List<String>) {
        if (!ok(s, "familyheart.admin")) {
            send(s, "general.no-permission")
            return
        }
        when (a.getOrNull(0)?.lowercase()) {
            "reload" -> {
                p.reloadConfig(); msg.reload(); settings.reload(); gui.reload(); send(s, "admin.reload")
            }
            "relation" -> when (a.getOrNull(1)?.lowercase()) {
                "info" -> if (ok(s, "familyheart.admin.relation.info")) a.getOrNull(2)?.let { id ->
                    rel.info(id).thenAccept { r -> Bukkit.getScheduler().runTask(p, Runnable {
                        if (r == null) send(s, "relationship.not-found") else send(s, "admin.info", mapOf(
                            "relationship_id" to r.relationshipId,
                            "relationship_type" to r.type.name,
                            "player" to r.playerA.toString(),
                            "target" to r.playerB.toString()
                        ))
                    }) }
                }
                "remove" -> if (ok(s, "familyheart.admin.relation.remove")) a.getOrNull(2)?.let { id ->
                    rel.removeById(id).thenRun {
                        Bukkit.getScheduler().runTask(p, Runnable { send(s, "relationship.removed", mapOf("relationship_id" to id)) })
                        audit.log(s.uniqueId, "RELATION_REMOVE", null, id, "SUCCESS", "admin")
                    }
                }
                "reset" -> if (ok(s, "familyheart.admin.relation.reset")) {
                    val x = a.getOrNull(2); val y = a.getOrNull(3)
                    if (x != null && y != null) {
                        // バグ修正: Bukkit.getOfflinePlayer(String)はローカルにキャッシュがない名前だと
                        // Mojang APIへのブロッキングHTTPリクエストを行うことがあり、コマンド実行スレッド
                        // (メインスレッド)で直接呼ぶとサーバー全体が一時停止しうる。非同期タスクへ逃がす。
                        Bukkit.getScheduler().runTaskAsynchronously(p, Runnable {
                            val targetA = Bukkit.getOfflinePlayer(x).uniqueId
                            val targetB = Bukkit.getOfflinePlayer(y).uniqueId
                            // バグ修正: 以前は結果を誰にも通知せず、監査ログにも記録していなかった
                            // (remove/addは記録している)。58章の監査要件に合わせて追加。
                            rel.resetPair(targetA, targetB).thenAccept { count ->
                                Bukkit.getScheduler().runTask(p, Runnable {
                                    send(s, "relationship.removed", mapOf("relationship_id" to "$x <-> $y ($count)"))
                                })
                                audit.log(s.uniqueId, "RELATION_RESET", targetA, null, "SUCCESS", "target=$y count=$count")
                            }
                        })
                    }
                }
            }
            "family" -> if (a.getOrNull(1)?.equals("info", true) == true && ok(s, "familyheart.admin.family.info")) {
                fun report(u: java.util.UUID) {
                    Bukkit.getScheduler().runTask(p, Runnable {
                        if (!rel.isLoaded(u)) { send(s, "general.database-error"); return@Runnable }
                        s.sendMessage("Family relationships: ${rel.relationships(u).size}")
                    })
                }
                val name = a.getOrNull(2)
                if (name == null) report(s.uniqueId)
                // バグ修正: Bukkit.getOfflinePlayer(String)はメインスレッドで呼ぶと
                // Mojang APIへのブロッキング問い合わせが発生しうるため非同期化する。
                else Bukkit.getScheduler().runTaskAsynchronously(p, Runnable { report(Bukkit.getOfflinePlayer(name).uniqueId) })
            }
            "penalty" -> when (a.getOrNull(1)?.lowercase()) {
                "add" -> if (ok(s, "familyheart.admin.penalty.add")) {
                    val effect = a.getOrNull(3) ?: "SPEED"
                    val mult = a.getOrNull(4)?.toDoubleOrNull() ?: 1.0
                    fun proceed(u: java.util.UUID) {
                        penalty.add(PenaltyTargetType.PLAYER, u, null, effect, 0.0, mult, null, true).thenAccept { id ->
                            Bukkit.getScheduler().runTask(p, Runnable {
                                send(s, "admin.penalty-added", mapOf("value" to id.toString()))
                            })
                            audit.log(s.uniqueId, "PENALTY_ADD", u, null, "SUCCESS", null)
                        }.exceptionally {
                            Bukkit.getScheduler().runTask(p, Runnable { send(s, "general.database-error") })
                            null
                        }
                    }
                    val name = a.getOrNull(2)
                    if (name == null) proceed(s.uniqueId)
                    // バグ修正: Bukkit.getOfflinePlayer(String)はメインスレッドで呼ぶと
                    // Mojang APIへのブロッキング問い合わせが発生しうるため非同期化する。
                    else Bukkit.getScheduler().runTaskAsynchronously(p, Runnable { proceed(Bukkit.getOfflinePlayer(name).uniqueId) })
                }
                "remove" -> if (ok(s, "familyheart.admin.penalty.remove")) a.getOrNull(2)?.toLongOrNull()?.let { id ->
                    penalty.remove(id).thenRun {
                        Bukkit.getScheduler().runTask(p, Runnable { send(s, "admin.penalty-removed") })
                    }
                }
            }
        }
    }

    override fun onTabComplete(s: CommandSender, c: Command, alias: String, args: Array<String>): List<String> = when (args.size) {
        1 -> listOf("family", "marry", "accept", "divorce", "child", "separation", "hug", "kiss", "requests", "gui", "admin")
            .filter { it.startsWith(args[0], true) }
        2 -> when (args[0].lowercase()) {
            "marry" -> Bukkit.getOnlinePlayers().map { it.name }
            "accept" -> listOf("wife", "husband")
            "child" -> listOf("parent", "child")
            "separation", "hug", "kiss" -> Bukkit.getOnlinePlayers().map { it.name }
            "admin" -> listOf("relation", "family", "penalty", "reload")
            else -> emptyList()
        }.filter { it.startsWith(args[1], true) }
        3 -> when (args[0].lowercase()) {
            "marry" -> listOf("wife", "husband")
            "child" -> Bukkit.getOnlinePlayers().map { it.name }
            else -> emptyList()
        }.filter { it.startsWith(args[2], true) }
        else -> emptyList()
    }
}
