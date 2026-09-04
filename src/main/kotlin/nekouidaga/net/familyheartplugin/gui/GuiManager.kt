package nekouidaga.net.familyheartplugin.gui

import nekouidaga.net.familyheartplugin.model.RequestProcessingGuard

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import nekouidaga.net.familyheartplugin.action.ActionService
import nekouidaga.net.familyheartplugin.message.Messages
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.service.messageKey
import nekouidaga.net.familyheartplugin.model.RelationshipType
import nekouidaga.net.familyheartplugin.model.RequestType
import nekouidaga.net.familyheartplugin.model.RelationshipRequest
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration

// バグ修正(4): 以前はクリック処理がevent.view.title()の文字列(「FamilyHeart」「申請」等)を
// 直接パターンマッチしており、gui.ymlでタイトルをカスタマイズすると
// クリック判定が一致しなくなりメニューが機能しなくなっていた(設定可能という設計と矛盾)。
// メニュー種別をInventoryHolderとして持たせ、タイトル文字列に依存しないようにする。
private enum class MenuType { MAIN, FAMILY, SKINSHIP, REQUESTS, MARRIAGE_ROLE, SETTINGS }

private class FamilyHeartHolder(
    val menu: MenuType,
    // MAINメニューでは、実際にgui.ymlへ設定されたslot番号を動的に読み取って
    // slot -> item-key の対応を保持する(バグ修正5: 以前はrawSlotの数値をハードコードしており、
    // 「家族(10)/配偶者(11)/親子関係(12)/Relationship(15)」が全部openFamily()に固定されていて
    // 実質同じ画面しか開けなかった)。
    val slotToKey: Map<Int, String> = emptyMap(),
    val requestId: Long? = null
) : InventoryHolder {
    lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
}

