package nekouidaga.net.familyheartplugin.config

import nekouidaga.net.familyheartplugin.model.BuffDefinition
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.potion.PotionEffectType
import org.bukkit.plugin.java.JavaPlugin

class Settings(private val p: JavaPlugin) {
    @Volatile private var buffCache: List<BuffDefinition> = emptyList()

    init { reload() }

    fun reload() {
        p.reloadConfig()
        buffCache = loadBuffs()
    }

    fun range() = p.config.getDouble("skinship.target-range", 3.0)
    fun econEnabled() = p.config.getBoolean("economy.enabled", false)
    fun cost(type: String) = p.config.getDouble("economy.${type}-cost", 0.0)
    fun actionCooldown(a: String) = p.config.getLong("actions.$a.cooldown-seconds", p.config.getLong("skinship.default-cooldown-seconds", 0))
    fun features(k: String) = p.config.getBoolean("features.$k", true)
    fun buffs(): List<BuffDefinition> = buffCache

    private fun loadBuffs(): List<BuffDefinition> {
        val file = p.dataFolder.resolve("buffs.yml")
        if (!file.exists()) p.saveResource("buffs.yml", false)
        val c = YamlConfiguration.loadConfiguration(file)
        val section = c.getConfigurationSection("family-buffs") ?: return emptyList()
        return section.getKeys(false).mapNotNull { key ->
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

    fun requestExpireSeconds(): Long = p.config.getLong("request-expire-seconds", 300L).coerceAtLeast(30L)
}
