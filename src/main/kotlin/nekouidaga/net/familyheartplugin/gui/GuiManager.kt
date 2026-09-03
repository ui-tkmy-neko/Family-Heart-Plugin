package nekouidaga.net.familyheartplugin.gui

import nekouidaga.net.familyheartplugin.model.RequestProcessingGuard

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import nekouidaga.net.familyheartplugin.action.ActionService
import nekouidaga.net.familyheartplugin.message.Messages
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.model.RelationshipType
import nekouidaga.net.familyheartplugin.model.RequestType
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
private enum class MenuType { MAIN, FAMILY, SKINSHIP, REQUESTS, SETTINGS }

private class FamilyHeartHolder(
    val menu: MenuType,
    // MAINメニューでは、実際にgui.ymlへ設定されたslot番号を動的に読み取って
    // slot -> item-key の対応を保持する(バグ修正5: 以前はrawSlotの数値をハードコードしており、
    // 「家族(10)/配偶者(11)/親子関係(12)/Relationship(15)」が全部openFamily()に固定されていて
    // 実質同じ画面しか開けなかった)。
    val slotToKey: Map<Int, String> = emptyMap()
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

    private fun text(value: String): Component = Component.text(value).decoration(TextDecoration.ITALIC, false)

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
                    val title = if (request.type == RequestType.CUSTOM_ITEM) "§e#${request.id} §b限定アイテム" else "§e#${request.id} §f${request.type}"
                    val lore = if (request.type == RequestType.CUSTOM_ITEM) {
                        listOf("§7対象MCID: §f${request.metadata ?: "?"}", "§a左: 承認", "§c右: 拒否")
                    } else listOf("§a左: 承認", "§c右: 拒否")
                    inventory.setItem(index, item("PAPER", title, lore))
                }
                player.openInventory(inventory)
            })
        }
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

    @EventHandler
    fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.rawSlot < 0) return
        val holder = event.inventory.holder as? FamilyHeartHolder ?: return

        when (holder.menu) {
            MenuType.MAIN -> {
                event.isCancelled = true
                when (holder.slotToKey[event.rawSlot]) {
                    "family" -> openFamily(player, null, "family", "家族")
                    "spouse" -> openFamily(player, RelationshipType.SPOUSE, "spouse", "配偶者")
                    "parent-child" -> openFamily(player, RelationshipType.PARENT_CHILD, "parent-child", "親子関係")
                    "relationship" -> openFamily(player, null, "relationship", "Relationship情報")
                    "skinship" -> openSkinship(player)
                    "requests" -> openRequests(player)
                    "settings" -> openSettings(player)
                }
            }

            MenuType.SKINSHIP -> {
                event.isCancelled = true
                val target = Bukkit.getOnlinePlayers()
                    .filter { it != player && it.world.uid == player.world.uid }
                    .minByOrNull { it.location.distanceSquared(player.location) }
                    ?: return
                val action = when (event.rawSlot) {
                    11 -> "hug"
                    13 -> "kiss"
                    15 -> "feed"
                    else -> return
                }
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
                // イベントハンドラ)から同期的に呼んでおり、クリックのたびにMySQL問い合わせで
                // メインスレッドがブロックされていた。get()もdecide()も非同期に連結し、
                // 画面更新などUI操作だけ runTask でメインスレッドへ戻す。
                req.get(id).thenAccept { request ->
                    if (request == null) return@thenAccept
                    if (request.type == RequestType.MARRY && accept) {
                        Bukkit.getScheduler().runTask(p, Runnable {
                            player.sendMessage(messages.get("request.marriage-role-required"))
                        })
                        return@thenAccept
                    }

                    if (accept && request.type == RequestType.CUSTOM_ITEM) {
                        req.acceptCustomItem(player.uniqueId, request.id).thenAccept {
                            Bukkit.getScheduler().runTask(p, Runnable {
                                player.sendMessage(messages.get("custom-item.accepted"))
                                openRequests(player)
                            })
                        }.exceptionally { ex ->
                            Bukkit.getScheduler().runTask(p, Runnable {
                                val cause = ex.cause ?: ex
                                when (cause.message) {
                                    "custom-item-offline" -> player.sendMessage(messages.get("custom-item.offline", mapOf("mcid" to (request.metadata ?: ""))))
                                    "custom-item-not-configured" -> player.sendMessage(messages.get("custom-item.not-configured"))
                                    else -> player.sendMessage(messages.get("general.database-error"))
                                }
                            })
                            null
                        }
                        return@thenAccept
                    }

                    if (accept && request.type == RequestType.SKINSHIP && request.metadata != null) {
                        req.claimAcceptance(player.uniqueId, request.id).thenAccept claimDone@{ claimed ->
                            req.setProcessingGuard(player.uniqueId, claimed.id, RequestProcessingGuard.SKINSHIP_INTENT).thenAccept guardDone@{ guarded ->
                                if (!guarded) {
                                    Bukkit.getScheduler().runTask(p, Runnable { player.sendMessage(messages.get("general.database-error")) })
                                    return@guardDone
                                }
                                Bukkit.getScheduler().runTask(p, Runnable {
                                    val actor = Bukkit.getPlayer(claimed.requester)
                                    val target = Bukkit.getPlayer(claimed.target)
                                    if (actor == null || target == null) {
                                        req.releaseProcessing(player.uniqueId, claimed.id)
                                        player.sendMessage(messages.get("general.offline"))
                                        return@Runnable
                                    }
                                    val actionName = claimed.metadata!!
                                    actions.prepareRequestAction(claimed.id, claimed.requester, claimed.target, actionName).thenAccept { state ->
                                        if (state == nekouidaga.net.familyheartplugin.model.ActionExecutionState.EXECUTED) {
                                            req.setProcessingGuard(player.uniqueId, claimed.id, RequestProcessingGuard.SKINSHIP_EXECUTED).thenAccept {
                                                req.acceptSkinshipAfterAction(player.uniqueId, claimed.id).thenAccept {
                                                    Bukkit.getScheduler().runTask(p, Runnable { openRequests(player) })
                                                }
                                            }
                                            return@thenAccept
                                        }
                                        Bukkit.getScheduler().runTask(p, Runnable {
                                            val currentActor = Bukkit.getPlayer(claimed.requester)
                                            val currentTarget = Bukkit.getPlayer(claimed.target)
                                            if (currentActor == null || currentTarget == null) {
                                                req.releaseProcessing(player.uniqueId, claimed.id)
                                                player.sendMessage(messages.get("general.offline"))
                                                return@Runnable
                                            }
                                            actions.approvedPersistent(currentActor, currentTarget, actionName, claimed.id).thenAccept { result ->
                                                if (!result.success) {
                                                    req.releaseProcessing(player.uniqueId, claimed.id)
                                                    val key = if (result.reason == "self") "relationship.self" else "general.distance"
                                                    currentActor.sendMessage(messages.get(key))
                                                    currentTarget.sendMessage(messages.get(key))
                                                    return@thenAccept
                                                }
                                                req.setProcessingGuard(player.uniqueId, claimed.id, RequestProcessingGuard.SKINSHIP_EXECUTED).thenAccept executionDone@{ executedMarked ->
                                                    if (!executedMarked) {
                                                        p.logger.severe("[FamilyHeart] SKINSHIP action is durably recorded but request guard update failed for request ${claimed.id}; recovery will finalize without replay")
                                                        return@executionDone
                                                    }
                                                    req.acceptSkinshipAfterAction(player.uniqueId, claimed.id).thenAccept {
                                                        Bukkit.getScheduler().runTask(p, Runnable { openRequests(player) })
                                                    }.exceptionally { ex ->
                                                        p.logger.severe("[FamilyHeart] Failed to persist accepted skinship request ${claimed.id}: ${ex.message}")
                                                        null
                                                    }
                                                }
                                            }.exceptionally { ex ->
                                                p.logger.severe("[FamilyHeart] Failed to persist SKINSHIP action for request ${claimed.id}: ${ex.message}")
                                                null
                                            }
                                        })
                                    }.exceptionally { ex ->
                                        Bukkit.getScheduler().runTask(p, Runnable { player.sendMessage(messages.get("general.database-error")) })
                                        null
                                    }
                                })
                            }
                        }.exceptionally { ex ->
                            Bukkit.getScheduler().runTask(p, Runnable { player.sendMessage(messages.get("general.database-error")) })
                            null
                        }
                    } else {
                        req.decide(player.uniqueId, id, accept).thenAccept {
                            Bukkit.getScheduler().runTask(p, Runnable { openRequests(player) })
                        }.exceptionally { ex ->
                            Bukkit.getScheduler().runTask(p, Runnable { player.sendMessage(messages.get("general.database-error")) })
                            null
                        }
                    }
                }
            }

            MenuType.FAMILY, MenuType.SETTINGS -> {
                // 表示専用メニュー。クリックによる持ち出し等を防ぐためキャンセルのみ行う。
                event.isCancelled = true
            }
        }
    }
}