class GuiManager(
    private val p: JavaPlugin,
    private val rel: RelationshipService,
    private val req: RequestService,
    private val actions: ActionService,
    private val messages: Messages
) : Listener {
    @Volatile private var y: YamlConfiguration = YamlConfiguration.loadConfiguration(p.dataFolder.resolve("gui.yml"))
    fun reload() { y = YamlConfiguration.loadConfiguration(p.dataFolder.resolve("gui.yml")) }

    private fun text(value: String): Component =
        LegacyComponentSerializer.legacySection().deserialize(
            org.bukkit.ChatColor.translateAlternateColorCodes('&', value)
        ).decoration(TextDecoration.ITALIC, false)

    private fun inv(holder: FamilyHeartHolder, title: String, size: Int): Inventory {
        val inventory = Bukkit.createInventory(holder, size, text(title))
        holder.inv = inventory
        return inventory
    }

    private fun item(materialName: String, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(Material.matchMaterial(materialName) ?: Material.STONE)
        val meta = item.itemMeta ?: return item
        meta.displayName(text(name))
        meta.lore(lore.map(::text))
        item.itemMeta = meta
        return item
    }

    fun openMain(player: Player) {
        val slotToKey = mutableMapOf<Int, String>()
        val holder = FamilyHeartHolder(MenuType.MAIN, slotToKey)
        val inventory = inv(holder, y.getString("main.title", "FamilyHeart") ?: "FamilyHeart", y.getInt("main.size", 27))
        listOf("family", "spouse", "parent-child", "skinship", "requests", "relationship", "settings").forEach { key ->
            val section = y.getConfigurationSection("main.items.$key") ?: return@forEach
            val slot = section.getInt("slot")
            slotToKey[slot] = key
            inventory.setItem(
                slot,
                item(
                    section.getString("material", "PAPER") ?: "PAPER",
                    section.getString("name", key) ?: key,
                    section.getStringList("lore")
                )
            )
        }
        player.openInventory(inventory)
    }

    /**
     * 家族情報一覧を表示する。filterを指定すると配偶者/親子関係のみに絞り込める
     * (バグ修正5: 以前は「家族」「配偶者」「親子関係」「Relationship」の4ボタンが
     * すべて無条件の全件一覧を開いており、実質同じ画面だった)。
     */
    fun openFamily(player: Player, filter: RelationshipType? = null, titleSection: String = "family", defaultTitle: String = "家族") {
        if (!rel.isLoaded(player.uniqueId)) {
            player.sendMessage(messages.get("general.database-error"))
            return
        }
        val holder = FamilyHeartHolder(MenuType.FAMILY)
        val inventory = inv(holder, y.getString("$titleSection.title", defaultTitle) ?: defaultTitle, 54)
        val relationships = rel.relationships(player.uniqueId).let { all ->
            if (filter != null) all.filter { it.type == filter } else all
        }
        relationships.take(45).forEachIndexed { index, relationship ->
            val otherUuid = relationship.getOther(player.uniqueId)
            val name = Bukkit.getOfflinePlayer(otherUuid).name ?: otherUuid.toString()
            inventory.setItem(index, item("PLAYER_HEAD", "§6$name", listOf("§7${relationship.type}", "§7${relationship.relationshipId}")))
        }
        player.openInventory(inventory)
    }

    fun openRequests(player: Player) {
        req.pending(player.uniqueId).thenAccept { requests ->
            Bukkit.getScheduler().runTask(p, Runnable {
                val holder = FamilyHeartHolder(MenuType.REQUESTS)
                val inventory = inv(holder, y.getString("requests.title", "申請") ?: "申請", 54)
                requests.take(45).forEachIndexed { index, request ->
                    val title = "§e#${request.id} §f${request.type}"
                    val lore = listOf("§a左: 承認", "§c右: 拒否")
                    inventory.setItem(index, item("PAPER", title, lore))
                }
                player.openInventory(inventory)
            })
        }.exceptionally { ex ->
            p.logger.warning("[FamilyHeart] Request list load failed for ${player.uniqueId}: ${ex.cause?.message ?: ex.message}")
            Bukkit.getScheduler().runTask(p, Runnable {
                player.sendMessage(messages.get("general.database-error"))
            })
            null
        }
    }

    private fun openMarriageRole(player: Player, requestId: Long) {
        val holder = FamilyHeartHolder(MenuType.MARRIAGE_ROLE, requestId = requestId)
        val inventory = inv(holder, "結婚申請の承認", 27)
        inventory.setItem(11, item("GOLD_INGOT", "§6夫として承認", listOf("§7この申請を夫役で承認します")))
        inventory.setItem(15, item("GOLD_INGOT", "§d妻として承認", listOf("§7この申請を妻役で承認します")))
        player.openInventory(inventory)
    }

    fun openSkinship(player: Player) {
        val holder = FamilyHeartHolder(MenuType.SKINSHIP)
        val inventory = inv(holder, y.getString("skinship.title", "スキンシップ") ?: "スキンシップ", 27)
        inventory.setItem(11, item("PINK_DYE", "§dHug", listOf("§7/fh hug [MCID]")))
        inventory.setItem(13, item("RED_DYE", "§cKiss", listOf("§7/fh kiss [MCID]")))
        inventory.setItem(15, item("APPLE", "§6Feed", listOf("§7/fh feed [MCID]")))
        player.openInventory(inventory)
    }

    fun openSettings(player: Player) {
        val holder = FamilyHeartHolder(MenuType.SETTINGS)
        val inventory = inv(holder, y.getString("settings.title", "設定") ?: "設定", 27)
        inventory.setItem(
            13,
            item(
                "COMPARATOR",
                "§7設定情報",
                listOf(
                    "§7距離: ${p.config.getDouble("skinship.target-range", 3.0)}",
                    "§7バフ: ${p.config.getBoolean("features.family-buffs", true)}"
                )
            )
        )
        player.openInventory(inventory)
    }

    /**
     * SKINSHIP種別のRequestを承認する一連の処理。GUIのRequests画面クリックと
     * /fh accept コマンドの両方から呼ばれる共通処理として切り出した。
     * バグ修正: 以前はこの処理がGUIのクリックハンドラ内にしか実装されておらず、
     * RequestService.decide()はSKINSHIP種別を常に拒否する設計であるため、
     * /fh accept (コマンド)でSKINSHIP申請を承認しようとすると常にdatabase-errorに
     * なっていた(GUIからしか承認できなかった)。承認完了後にonDoneが呼ばれる。
     */
    fun acceptSkinship(player: Player, request: RelationshipRequest, onDone: () -> Unit) {
        val actionName = request.metadata ?: run {
            player.sendMessage(messages.get("general.invalid-input"))
            return
        }
        req.claimAcceptance(player.uniqueId, request.id, player.name).thenAccept { claimed ->
            Bukkit.getScheduler().runTask(p, Runnable {
                val actor = Bukkit.getPlayer(claimed.requester)
                val target = Bukkit.getPlayer(claimed.target)
                if (actor == null || target == null) {
                    req.releaseProcessing(player.uniqueId, claimed.id)
                    player.sendMessage(messages.get("general.offline"))
                    return@Runnable
                }
                if (actor.world.uid != target.world.uid ||
                    actor.location.distanceSquared(target.location) > settingsRange()) {
                    req.releaseProcessing(player.uniqueId, claimed.id)
                    player.sendMessage(messages.get("general.distance"))
                    return@Runnable
                }
                actions.approvedPersistent(actor, target, actionName, claimed.id).thenAccept { result ->
                    if (!result.success) {
                        req.releaseProcessing(player.uniqueId, claimed.id)
                        val key = if (result.reason == "self") "relationship.self" else "general.distance"
                        Bukkit.getScheduler().runTask(p, Runnable {
                            actor.sendMessage(messages.get(key))
                            target.sendMessage(messages.get(key))
                        })
                        return@thenAccept
                    }
                    req.acceptSkinshipAfterAction(player.uniqueId, claimed.id, player.name).thenAccept {
                        Bukkit.getScheduler().runTask(p, Runnable { onDone() })
                    }.exceptionally { ex ->
                        p.logger.severe("[FamilyHeart] Failed to finalize SKINSHIP request ${claimed.id}: ${ex.message}")
                        null
                    }
                }.exceptionally { ex ->
                    p.logger.severe("[FamilyHeart] Failed to execute SKINSHIP request ${claimed.id}: ${ex.message}")
                    Bukkit.getScheduler().runTask(p, Runnable {
                        player.sendMessage(messages.get("general.database-error"))
                    })
                    null
                }
            })
        }.exceptionally { ex ->
            p.logger.warning("[FamilyHeart] Failed to claim SKINSHIP request ${request.id}: ${ex.message}")
            Bukkit.getScheduler().runTask(p, Runnable {
                player.sendMessage(messages.get("request.not-found"))
            })
            null
        }
    }

    private fun settingsRange(): Double = p.config.getDouble("skinship.target-range", 3.0).coerceAtLeast(0.0).let { it * it }

    @EventHandler
    fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot < 0) return
        val holder = event.inventory.holder as? FamilyHeartHolder ?: return

        when (holder.menu) {
            MenuType.MAIN -> {
                event.isCancelled = true
                // バグ修正(監査): コマンド経路(FamilyHeartCommand)は "family"/"requests" 等の
                // サブコマンドごとに familyheart.family / familyheart.requests をチェックしているが、
                // GUIのメインメニューからは同じ画面をパーミッションチェック無しで開けてしまい、
                // 管理者が特定プレイヤーからこれらの権限だけ剥奪してもGUI経由では制限できなかった。
                // コマンド側と同じ権限ノードをここでも確認する。スキンシップ自体を開く専用の
                // 権限ノードはplugin.yml上に存在しない(hug/kiss/feed個別ノードのみ)ため、
                // メニューを開くこと自体は familyheart.use のみで許可し、個々の実行操作は
                // SKINSHIPメニューのクリック時に別途チェックする。
                when (holder.slotToKey[event.rawSlot]) {
                    "family" -> if (player.hasPermission("familyheart.family")) openFamily(player, null, "family", "家族") else player.sendMessage(messages.get("general.no-permission"))
                    "spouse" -> if (player.hasPermission("familyheart.family")) openFamily(player, RelationshipType.SPOUSE, "spouse", "配偶者") else player.sendMessage(messages.get("general.no-permission"))
                    "parent-child" -> if (player.hasPermission("familyheart.family")) openFamily(player, RelationshipType.PARENT_CHILD, "parent-child", "親子関係") else player.sendMessage(messages.get("general.no-permission"))
                    "relationship" -> if (player.hasPermission("familyheart.family")) openFamily(player, null, "relationship", "Relationship情報") else player.sendMessage(messages.get("general.no-permission"))
                    "skinship" -> openSkinship(player)
                    "requests" -> if (player.hasPermission("familyheart.requests")) openRequests(player) else player.sendMessage(messages.get("general.no-permission"))
                    "settings" -> openSettings(player)
                }
            }

            MenuType.SKINSHIP -> {
                event.isCancelled = true
                val action = when (event.rawSlot) {
                    11 -> "hug"
                    13 -> "kiss"
                    15 -> "feed"
                    else -> return
                }
                // バグ修正(監査): /fh hug|kiss|feed コマンドは familyheart.action.<action> を
                // 確認しているが、このGUIクリック経路には同じチェックが無く、当該権限を
                // 剥奪されたプレイヤーでもGUIからは実行できてしまっていた。
                if (!player.hasPermission("familyheart.action.$action")) {
                    player.sendMessage(messages.get("general.no-permission"))
                    return
                }
                val target = Bukkit.getOnlinePlayers()
                    .filter { it != player && it.world.uid == player.world.uid }
                    .minByOrNull { it.location.distanceSquared(player.location) }
                    ?: return
                val result = actions.execute(player, target, action) { id ->
                    player.sendMessage(messages.get("request.created", mapOf("request_id" to id.toString())))
                }
                if (result.success) {
                    player.sendMessage(messages.get("action.executed", mapOf("action" to action)))
                } else if (result.reason == "distance") {
                    player.sendMessage(messages.get("general.distance"))
                }
            }

            MenuType.REQUESTS -> {
                event.isCancelled = true
                val display = event.currentItem?.itemMeta?.displayName() ?: return
                val displayName = PlainTextComponentSerializer.plainText().serialize(display)
                val id = Regex("#(\\d+)").find(displayName)?.groupValues?.get(1)?.toLongOrNull() ?: return
                val accept = event.isLeftClick

                // バグ修正: 以前は req.get(id).join() をメインスレッド(インベントリクリックの
                // イベントハンドラ)から同期的に呼んでおり、クリックのたびにSQLite問い合わせで
                // メインスレッドがブロックされていた。get()もdecide()も非同期に連結し、
                // 画面更新などUI操作だけ runTask でメインスレッドへ戻す。
                req.get(id).thenAccept { request ->
                    if (request == null) return@thenAccept
                    if (request.type == RequestType.MARRY && accept) {
                        Bukkit.getScheduler().runTask(p, Runnable { openMarriageRole(player, request.id) })
                        return@thenAccept
                    }
                    if (accept && request.type == RequestType.SKINSHIP && request.metadata != null) {
                        acceptSkinship(player, request) { openRequests(player) }
                    } else {
                        req.decide(player.uniqueId, id, accept, null, player.name).thenAccept {
                            Bukkit.getScheduler().runTask(p, Runnable { openRequests(player) })
                        }.exceptionally { ex ->
                            val cause = ex.cause ?: ex
                            Bukkit.getScheduler().runTask(p, Runnable {
                                if (cause is nekouidaga.net.familyheartplugin.service.RelationshipException) {
                                    player.sendMessage(messages.get(cause.error.messageKey()))
                                } else {
                                    player.sendMessage(messages.get("general.database-error"))
                                }
                            })
                            null
                        }
                    }
                }
            }

            MenuType.MARRIAGE_ROLE -> {
                event.isCancelled = true
                val requestId = holder.requestId ?: return
                val role = when (event.rawSlot) {
                    11 -> nekouidaga.net.familyheartplugin.model.SpouseRole.HUSBAND
                    15 -> nekouidaga.net.familyheartplugin.model.SpouseRole.WIFE
                    else -> return
                }
                req.decide(player.uniqueId, requestId, true, role, player.name).thenAccept { result ->
                    Bukkit.getScheduler().runTask(p, Runnable {
                        player.closeInventory()
                        player.sendMessage(messages.get("request.accepted"))
                        Bukkit.getPlayer(result.requester)?.let { requester ->
                            requester.sendMessage(messages.get("request.accepted"))
                        }
                    })
                }.exceptionally { ex ->
                    Bukkit.getScheduler().runTask(p, Runnable {
                        val cause = ex.cause ?: ex
                        // バグ修正(監査): RelationshipException(ALREADY_HAS_SPOUSE等)は文字列switchの
                        // どのcaseにも一致せず常にdatabase-errorへ丸められ、messages.ymlに定義済みの
                        // relationship.* 専用メッセージが到達不能だった。型で先に判定する。
                        if (cause is nekouidaga.net.familyheartplugin.service.RelationshipException) {
                            player.sendMessage(messages.get(cause.error.messageKey()))
                            return@Runnable
                        }
                        when (cause.message) {
                            // バグ修正: 以前はコストを常に空文字で表示しており、金額が表示されなかった。
                            "economy.insufficient" -> player.sendMessage(messages.get("economy.insufficient-funds", mapOf("cost" to req.cost(RequestType.MARRY).toString())))
                            "economy.charge-failed" -> player.sendMessage(messages.get("economy.unavailable"))
                            else -> player.sendMessage(messages.get("general.database-error"))
                        }
                    })
                    null
                }
            }

            MenuType.FAMILY, MenuType.SETTINGS -> {
                // 表示専用メニュー。クリックによる持ち出し等を防ぐためキャンセルのみ行う。
                event.isCancelled = true
            }
        }
    }
}
