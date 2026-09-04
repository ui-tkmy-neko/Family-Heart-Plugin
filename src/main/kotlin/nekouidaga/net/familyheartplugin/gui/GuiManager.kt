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
import nekouidaga.net.familyheartplugin.model.SpouseRole
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
import java.util.concurrent.CompletableFuture

// バグ修正(4): 以前はクリック処理がevent.view.title()の文字列(「FamilyHeart」「申請」等)を
// 直接パターンマッチしており、gui.ymlでタイトルをカスタマイズすると
// クリック判定が一致しなくなりメニューが機能しなくなっていた(設定可能という設計と矛盾)。
// メニュー種別をInventoryHolderとして持たせ、タイトル文字列に依存しないようにする。
private enum class MenuType { MAIN, FAMILY, SKINSHIP, REQUESTS, MARRIAGE_ROLE, SETTINGS, INFO, CHILDREN }

private class FamilyHeartHolder(
    val menu: MenuType,
    // MAINメニューでは、実際にgui.ymlへ設定されたslot番号を動的に読み取って
    // slot -> item-key の対応を保持する(バグ修正5: 以前はrawSlotの数値をハードコードしており、
    // 「家族(10)/配偶者(11)/親子関係(12)/Relationship(15)」が全部openFamily()に固定されていて
    // 実質同じ画面しか開けなかった)。
    val slotToKey: Map<Int, String> = emptyMap(),
    val requestId: Long? = null,
    val targetUuid: java.util.UUID? = null,
    val page: Int = 0
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

    private fun decorate(inventory: Inventory, frameMaterial: String = "CYAN_STAINED_GLASS_PANE") {
        val filler = item(frameMaterial, "§r", emptyList())
        val size = inventory.size
        val columns = 9
        val rows = size / columns
        for (slot in 0 until size) {
            val row = slot / columns
            val col = slot % columns
            if (row == 0 || row == rows - 1 || col == 0 || col == columns - 1) {
                inventory.setItem(slot, filler.clone())
            }
        }
    }

    private fun backItem(label: String = y.getString("buttons.back", "§c戻る") ?: "§c戻る"): ItemStack =
        item("ARROW", label, listOf("§7前の画面へ戻る"))

    private fun setBack(inventory: Inventory, slot: Int = 31, label: String? = null) {
        inventory.setItem(slot, backItem(label ?: (y.getString("main.buttons.back", "§c戻る") ?: "§c戻る")))
    }

    fun openMain(player: Player) {
        val slotToKey = mutableMapOf<Int, String>()
        val holder = FamilyHeartHolder(MenuType.MAIN, slotToKey)
        val inventory = inv(holder, y.getString("main.title", "FamilyHeart") ?: "FamilyHeart", y.getInt("main.size", 27))
        decorate(inventory, y.getString("decoration.main-frame", "CYAN_STAINED_GLASS_PANE") ?: "CYAN_STAINED_GLASS_PANE")
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
        val inventory = inv(holder, y.getString("$titleSection.title", defaultTitle) ?: defaultTitle, 36)
        decorate(inventory, y.getString("decoration.family-frame", "BLUE_STAINED_GLASS_PANE") ?: "BLUE_STAINED_GLASS_PANE")
        val relationships = rel.relationships(player.uniqueId).let { all ->
            if (filter != null) all.filter { it.type == filter } else all
        }
        val contentSlots = listOf(10,11,12,13,14,15,16,19,20,21,22,23,24,25)
        relationships.take(contentSlots.size).forEachIndexed { index, relationship ->
            val otherUuid = relationship.getOther(player.uniqueId)
            val name = Bukkit.getPlayer(otherUuid)?.name ?: Bukkit.getOfflinePlayer(otherUuid).name ?: otherUuid.toString()
            val role = relationship.roleOf(otherUuid)
            inventory.setItem(contentSlots[index], item("PLAYER_HEAD", "§6$name", listOf("§7${relationship.type}", "§7役割: $role", "§eクリックで詳細")))
        }
        setBack(inventory)
        player.openInventory(inventory)
    }

    fun openInfo(viewer: Player, targetUuid: java.util.UUID) {
        if (!rel.isLoaded(targetUuid)) {
            viewer.sendMessage(messages.get("general.database-error"))
            return
        }
        val holder = FamilyHeartHolder(MenuType.INFO, targetUuid = targetUuid)
        val inventory = inv(holder, y.getString("info.title", "FamilyHeart情報") ?: "FamilyHeart情報", 27)
        decorate(inventory, y.getString("decoration.info-frame", "LIGHT_BLUE_STAINED_GLASS_PANE") ?: "LIGHT_BLUE_STAINED_GLASS_PANE")
        val relationships = rel.relationships(targetUuid)
        val spouse = relationships.firstOrNull { it.type == RelationshipType.SPOUSE }
        val children = rel.children(targetUuid)
        val spouseMcidFuture: CompletableFuture<String?> = spouse?.getOther(targetUuid)?.let(req::mcid) ?: CompletableFuture.completedFuture<String?>(null)
        req.mcid(targetUuid).thenCombine(spouseMcidFuture) { selfMcid, spouseMcid ->
            Bukkit.getScheduler().runTask(p, Runnable {
                val selfLabel = selfMcid ?: (Bukkit.getPlayer(targetUuid)?.name ?: targetUuid.toString())
                inventory.setItem(4, item("PLAYER_HEAD", "§b$selfLabel", listOf("§7UUID: $targetUuid", "§8クリック項目を選択")))
                if (spouse != null && spouseMcid != null) {
                    val spouseRole = spouse.roleOf(spouse.getOther(targetUuid))
                    val days = java.time.Duration.between(spouse.createdAt, java.time.Instant.now()).toDays().coerceAtLeast(0)
                    inventory.setItem(11, item("GOLDEN_HELMET", "§6配偶者: $spouseMcid", listOf("§7役割: $spouseRole", "§7結婚歴: ${days}日", "§eクリックで配偶者のInfo")))
                } else {
                    inventory.setItem(11, item("BARRIER", "§7配偶者: なし", listOf("§8現在配偶者はいません")))
                }
                val threshold = p.config.getInt("info.child-list-threshold", 5).coerceAtLeast(1)
                if (children.size >= threshold) {
                    inventory.setItem(15, item("BOOK", "§a子供一覧: ${children.size}人", listOf("§7クリックして子供一覧を開く", "§e/fh info ${selfLabel} child")))
                } else if (children.isEmpty()) {
                    inventory.setItem(15, item("BARRIER", "§7子供: 0人", listOf("§8子供はいません")))
                } else {
                    val shown = children.take(5)
                    val lines = shown.mapIndexed { i, child ->
                        val label = Bukkit.getPlayer(child)?.name ?: child.toString()
                        "§7${i + 1}. $label"
                    }
                    inventory.setItem(15, item("PLAYER_HEAD", "§a子供: ${children.size}人", lines + "§eクリックで子供一覧"))
                }
                inventory.setItem(13, item("CLOCK", "§e結婚歴", listOf(spouse?.let { "§7${java.time.Duration.between(it.createdAt, java.time.Instant.now()).toDays().coerceAtLeast(0)}日" } ?: "§7未婚")))
                inventory.setItem(22, item("BARRIER", "§c戻る", listOf("§7メインへ")))
                viewer.openInventory(inventory)
            })
        }
    }

    fun openChildrenList(viewer: Player, targetUuid: java.util.UUID, page: Int = 0) {
        if (!rel.isLoaded(targetUuid)) { viewer.sendMessage(messages.get("general.database-error")); return }
        val children = rel.children(targetUuid)
        val pageSize = p.config.getInt("info.child-page-size", 7).coerceIn(1, 7)
        val maxPage = ((children.size - 1).coerceAtLeast(0)) / pageSize
        val safePage = page.coerceIn(0, maxPage)
        val holder = FamilyHeartHolder(MenuType.CHILDREN, targetUuid = targetUuid, page = safePage)
        val inventory = inv(holder, y.getString("info.children-title", "子供一覧") ?: "子供一覧", 27)
        decorate(inventory, y.getString("decoration.children-frame", "GREEN_STAINED_GLASS_PANE") ?: "GREEN_STAINED_GLASS_PANE")
        val slice = children.drop(safePage * pageSize).take(pageSize)
        val mcidFutures = slice.map { req.mcid(it) }
        CompletableFuture.allOf(*mcidFutures.toTypedArray()).thenRun {
            Bukkit.getScheduler().runTask(p, Runnable {
                slice.forEachIndexed { index, childUuid ->
                    val label = mcidFutures[index].getNow(null) ?: Bukkit.getPlayer(childUuid)?.name ?: childUuid.toString()
                    inventory.setItem(10 + index, item("PLAYER_HEAD", "§a$label", listOf("§eクリックでInfo", "§7/fh info $label")))
                }
                if (safePage > 0) inventory.setItem(18, item("ARROW", "§e前のページ", emptyList()))
                if (safePage < maxPage) inventory.setItem(26, item("ARROW", "§e次のページ", emptyList()))
                inventory.setItem(22, item("BARRIER", "§c戻る", emptyList()))
                viewer.openInventory(inventory)
            })
        }
    }

    private fun openPersonInfoFromRelationship(viewer: Player, otherUuid: java.util.UUID) = openInfo(viewer, otherUuid)

    fun openRequests(player: Player) {
        req.pending(player.uniqueId).thenAccept { requests ->
            Bukkit.getScheduler().runTask(p, Runnable {
                val holder = FamilyHeartHolder(MenuType.REQUESTS)
                val inventory = inv(holder, y.getString("requests.title", "申請") ?: "申請", 27)
                decorate(inventory, y.getString("decoration.requests-frame", "YELLOW_STAINED_GLASS_PANE") ?: "YELLOW_STAINED_GLASS_PANE")
                val requestSlots = listOf(10,11,12,13,14,15,16,19,20)
                requests.take(requestSlots.size).forEachIndexed { index, request ->
                    val title = "§e#${request.id} §f${request.type}"
                    val content = when (request.type) {
                        RequestType.MARRY -> "§7希望役割: §f${request.metadata ?: "?"}"
                        RequestType.CHILD_PARENT -> "§7申請役割: §f${request.metadata ?: "?"}"
                        RequestType.SKINSHIP -> "§7内容: §f${request.metadata ?: "?"}"
                        RequestType.DIVORCE -> "§7内容: §f離婚"
                        RequestType.SEPARATION -> "§7内容: §f親子関係解除"
                    }
                    val lore = listOf(content, "§a左: 承認", "§c右: 拒否")
                    inventory.setItem(requestSlots[index], item("PAPER", title, lore))
                }
                inventory.setItem(22, backItem())
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
        decorate(inventory, "MAGENTA_STAINED_GLASS_PANE")
        inventory.setItem(11, item("GOLD_INGOT", "§6夫として承認", listOf("§7この申請を夫役で承認します")))
        inventory.setItem(15, item("GOLD_INGOT", "§d妻として承認", listOf("§7この申請を妻役で承認します")))
        inventory.setItem(22, backItem())
        player.openInventory(inventory)
    }

    fun openSkinship(player: Player) {
        val holder = FamilyHeartHolder(MenuType.SKINSHIP)
        val inventory = inv(holder, y.getString("skinship.title", "スキンシップ") ?: "スキンシップ", 27)
        decorate(inventory, y.getString("decoration.skinship-frame", "PINK_STAINED_GLASS_PANE") ?: "PINK_STAINED_GLASS_PANE")
        inventory.setItem(11, item("PINK_DYE", "§dHug", listOf("§7/fh hug [MCID]")))
        inventory.setItem(13, item("RED_DYE", "§cKiss", listOf("§7/fh kiss [MCID]")))
        inventory.setItem(15, item("APPLE", "§6Feed", listOf("§7/fh feed [MCID]")))
        inventory.setItem(22, backItem())
        player.openInventory(inventory)
    }

    fun openSettings(player: Player) {
        val holder = FamilyHeartHolder(MenuType.SETTINGS)
        val inventory = inv(holder, y.getString("settings.title", "設定") ?: "設定", 27)
        decorate(inventory, y.getString("decoration.settings-frame", "GRAY_STAINED_GLASS_PANE") ?: "GRAY_STAINED_GLASS_PANE")
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
        inventory.setItem(22, backItem())
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
                    "spouse" -> if (player.hasPermission("familyheart.family")) openInfo(player, player.uniqueId) else player.sendMessage(messages.get("general.no-permission"))
                    "parent-child" -> if (player.hasPermission("familyheart.family")) openFamily(player, RelationshipType.PARENT_CHILD, "parent-child", "親子関係") else player.sendMessage(messages.get("general.no-permission"))
                    "relationship" -> if (player.hasPermission("familyheart.family")) openFamily(player, null, "relationship", "Relationship情報") else player.sendMessage(messages.get("general.no-permission"))
                    "skinship" -> openSkinship(player)
                    "requests" -> if (player.hasPermission("familyheart.requests")) openRequests(player) else player.sendMessage(messages.get("general.no-permission"))
                    "settings" -> openSettings(player)
                }
            }

            MenuType.SKINSHIP -> {
                event.isCancelled = true
                if (event.rawSlot == 22) { openMain(player); return }
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
                    player.sendMessage(messages.request(RequestType.SKINSHIP, "sent", action, mapOf(
                        "request_id" to id.toString(),
                        "player" to player.name,
                        "target" to target.name
                    )))
                }
                if (result.success) {
                    player.sendMessage(messages.get("action.executed", mapOf("action" to action)))
                } else if (result.reason == "distance") {
                    player.sendMessage(messages.get("general.distance"))
                }
            }

            MenuType.REQUESTS -> {
                event.isCancelled = true
                if (event.rawSlot == 22) { openMain(player); return }
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
                        acceptSkinship(player, request) {
                        player.sendMessage(messages.request(request.type, "accepted", request.metadata, mapOf("request_id" to request.id.toString(), "player" to player.name, "target" to player.name)))
                        openRequests(player)
                    }
                    } else {
                        req.decide(player.uniqueId, id, accept, null, player.name).thenAccept { result ->
                            Bukkit.getScheduler().runTask(p, Runnable {
                                val phase = if (accept) "accepted" else "denied"
                                val notifyPhase = if (accept) "accepted-notify" else "deny-notify"
                                val targetName = player.name
                                val requesterName = Bukkit.getPlayer(result.requester)?.name ?: result.requester.toString()
                                player.sendMessage(messages.request(result.type, phase, result.metadata, mapOf("request_id" to result.id.toString(), "player" to requesterName, "target" to targetName)))
                                Bukkit.getPlayer(result.requester)?.sendMessage(messages.request(result.type, notifyPhase, result.metadata, mapOf("request_id" to result.id.toString(), "player" to targetName, "target" to targetName)))
                                openRequests(player)
                            })
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
                if (event.rawSlot == 22) { openRequests(player); return }
                val requestId = holder.requestId ?: return
                val role = when (event.rawSlot) {
                    11 -> nekouidaga.net.familyheartplugin.model.SpouseRole.HUSBAND
                    15 -> nekouidaga.net.familyheartplugin.model.SpouseRole.WIFE
                    else -> return
                }
                req.decide(player.uniqueId, requestId, true, role, player.name).thenAccept { result ->
                    Bukkit.getScheduler().runTask(p, Runnable {
                        player.closeInventory()
                        val targetName = player.name
                        val requesterName = Bukkit.getPlayer(result.requester)?.name ?: result.requester.toString()
                        player.sendMessage(messages.request(RequestType.MARRY, "accepted", role.name, mapOf("request_id" to requestId.toString(), "player" to requesterName, "target" to targetName)))
                        Bukkit.getPlayer(result.requester)?.sendMessage(messages.request(RequestType.MARRY, "accepted-notify", role.name, mapOf("request_id" to requestId.toString(), "player" to targetName, "target" to targetName)))
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

            MenuType.FAMILY -> {
                event.isCancelled = true
                if (event.rawSlot == 31) { openMain(player); return }
                val contentIndex = listOf(10,11,12,13,14,15,16,19,20,21,22,23,24,25).indexOf(event.rawSlot)
                if (contentIndex >= 0) {
                    val relationship = rel.relationships(player.uniqueId).getOrNull(contentIndex) ?: return
                    openPersonInfoFromRelationship(player, relationship.getOther(player.uniqueId))
                }
            }
            MenuType.INFO -> {
                event.isCancelled = true
                val targetUuid = holder.targetUuid ?: return
                when (event.rawSlot) {
                    11 -> {
                        val spouse = rel.spouse(targetUuid)
                        if (spouse != null) openInfo(player, spouse)
                    }
                    15 -> openChildrenList(player, targetUuid)
                    22 -> openMain(player)
                }
            }
            MenuType.CHILDREN -> {
                event.isCancelled = true
                val targetUuid = holder.targetUuid ?: return
                when {
                    event.rawSlot in 10..16 -> {
                        val pageSize = p.config.getInt("info.child-page-size", 7).coerceIn(1, 7)
                        val index = holder.page * pageSize + (event.rawSlot - 10)
                        rel.children(targetUuid).getOrNull(index)?.let { openInfo(player, it) }
                    }
                    event.rawSlot == 18 && holder.page > 0 -> openChildrenList(player, targetUuid, holder.page - 1)
                    event.rawSlot == 26 -> openChildrenList(player, targetUuid, holder.page + 1)
                    event.rawSlot == 22 -> openInfo(player, targetUuid)
                }
            }
            MenuType.SETTINGS -> {
                event.isCancelled = true
                if (event.rawSlot == 22) { openMain(player); return }
            }
        }
    }
}
