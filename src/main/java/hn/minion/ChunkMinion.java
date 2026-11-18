package hn.minion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minion cargador de chunks: un armor stand pequeño que mantiene cargada
 * (con ticks de entidades y bloques) el área alrededor de él.
 */
public final class ChunkMinion extends JavaPlugin implements Listener {

    private record MinionData(String world, int x, int y, int z) {
        int chunkX() { return x >> 4; }
        int chunkZ() { return z >> 4; }
    }

    private NamespacedKey itemKey;
    private NamespacedKey entityKey;
    private NamespacedKey ownerKey;
    private final Map<UUID, MinionData> minions = new HashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "minion_item");
        entityKey = new NamespacedKey(this, "minion_entity");
        ownerKey = new NamespacedKey(this, "minion_owner");
        dataFile = new File(getDataFolder(), "minions.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);

        // Esperar a que los mundos estén listos y reponer los tickets.
        Bukkit.getScheduler().runTask(this, () -> {
            for (MinionData d : minions.values()) addTickets(d);
            getLogger().info(minions.size() + " minion(s) restaurado(s).");
        });
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------- Chunk tickets ----------

    private int radius() {
        return Math.max(0, Math.min(getConfig().getInt("radius", 2), 8));
    }

    private void addTickets(MinionData d) {
        World w = Bukkit.getWorld(d.world());
        if (w == null) return;
        int r = radius();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                w.addPluginChunkTicket(d.chunkX() + dx, d.chunkZ() + dz, this);
    }

    private void removeTickets(MinionData d) {
        World w = Bukkit.getWorld(d.world());
        if (w == null) return;
        int r = radius();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                w.removePluginChunkTicket(d.chunkX() + dx, d.chunkZ() + dz, this);
    }

    // ---------- Registro / persistencia ----------

    private void register(ArmorStand stand) {
        Location l = stand.getLocation();
        MinionData d = new MinionData(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        minions.put(stand.getUniqueId(), d);
        addTickets(d);
        saveData();
    }

    private void unregister(UUID id) {
        MinionData d = minions.remove(id);
        if (d == null) return;
        removeTickets(d);
        saveData();
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : y.getKeys(false)) {
            try {
                minions.put(UUID.fromString(key), new MinionData(
                        y.getString(key + ".world", "world"),
                        y.getInt(key + ".x"), y.getInt(key + ".y"), y.getInt(key + ".z")));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        minions.forEach((id, d) -> {
            y.set(id + ".world", d.world());
            y.set(id + ".x", d.x());
            y.set(id + ".y", d.y());
            y.set(id + ".z", d.z());
        });
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("No se pudo guardar minions.yml: " + e.getMessage());
        }
    }

    // ---------- Item ----------

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.ARMOR_STAND);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Minion cargador de chunks", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Click derecho en un bloque para colocarlo.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isMinionItem(ItemStack item) {
        return item != null && item.getType() == Material.ARMOR_STAND && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private boolean isMinion(Entity e) {
        return e instanceof ArmorStand
                && e.getPersistentDataContainer().has(entityKey, PersistentDataType.BYTE);
    }

    // ---------- Eventos ----------

    @EventHandler
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isMinionItem(item)) return;
        e.setCancelled(true); // evita colocar un armor stand normal

        Player p = e.getPlayer();
        if (!p.hasPermission("chunkminion.use")) {
            p.sendMessage(Component.text("No tienes permiso para colocar minions.", NamedTextColor.RED));
            return;
        }
        int max = getConfig().getInt("max-per-player", 3);
        long owned = 0;
        for (UUID id : minions.keySet()) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null && p.getUniqueId().toString().equals(
                    ent.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) owned++;
        }
        if (max > 0 && owned >= max && !p.hasPermission("chunkminion.admin")) {
            p.sendMessage(Component.text("Alcanzaste el límite de " + max + " minions.", NamedTextColor.RED));
            return;
        }

        Location loc = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, as -> {
            as.setSmall(true);
            as.setBasePlate(false);
            as.setArms(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(true);
            as.customName(Component.text("Minion cargador", NamedTextColor.GOLD));
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
            as.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        register(stand);

        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        p.sendMessage(Component.text("Minion colocado. Chunks cargados en radio " + radius() + ".", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onInteractMinion(PlayerInteractAtEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity ent = e.getRightClicked();
        if (!isMinion(ent)) return;
        e.setCancelled(true); // que nadie le ponga/quite armadura

        Player p = e.getPlayer();
        if (!p.isSneaking()) {
            p.sendMessage(Component.text("Agáchate y haz click derecho para recogerlo.", NamedTextColor.GRAY));
            return;
        }
        String owner = ent.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner != null && !owner.equals(p.getUniqueId().toString()) && !p.hasPermission("chunkminion.admin")) {
            p.sendMessage(Component.text("Este minion no es tuyo.", NamedTextColor.RED));
            return;
        }
        unregister(ent.getUniqueId());
        ent.getWorld().dropItemNaturally(ent.getLocation(), createItem());
        ent.remove();
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!isMinion(e.getEntity())) return;
        unregister(e.getEntity().getUniqueId());
        e.getDrops().clear();
        e.getDrops().add(createItem());
    }

    // ---------- Comandos ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("/minion give [jugador] | clean | reload");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "give" -> {
                Player target = a.length > 1 ? Bukkit.getPlayerExact(a[1])
                        : (s instanceof Player pl ? pl : null);
                if (target == null) { s.sendMessage("Jugador no encontrado."); return true; }
                target.getInventory().addItem(createItem());
                s.sendMessage("Minion entregado a " + target.getName() + ".");
            }
            case "clean" -> {
                // Quita del registro los minions cuya entidad ya no existe en chunks cargados.
                List<UUID> gone = new ArrayList<>();
                for (Map.Entry<UUID, MinionData> en : minions.entrySet()) {
                    World w = Bukkit.getWorld(en.getValue().world());
                    boolean loaded = w != null && w.isChunkLoaded(en.getValue().chunkX(), en.getValue().chunkZ());
                    if (loaded && Bukkit.getEntity(en.getKey()) == null) gone.add(en.getKey());
                }
                gone.forEach(this::unregister);
                s.sendMessage("Registros limpiados: " + gone.size());
            }
            case "reload" -> {
                minions.values().forEach(this::removeTickets);
                reloadConfig();
                minions.values().forEach(this::addTickets);
                s.sendMessage("Configuración recargada.");
            }
            default -> s.sendMessage("/minion give [jugador] | clean | reload");
        }
        return true;
    }
}
