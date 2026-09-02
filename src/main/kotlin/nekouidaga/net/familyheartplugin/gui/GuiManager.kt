package nekouidaga.net.familyheartplugin.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import nekouidaga.net.familyheartplugin.action.ActionService
import nekouidaga.net.familyheartplugin.message.Messages
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import nekouidaga.net.familyheartplugin.model.RequestType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration

class GuiManager(
    private val p: JavaPlugin,
    private val rel: RelationshipService,
    private val req: RequestService,
    private val actions: ActionService,
    private val messages: Messages
) : Listener {
    private val y: YamlConfiguration
        get() = YamlConfiguration.loadConfiguration(p.dataFolder.resolve("gui.yml"))

    private fun text(value: String): Component = Component.text(value).decoration(TextDecoration.ITALIC, false)

    private fun inv(title: String, size: Int) = Bukkit.createInventory(null, size, text(title))

    private fun item(materialName: String, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(Material.matchMaterial(materialName) ?: Material.STONE)
        val meta = item.itemMeta ?: return item
        meta.displayName(text(name))
        meta.lore(lore.map(::text))
        item.itemMeta = meta
        return item
    }

    fun openMain(player: Player) {
        val inventory = inv(y.getString("main.title", "FamilyHeart") ?: "FamilyHeart", y.getInt("main.size", 27))
        listOf("family", "spouse", "parent-child", "skinship", "requests", "relationship", "settings").forEach { key ->
            val section = y.getConfigurationSection("main.items.$key") ?: return@forEach
            inventory.setItem(
                section.getInt("slot"),
                item(
                    section.getString("material", "PAPER") ?: "PAPER",
                    section.getString("name", key) ?: key,
                    section.getStringList("lore")
                )
            )
        }
        player.openInventory(inventory)
    }

    fun openFamily(player: Player) {
        val inventory = inv(y.getString("family.title", "家族") ?: "家族", 54)
        rel.relationships(player.uniqueId).take(45).forEachIndexed { index, relationship ->
            val otherUuid = relationship.getOther(player.uniqueId)
            val name = Bukkit.getOfflinePlayer(otherUuid).name ?: otherUuid.toString()
            inventory.setItem(index, item("PLAYER_HEAD", "§6$name", listOf("§7${relationship.type}", "§7${relationship.relationshipId}")))
        }
        player.openInventory(inventory)
    }

    fun openRequests(player: Player) {
        req.pending(player.uniqueId).thenAccept { requests ->
            Bukkit.getScheduler().runTask(p, Runnable {
                val inventory = inv(y.getString("requests.title", "申請") ?: "申請", 54)
                requests.take(45).forEachIndexed { index, request ->
                    inventory.setItem(index, item("PAPER", "§e#${request.id} §f${request.type}", listOf("§a左: 承認", "§c右: 拒否")))
                }
                player.openInventory(inventory)
            })
        }
    }

    fun openSkinship(player: Player) {
        val inventory = inv(y.getString("skinship.title", "スキンシップ") ?: "スキンシップ", 27)
        inventory.setItem(11, item("PINK_DYE", "§dHug", listOf("§7/fh hug [MCID]")))
        inventory.setItem(13, item("RED_DYE", "§cKiss", listOf("§7/fh kiss [MCID]")))
        inventory.setItem(15, item("APPLE", "§6Feed", listOf("§7/fh feed [MCID]")))
        player.openInventory(inventory)
    }

    fun openSettings(player: Player) {
        val inventory = inv(y.getString("settings.title", "設定") ?: "設定", 27)
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
        val title = PlainTextComponentSerializer.plainText().serialize(event.view.title())

        when (title) {
            "FamilyHeart" -> {
                event.isCancelled = true
                when (event.rawSlot) {
                    10, 11, 12, 15 -> openFamily(player)
                    13 -> openSkinship(player)
                    14 -> openRequests(player)
                    16 -> openSettings(player)
                }
            }

            "スキンシップ" -> {
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

            "申請" -> {
                event.isCancelled = true
                val display = event.currentItem?.itemMeta?.displayName() ?: return
                val displayName = PlainTextComponentSerializer.plainText().serialize(display)
                val id = Regex("#(\\d+)").find(displayName)?.groupValues?.get(1)?.toLongOrNull() ?: return

                val request = req.get(id).join() ?: return
                if (request.type == RequestType.MARRY && event.isLeftClick) {
                    player.sendMessage(messages.get("request.marriage-role-required"))
                    return
                }

                req.decide(player.uniqueId, id, event.isLeftClick).thenAccept { decided ->
                    if (event.isLeftClick && decided.type == RequestType.SKINSHIP && decided.metadata != null) {
                        val actor = Bukkit.getPlayer(decided.requester)
                        val target = Bukkit.getPlayer(decided.target)
                        if (actor != null && target != null) actions.approved(actor, target, decided.metadata!!)
                    }
                    Bukkit.getScheduler().runTask(p, Runnable { openRequests(player) })
                }
            }
        }
    }
}
