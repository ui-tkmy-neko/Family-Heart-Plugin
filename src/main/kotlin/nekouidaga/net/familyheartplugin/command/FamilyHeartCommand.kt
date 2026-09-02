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
            .exceptionally {
                Bukkit.getScheduler().runTask(p, Runnable { send(pl, "general.database-error") })
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
                val cost = settings.cost("marriage")
                if (settings.econEnabled() && (!economy.available() || !economy.canCharge(s, cost))) {
                    send(s, "general.database-error")
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
                            .exceptionally {
                                Bukkit.getScheduler().runTask(p, Runnable { send(s, "general.database-error") })
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
                if (mode == "parent") request(s, target, RequestType.CHILD_PARENT)
                else request(target, s, RequestType.CHILD_PARENT)
            }

            "separation" -> {
                if (!ok(s, "familyheart.separation")) {
                    send(s, "general.no-permission")
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
                p.reloadConfig(); msg.reload(); settings.reload(); send(s, "admin.reload")
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
                    if (x != null && y != null) rel.resetPair(Bukkit.getOfflinePlayer(x).uniqueId, Bukkit.getOfflinePlayer(y).uniqueId)
                }
            }
            "family" -> if (a.getOrNull(1)?.equals("info", true) == true && ok(s, "familyheart.admin.family.info")) {
                val u = a.getOrNull(2)?.let { Bukkit.getOfflinePlayer(it).uniqueId } ?: s.uniqueId
                s.sendMessage("Family relationships: ${rel.relationships(u).size}")
            }
            "penalty" -> when (a.getOrNull(1)?.lowercase()) {
                "add" -> if (ok(s, "familyheart.admin.penalty.add")) {
                    val u = a.getOrNull(2)?.let { Bukkit.getOfflinePlayer(it).uniqueId } ?: s.uniqueId
                    val effect = a.getOrNull(3) ?: "SPEED"
                    val mult = a.getOrNull(4)?.toDoubleOrNull() ?: 1.0
                    penalty.add(PenaltyTargetType.PLAYER, u, null, effect, 0.0, mult, null, true).thenAccept { id ->
                        send(s, "admin.penalty-added", mapOf("value" to id.toString()))
                        audit.log(s.uniqueId, "PENALTY_ADD", u, null, "SUCCESS", null)
                    }
                }
                "remove" -> if (ok(s, "familyheart.admin.penalty.remove")) a.getOrNull(2)?.toLongOrNull()?.let { id ->
                    penalty.remove(id).thenRun { send(s, "admin.penalty-removed") }
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
