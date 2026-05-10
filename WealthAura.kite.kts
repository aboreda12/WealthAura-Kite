@file:Repository("https://repo.milkbowl.net/repository/maven-public/")
@file:DependsOn("net.milkbowl.vault:Vault:1.7.1")

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// --- CONFIGURATION ---
val UPDATE_INTERVAL_TICKS = 1200L // 60 seconds
val PARTICLE_INTERVAL_TICKS = 5L  // Every 0.25 seconds

val RANK_1_PARTICLE = Particle.SCRAPE
val RANK_2_PARTICLE = Particle.END_ROD
val RANK_3_PARTICLE = Particle.COPPER_WAX_OFF

val PREFIX = "§6§lWealthAura §8» "
// --- END CONFIGURATION ---

val topPlayers = ConcurrentHashMap<UUID, Int>() // UUID -> Rank (1, 2, or 3)
val auraToggledOff = ConcurrentHashMap.newKeySet<UUID>()
var econ: Economy? = null

onLoad {
    val rsp = Bukkit.getServer().servicesManager.getRegistration(Economy::class.java)
    if (rsp == null) {
        logger.severe("Vault economy not found!")
        return@onLoad
    }
    econ = rsp.provider
    logger.info("WealthAura loaded and hooked into Vault.")

    // Global async task for economy lookup
    server.globalRegionScheduler.runAtFixedRate(plugin, { _ ->
        val currentEcon = econ ?: return@runAtFixedRate

        // Fetch offline players and sort by balance.
        // Note: Bukkit.getOfflinePlayers() is heavy and expensive on large servers.
        // For production environments with many players, consider using a more optimized
        // approach such as a direct database lookup or a cached baltop from the economy provider.
        val sorted = Bukkit.getOfflinePlayers()
            .map { it to currentEcon.getBalance(it) }
            .sortedByDescending { it.second }
            .take(3)

        val newTop = mutableMapOf<UUID, Int>()
        sorted.forEachIndexed { index, pair ->
            newTop[pair.first.uniqueId] = index + 1
        }

        // Atomically update the map to avoid flickering
        topPlayers.putAll(newTop)
        topPlayers.keys.retainAll(newTop.keys)
    }, 1L, UPDATE_INTERVAL_TICKS)

    Bukkit.getOnlinePlayers().forEach { startAuraTask(it) }
}

listen<PlayerJoinEvent> { event ->
    startAuraTask(event.player)
}

fun startAuraTask(player: Player) {
    player.scheduler.runAtFixedRate(plugin, { _ ->
        val rank = topPlayers[player.uniqueId] ?: return@runAtFixedRate
        if (auraToggledOff.contains(player.uniqueId)) return@runAtFixedRate

        val particle = when(rank) {
            1 -> RANK_1_PARTICLE
            2 -> RANK_2_PARTICLE
            3 -> RANK_3_PARTICLE
            else -> return@runAtFixedRate
        }

        // Spawn particles in a circle or around the player
        val loc = player.location.clone().add(0.0, 1.0, 0.0)
        player.world.spawnParticle(particle, loc, 5, 0.3, 0.5, 0.3, 0.0)
    }, null, 1L, PARTICLE_INTERVAL_TICKS)
}

command("aura") {
    executor { sender, args ->
        if (sender !is Player) {
            sender.sendMessage("${PREFIX}Only players can use this.")
            return@executor
        }
        
        if (!topPlayers.containsKey(sender.uniqueId)) {
            sender.sendMessage("${PREFIX}§cYou must be in the Top 3 richest players to use this!")
            return@executor
        }
        
        if (auraToggledOff.contains(sender.uniqueId)) {
            auraToggledOff.remove(sender.uniqueId)
            sender.sendMessage("${PREFIX}§aAura enabled.")
        } else {
            auraToggledOff.add(sender.uniqueId)
            sender.sendMessage("${PREFIX}§cAura disabled.")
        }
    }
}
