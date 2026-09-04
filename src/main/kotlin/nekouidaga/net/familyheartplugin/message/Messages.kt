package nekouidaga.net.familyheartplugin.message
import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin
class Messages(private val p:JavaPlugin){private var y=org.bukkit.configuration.file.YamlConfiguration();init{reload()};fun reload(){if(!p.dataFolder.resolve("messages.yml").exists())p.saveResource("messages.yml",false);y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(p.dataFolder.resolve("messages.yml"))};fun get(path:String, vars:Map<String, String> = emptyMap()):String{val raw=y.getString(path);val s0: String = raw ?: run { p.logger.warning("[FamilyHeart] Missing message key: $path"); y.getString("general.message-unavailable") ?: "&cメッセージを表示できませんでした。" };var s=s0;vars.forEach{(k,v)->s=s.replace("%$k%",v)};val prefix=y.getString("prefix") ?: "";return ChatColor.translateAlternateColorCodes('&',prefix+s)}
    fun requestKey(type: nekouidaga.net.familyheartplugin.model.RequestType, phase: String, metadata: String?): String = when (type) {
        nekouidaga.net.familyheartplugin.model.RequestType.MARRY -> "request.marry.$phase.${metadata?.lowercase() ?: "unknown"}"
        nekouidaga.net.familyheartplugin.model.RequestType.CHILD_PARENT -> "request.child.$phase.${metadata?.lowercase() ?: "unknown"}"
        nekouidaga.net.familyheartplugin.model.RequestType.SKINSHIP -> "request.skinship.${metadata?.lowercase() ?: "unknown"}.$phase"
        nekouidaga.net.familyheartplugin.model.RequestType.DIVORCE -> "request.divorce.$phase"
        nekouidaga.net.familyheartplugin.model.RequestType.SEPARATION -> "request.separation.$phase"
    }

    fun request(type: nekouidaga.net.familyheartplugin.model.RequestType, phase: String, metadata: String?, vars: Map<String, String> = emptyMap()): String =
        get(requestKey(type, phase, metadata), vars)
}
