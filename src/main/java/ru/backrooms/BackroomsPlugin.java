package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class BackroomsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private String worldName;
    private int originX, originZ, levelSpacing, floorY, baseCeilingY;
    private boolean generationEnabled;
    private final Set<String> builtChunks = new HashSet<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, String> returnWorlds = new HashMap<>();
    private final Map<UUID, Long> portalCooldowns = new HashMap<>();
    private NamespacedKey lootMarker;
    private File playersDataFile;
    private FileConfiguration playersData;
    private final Set<String> trackedPlayerNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Random npcRandom = new Random();
    private final Map<String, Location> cloneLastLocations = new HashMap<>();
    private final Map<String, Long> cloneBehaviorUntil = new HashMap<>();
    private final Map<String, Long> cloneRespawnAt = new HashMap<>();
    private final Map<String, String> cloneModes = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        lootMarker = new NamespacedKey(this, "loot_populated");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("backrooms")).setExecutor(this);
        if (getCommand("br") != null) getCommand("br").setExecutor(this);
        loadTrackedPlayers();
        for (String name : getConfig().getStringList("clones.names")) {
            if (name != null && !name.isBlank()) trackedPlayerNames.add(name.trim());
        }
        if (getConfig().getStringList("clones.names").isEmpty()) {
            trackedPlayerNames.add("7gh");
            trackedPlayerNames.add("Alon");
            trackedPlayerNames.add("Voredishn");
        }
        if (getConfig().getBoolean("clones.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(this, this::spawnTrackedClones, 60L);
            Bukkit.getScheduler().runTaskTimer(this, this::tickCloneBehavior, 100L, 20L);
            if (!getServer().getPluginManager().isPluginEnabled("Citizens")) {
                getLogger().warning("NPC-клоны включены, но Citizens не установлен.");
            }
        }
        getLogger().info("Backrooms enabled. World: " + worldName);
    }

    private void loadTrackedPlayers() {
        playersDataFile = new File(getDataFolder(), "players.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        playersData = YamlConfiguration.loadConfiguration(playersDataFile);
        for (String name : playersData.getStringList("seen-player-names")) {
            if (name != null && !name.isBlank()) trackedPlayerNames.add(name.trim());
        }
    }

    private void saveTrackedPlayers() {
        if (playersData == null) return;
        playersData.set("seen-player-names", new ArrayList<>(trackedPlayerNames));
        try { playersData.save(playersDataFile); }
        catch (IOException ex) { getLogger().warning("Не удалось сохранить список игроков: " + ex.getMessage()); }
    }

    @EventHandler
    public void onPlayerJoinTrack(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("clones.auto-track-players", true)) return;
        String name = event.getPlayer().getName();
        if (trackedPlayerNames.add(name)) saveTrackedPlayers();
        if (getConfig().getBoolean("clones.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> spawnCloneIfMissing(name), 100L);
        }
    }

    private void spawnTrackedClones() {
        if (!getConfig().getBoolean("clones.enabled", true)) return;
        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        int limit = Math.max(1, getConfig().getInt("clones.max-npcs", 16));
        int count = 0;
        for (String name : new ArrayList<>(trackedPlayerNames)) {
            if (count++ >= limit) break;
            spawnCloneIfMissing(name);
        }
    }

    private void spawnCloneIfMissing(String playerName) {
        if (!getConfig().getBoolean("clones.enabled", true)) return;
        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
            if (registry instanceof Iterable<?> iterable) {
                for (Object npc : iterable) {
                    Object existingName = npc.getClass().getMethod("getName").invoke(npc);
                    if (playerName.equalsIgnoreCase(String.valueOf(existingName))) return;
                }
            }
            int radiusChunks = Math.max(2, getConfig().getInt("clones.spawn-radius-chunks", 12));
            int chunkX = Math.floorDiv(originX, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
            int chunkZ = Math.floorDiv(originZ, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
            String chunkKey = world.getName() + ":" + chunkX + ":" + chunkZ;
            if (builtChunks.add(chunkKey)) buildChunk(world, chunkX, chunkZ);
            Location spawn = findNpcSpawnLocation(world, chunkX, chunkZ);
            Method createNpc = null;
            for (Method method : registry.getClass().getMethods()) {
                if (method.getName().equals("createNPC") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == EntityType.class
                    && method.getParameterTypes()[1] == String.class) { createNpc = method; break; }
            }
            if (createNpc == null) throw new NoSuchMethodException("NPCRegistry.createNPC");
            Object npc = createNpc.invoke(registry, EntityType.PLAYER, playerName);
            try {
                Class<?> skinTrait = Class.forName("net.citizensnpcs.trait.SkinTrait");
                Object trait = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, skinTrait);
                skinTrait.getMethod("setSkinName", String.class).invoke(trait, playerName);
            } catch (ReflectiveOperationException skinError) {
                getLogger().warning("Скин NPC " + playerName + ": " + skinError.getMessage());
            }
            try { npc.getClass().getMethod("setProtected", boolean.class).invoke(npc, false); } catch (ReflectiveOperationException ignored) { }
            npc.getClass().getMethod("spawn", Location.class).invoke(npc, spawn);
            cloneLastLocations.put(playerName.toLowerCase(Locale.ROOT), spawn.clone());
            cloneModes.put(playerName.toLowerCase(Locale.ROOT), "wander");
            cloneBehaviorUntil.put(playerName.toLowerCase(Locale.ROOT), System.currentTimeMillis() + 3000L);
        } catch (ClassNotFoundException ex) {
            getLogger().warning("Citizens API не найдена.");
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("NPC-клон " + playerName + ": " + ex.getMessage());
        }
    }

    private void tickCloneBehavior() {
        if (!getConfig().getBoolean("clones.enabled", true)
                || !getServer().getPluginManager().isPluginEnabled("Citizens")) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = api.getMethod("getNPCRegistry").invoke(null);
            if (!(registry instanceof Iterable<?> iterable)) return;
            long now = System.currentTimeMillis();
            for (Object npc : iterable) {
                String name;
                try { name = String.valueOf(npc.getClass().getMethod("getName").invoke(npc)); }
                catch (ReflectiveOperationException e) { continue; }
                String key = name.toLowerCase(Locale.ROOT);
                if (!trackedPlayerNames.contains(name) && !getConfig().getStringList("clones.names").contains(name)) continue;
                boolean spawned;
                try { spawned = Boolean.TRUE.equals(npc.getClass().getMethod("isSpawned").invoke(npc)); }
                catch (ReflectiveOperationException e) { continue; }
                Long respawnAt = cloneRespawnAt.get(key);
                if (!spawned) {
                    if (respawnAt == null) {
                        cloneRespawnAt.put(key, now + 45000L + npcRandom.nextInt(90000));
                        continue;
                    }
                    if (now >= respawnAt) {
                        Location base = findRandomCloneLocation(world);
                        if (base != null) {
                            npc.getClass().getMethod("spawn", Location.class).invoke(npc, base);
                            cloneLastLocations.put(key, base.clone());
                            cloneModes.put(key, "wander");
                            cloneBehaviorUntil.put(key, now + 3000L);
                            cloneRespawnAt.remove(key);
                        }
                    }
                    continue;
                }
                Object entityObj = npc.getClass().getMethod("getEntity").invoke(npc);
                if (!(entityObj instanceof org.bukkit.entity.Entity entity)) continue;
                Location current = entity.getLocation();
                if (!current.getWorld().equals(world)) continue;
                cloneLastLocations.put(key, current.clone());

                Player nearest = null;
                double nearestDistance = Double.MAX_VALUE;
                for (Player player : world.getPlayers()) {
                    double distance = player.getLocation().distanceSquared(current);
                    if (distance < nearestDistance) { nearestDistance = distance; nearest = player; }
                }
                String mode = cloneModes.getOrDefault(key, "wander");
                long until = cloneBehaviorUntil.getOrDefault(key, 0L);

                if ("stare".equals(mode)) {
                    if (nearest != null && nearestDistance <= 18 * 18) faceNpc(npc, nearest.getEyeLocation());
                    if (now < until) continue;
                    if (npcRandom.nextDouble() < 0.72) {
                        npc.getClass().getMethod("despawn").invoke(npc);
                        cloneRespawnAt.put(key, now + 12000L + npcRandom.nextInt(30000));
                        cloneModes.put(key, "hidden");
                    } else {
                        wanderNpc(npc, current, world);
                        cloneModes.put(key, "wander");
                        cloneBehaviorUntil.put(key, now + 8000L + npcRandom.nextInt(12000));
                    }
                    continue;
                }

                if (nearest != null && nearestDistance <= 18 * 18 && now >= until) {
                    if (npcRandom.nextDouble() < 0.42) {
                        faceNpc(npc, nearest.getEyeLocation());
                        cloneModes.put(key, "stare");
                        cloneBehaviorUntil.put(key, now + 1800L + npcRandom.nextInt(2600));
                    } else if (npcRandom.nextDouble() < 0.38) {
                        npc.getClass().getMethod("despawn").invoke(npc);
                        cloneRespawnAt.put(key, now + 12000L + npcRandom.nextInt(26000));
                        cloneModes.put(key, "hidden");
                        continue;
                    } else {
                        wanderNpc(npc, current, world);
                        cloneModes.put(key, "wander");
                        cloneBehaviorUntil.put(key, now + 7000L + npcRandom.nextInt(9000));
                    }
                } else if (now >= until) {
                    wanderNpc(npc, current, world);
                    cloneModes.put(key, "wander");
                    cloneBehaviorUntil.put(key, now + 9000L + npcRandom.nextInt(16000));
                }
            }
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            getLogger().fine("NPC tick skipped: " + ex.getMessage());
        }
    }

    private Location findRandomCloneLocation(World world) {
        int radiusChunks = Math.max(2, getConfig().getInt("clones.spawn-radius-chunks", 12));
        int cx = Math.floorDiv(originX, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
        int cz = Math.floorDiv(originZ, 16) + npcRandom.nextInt(radiusChunks * 2 + 1) - radiusChunks;
        String chunkKey = world.getName() + ":" + cx + ":" + cz;
        if (builtChunks.add(chunkKey)) buildChunk(world, cx, cz);
        return findNpcSpawnLocation(world, cx, cz);
    }

    private void faceNpc(Object npc, Location target) {
        try { npc.getClass().getMethod("faceLocation", Location.class).invoke(npc, target); }
        catch (ReflectiveOperationException ignored) { }
    }

    private void wanderNpc(Object npc, Location current, World world) {
        try {
            int radius = 6 + npcRandom.nextInt(13);
            int x = current.getBlockX() + npcRandom.nextInt(radius * 2 + 1) - radius;
            int z = current.getBlockZ() + npcRandom.nextInt(radius * 2 + 1) - radius;
            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
            String chunkKey = world.getName() + ":" + cx + ":" + cz;
            if (builtChunks.add(chunkKey)) buildChunk(world, cx, cz);
            Location target = findNpcSpawnLocation(world, cx, cz);
            cloneLastLocations.put(String.valueOf(npc.getClass().getMethod("getName").invoke(npc)).toLowerCase(Locale.ROOT), target.clone());
            Object navigator = npc.getClass().getMethod("getNavigator").invoke(npc);
            navigator.getClass().getMethod("setTarget", Location.class).invoke(navigator, target);
            try {
                Object params = navigator.getClass().getMethod("getLocalParameters").invoke(navigator);
                params.getClass().getMethod("speedModifier", float.class).invoke(params, 0.65f + npcRandom.nextFloat() * 0.45f);
            } catch (ReflectiveOperationException ignored) { }
        } catch (ReflectiveOperationException ignored) { }
    }

    private Location findNpcSpawnLocation(World world, int chunkX, int chunkZ) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        int ceiling = getCeilingAt(baseX + 8, baseZ + 8);
        List<Location> candidates = new ArrayList<>();
        for (int x = 1; x < 15; x++) for (int z = 1; z < 15; z++) {
            Block floor = world.getBlockAt(baseX + x, floorY, baseZ + z);
            Block feet = world.getBlockAt(baseX + x, floorY + 1, baseZ + z);
            Block head = world.getBlockAt(baseX + x, floorY + 2, baseZ + z);
            if (floor.getType().isSolid() && feet.getType().isAir() && head.getType().isAir() && floorY + 2 < ceiling) {
                candidates.add(new Location(world, baseX + x + 0.5, floorY + 1, baseZ + z + 0.5));
            }
        }
        if (!candidates.isEmpty()) return candidates.get(npcRandom.nextInt(candidates.size()));
        return new Location(world, baseX + 8.5, floorY + 1, baseZ + 8.5);
    }

    private void readConfig() {
        FileConfiguration c = getConfig();
        worldName = c.getString("world-name", "flat");
        originX = c.getInt("origin-x", 0);
        originZ = c.getInt("origin-z", 0);
        levelSpacing = Math.max(1024, c.getInt("level-spacing", 4096));
        floorY = c.getInt("floor-y", 60);
        baseCeilingY = Math.max(floorY + 5, c.getInt("ceiling-y", 68));
        generationEnabled = c.getBoolean("generation-enabled", false);
    }

    /**
     * Детерминированная высота потолка для чанка.
     * Уровни 0 (жёлтый офис), 1, 6, 8 (архивы/офисы) чаще высокие,
     * тёмные (5, 21) и подуровни — ниже. Возвращает Y-координату потолка.
     */
    private int getCeilingAt(int x, int z) {
        int level = getLevelAt(x, z);
        int chunkX = x >> 4, chunkZ = z >> 4;
        long h = Math.floorMod(hash(chunkX, chunkZ, 3331), 100);
        int theme = themeFor(level);
        // Базовые офсеты: 0, +2, +4 (реже +6).
        int offset;
        if (theme == 0 || level == 0) {
            // Жёлтые офисы: высокие потолки часто
            offset = h < 45 ? 4 : (h < 80 ? 2 : 0);
        } else if (theme == 2 || theme == 5) {
            // Тёмные/ритуальные — низкие
            offset = h < 70 ? 0 : 2;
        } else {
            offset = h < 33 ? 0 : (h < 66 ? 2 : 4);
        }
        // Не выше, чем позволяет конфиг +6
        offset = Math.min(offset, 6);
        return baseCeilingY + offset;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("backrooms.admin")) {
            sender.sendMessage(ChatColor.RED + "Все команды Backrooms доступны только операторам (OP).");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/backrooms generate — включить генерацию");
            sender.sendMessage(ChatColor.YELLOW + "/backrooms stop — выключить генерацию");
            sender.sendMessage(ChatColor.YELLOW + "/br level <0-10|0.1-10.1|exit> — телепорт");
            sender.sendMessage(ChatColor.YELLOW + "/backrooms info — состояние");
            return true;
        }
        if (args[0].equalsIgnoreCase("generate")) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Мир '" + worldName + "' не загружен.");
                return true;
            }
            generationEnabled = true;
            getConfig().set("generation-enabled", true);
            saveConfig();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(world)) buildAround(p.getLocation());
            }
            sender.sendMessage(ChatColor.GREEN + "Генерация Backrooms включена в мире " + worldName + ".");
            Bukkit.getScheduler().runTaskLater(this, this::spawnTrackedClones, 40L);
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) {
            generationEnabled = false;
            getConfig().set("generation-enabled", false);
            saveConfig();
            sender.sendMessage(ChatColor.YELLOW + "Генерация Backrooms выключена.");
            return true;
        }
        if (args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(ChatColor.YELLOW + "Мир: " + worldName + " | генерация: " + (generationEnabled ? "включена" : "выключена"));
            return true;
        }
        if (args[0].equalsIgnoreCase("level")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Использование: /br level <0-10|0.1-10.1|exit>");
                return true;
            }
            int targetLevel;
            if (args[1].equalsIgnoreCase("exit")) targetLevel = 22;
            else {
                try {
                    String[] parts = args[1].split("\\.", -1);
                    int main = Integer.parseInt(parts[0]);
                    if (main < 0 || main > 10 || parts.length > 2 || (parts.length == 2 && !parts[1].equals("1"))) throw new NumberFormatException();
                    targetLevel = main * 2 + (parts.length == 2 ? 1 : 0);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Уровень от 0 до 10, например 3.1, или exit.");
                    return true;
                }
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Мир '" + worldName + "' не загружен.");
                return true;
            }
            Location target = findLevelLocation(world, targetLevel, sender instanceof Player ? ((Player) sender).getLocation() : new Location(world, originX, floorY + 1, originZ));
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Не удалось найти область уровня.");
                return true;
            }
            int cx = target.getBlockX() >> 4, cz = target.getBlockZ() >> 4;
            String targetChunkKey = world.getName() + ":" + cx + ":" + cz;
            if (builtChunks.add(targetChunkKey)) buildChunk(world, cx, cz);
            target.setY(floorY + 1);
            if (sender instanceof Player player) {
                player.teleport(target);
                player.sendMessage(ChatColor.GREEN + "Телепортировано на " + levelName(targetLevel) + ".");
            } else sender.sendMessage(ChatColor.GREEN + "Координаты: " + target.getBlockX() + ", " + target.getBlockZ());
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Неизвестная команда.");
        return true;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!generationEnabled || !event.getWorld().getName().equalsIgnoreCase(worldName)) return;
        String key = event.getWorld().getName() + ":" + event.getChunk().getX() + ":" + event.getChunk().getZ();
        if (builtChunks.add(key)) {
            int x = event.getChunk().getX(), z = event.getChunk().getZ();
            Bukkit.getScheduler().runTask(this, () -> buildChunk(event.getWorld(), x, z));
        }
    }

    private void buildAround(Location location) {
        World world = location.getWorld();
        if (world == null) return;
        int cx = location.getChunk().getX(), cz = location.getChunk().getZ();
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                String key = world.getName() + ":" + x + ":" + z;
                if (builtChunks.add(key)) {
                    final int bx = x, bz = z;
                    Bukkit.getScheduler().runTask(this, () -> buildChunk(world, bx, bz));
                }
            }
        }
    }

    private void buildChunk(World world, int chunkX, int chunkZ) {
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        int level = getLevelAt(baseX + 8, baseZ + 8);
        int ceiling = getCeilingAt(baseX + 8, baseZ + 8);
        Random random = new Random(hash(chunkX, chunkZ, level));
        Material wall = wallForLevel(level);
        Material floor = floorForLevel(level);
        Material ceilingMat = ceilingForLevel(level);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.getBlockAt(baseX + x, floorY, baseZ + z).setType(floor, false);
                world.getBlockAt(baseX + x, ceiling, baseZ + z).setType(ceilingMat, false);
                for (int y = floorY + 1; y < ceiling; y++) {
                    world.getBlockAt(baseX + x, y, baseZ + z).setType(Material.AIR, false);
                }
            }
        }

        // Стены по краям, с проходами шириной 4 блока в центре каждой стороны.
        for (int i = 0; i < 16; i++) {
            boolean gap = i >= 6 && i <= 9;
            if (!edgeOpen(chunkX, chunkZ, 0) || !gap) wallColumn(world, baseX + i, baseZ, wall, ceiling);
            if (!edgeOpen(chunkX, chunkZ + 1, 0) || !gap) wallColumn(world, baseX + i, baseZ + 15, wall, ceiling);
            if (!edgeOpen(chunkX, chunkZ, 1) || !gap) wallColumn(world, baseX, baseZ + i, wall, ceiling);
            if (!edgeOpen(chunkX + 1, chunkZ, 1) || !gap) wallColumn(world, baseX + 15, baseZ + i, wall, ceiling);
        }

        for (int i = 0; i < 2 + random.nextInt(3); i++) {
            int lx = baseX + 2 + random.nextInt(12);
            int lz = baseZ + 2 + random.nextInt(12);
            world.getBlockAt(lx, ceiling, lz).setType(Material.SEA_LANTERN, false);
        }

        decorateRoom(world, baseX, baseZ, level, ceiling, random, wall, floor);
        addLevelSignature(world, baseX, baseZ, level, ceiling, random);

        if (random.nextInt(20) == 0) {
            int portalX = baseX + 6 + random.nextInt(4);
            int portalZ = baseZ + 6 + random.nextInt(4);
            buildPortal(world, portalX, portalZ, ceiling);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> populateContainersInChunk(world, chunkX, chunkZ, level), 5L);
        Bukkit.getScheduler().runTaskLater(this, () -> populateContainersInChunk(world, chunkX, chunkZ, level), 40L);
    }

    private void buildPortal(World world, int x, int z, int ceiling) {
        int bottom = floorY + 1;
        int top = Math.min(ceiling - 1, bottom + 4);
        for (int dx = 0; dx < 4; dx++) {
            world.getBlockAt(x + dx, bottom, z).setType(Material.CRYING_OBSIDIAN, false);
            world.getBlockAt(x + dx, top, z).setType(Material.CRYING_OBSIDIAN, false);
        }
        for (int dy = 1; dy < top - bottom; dy++) {
            world.getBlockAt(x, bottom + dy, z).setType(Material.CRYING_OBSIDIAN, false);
            world.getBlockAt(x + 3, bottom + dy, z).setType(Material.CRYING_OBSIDIAN, false);
            for (int dx = 1; dx <= 2; dx++) {
                world.getBlockAt(x + dx, bottom + dy, z).setType(Material.PURPLE_STAINED_GLASS, false);
            }
        }
        world.getBlockAt(x + 1, bottom, z + 1).setType(Material.POLISHED_BLACKSTONE, false);
        world.getBlockAt(x + 2, bottom, z + 1).setType(Material.POLISHED_BLACKSTONE, false);
    }

    @EventHandler
    public void onPortalStep(PlayerMoveEvent event) {
        if (!generationEnabled || event.getTo() == null) return;
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equalsIgnoreCase(worldName)) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Location to = event.getTo();
        boolean nearPortal = false;
        int px = to.getBlockX(), py = to.getBlockY(), pz = to.getBlockZ();
        for (int ox = -1; ox <= 1 && !nearPortal; ox++) {
            for (int oy = 0; oy <= 1 && !nearPortal; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (player.getWorld().getBlockAt(px + ox, py + oy, pz + oz).getType() == Material.PURPLE_STAINED_GLASS) {
                        nearPortal = true;
                        break;
                    }
                }
            }
        }
        if (!nearPortal) return;

        long now = System.currentTimeMillis();
        Long last = portalCooldowns.get(player.getUniqueId());
        if (last != null && now - last < 1500) return;
        portalCooldowns.put(player.getUniqueId(), now);

        Random portalRandom = new Random(now ^ player.getUniqueId().getMostSignificantBits());
        int nextLevel = portalRandom.nextInt(100) == 0 ? 22 : portalRandom.nextInt(22);
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location target = findLevelLocation(world, nextLevel, to);
        if (target == null) return;
        int destinationChunkX = target.getBlockX() >> 4;
        int destinationChunkZ = target.getBlockZ() >> 4;
               String destinationKey = world.getName() + ":" + destinationChunkX + ":" + destinationChunkZ;
        if (builtChunks.add(destinationKey)) buildChunk(world, destinationChunkX, destinationChunkZ);
        target.getBlock().setType(Material.AIR, false);
        target.clone().add(0, 1, 0).getBlock().setType(Material.AIR, false);
        player.teleport(target);
        if (player.isOp()) player.sendMessage(ChatColor.LIGHT_PURPLE + "[Backrooms] Портал перенёс тебя на " + levelName(nextLevel) + ".");
    }

    private void decorateRoom(World world, int baseX, int baseZ, int level, int ceiling, Random random, Material wall, Material floor) {
        int theme = themeFor(level);
        Material accent = accentForTheme(theme);
        int height = ceiling - floorY - 1; // свободная высота
        int pattern = random.nextInt(6);

        if (pattern == 0) {
            // Вертикальная перегородка с проходом в середине
            int x = baseX + 4 + random.nextInt(5);
            for (int z = baseZ + 3; z <= baseZ + 12; z++) {
                if (z >= baseZ + 7 && z <= baseZ + 9) continue;
                int wallTop = floorY + 1 + Math.min(3, height);
                for (int y = floorY + 1; y < wallTop; y++) world.getBlockAt(x, y, z).setType(wall, false);
            }
        } else if (pattern == 1) {
            // Горизонтальная перегородка
            int z = baseZ + 5 + random.nextInt(5);
            for (int x = baseX + 3; x <= baseX + 12; x++) {
                if (x >= baseX + 7 && x <= baseX + 9) continue;
                int wallTop = floorY + 1 + Math.min(2, height);
                for (int y = floorY + 1; y < wallTop; y++) world.getBlockAt(x, y, z).setType(wall, false);
            }
        } else if (pattern == 2) {
            // Угловые колонны (низкие, не до потолка) — только 4 штуки
            for (int[] pos : new int[][]{{3,3},{12,3},{3,12},{12,12}}) {
                int colTop = floorY + 1 + Math.min(3, height);
                for (int y = floorY + 1; y < colTop; y++) world.getBlockAt(baseX + pos[0], y, baseZ + pos[1]).setType(accent, false);
            }
        } else if (pattern == 3) {
            Material carpet = theme == 2 || theme == 5 ? Material.RED_CARPET
                    : (theme == 1 || theme == 3 ? Material.GRAY_CARPET : Material.YELLOW_CARPET);
            int x0 = baseX + 4 + random.nextInt(3), z0 = baseZ + 4 + random.nextInt(3);
            for (int x = x0; x < x0 + 4; x++)
                for (int z = z0; z < z0 + 4; z++)
                    world.getBlockAt(x, floorY + 1, z).setType(carpet, false);
        } else if (pattern == 4) {
            // Брошенный стол/шкаф
            for (int x = baseX + 5; x <= baseX + 7; x++) {
                world.getBlockAt(x, floorY + 1, baseZ + 10).setType(accent, false);
                world.getBlockAt(x, floorY + 2, baseZ + 10).setType(Material.OAK_SLAB, false);
            }
        } else {
            // Г-образная перегородка (высотой 3 блока), с проходом
            int wallTop = floorY + 1 + Math.min(3, height);
            for (int z = baseZ + 4; z <= baseZ + 9; z++)
                for (int y = floorY + 1; y < wallTop; y++) world.getBlockAt(baseX + 10, y, z).setType(wall, false);
            for (int x = baseX + 6; x <= baseX + 10; x++)
                for (int y = floorY + 1; y < wallTop; y++) world.getBlockAt(x, y, baseZ + 4).setType(wall, false);
            for (int y = floorY + 1; y < wallTop; y++) world.getBlockAt(baseX + 10, y, baseZ + 7).setType(Material.AIR, false);
        }

        // Потолочный светильник
        if (random.nextBoolean()) {
            int lx = baseX + 5 + random.nextInt(6), lz = baseZ + 5 + random.nextInt(6);
            world.getBlockAt(lx, ceiling, lz).setType(Material.SEA_LANTERN, false);
        }

        // Один связный тип комнаты на чанк, без случайного «мусора»
        if (random.nextInt(100) < 72) {
            int roomType = random.nextInt(3);
            if (roomType == 0) buildWarehouse(world, baseX, baseZ, level, ceiling, random);
            else if (roomType == 1) buildOffice(world, baseX, baseZ, level, ceiling, random);
            else buildLibrary(world, baseX, baseZ, level, ceiling, random);
        } else {
            addRoomDecor(world, baseX, baseZ, level, ceiling, random, accent);
            addLevelSpecificDecor(world, baseX, baseZ, level, ceiling, random);
        }
    }

    private void addRoomDecor(World world, int baseX, int baseZ, int level, int ceiling, Random random, Material accent) {
        int props = random.nextInt(100) < 78 ? 3 + random.nextInt(5) : random.nextInt(2);
        for (int i = 0; i < props; i++) {
            int x = baseX + 2 + random.nextInt(12);
            int z = baseZ + 2 + random.nextInt(12);
            if (!world.getBlockAt(x, floorY + 1, z).getType().isAir()) continue;
            if (floorY + 2 >= ceiling) continue;
            int theme = themeFor(level);
            Material prop = switch (theme) {
                case 1 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.FURNACE;
                    case 3 -> Material.IRON_BLOCK; case 4 -> Material.CHAIN; case 5 -> Material.LIGHTNING_ROD;
                    default -> Material.CRAFTING_TABLE;
                };
                case 2 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.SOUL_LANTERN;
                    case 3 -> Material.CRYING_OBSIDIAN; case 4 -> Material.CHAIN; case 5 -> Material.FURNACE;
                    default -> Material.POLISHED_BLACKSTONE_BRICKS;
                };
                case 3 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.COPPER_BLOCK;
                    case 3 -> Material.LIGHTNING_ROD; case 4 -> Material.CUT_COPPER; case 5 -> Material.BLAST_FURNACE;
                    default -> Material.IRON_BARS;
                };
                case 4 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.MOSS_BLOCK;
                    case 3 -> Material.VINE; case 4 -> Material.MOSSY_COBBLESTONE; case 5 -> Material.LANTERN;
                    default -> Material.ROOTED_DIRT;
                };
                case 5 -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.CRYING_OBSIDIAN;
                    case 3 -> Material.CHAIN; case 4 -> Material.SCULK; case 5 -> Material.SOUL_LANTERN;
                    default -> Material.POLISHED_BLACKSTONE_BRICKS;
                };
                default -> switch (random.nextInt(7)) {
                    case 0 -> Material.CHEST; case 1 -> Material.BARREL; case 2 -> Material.FURNACE;
                    case 3 -> Material.CRAFTING_TABLE; case 4 -> Material.OAK_STAIRS; case 5 -> Material.FLOWER_POT;
                    default -> Material.BROWN_TERRACOTTA;
                };
            };
            world.getBlockAt(x, floorY + 1, z).setType(prop, false);
            if (prop == Material.CHEST || prop == Material.BARREL) fillLoot(world.getBlockAt(x, floorY + 1, z), level, random);
            if (prop == Material.IRON_BLOCK && random.nextBoolean() && floorY + 3 < ceiling)
                world.getBlockAt(x, floorY + 2, z).setType(Material.IRON_BARS, false);
        }

        if (random.nextInt(9) == 0) {
            int x = baseX + 5 + random.nextInt(4), z = baseZ + 5 + random.nextInt(4);
            int theme = themeFor(level);
            Material base = theme >= 2 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.BROWN_TERRACOTTA;
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 2; dz++)
                world.getBlockAt(x + dx, floorY + 1, z + dz).setType(base, false);
            if (floorY + 2 < ceiling) world.getBlockAt(x + 1, floorY + 2, z).setType(Material.FURNACE, false);
            Material storage = random.nextBoolean() ? Material.CHEST : Material.BARREL;
            if (floorY + 2 < ceiling) {
                world.getBlockAt(x + 2, floorY + 2, z + 1).setType(storage, false);
                fillLoot(world.getBlockAt(x + 2, floorY + 2, z + 1), level, random);
            }
        }

        if (random.nextInt(18) == 0) {
            int x = baseX + 1 + random.nextInt(14), z = baseZ + 1 + random.nextInt(14);
            int y = floorY + 2;
            if (y + 2 < ceiling && isWall(world.getBlockAt(x, y, z).getType())) {
                try {
                    Painting painting = world.spawn(new Location(world, x + 0.5, y + 0.1, z + 0.5), Painting.class);
                    painting.setFacingDirection(BlockFace.SOUTH, true);
                    painting.setArt(Art.KEBAB, true);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void addLevelSpecificDecor(World world, int bx, int bz, int level, int ceiling, Random random) {
        int theme = themeFor(level);
        int maxClusterHeight = Math.min(3, ceiling - floorY - 1);
        int clusters = 2 + random.nextInt(4) + (level % 2);
        for (int i = 0; i < clusters; i++) {
            int x = bx + 2 + random.nextInt(12), z = bz + 2 + random.nextInt(12);
            if (!world.getBlockAt(x, floorY + 1, z).getType().isAir()) continue;
            if (theme == 0) {
                if (random.nextBoolean()) {
                    world.getBlockAt(x, floorY + 1, z).setType(Material.OAK_PLANKS, false);
                    if (maxClusterHeight >= 1) world.getBlockAt(x, floorY + 2, z).setType(Material.OAK_SLAB, false);
                    if (random.nextBoolean()) world.getBlockAt(x + 1, floorY + 1, z).setType(Material.BOOKSHELF, false);
                } else {
                    world.getBlockAt(x, floorY + 1, z).setType(Material.YELLOW_CARPET, false);
                    if (random.nextInt(4) == 0 && maxClusterHeight >= 1)
                        world.getBlockAt(x, floorY + 2, z).setType(Material.FLOWER_POT, false);
                }
            } else if (theme == 1 || theme == 3) {
                Material[] industrial = theme == 3
                    ? new Material[]{Material.CUT_COPPER, Material.COPPER_BLOCK, Material.LIGHTNING_ROD, Material.CHAIN, Material.BLAST_FURNACE, Material.BARREL}
                    : new Material[]{Material.IRON_BLOCK, Material.IRON_BARS, Material.CHAIN, Material.BLAST_FURNACE, Material.COPPER_BLOCK, Material.BARREL};
                Material m = industrial[random.nextInt(industrial.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (maxClusterHeight >= 1 && random.nextBoolean())
                    world.getBlockAt(x, floorY + 2, z).setType(Material.IRON_BARS, false);
            } else if (theme == 4) {
                Material[] dark = {Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.VINE, Material.ROOTED_DIRT, Material.LANTERN, Material.BARREL};
                Material m = dark[random.nextInt(dark.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (m == Material.MOSS_BLOCK && maxClusterHeight >= 1 && random.nextBoolean())
                    world.getBlockAt(x, floorY + 2, z).setType(Material.VINE, false);
            } else if (theme == 5) {
                Material[] dark = {Material.CRYING_OBSIDIAN, Material.POLISHED_BLACKSTONE_BRICKS, Material.CHAIN, Material.SOUL_LANTERN, Material.SCULK, Material.OBSIDIAN};
                Material m = dark[random.nextInt(dark.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (m == Material.SOUL_LANTERN && maxClusterHeight >= 1)
                    world.getBlockAt(x, floorY + 2, z).setType(Material.CHAIN, false);
            } else {
                Material[] dark = {Material.SOUL_SAND, Material.CRYING_OBSIDIAN, Material.POLISHED_BLACKSTONE_BRICKS, Material.CHAIN, Material.SOUL_LANTERN, Material.SCULK};
                Material m = dark[random.nextInt(dark.length)];
                world.getBlockAt(x, floorY + 1, z).setType(m, false);
                if (m == Material.SOUL_SAND && maxClusterHeight >= 1 && random.nextBoolean())
                    world.getBlockAt(x, floorY + 2, z).setType(Material.SOUL_LANTERN, false);
            }
        }
        int lights = 2 + random.nextInt(3);
        for (int i = 0; i < lights; i++) {
            int x = bx + 2 + random.nextInt(12), z = bz + 2 + random.nextInt(12);
            Material light = (theme == 2 || theme == 5) ? Material.SOUL_LANTERN
                    : (theme == 3 || theme == 4 ? Material.LANTERN : Material.SEA_LANTERN);
            world.getBlockAt(x, ceiling, z).setType(light, false);
        }
    }

    private void buildWarehouse(World world, int bx, int bz, int level, int ceiling, Random random) {
        int levelIndex = level;
        int theme = themeFor(level);
        Material shelf = (theme == 2 || theme == 5) ? Material.POLISHED_BLACKSTONE_BRICKS
                : ((theme == 1 || theme == 3) ? Material.IRON_BLOCK : Material.SPRUCE_PLANKS);
        int shelfTop = Math.min(3, ceiling - floorY - 1);
        for (int row = 0; row < 2; row++) {
            int z = bz + 4 + row * 7;
            for (int x = bx + 3; x <= bx + 12; x++) {
                if (x == bx + 7 || x == bx + 8) continue;
                world.getBlockAt(x, floorY + 1, z).setType(shelf, false);
                if (shelfTop >= 1 && random.nextInt(100) < 65) {
                    Material storage = random.nextBoolean() ? Material.BARREL : Material.CHEST;
                    Block b = world.getBlockAt(x, floorY + 2, z);
                    b.setType(storage, false);
                    fillLoot(b, levelIndex, random);
                }
                if (shelfTop >= 2 && random.nextInt(100) < 45)
                    world.getBlockAt(x, floorY + 3, z).setType(theme == 0 ? Material.BOOKSHELF : Material.IRON_BARS, false);
            }
        }
        if (random.nextBoolean()) {
            Block crate = world.getBlockAt(bx + 6 + random.nextInt(4), floorY + 1, bz + 8);
            crate.setType(Material.CHEST, false);
            fillLoot(crate, levelIndex, random);
        }
    }

    private void buildOffice(World world, int bx, int bz, int level, int ceiling, Random random) {
        int levelIndex = level;
        int theme = themeFor(level);
        Material desk = (theme == 2 || theme == 5) ? Material.DARK_OAK_PLANKS : Material.OAK_PLANKS;
        for (int i = 0; i < 3; i++) {
            int x = bx + 3 + i * 4;
            int z = bz + (i % 2 == 0 ? 4 : 11);
            world.getBlockAt(x, floorY + 1, z).setType(desk, false);
            world.getBlockAt(x + 1, floorY + 1, z).setType(Material.OAK_STAIRS, false);
            if (floorY + 2 < ceiling)
                world.getBlockAt(x, floorY + 2, z).setType(random.nextBoolean() ? Material.LECTERN : Material.COMPOSTER, false);
            if (random.nextBoolean()) {
                Block filing = world.getBlockAt(x + 1, floorY + 1, z + 1);
                filing.setType(random.nextBoolean() ? Material.BARREL : Material.CHEST, false);
                fillLoot(filing, levelIndex, random);
            }
        }
        for (int x = bx + 3; x <= bx + 12; x += 3) {
            world.getBlockAt(x, floorY + 1, bz + 2).setType(theme == 1 ? Material.IRON_BLOCK : Material.BOOKSHELF, false);
        }
    }

    private void buildLibrary(World world, int bx, int bz, int level, int ceiling, Random random) {
        int levelIndex = level;
        int shelfTop = Math.min(2, ceiling - floorY - 1);
        for (int z : new int[]{bz + 3, bz + 5, bz + 11, bz + 13}) {
            for (int x = bx + 3; x <= bx + 12; x++) {
                if (x == bx + 7 || x == bx + 8) continue;
                world.getBlockAt(x, floorY + 1, z).setType(Material.BOOKSHELF, false);
                if (shelfTop >= 1 && random.nextBoolean())
                    world.getBlockAt(x, floorY + 2, z).setType(Material.BOOKSHELF, false);
            }
        }
        world.getBlockAt(bx + 7, floorY + 1, bz + 8).setType(Material.SPRUCE_PLANKS, false);
        world.getBlockAt(bx + 8, floorY + 1, bz + 8).setType(Material.SPRUCE_PLANKS, false);
        if (random.nextInt(100) < 70) {
            Block hidden = world.getBlockAt(bx + 10, floorY + 1, bz + 8);
            hidden.setType(random.nextBoolean() ? Material.CHEST : Material.BARREL, false);
            fillLoot(hidden, levelIndex, random);
        }
    }

    private void fillLoot(Block block, int level, Random random) {
        long lootSeed = random.nextLong();
        Bukkit.getScheduler().runTaskLater(this, () -> fillLootNow(block, level, new Random(lootSeed)), 2L);
    }

    private void populateContainersInChunk(World world, int chunkX, int chunkZ, int level) {
        int bx = chunkX << 4, bz = chunkZ << 4;
        int ceiling = getCeilingAt(bx + 8, bz + 8);
        for (int x = bx; x < bx + 16; x++) {
            for (int z = bz; z < bz + 16; z++) {
                for (int y = floorY + 1; y < ceiling; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getState() instanceof Container) {
                        fillLootNow(b, level, new Random(hash(x, z, level + 911)));
                    }
                }
            }
        }
    }

    private void fillLootNow(Block block, int level, Random random) {
        if (!(block.getState() instanceof Container container)) return;
        Inventory inv = container.getInventory();
        if (!(container instanceof TileState tile)) return;
        if (!inv.isEmpty()) {
            tile.getPersistentDataContainer().set(lootMarker, PersistentDataType.BYTE, (byte) 1);
            container.update(true, true);
            return;
        }
        inv.clear();
        int entries = Math.min(inv.getSize() - 1, 10 + random.nextInt(7));
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, random);
        for (int i = 0; i < entries; i++) {
            ItemStack stack = makeLootItem(level, random);
            if (stack != null && stack.getType() != Material.AIR) inv.setItem(slots.get(i), stack);
        }
        if (inv.getSize() > 1) inv.setItem(slots.get(inv.getSize() - 1), makeNote(level, random));
        tile.getPersistentDataContainer().set(lootMarker, PersistentDataType.BYTE, (byte) 1);
        container.update(true, true);
    }

    private ItemStack makeLootItem(int level, Random random) {
        int theme = themeFor(level);
        if (random.nextInt(100) < 14) return makeStoryBook(random.nextBoolean());
        if (random.nextInt(100) < 24) return makeNote(level, random);
        int lootLevel = level == 22 ? 11 : level / 2;
        Material[] pool = switch (lootLevel) {
            case 0 -> new Material[]{Material.BREAD, Material.COOKIE, Material.PAPER, Material.MAP, Material.COMPASS, Material.STRING, Material.LEATHER, Material.GLASS_BOTTLE, Material.COPPER_INGOT, Material.OAK_PLANKS};
            case 1 -> new Material[]{Material.BREAD, Material.PAPER, Material.BOOK, Material.IRON_NUGGET, Material.COPPER_INGOT, Material.STRING, Material.CARROT, Material.COOKIE, Material.FEATHER};
            case 2 -> new Material[]{Material.IRON_INGOT, Material.COPPER_INGOT, Material.REDSTONE, Material.RAIL, Material.COAL, Material.RAW_IRON, Material.FLINT, Material.LEVER, Material.REPEATER, Material.CHAIN};
            case 3 -> new Material[]{Material.REDSTONE, Material.IRON_INGOT, Material.COPPER_INGOT, Material.GUNPOWDER, Material.RAIL, Material.FURNACE, Material.IRON_NUGGET, Material.TORCH, Material.CHAIN};
            case 4 -> new Material[]{Material.BONE, Material.COAL, Material.STRING, Material.SPIDER_EYE, Material.ENDER_PEARL, Material.GOLD_NUGGET, Material.OBSIDIAN, Material.SCULK, Material.ARROW};
            case 5 -> new Material[]{Material.SOUL_SAND, Material.BONE, Material.GLOWSTONE_DUST, Material.BLACK_DYE, Material.CRYING_OBSIDIAN, Material.GOLD_INGOT, Material.ENDER_PEARL, Material.FERMENTED_SPIDER_EYE};
            case 6 -> new Material[]{Material.PAPER, Material.MAP, Material.COMPASS, Material.CLOCK, Material.WRITABLE_BOOK, Material.BOOK, Material.INK_SAC, Material.STRING};
            case 7 -> new Material[]{Material.IRON_INGOT, Material.COPPER_INGOT, Material.REDSTONE, Material.QUARTZ, Material.REPEATER, Material.COMPARATOR, Material.OBSERVER, Material.LEVER};
            case 8 -> new Material[]{Material.GOLD_NUGGET, Material.AMETHYST_SHARD, Material.GLOWSTONE_DUST, Material.SCULK, Material.ECHO_SHARD, Material.BONE, Material.ENDER_PEARL};
            case 9 -> new Material[]{Material.IRON_INGOT, Material.GOLD_INGOT, Material.REDSTONE, Material.ENDER_PEARL, Material.GHAST_TEAR, Material.PHANTOM_MEMBRANE, Material.CLOCK};
            case 10 -> new Material[]{Material.DIAMOND, Material.GOLD_INGOT, Material.OBSIDIAN, Material.ENDER_PEARL, Material.BLAZE_POWDER, Material.GLOWSTONE_DUST, Material.SCULK_CATALYST};
            default -> new Material[]{Material.DIAMOND, Material.ECHO_SHARD, Material.NETHERITE_SCRAP, Material.GOLD_INGOT, Material.CRYING_OBSIDIAN, Material.TOTEM_OF_UNDYING, Material.ENDER_PEARL, Material.NETHERITE_INGOT, Material.ENCHANTED_GOLDEN_APPLE};
        };
        Material material = pool[random.nextInt(pool.length)];
        ItemStack item = new ItemStack(material, 1 + random.nextInt(theme >= 4 ? 3 : 8));
        ItemMeta meta = item.getItemMeta();
        if (meta != null && (material == Material.PAPER || material == Material.BOOK || material == Material.WRITABLE_BOOK)) {
            meta.setDisplayName(random.nextBoolean() ? ChatColor.GRAY + "Обрывок системного журнала" : ChatColor.DARK_RED + "Заражённый фрагмент данных");
            meta.setLore(List.of(ChatColor.DARK_GRAY + "Найдено: " + levelName(level), ChatColor.GRAY + "Содержимое может быть повреждено."));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeNote(int level, Random random) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            boolean virus = random.nextBoolean();
            meta.setDisplayName((virus ? ChatColor.DARK_RED : ChatColor.AQUA) + (virus ? "[ВИРУС] Перехваченная записка" : "[СИСТЕМА] Отчёт восстановления"));
            List<String> lines = new ArrayList<>();
            lines.add(ChatColor.GRAY + "Источник: " + (virus ? "UNKNOWN.EXE" : "BACKROOMS OS"));
            lines.add(ChatColor.DARK_GRAY + "Уровень: " + levelName(level));
            meta.setLore(lines);
            paper.setItemMeta(meta);
        }
        return paper;
    }

    private ItemStack makeStoryBook(boolean driver) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            if (driver) {
                meta.setTitle("ДРАЙВЕР // KERNEL");
                meta.setAuthor("BACKROOMS SYSTEM");
                meta.setPages(List.of(
                    "[СИСТЕМА: ДРАЙВЕР]\n\nУстройство обнаружено: LIMINAL_NODE_07\nСтатус: нестабилен\n\nКод восстановления:\nBR-07-4F-A19C\n\nНе запускайте модуль повторно без резервной копии.",
                    "// driver_patch.sys\nLOAD SECTOR 07\nCHECKSUM: 9A-00-BC-17\n\nЕсли коридор повторяется трижды — драйвер не найден. Ищите терминал рядом с работающим светом."
                ));
            } else {
                meta.setTitle("АНТИВИРУС // QUARANTINE");
                meta.setAuthor("UNKNOWN PROCESS");
                meta.setPages(List.of(
                    "[АНТИВИРУС: КАРАНТИН]\n\nОбнаружена угроза: MIRROR.VIR\nУровень риска: КРИТИЧЕСКИЙ\n\nКлюч сигнатуры:\nVIR-13-0X-NULL\n\nНе доверяйте сообщениям, которые приходят из пустых комнат.",
                    "scan --deep /level/*\nquarantine --isolate MIRROR.VIR\nrestore --safe-mode\n\nОШИБКА: процесс уже имеет права администратора.\n\nЕсли вы читаете это, не подключайте неизвестные диски."
                ));
            }
            book.setItemMeta(meta);
        }
        return book;
    }

    private int themeFor(int levelIndex) {
        if (levelIndex == 22) return 5;
        return Math.max(0, Math.min(5, (levelIndex / 2) % 6));
    }

    private String levelName(int levelIndex) {
        if (levelIndex == 22) return "EXIT — единственный уровень-выход";
        int main = levelIndex / 2;
        return "Уровень " + main + (levelIndex % 2 == 1 ? ".1 (подуровень)" : "");
    }

    private Location findLevelLocation(World world, int wantedLevel, Location near) {
        Random random = new Random(System.nanoTime() ^ (near.getBlockX() * 31L) ^ near.getBlockZ());
        int nearRegionX = Math.floorDiv(near.getBlockX() - originX, levelSpacing);
        int nearRegionZ = Math.floorDiv(near.getBlockZ() - originZ, levelSpacing);
        for (int i = 0; i < 5000; i++) {
            int rx = nearRegionX + random.nextInt(401) - 200;
            int rz = nearRegionZ + random.nextInt(401) - 200;
            int sampleX = originX + rx * levelSpacing + levelSpacing / 2;
            int sampleZ = originZ + rz * levelSpacing + levelSpacing / 2;
            if (getLevelAt(sampleX, sampleZ) == wantedLevel) {
                return new Location(world, sampleX + random.nextInt(9) - 4 + 0.5, floorY + 1, sampleZ + random.nextInt(9) - 4 + 0.5, 0f, 0f);
            }
        }
        for (int radius = 0; radius < 2000; radius++) {
            int rx = nearRegionX + radius;
            int rz = nearRegionZ + (radius * 37 % 1999) - 999;
            int sampleX = originX + rx * levelSpacing + levelSpacing / 2;
            int sampleZ = originZ + rz * levelSpacing + levelSpacing / 2;
            if (getLevelAt(sampleX, sampleZ) == wantedLevel)
                return new Location(world, sampleX + 0.5, floorY + 1, sampleZ + 0.5, 0f, 0f);
        }
        return null;
    }

    private Material wallForLevel(int level) {
        return switch (level) {
            case 0 -> Material.YELLOW_TERRACOTTA;
            case 1 -> Material.MOSSY_COBBLESTONE;
            case 2 -> Material.SMOOTH_STONE;
            case 3 -> Material.CUT_COPPER;
            case 4 -> Material.MOSS_BLOCK;
            case 5 -> Material.POLISHED_BLACKSTONE_BRICKS;
            case 6 -> Material.OAK_PLANKS;
            case 7 -> Material.IRON_BLOCK;
            case 8 -> Material.BOOKSHELF;
            case 9 -> Material.RED_NETHER_BRICKS;
            case 10 -> Material.PACKED_ICE;
            case 11 -> Material.BLUE_ICE;
            case 12 -> Material.PRISMARINE_BRICKS;
            case 13 -> Material.DARK_PRISMARINE;
            case 14 -> Material.BONE_BLOCK;
            case 15 -> Material.NETHER_BRICKS;
            case 16 -> Material.END_STONE_BRICKS;
            case 17 -> Material.PURPUR_BLOCK;
            case 18 -> Material.CALCITE;
            case 19 -> Material.TUFF_BRICKS;
            case 20 -> Material.DEEPSLATE_BRICKS;
            case 21 -> Material.CRYING_OBSIDIAN;
            default -> Material.POLISHED_BLACKSTONE_BRICKS;
        };
    }

    private Material floorForLevel(int level) {
        return switch (level) {
            case 0 -> Material.YELLOW_WOOL;
            case 1 -> Material.MOSSY_STONE_BRICKS;
            case 2 -> Material.GRAY_CONCRETE;
            case 3 -> Material.EXPOSED_COPPER;
            case 4 -> Material.MOSS_BLOCK;
            case 5 -> Material.BLACKSTONE;
            case 6 -> Material.SPRUCE_PLANKS;
            case 7 -> Material.IRON_BLOCK;
            case 8 -> Material.OAK_PLANKS;
            case 9 -> Material.RED_CONCRETE;
            case 10 -> Material.SNOW_BLOCK;
            case 11 -> Material.BLUE_ICE;
            case 12 -> Material.PRISMARINE;
            case 13 -> Material.WATER;
            case 14 -> Material.BONE_BLOCK;
            case 15 -> Material.SOUL_SOIL;
            case 16 -> Material.END_STONE;
            case 17 -> Material.PURPUR_BLOCK;
            case 18 -> Material.CALCITE;
            case 19 -> Material.MUD_BRICKS;
            case 20 -> Material.POLISHED_DEEPSLATE;
            case 21 -> Material.MAGMA_BLOCK;
            default -> Material.BLACKSTONE;
        };
    }

        private Material ceilingForLevel(int level) {
        return switch (level) {
            case 0 -> Material.SMOOTH_SANDSTONE;
            case 1 -> Material.MOSSY_COBBLESTONE;
            case 2 -> Material.IRON_BLOCK;
            case 3 -> Material.COPPER_BLOCK;
            case 4 -> Material.OAK_LEAVES;
            case 5 -> Material.POLISHED_BLACKSTONE;
            case 6 -> Material.SPRUCE_PLANKS;
            case 7 -> Material.CHAIN;
            case 8 -> Material.BOOKSHELF;
            case 9 -> Material.NETHER_BRICKS;
            case 10 -> Material.SNOW_BLOCK;
            case 11 -> Material.BLUE_ICE;
            case 12 -> Material.PRISMARINE;
            case 13 -> Material.DARK_PRISMARINE;
            case 14 -> Material.BONE_BLOCK;
            case 15 -> Material.NETHERRACK;
            case 16 -> Material.END_STONE_BRICKS;
            case 17 -> Material.PURPUR_PILLAR;
            case 18 -> Material.CALCITE;
            case 19 -> Material.TUFF_BRICKS;
            case 20 -> Material.DEEPSLATE_TILES;
            case 21 -> Material.OBSIDIAN;
            default -> Material.POLISHED_BLACKSTONE;
        };
    }

    private Material accentForTheme(int theme) {
        return switch (theme) {
            case 1 -> Material.IRON_BLOCK;
            case 2 -> Material.POLISHED_BLACKSTONE_BRICKS;
            case 3 -> Material.CUT_COPPER;
            case 4 -> Material.MOSS_BLOCK;
            case 5 -> Material.CRYING_OBSIDIAN;
            default -> Material.BROWN_TERRACOTTA;
        };
    }


    /**
     * Уникальная сигнатура уровня. ВАЖНО: больше НЕ строим сетку столбов —
     * каждый уровень получает узнаваемый, но не заполняющий комнату объект.
     */
    private void addLevelSignature(World world, int bx, int bz, int level, int ceiling, Random random) {
        int cx = bx + 8, cz = bz + 8, y = floorY + 1;
        int freeHeight = ceiling - floorY - 1;
        int maxH = Math.min(5, freeHeight);
        switch (level) {
            case 0 -> {
                // Жёлтый офис: две тонкие стенки-кубиклы, а не лес столбов.
                int wallTop = floorY + 1 + Math.min(3, freeHeight);
                for (int x = bx + 4; x <= bx + 11; x++) {
                    if (x == bx + 7 || x == bx + 8) continue;
                    for (int yy = floorY + 1; yy < wallTop; yy++)
                        world.getBlockAt(x, yy, bz + 6).setType(Material.YELLOW_TERRACOTTA, false);
                }
                for (int z = bz + 4; z <= bz + 11; z++) {
                    if (z == bz + 7 || z == bz + 8) continue;
                    for (int yy = floorY + 1; yy < wallTop; yy++)
                        world.getBlockAt(bx + 11, yy, z).setType(Material.YELLOW_TERRACOTTA, false);
                }
                // Одинокая лампа и стул — пустая комната страшнее заполненной.
                world.getBlockAt(cx, floorY + 1, cz).setType(Material.OAK_STAIRS, false);
            }
            case 1 -> { // влажный подуровень: лужи + один сломанный столб
                for (int i = 0; i < 5; i++)
                    patch(world, bx + 2 + random.nextInt(11), bz + 2 + random.nextInt(11), y, Material.WATER, 2);
                pillar(world, cx, cz, y, Material.MOSSY_COBBLESTONE, Math.min(4, maxH));
            }
            case 2 -> { // машинный зал: два столба по бокам, не сетка
                for (int x : new int[]{bx + 4, bx + 11}) {
                    pillar(world, x, cz, y, Material.IRON_BLOCK, Math.min(4, maxH));
                    if (floorY + 5 < ceiling) world.getBlockAt(x, floorY + 5, cz).setType(Material.CHAIN, false);
                }
            }
            case 3 -> { // медный реле с красной проводкой на полу
                for (int i = -3; i <= 3; i++) {
                    world.getBlockAt(cx + i, y, cz).setType(Material.REDSTONE_BLOCK, false);
                    world.getBlockAt(cx, y, cz + i).setType(Material.CUT_COPPER, false);
                }
                if (floorY + 2 < ceiling) world.getBlockAt(cx, y + 1, cz).setType(Material.LIGHTNING_ROD, false);
            }
            case 4 -> { // оранжерея: мох, листья, лозы (короткие)
                for (int i = 0; i < 6; i++) {
                    int x = bx + 2 + random.nextInt(12), z = bz + 2 + random.nextInt(12);
                    world.getBlockAt(x, y, z).setType(Material.MOSS_BLOCK, false);
                    if (floorY + 2 < ceiling) world.getBlockAt(x, y + 1, z).setType(Material.OAK_LEAVES, false);
                    if (floorY + 3 < ceiling) world.getBlockAt(x, y + 2, z).setType(Material.VINE, false);
                }
            }
            case 5 -> { // ритуальный зал: круг из 8 столбиков, но низких
                int h = Math.min(3, maxH);
                for (int i = 0; i < 8; i++) {
                    int px = cx + (int)Math.round(3 * Math.cos(i * Math.PI / 4));
                    int pz = cz + (int)Math.round(3 * Math.sin(i * Math.PI / 4));
                    pillar(world, px, pz, y, Material.CRYING_OBSIDIAN, h);
                }
                world.getBlockAt(cx, y, cz).setType(Material.SOUL_SAND, false);
            }
            case 6 -> { // лабиринт кубиклов — низкие перегородки 2 блока
                for (int x = bx + 3; x < bx + 13; x += 3)
                    for (int z = bz + 3; z < bz + 13; z += 4) {
                        world.getBlockAt(x, y, z).setType(Material.OAK_PLANKS, false);
                        if (floorY + 2 < ceiling) world.getBlockAt(x, y + 1, z).setType(Material.OAK_PLANKS, false);
                    }
            }
            case 7 -> { // блок тюремных камер — решётки по краям
                int h = Math.min(3, maxH);
                for (int x : new int[]{bx + 3, bx + 12})
                    for (int z = bz + 3; z < bz + 13; z++)
                        for (int yy = y; yy < y + h; yy++)
                            world.getBlockAt(x, yy, z).setType(Material.IRON_BARS, false);
                world.getBlockAt(cx, y, cz).setType(Material.IRON_DOOR, false);
            }
            case 8 -> { // архивы: книжные полки вдоль двух стен
                for (int x = bx + 3; x < bx + 13; x += 2) {
                    world.getBlockAt(x, y, bz + 3).setType(Material.BOOKSHELF, false);
                    world.getBlockAt(x, y, bz + 12).setType(Material.BOOKSHELF, false);
                }
            }
            case 9 -> { // красный лабиринт: одна перегородка, а не стена через всю комнату
                int h = Math.min(3, maxH);
                for (int z = bz + 3; z < bz + 13; z++) {
                    if (z % 3 == 0) continue;
                    for (int yy = y; yy < y + h; yy++)
                        world.getBlockAt(cx, yy, z).setType(Material.RED_NETHER_BRICKS, false);
                }
            }
            case 10 -> { // ледяной: несколько ледяных пятен
                for (int i = 0; i < 7; i++)
                    patch(world, bx + 2 + random.nextInt(12), bz + 2 + random.nextInt(12), y, Material.PACKED_ICE, 2);
            }
            case 11 -> { // синий лёд: тонкие колонны 4 шт.
                int h = Math.min(4, maxH);
                for (int x = bx + 3; x < bx + 13; x += 3) pillar(world, x, cz, y, Material.BLUE_ICE, h);
            }
            case 12 -> { // затопленный храм — вода только в шахматном порядке
                for (int x = bx + 4; x <= bx + 11; x++)
                    for (int z = bz + 4; z <= bz + 11; z++)
                        if ((x + z) % 3 == 0) world.getBlockAt(x, y, z).setType(Material.WATER, false);
                for (int x : new int[]{bx + 3, bx + 12})
                    for (int z : new int[]{bz + 3, bz + 12})
                        pillar(world, x, z, y, Material.PRISMARINE_BRICKS, Math.min(4, maxH));
            }
            case 13 -> { // тёмный призмарин: приподнятые дорожки
                for (int z = bz + 3; z <= bz + 12; z++)
                    for (int x = bx + 3; x <= bx + 12; x++)
                        if (x % 3 == 0) world.getBlockAt(x, floorY, z).setType(Material.DARK_PRISMARINE, false);
            }
            case 14 -> { // костница: 4 колонны
                int h = Math.min(4, maxH);
                for (int i = 0; i < 4; i++)
                    pillar(world, bx + 4 + i * 3, bz + 4 + (i % 2) * 6, y, Material.BONE_BLOCK, h);
            }
            case 15 -> { // адский подуровень — магма-пятна
                for (int i = 0; i < 5; i++)
                    patch(world, bx + 2 + random.nextInt(12), bz + 2 + random.nextInt(12), floorY, Material.MAGMA_BLOCK, 2);
            }
            case 16 -> { // энд-геометрия: 4 колонны
                int h = Math.min(5, maxH);
                for (int i = 0; i < 4; i++)
                    pillar(world, bx + 3 + i * 3, bz + 4 + (i % 2) * 6, y, Material.END_STONE_BRICKS, h);
            }
            case 17 -> { // пурпур: висящие колонны
                for (int x = bx + 3; x < bx + 13; x += 3) {
                    if (floorY + 3 < ceiling) world.getBlockAt(x, y + 2, cz).setType(Material.PURPUR_PILLAR, false);
                    if (floorY + 2 < ceiling) world.getBlockAt(x, y + 1, cz).setType(Material.CHAIN, false);
                }
            }
            case 18 -> { // кальцитовый лабиринт: одна стенка с проходом
                int h = Math.min(3, maxH);
                for (int z = bz + 3; z < bz + 13; z += 3)
                    for (int yy = y; yy < y + h; yy++)
                        world.getBlockAt(cx, yy, z).setType(Material.CALCITE, false);
            }
            case 19 -> { // грязь и туф
                for (int i = 0; i < 7; i++)
                    patch(world, bx + 2 + random.nextInt(12), bz + 2 + random.nextInt(12), y, Material.MUD, 2);
            }
            case 20 -> { // глубокие катакомбы: 4 столба
                int h = Math.min(5, maxH);
                for (int x : new int[]{bx + 4, bx + 11})
                    for (int z : new int[]{bz + 4, bz + 11})
                        pillar(world, x, z, y, Material.DEEPSLATE_BRICKS, h);
            }
            case 21 -> { // опасная магма
                for (int i = 0; i < 6; i++)
                    patch(world, bx + 2 + random.nextInt(12), bz + 2 + random.nextInt(12), floorY, Material.MAGMA_BLOCK, 2);
                pillar(world, cx, cz, y, Material.CRYING_OBSIDIAN, Math.min(5, maxH));
            }
            case 22 -> { // EXIT — маяк с золотой рамкой
                int h = Math.min(5, maxH);
                pillar(world, cx, cz, y, Material.QUARTZ_PILLAR, h);
                if (floorY + h + 1 < ceiling) world.getBlockAt(cx, y + h, cz).setType(Material.BEACON, false);
                for (int x = cx - 3; x <= cx + 3; x++)
                    for (int z = cz - 3; z <= cz + 3; z++)
                        if (Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3)
                            world.getBlockAt(x, y, z).setType(Material.GOLD_BLOCK, false);
            }
        }
    }

    private void pillar(World world, int x, int z, int y, Material material, int height) {
        for (int yy = 0; yy < height; yy++) world.getBlockAt(x, y + yy, z).setType(material, false);
    }

    private void patch(World world, int x, int z, int y, Material material, int radius) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                if (dx * dx + dz * dz <= radius * radius)
                    world.getBlockAt(x + dx, y, z + dz).setType(material, false);
    }

    private boolean edgeOpen(int boundaryChunkX, int boundaryChunkZ, int axis) {
        return Math.floorMod(hash(boundaryChunkX, boundaryChunkZ, axis), 5) != 0;
    }

    private void wallColumn(World world, int x, int z, Material wall, int ceiling) {
        for (int y = floorY + 1; y < ceiling; y++) world.getBlockAt(x, y, z).setType(wall, false);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!generationEnabled || !player.getWorld().getName().equalsIgnoreCase(worldName)) return;
        Block block = event.getBlock();
        if (block.getY() <= floorY || block.getY() >= baseCeilingY + 6) {
            event.setCancelled(true);
            return;
        }
        if (!isWall(block.getType())) return;
        final Block broken = block;
        Bukkit.getScheduler().runTask(this, () -> generateRoomBehind(broken));
        if (player.isOp()) player.sendMessage(ChatColor.GRAY + "[Backrooms] За проломом появляется новая комната.");
    }

    private void generateRoomBehind(Block opening) {
        World world = opening.getWorld();
        int dx = 0, dz = 0;
        int localX = Math.floorMod(opening.getX(), 16);
        int localZ = Math.floorMod(opening.getZ(), 16);
        if (localX == 0) dx = -1;
        else if (localX == 15) dx = 1;
        else if (localZ == 0) dz = -1;
        else if (localZ == 15) dz = 1;
        else {
            boolean alongX = isWall(opening.getRelative(-1, 0, 0).getType()) || isWall(opening.getRelative(1, 0, 0).getType());
            boolean alongZ = isWall(opening.getRelative(0, 0, -1).getType()) || isWall(opening.getRelative(0, 0, 1).getType());
            if (alongX && !alongZ) dz = 1;
            else if (alongZ && !alongX) dx = 1;
            else if (alongX) dz = 1;
            else dx = 1;
        }

        int level = getLevelAt(opening.getX(), opening.getZ());
        Random random = new Random(hash(opening.getX(), opening.getZ(), level + 71));
        int theme = themeFor(level);
        Material floor = floorForLevel(level);
        Material wall = wallForLevel(level);
        Material ceilingMat = ceilingForLevel(level);

        // Высота потолка берётся из чанка, в который ведёт пролом.
        int probeX = opening.getX() + dx * 8;
        int probeZ = opening.getZ() + dz * 8;
        int ceiling = getCeilingAt(probeX, probeZ);

        boolean corridor = random.nextInt(100) < 42;
        int depthMax = corridor ? 26 + random.nextInt(13) : 13;
        int halfWidth = corridor ? (random.nextBoolean() ? 2 : 3) : 6;

        for (int depth = 1; depth <= depthMax; depth++) {
            for (int side = -halfWidth; side <= halfWidth; side++) {
                int x = opening.getX() + dx * depth + (dz != 0 ? side : 0);
                int z = opening.getZ() + dz * depth + (dx != 0 ? side : 0);
                world.getBlockAt(x, floorY, z).setType(floor, false);
                world.getBlockAt(x, ceiling, z).setType(ceilingMat, false);
                for (int y = floorY + 1; y < ceiling; y++) {
                    boolean sideWall = Math.abs(side) == halfWidth;
                    boolean farWall = !corridor && depth == depthMax;
                    boolean nearWall = depth == 1 && side != 0;
                    if (sideWall || farWall || nearWall) world.getBlockAt(x, y, z).setType(wall, false);
                    else world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        if (corridor) {
            int branches = 1 + random.nextInt(3);
            for (int b = 0; b < branches; b++) {
                int branchDepth = 5 + random.nextInt(Math.max(1, depthMax - 9));
                int sideDir = random.nextBoolean() ? 1 : -1;
                int startX = opening.getX() + dx * branchDepth;
                int startZ = opening.getZ() + dz * branchDepth;
                int branchLen = 8 + random.nextInt(9);
                for (int length = 1; length <= branchLen; length++) {
                    for (int side = -1; side <= 1; side++) {
                        int x = startX + (dz != 0 ? sideDir * length : side);
                        int z = startZ + (dx != 0 ? sideDir * length : side);
                        world.getBlockAt(x, floorY, z).setType(floor, false);
                        world.getBlockAt(x, ceiling, z).setType(ceilingMat, false);
                        for (int y = floorY + 1; y < ceiling; y++) {
                            boolean edge = Math.abs(side) == 1;
                            if (edge) world.getBlockAt(x, y, z).setType(wall, false);
                            else world.getBlockAt(x, y, z).setType(Material.AIR, false);
                        }
                    }
                }
            }
        } else {
            int cx = opening.getX() + dx * 7;
            int cz = opening.getZ() + dz * 7;
            if (random.nextBoolean()) {
                Material accent = theme >= 2 ? Material.CHAIN : Material.OAK_LOG;
                int hMax = Math.min(3, ceiling - floorY - 1);
                for (int y = floorY + 1; y < floorY + 1 + hMax; y++)
                    world.getBlockAt(cx, y, cz).setType(accent, false);
            } else {
                world.getBlockAt(cx, floorY + 1, cz).setType(theme >= 2 ? Material.RED_CARPET : Material.BROWN_CARPET, false);
            }
            addRoomDecor(world, cx - 8, cz - 8, level, ceiling, random, accentForTheme(theme));
        }
        int lightX = opening.getX() + dx * Math.min(depthMax / 2, 8);
        int lightZ = opening.getZ() + dz * Math.min(depthMax / 2, 8);
        world.getBlockAt(lightX, ceiling, lightZ).setType(Material.SEA_LANTERN, false);
    }

    private boolean isWall(Material m) {
        return m == Material.YELLOW_TERRACOTTA || m == Material.SMOOTH_STONE
                || m == Material.DEEPSLATE_BRICKS || m == Material.GRAY_CONCRETE
                || m == Material.BRICKS || m == Material.MOSSY_COBBLESTONE
                || m == Material.POLISHED_BLACKSTONE_BRICKS || m == Material.CUT_COPPER
                || m == Material.OAK_PLANKS || m == Material.IRON_BLOCK
                || m == Material.BOOKSHELF || m == Material.RED_NETHER_BRICKS
                || m == Material.PACKED_ICE || m == Material.BLUE_ICE
                || m == Material.PRISMARINE_BRICKS || m == Material.DARK_PRISMARINE
                || m == Material.BONE_BLOCK || m == Material.NETHER_BRICKS
                || m == Material.END_STONE_BRICKS || m == Material.PURPUR_BLOCK
                || m == Material.CALCITE || m == Material.TUFF_BRICKS
                || m == Material.CRYING_OBSIDIAN || m == Material.MOSS_BLOCK;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!generationEnabled || !p.getWorld().getName().equalsIgnoreCase(worldName)) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepLevel(true);

        Location bed = p.getBedSpawnLocation();
        if (bed != null && bed.getWorld() != null) {
            returnLocations.put(p.getUniqueId(), bed);
            returnWorlds.put(p.getUniqueId(), bed.getWorld().getName());
        } else {
            Location fallback = returnLocations.get(p.getUniqueId());
            if (fallback == null || fallback.getWorld() == null) {
                fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            returnLocations.put(p.getUniqueId(), fallback);
            returnWorlds.put(p.getUniqueId(), fallback.getWorld().getName());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Location target = returnLocations.get(p.getUniqueId());
        if (target != null && target.getWorld() != null) {
            event.setRespawnLocation(target);
            Bukkit.getScheduler().runTask(this, () -> {
                returnLocations.remove(p.getUniqueId());
                returnWorlds.remove(p.getUniqueId());
            });
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (event.getFrom().getName().equalsIgnoreCase(worldName)
                && !p.getWorld().getName().equalsIgnoreCase(worldName)) {
            returnLocations.put(p.getUniqueId(), p.getLocation());
        }
    }

    private int getLevelAt(int x, int z) {
        int regionX = Math.floorDiv(x - originX, levelSpacing);
        int regionZ = Math.floorDiv(z - originZ, levelSpacing);
        if (Math.floorMod(hash(regionX, regionZ, 77123), 1000) == 0) return 22;
        return Math.floorMod((int) Math.floorMod(hash(regionX, regionZ, 991), 22), 22);
    }

    private long hash(int x, int z, int salt) {
        long v = ((long)x * 341873128712L) ^ ((long)z * 132897987541L) ^ ((long)salt * 42317861L);
        v ^= v >>> 13;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 16;
        return v;
    }
}
