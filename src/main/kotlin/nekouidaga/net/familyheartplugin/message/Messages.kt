package nekouidaga.net.familyheartplugin.message

import nekouidaga.net.familyheartplugin.model.RequestType
import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin

class Messages(private val p: JavaPlugin) {
    private var y = org.bukkit.configuration.file.YamlConfiguration()

    init { reload() }

    fun reload() {
        val file = p.dataFolder.resolve("messages.yml")
        if (!file.exists()) p.saveResource("messages.yml", false)
        y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
    }

    fun get(path: String, vars: Map<String, String> = emptyMap()): String {
        var s = y.getString(path, path) ?: path
        vars.forEach { (k, v) -> s = s.replace("%$k%", v) }
        return ChatColor.translateAlternateColorCodes('&', y.getString("prefix", "") + s)
    }

    fun requestKey(type: RequestType, phase: String, metadata: String?): String = when (type) {
        RequestType.MARRY -> "request.marry.$phase.${metadata?.lowercase() ?: "unknown"}"
        RequestType.CHILD_PARENT -> "request.child.$phase.${metadata?.lowercase() ?: "unknown"}"
        RequestType.SKINSHIP -> "request.skinship.${metadata?.lowercase() ?: "unknown"}.$phase"
        RequestType.DIVORCE -> "request.divorce.$phase"
        RequestType.SEPARATION -> "request.separation.$phase"
    }

    fun request(type: RequestType, phase: String, metadata: String?, vars: Map<String, String> = emptyMap()): String =
        get(requestKey(type, phase, metadata), vars)
}
