package nekouidaga.net.familyheartplugin.config

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

class EconomyService(private val plugin: JavaPlugin) {
    @Volatile private var economy: Economy? = null

    fun hook() {
        // VaultがサーバーにインストールされていなければEconomyクラス自体が
        // クラスパス上に存在しないため、参照する前に必ずプラグインの有無を確認する
        // (そうしないとNoClassDefFoundErrorでonEnable全体が落ちる)。
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.logger.info("[FamilyHeart] Vault not found; economy-based features disabled.")
            return
        }
        economy = plugin.server.servicesManager.getRegistration(Economy::class.java)?.provider
    }

    fun available(): Boolean = economy != null

    private fun <T> onMainAsync(action: () -> T): CompletableFuture<T> {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(action())
        val future = CompletableFuture<T>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try { future.complete(action()) } catch (t: Throwable) { future.completeExceptionally(t) }
        })
        return future
    }

    fun offlinePlayerAsync(uuid: UUID): CompletableFuture<OfflinePlayer> = onMainAsync { Bukkit.getOfflinePlayer(uuid) }

    fun canChargeAsync(player: OfflinePlayer, amount: Double): CompletableFuture<Boolean> = onMainAsync {
        if (amount <= 0.0) true else economy?.has(player, amount) == true
    }

    fun chargeAsync(player: OfflinePlayer, amount: Double): CompletableFuture<Boolean> = onMainAsync {
        if (amount <= 0.0) true else economy?.withdrawPlayer(player, amount)?.transactionSuccess() == true
    }

    fun refundAsync(player: OfflinePlayer, amount: Double): CompletableFuture<Boolean> = onMainAsync {
        if (amount <= 0.0) true else economy?.depositPlayer(player, amount)?.transactionSuccess() == true
    }
}
