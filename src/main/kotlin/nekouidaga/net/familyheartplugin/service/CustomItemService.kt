package nekouidaga.net.familyheartplugin.service

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import nekouidaga.net.familyheartplugin.config.CustomItemDefinition
import nekouidaga.net.familyheartplugin.config.Settings
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.plugin.java.JavaPlugin
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData

class CustomItemService(val plugin: JavaPlugin, private val settings: Settings) {
    private val itemIdKey = NamespacedKey(plugin, "custom_item_id")
    private val mcidKey = NamespacedKey(plugin, "custom_item_mcid")
    private val requestIdKey = NamespacedKey(plugin, "custom_item_request_id")

    fun definition(mcid: String): CustomItemDefinition? = settings.customItem(mcid)

    fun give(player: Player, mcid: String, requestId: Long): Boolean {
        check(Bukkit.isPrimaryThread()) { "CustomItemService.give must run on the main thread" }
        val definition = definition(mcid) ?: return false
        val item = buildItem(definition, mcid, requestId)
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { leftover ->
            player.world.dropItemNaturally(player.location, leftover)
        }
        definition.effects.forEach { effect ->
            player.addPotionEffect(PotionEffect(
                effect.type,
                effect.durationTicks,
                effect.amplifier,
                effect.ambient,
                effect.particles,
                effect.icon
            ))
        }
        return true
    }

    private fun buildItem(d: CustomItemDefinition, mcid: String, requestId: Long): ItemStack {
        val item = ItemStack.of(d.material, d.amount)
        // バグ修正(重大・コンパイル不可): 外側の d.name?.let{it} の it(String)と、
        // 内側の item.editMeta{it} の it(ItemMeta)が衝突し、内側のitが外側をシャドーイングしていた。
        // legacy(it)がString版ではなくItemMeta版のitを受け取ろうとして型が一致せずコンパイルできなかった。
        d.name?.let { itemName -> item.editMeta { meta -> meta.displayName(legacy(itemName)) } }
        if (d.lore.isNotEmpty()) item.editMeta { it.lore(d.lore.map(::legacy)) }
        d.customModelData?.let { value ->
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(value.toFloat()).build())
        }
        if (d.enchantments.isNotEmpty()) item.editMeta { meta ->
            d.enchantments.forEach { (enchant, level) -> meta.addEnchant(enchant, level, true) }
        }
        item.editPersistentDataContainer { pdc ->
            pdc.set(itemIdKey, PersistentDataType.STRING, settings.customItemCommand())
            pdc.set(mcidKey, PersistentDataType.STRING, mcid)
            pdc.set(requestIdKey, PersistentDataType.LONG, requestId)
        }
        return item
    }

    private fun legacy(value: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(value).decoration(TextDecoration.ITALIC, false)
}
