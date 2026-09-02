package nekouidaga.net.familyheartplugin.config
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.java.JavaPlugin
class EconomyService(private val p:JavaPlugin){private var economy:Economy?=null;fun hook(){val rsp=p.server.servicesManager.getRegistration(Economy::class.java);economy=rsp?.provider};fun canCharge(player:org.bukkit.entity.Player,amount:Double):Boolean{if(amount<=0)return true;val e=economy?:return false;return e.has(player,amount)};fun charge(player:org.bukkit.entity.Player,amount:Double):Boolean{if(amount<=0)return true;val e=economy?:return false;return e.withdrawPlayer(player,amount).transactionSuccess()};fun available()=economy!=null}
