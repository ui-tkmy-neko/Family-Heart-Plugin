package nekouidaga.net.familyheartplugin
import nekouidaga.net.familyheartplugin.action.ActionService
import nekouidaga.net.familyheartplugin.audit.AuditService
import nekouidaga.net.familyheartplugin.buff.BuffService
import nekouidaga.net.familyheartplugin.cache.RelationshipCache
import nekouidaga.net.familyheartplugin.command.FamilyHeartCommand
import nekouidaga.net.familyheartplugin.config.*
import nekouidaga.net.familyheartplugin.database.*
import nekouidaga.net.familyheartplugin.gui.GuiManager
import nekouidaga.net.familyheartplugin.listener.PlayerConnectionListener
import nekouidaga.net.familyheartplugin.message.Messages
import nekouidaga.net.familyheartplugin.penalty.PenaltyService
import nekouidaga.net.familyheartplugin.relationship.RelationshipService
import nekouidaga.net.familyheartplugin.request.RequestService
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
class FamilyHeartPlugin:JavaPlugin(){
    lateinit var db:DatabaseManager;
    override fun onEnable(){saveDefaultConfig();
    listOf("messages.yml","gui.yml","buffs.yml","penalties.yml").forEach{if(!dataFolder.resolve(it).exists())saveResource(it,false)};db=DatabaseManager(logger);try{db.connect(config)}catch(e:Exception){logger.severe("[FamilyHeart] MySQL接続失敗: ${e.message}");server.pluginManager.disablePlugin(this);return};val players=PlayerDao();val rdao=RelationshipDao();val cache=RelationshipCache();val rel=RelationshipService(this,db,rdao,players,cache);val req= nekouidaga.net.familyheartplugin.request.RequestService(db,RequestDao(),rel);val settings=Settings(this);val econ=EconomyService(this);econ.hook();val pen=PenaltyService(db,PenaltyDao());val act=ActionService(this,db,ActionDao(),rel,req,settings);val audit=AuditService(db,AuditDao());val buffs=BuffService(this,db,act,rel,pen,settings);val messages=Messages(this);val gui=GuiManager(this,rel,req,act,messages);server.pluginManager.registerEvents(gui,this);server.pluginManager.registerEvents(PlayerConnectionListener(this,db,players,rel,req,buffs,messages),this);val cmd=FamilyHeartCommand(this,rel,req,act,gui,messages,settings,econ,pen,buffs,audit);getCommand("familyheart")?.setExecutor(cmd);getCommand("familyheart")?.tabCompleter=cmd;server.scheduler.runTaskTimer(this,Runnable{Bukkit.getOnlinePlayers().forEach(buffs::recompute);req.expireAll()},20L,config.getLong("buff-task-interval-ticks",20L));logger.info("[FamilyHeart] Kotlin / Paper 26.2 enabled")};override fun onDisable(){if(::db.isInitialized)db.shutdown()}}
