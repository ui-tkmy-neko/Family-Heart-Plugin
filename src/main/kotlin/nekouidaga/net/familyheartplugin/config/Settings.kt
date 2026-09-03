package nekouidaga.net.familyheartplugin.config

import nekouidaga.net.familyheartplugin.model.BuffDefinition
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import org.bukkit.potion.PotionEffectType
import org.bukkit.enchantments.Enchantment
import org.bukkit.plugin.java.JavaPlugin

data class CustomItemEffectDefinition(
    val type: PotionEffectType,
    val durationTicks: Int,
    val amplifier: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean
)

data class CustomItemDefinition(
    val mcid: String,
    val material: Material,
    val amount: Int,
    val name: String?,
    val lore: List<String>,
    val customModelData: Int?,
    val enchantments: Map<Enchantment, Int>,
    val effects: List<CustomItemEffectDefinition>
)

class Settings(private val p: JavaPlugin) {
    @Volatile private var buffCache: List<BuffDefinition> = emptyList()
    @Volatile private var customItemsCache: Map<String, CustomItemDefinition> = emptyMap()

    init { reload() }

    fun reload() {
        p.reloadConfig()
        buffCache = loadBuffs()
        customItemsCache = loadCustomItems()
    }

    fun range() = p.config.getDouble("skinship.target-range", 3.0)
    fun econEnabled() = p.config.getBoolean("economy.enabled", false)
    fun cost(type: String) = p.config.getDouble("economy.${type}-cost", 0.0)
    fun actionCooldown(a: String) = p.config.getLong("actions.$a.cooldown-seconds", p.config.getLong("skinship.default-cooldown-seconds", 0))
    fun features(k: String) = p.config.getBoolean("features.$k", true)
    fun buffs(): List<BuffDefinition> = buffCache
    fun customItemCommand(): String {
        val configured = p.config.getString("custom-item.command", "hdb726yb")?.trim() ?: "hdb726yb"
        val reserved = setOf("family", "marry", "accept", "divorce", "child", "separation", "hug", "kiss", "requests", "gui", "admin")
        return if (configured.isNotEmpty() && configured.lowercase() !in reserved) configured else "hdb726yb"
    }
    fun customItem(mcid: String): CustomItemDefinition? = customItemsCache[mcid] ?: customItemsCache.entries.firstOrNull { it.key.equals(mcid, true) }?.value
    fun customItemMcids(): Set<String> = customItemsCache.keys

    private fun loadCustomItems(): Map<String, CustomItemDefinition> {
        val file = p.dataFolder.resolve("custom-items.yml")
        if (!file.exists()) p.saveResource("custom-items.yml", false)
        val c = YamlConfiguration.loadConfiguration(file)
        val section = c.getConfigurationSection("items") ?: return emptyMap()
        return section.getKeys(false).mapNotNull { mcid ->
            val x = section.getConfigurationSection(mcid) ?: return@mapNotNull null
            val material = Material.matchMaterial(x.getString("material", "PAPER") ?: "PAPER") ?: return@mapNotNull null
            val amount = x.getInt("amount", 1).coerceIn(1, material.maxStackSize)
            val name = x.getString("name")
            val lore = x.getStringList("lore")
            val customModelData = if (x.contains("custom-model-data")) x.getInt("custom-model-data") else null
            val enchantments = buildMap {
                val e = x.getConfigurationSection("enchantments") ?: return@buildMap
                e.getKeys(false).forEach { key ->
                    val enchant = Enchantment.getByName(key.uppercase()) ?: return@forEach
                    put(enchant, e.getInt(key, 1).coerceAtLeast(1))
                }
            }
            val effects = buildList {
                val e = x.getConfigurationSection("effects") ?: return@buildList
                e.getKeys(false).forEach { key ->
                    val type = PotionEffectType.getByName(key.uppercase()) ?: return@forEach
                    val effect = e.getConfigurationSection(key) ?: return@forEach
                    val durationTicks = when {
                        effect.contains("duration-ticks") -> effect.getInt("duration-ticks", 200)
                        else -> (effect.getLong("duration-seconds", 10L).coerceAtLeast(1L) * 20L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }.coerceAtLeast(1)
                    add(CustomItemEffectDefinition(
                        type,
                        durationTicks,
                        effect.getInt("amplifier", 0).coerceAtLeast(0),
                        effect.getBoolean("ambient", false),
                        effect.getBoolean("particles", true),
                        effect.getBoolean("icon", true)
                    ))
                }
            }
            mcid to CustomItemDefinition(mcid, material, amount, name, lore, customModelData, enchantments, effects)
        }.toMap()
    }

    private fun loadBuffs(): List<BuffDefinition> {
        val file = p.dataFolder.resolve("buffs.yml")
        if (!file.exists()) p.saveResource("buffs.yml", false)
        val c = YamlConfiguration.loadConfiguration(file)
        val section = c.getConfigurationSection("family-buffs") ?: return emptyList()
        return section.getKeys(false).mapNotNull { key: String ->
            val x = section.getConfigurationSection(key) ?: return@mapNotNull null
            BuffDefinition(
                key,
                x.getBoolean("enabled", false),
                x.getString("condition", "") ?: "",
                x.getDouble("required", 0.0),
                x.getInt("amplifier", 0),
                x.getInt("duration", 120),
                x.getString("effect", "SPEED") ?: "SPEED",
                x.getDouble("multiplier", 1.0)
            )
        }
    }
}
