package nekouidaga.net.familyheartplugin.config

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Optional Vault integration without hard-linking the plugin's main classloader to Vault.
 * Vault classes are resolved only after the Vault plugin is confirmed to be installed.
 */
class EconomyService(private val plugin: JavaPlugin) {
    @Volatile private var provider: Any? = null
    @Volatile private var economyClass: Class<*>? = null
    @Volatile private var hasMethod: Method? = null
    @Volatile private var withdrawMethod: Method? = null
    @Volatile private var depositMethod: Method? = null
    @Volatile private var offlinePlayerClass: Class<*>? = null

    fun hook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.logger.info("[FamilyHeart] Vault not found; economy-based features disabled.")
            return
        }

        try {
            val economyType = Class.forName("net.milkbowl.vault.economy.Economy")
            val offlineType = Class.forName("org.bukkit.OfflinePlayer")
            @Suppress("UNCHECKED_CAST")
            val registration = plugin.server.servicesManager.getRegistration(economyType as Class<Any>)
            val hooked = registration?.provider
                ?: throw IllegalStateException("No Vault economy provider is registered")

            economyClass = economyType
            offlinePlayerClass = offlineType
            hasMethod = economyType.getMethod("has", offlineType, Double::class.javaPrimitiveType)
            withdrawMethod = economyType.getMethod("withdrawPlayer", offlineType, Double::class.javaPrimitiveType)
            depositMethod = economyType.getMethod("depositPlayer", offlineType, Double::class.javaPrimitiveType)
            provider = hooked
            plugin.logger.info("[FamilyHeart] Vault economy provider hooked: ${economyType.name}")
        } catch (t: Throwable) {
            provider = null
            plugin.logger.warning("[FamilyHeart] Vault economy unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun available(): Boolean = provider != null

    private fun <T> onMain(action: () -> T): CompletableFuture<T> {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(action())
        val future = CompletableFuture<T>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try { future.complete(action()) } catch (t: Throwable) { future.completeExceptionally(t) }
        })
        return future
    }

    private fun transaction(uuid: UUID, amount: Double, method: Method?): Boolean {
        if (amount <= 0.0) return true
        val econ = provider ?: return false
        val playerType = offlinePlayerClass ?: return false
        if (method == null) return false
        val player = Bukkit.getOfflinePlayer(uuid)
        require(playerType.isInstance(player)) { "OfflinePlayer type mismatch" }
        val result = method.invoke(econ, player, amount)
        return result?.let { successMethod(it) } ?: false
    }

    private fun successMethod(response: Any): Boolean = runCatching {
        response.javaClass.getMethod("transactionSuccess").invoke(response) as Boolean
    }.getOrElse {
        // Defensive fallback for economy implementations that expose a Boolean response.
        (response as? Boolean) ?: false
    }

    fun canChargeAsync(uuid: UUID, amount: Double): CompletableFuture<Boolean> = onMain {
        if (amount <= 0.0) true
        else {
            val econ = provider ?: return@onMain false
            val method = hasMethod ?: return@onMain false
            val player = Bukkit.getOfflinePlayer(uuid)
            val result = method.invoke(econ, player, amount)
            result as? Boolean ?: false
        }
    }

    fun chargeAsync(uuid: UUID, amount: Double): CompletableFuture<Boolean> = onMain {
        transaction(uuid, amount, withdrawMethod)
    }

    fun refundAsync(uuid: UUID, amount: Double): CompletableFuture<Boolean> = onMain {
        transaction(uuid, amount, depositMethod)
    }
}
