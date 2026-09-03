package nekouidaga.net.familyheartplugin.message
import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin
class Messages(private val p:JavaPlugin){private var y=org.bukkit.configuration.file.YamlConfiguration();init{reload()};fun reload(){if(!p.dataFolder.resolve("messages.yml").exists())p.saveResource("messages.yml",false);y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(p.dataFolder.resolve("messages.yml"))};fun get(path:String, vars:Map<String, String> = emptyMap()):String{var s=y.getString(path,path)?:path;vars.forEach{(k,v)->s=s.replace("%$k%",v)};return ChatColor.translateAlternateColorCodes('&',y.getString("prefix","")+s)}}
