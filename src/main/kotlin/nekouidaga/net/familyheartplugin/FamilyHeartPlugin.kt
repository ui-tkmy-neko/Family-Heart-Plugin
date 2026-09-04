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
    lateinit var requestService: nekouidaga.net.familyheartplugin.request.RequestService;
    override fun onEnable(){saveDefaultConfig();
    listOf("messages.yml","gui.yml","buffs.yml","penalties.yml").forEach{if(!dataFolder.resolve(it).exists())saveResource(it,false)};db=DatabaseManager(logger, dataFolder);try{db.connect(config)}catch(e:Exception){logger.severe("[FamilyHeart] SQLite初期化失敗: ${e.message}");server.pluginManager.disablePlugin(this);return};val players=PlayerDao();val rdao=RelationshipDao();val cache=RelationshipCache();val rel=RelationshipService(this,db,rdao,players,cache);val settings=Settings(this);val econ=EconomyService(this);econ.hook();val requestLog=nekouidaga.net.familyheartplugin.request.RequestLogService(dataFolder.toPath().resolve("logs"));requestService=nekouidaga.net.familyheartplugin.request.RequestService(db,rel,settings,econ,players,requestLog);val req=requestService;val pen=PenaltyService(this,db,PenaltyDao(),rel);val messages=Messages(this);val act=ActionService(this,db,ActionDao(),rel,req,settings,messages);val audit=AuditService(db,AuditDao());val buffs=BuffService(this,db,act,rel,pen,settings);
    // DBのRelationshipをサーバー起動時に一括ロード。以後、各書き込み完了時とJoin時に差分反映する。
    rel.preloadAllBlocking();
    rel.setAffectedHandler { ids -> pen.refreshAffectedAsync(ids) };pen.setAffectedHandler { ids -> server.scheduler.runTask(this, Runnable { ids.forEach { id -> Bukkit.getPlayer(id)?.let(buffs::recompute) } }) };val gui=GuiManager(this,rel,req,act,messages);server.pluginManager.registerEvents(gui,this);server.pluginManager.registerEvents(PlayerConnectionListener(this,db,players,rel,req,buffs,messages,pen),this);val cmd=FamilyHeartCommand(this,rel,req,act,gui,messages,settings,econ,pen,buffs,audit);getCommand("familyheart")?.setExecutor(cmd);getCommand("familyheart")?.tabCompleter=cmd;val buffTicks=config.getLong("buff-task-interval-ticks",20L).coerceAtLeast(1L);server.scheduler.runTaskTimer(this,Runnable{Bukkit.getOnlinePlayers().forEach(buffs::recompute)},buffTicks,buffTicks);server.scheduler.runTaskTimer(this,Runnable{
    // バグ修正(監査): request.expired は定義済みなのに送信されていなかった。
    // expireAll()がタイマー実行中の主スレッド上で戻すEXPIRED分だけ、
    // 申請者・対象それぞれオンラインなら通知する。
    req.expireAll().forEach { r -> listOf(r.requester, r.target).forEach { u -> Bukkit.getPlayer(u)?.sendMessage(messages.get("request.expired")) } }
    pen.expireAndRefreshOnline()
},20L,20L);logger.info("[FamilyHeart] Kotlin / Paper 26.2 enabled")};override fun onDisable(){if(::requestService.isInitialized)requestService.shutdown();if(::db.isInitialized){db.shutdown()}}}
