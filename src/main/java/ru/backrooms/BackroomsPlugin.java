package ru.backrooms;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Barrel;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class BackroomsPlugin extends JavaPlugin implements Listener {
    private final Map<Integer, Location> levelSpawns = new TreeMap<>();
    private final Map<String, Integer> portalTargets = new HashMap<>();
    private final Set<UUID> portalCooldown = new HashSet<>();
    private BukkitTask generationTask;
    private boolean generating;
    private int generated;
    private World world;
    private int levelCount, spacing, size;
    private final Map<Integer, Location> levelOrigins = new TreeMap<>();
    private final Map<String, Integer> blockLevels = new HashMap<>();
    private final Set<String> protectedFloorCeilings = new HashSet<>();
    private final Set<String> expandedWalls = new HashSet<>();
    private final Set<String> expandedDoors = new HashSet<>();
    private final Set<UUID> diedInBackrooms = new HashSet<>();
    private final Set<UUID> respawnHomeRoll = new HashSet<>();

    private enum Theme { YELLOW, INDUSTRIAL, OFFICES, HOTEL, POOLS, RED, SCHOOL, GARDEN, SUBURB, DARK }

    @Override public void onEnable() {
        saveDefaultConfig();
        world = Bukkit.getWorld(getConfig().getString("world-name", "world"));
        levelCount = Math.max(1, Math.min(40, getConfig().getInt("level-count", 12)));
        spacing = Math.max(180, getConfig().getInt("level-spacing", 320));
        size = Math.max(50, Math.min(150, getConfig().getInt("level-size", 90)));
        Bukkit.getPluginManager().registerEvents(this, this);
        if (world == null) getLogger().warning("World not found. Set world-name to an existing world in config.yml.");
    }

    @Override public void onDisable() { if (generationTask != null) generationTask.cancel(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) return true;
        if (args.length == 0) { player.sendMessage(ChatColor.YELLOW + "/br generate | stop | info | level <номер> | home <ник>"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "generate" -> startGeneration(player);
            case "stop" -> {
                if (generationTask != null) generationTask.cancel();
                generationTask = null; generating = false;
                player.sendMessage(ChatColor.RED + "Генерация остановлена. Создано уровней: " + generated);
            }
            case "info" -> player.sendMessage(ChatColor.GOLD + "Backrooms: " + (generating ? "генерация" : "ожидание") + "; уровней: " + generated + "; мир: " + (world == null ? "не найден" : world.getName()));
            case "home" -> {
                if (args.length < 2) { player.sendMessage(ChatColor.YELLOW + "Использование: /br home <ник>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { player.sendMessage(ChatColor.RED + "Игрок должен быть в сети."); return true; }
                Location home = getHome(target);
                target.teleport(home);
                target.sendMessage(ChatColor.GREEN + "Администратор отправил тебя домой.");
                player.sendMessage(ChatColor.GREEN + "Игрок " + target.getName() + " отправлен домой.");
            }
            case "level" -> {
                if (args.length < 2) { player.sendMessage(ChatColor.YELLOW + "Использование: /br level <номер>"); return true; }
                try {
                    int requested=Math.max(1,Integer.parseInt(args[1]));
                    if (requested>generated+1) { player.sendMessage(ChatColor.RED + "Сначала открой предыдущие уровни через переходы. Следующий доступный: "+(generated+1)); return true; }
                    if (!levelSpawns.containsKey(requested) && requested==generated+1) generateNextLevel(requested);
                    Location loc=levelSpawns.get(requested);
                    if (loc==null) player.sendMessage(ChatColor.RED + "Уровень не найден. Сначала /br generate.");
                    else { player.teleport(loc); player.sendMessage(ChatColor.GREEN + "Телепорт на уровень "+requested+"."); }
                } catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Номер уровня должен быть числом."); }
            }
            default -> player.sendMessage(ChatColor.YELLOW + "/br generate | stop | info | level <номер>");
        }
        return true;
    }

    private Location getHome(Player player) {
        Location bed = player.getBedSpawnLocation();
        if (bed != null) return bed;
        return player.getWorld().getSpawnLocation();
    }

    private boolean isInBackrooms(Location location) {
        if (location == null || world == null || location.getWorld() != world) return false;
        for (Location origin : levelOrigins.values()) {
            if (Math.abs(location.getX()-origin.getX()) <= size/2.0 + 100
                    && Math.abs(location.getZ()-origin.getZ()) <= size/2.0 + 100
                    && Math.abs(location.getY()-origin.getY()) <= 30) return true;
        }
        return false;
    }

    @EventHandler public void onBackroomsDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isInBackrooms(player.getLocation())) return;
        // Keep inventory and XP for every death inside the Backrooms.
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        diedInBackrooms.add(player.getUniqueId());
        if (ThreadLocalRandom.current().nextBoolean()) respawnHomeRoll.add(player.getUniqueId());
        else respawnHomeRoll.remove(player.getUniqueId());
    }

    @EventHandler public void onBackroomsRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!diedInBackrooms.remove(id)) return;
        if (respawnHomeRoll.remove(id)) {
            event.setRespawnLocation(getHome(event.getPlayer()));
            Bukkit.getScheduler().runTask(this, () -> event.getPlayer().sendMessage(ChatColor.GREEN + "Тебе повезло: после смерти ты вернулся домой. Инвентарь и опыт сохранены."));
        } else {
            Bukkit.getScheduler().runTask(this, () -> event.getPlayer().sendMessage(ChatColor.YELLOW + "После смерти ты остался в мире Backrooms. Инвентарь и опыт сохранены."));
        }
    }

    private void startGeneration(Player player) {
        if (world == null) world = Bukkit.getWorld(getConfig().getString("world-name", "world"));
        if (world == null) { player.sendMessage(ChatColor.RED + "Мир не найден. Укажи существующий мир в config.yml."); return; }
        if (generating) { player.sendMessage(ChatColor.RED + "Генерация уже идёт."); return; }
        generating = true; generated = 0; levelSpawns.clear(); levelOrigins.clear(); portalTargets.clear(); blockLevels.clear(); protectedFloorCeilings.clear(); expandedWalls.clear(); expandedDoors.clear();
        player.sendMessage(ChatColor.GREEN + "Начинаю генерацию " + levelCount + " уровней...");
        final int[] i = {0};
        generationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!generating || i[0] >= levelCount) {
                if (generationTask != null) generationTask.cancel();
                generationTask = null; generating = false;
                player.sendMessage(ChatColor.GREEN + "Готово. Создано уровней: " + generated); return;
            }
            int n = i[0] + 1, x = 1000 + (i[0] % 4) * spacing, z = 1000 + (i[0] / 4) * spacing;
            int y = world.getHighestBlockYAt(x, z) + 2;
            if (!getConfig().getBoolean("place-above-ground", true)) y = Math.max(20, y - 35);
            Location origin = new Location(world, x, y, z);
            buildLevel(origin, n, Theme.values()[i[0] % Theme.values().length]);
            registerLevelBlocks(origin, n);
            levelSpawns.put(n, origin.clone().add(0, 1, 0)); levelOrigins.put(n, origin.clone()); generated++; i[0]++;
        }, 1L, 2L);
    }

    private void buildLevel(Location o, int level, Theme theme) {
        int ox=o.getBlockX(), oy=o.getBlockY(), oz=o.getBlockZ(), radius=size/2;
        Material floor, wall, ceiling, lamp;
        switch (theme) {
            case INDUSTRIAL -> { floor=Material.GRAY_CONCRETE; wall=Material.LIGHT_GRAY_CONCRETE; ceiling=Material.GRAY_CONCRETE; lamp=Material.SEA_LANTERN; }
            case OFFICES -> { floor=Material.GRAY_CARPET; wall=Material.WHITE_CONCRETE; ceiling=Material.QUARTZ_BLOCK; lamp=Material.SEA_LANTERN; }
            case HOTEL -> { floor=Material.RED_CARPET; wall=Material.TERRACOTTA; ceiling=Material.SMOOTH_QUARTZ; lamp=Material.SEA_LANTERN; }
            case POOLS -> { floor=Material.PRISMARINE_BRICKS; wall=Material.QUARTZ_BLOCK; ceiling=Material.SMOOTH_QUARTZ; lamp=Material.SEA_LANTERN; }
            case RED -> { floor=Material.RED_CONCRETE; wall=Material.BLACKSTONE; ceiling=Material.BLACKSTONE; lamp=Material.REDSTONE_LAMP; }
            case SCHOOL -> { floor=Material.SMOOTH_STONE; wall=Material.YELLOW_TERRACOTTA; ceiling=Material.WHITE_CONCRETE; lamp=Material.SEA_LANTERN; }
            case GARDEN -> { floor=Material.GRASS_BLOCK; wall=Material.WHITE_CONCRETE; ceiling=Material.OAK_LEAVES; lamp=Material.GLOWSTONE; }
            case SUBURB -> { floor=Material.GRASS_BLOCK; wall=Material.BIRCH_PLANKS; ceiling=Material.OAK_PLANKS; lamp=Material.LANTERN; }
            case DARK -> { floor=Material.DEEPSLATE_BRICKS; wall=Material.DEEPSLATE_TILES; ceiling=Material.DEEPSLATE_BRICKS; lamp=Material.SOUL_LANTERN; }
            default -> { floor=Material.YELLOW_WOOL; wall=Material.YELLOW_TERRACOTTA; ceiling=Material.SMOOTH_SANDSTONE; lamp=Material.SEA_LANTERN; }
        }
        for (int x=-radius; x<=radius; x++) for (int z=-radius; z<=radius; z++) {
            for (int y=0; y<=7; y++) o.getWorld().getBlockAt(ox+x,oy+y,oz+z).setType(Material.AIR,false);
            o.getWorld().getBlockAt(ox+x,oy,oz+z).setType(floor,false);
            o.getWorld().getBlockAt(ox+x,oy+6,oz+z).setType(ceiling,false);
            if (Math.abs(x)==radius || Math.abs(z)==radius) for (int y=1;y<6;y++) o.getWorld().getBlockAt(ox+x,oy+y,oz+z).setType(wall,false);
        }
        Random random = new Random(level * 9173L);
        for (int x=-radius+8; x<radius-6; x+=10) for (int z=-radius+8; z<radius-6; z+=10) {
            if (random.nextBoolean()) {
                int gap = 2 + random.nextInt(5);
                for (int yy=1; yy<5; yy++) for (int dx=0; dx<8; dx++) if (dx!=gap)
                    o.getWorld().getBlockAt(ox+x+dx,oy+yy,oz+z).setType(wall,false);
            }
            if (random.nextInt(100)<35) o.getWorld().getBlockAt(ox+x+2,oy+5,oz+z+2).setType(lamp,false);
            if (theme==Theme.POOLS && random.nextInt(100)<22) for (int dx=0;dx<3;dx++) for (int dz=0;dz<3;dz++) o.getWorld().getBlockAt(ox+x+3+dx,oy+1,oz+z+3+dz).setType(Material.WATER,false);
            if (random.nextInt(100)<12) placeLoot(o.getWorld().getBlockAt(ox+x+3,oy+1,oz+z+3), level);
        }
        int px=ox, pz=oz-radius+3;
        o.getWorld().getBlockAt(px,oy+1,pz).setType(Material.GOLD_BLOCK,false);
        o.getWorld().getBlockAt(px,oy,pz).setType(Material.LODESTONE,false);
        portalTargets.put(key(px,oy+1,pz), level+1);
        for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) for (int dy=1;dy<=3;dy++) o.getWorld().getBlockAt(ox+dx,oy+dy,oz+dz).setType(Material.AIR,false);
        o.getWorld().getBlockAt(ox,oy,oz).setType(floor,false);
        o.getWorld().getBlockAt(ox,oy+1,oz).setType(Material.AIR,false);
        o.getWorld().getBlockAt(ox,oy+2,oz).setType(Material.AIR,false);
    }

    private void registerLevelBlocks(Location o, int level) {
        int r=size/2, ox=o.getBlockX(), oy=o.getBlockY(), oz=o.getBlockZ();
        for (int x=-r; x<=r; x++) for (int z=-r; z<=r; z++) {
            String floorKey=key(ox+x,oy,oz+z), ceilingKey=key(ox+x,oy+6,oz+z);
            blockLevels.put(floorKey, level);
            blockLevels.put(ceilingKey, level);
            protectedFloorCeilings.add(floorKey);
            protectedFloorCeilings.add(ceilingKey);
            // Register every generated wall block as well; otherwise wall breaking is never detected.
            for (int y=oy+1; y<oy+6; y++) {
                Block candidate=world.getBlockAt(ox+x,y,oz+z);
                if (isWallMaterial(candidate.getType())) blockLevels.put(key(ox+x,y,oz+z),level);
            }
        }
    }

    private void placeLoot(Block block, int level) {
        block.setType(ThreadLocalRandom.current().nextBoolean() ? Material.CHEST : Material.BARREL, false);
        if (!(block.getState() instanceof Container container)) return;
        var inv=container.getInventory();
        Random r = new Random((long)level * 7919L + block.getX()*31L + block.getZ());
        // Loot pools vary by level type; higher levels provide rarer supplies.
        inv.clear();
        if (level % 10 == 5) {
            addLoot(inv, Material.COOKED_BEEF, 1, 4, r); addLoot(inv, Material.GOLDEN_CARROT, 1, 3, r);
            addLoot(inv, Material.IRON_INGOT, 1, 5, r); addLoot(inv, Material.LANTERN, 1, 4, r);
        } else if (level % 10 == 4) {
            addLoot(inv, Material.POTION, 1, 2, r); addLoot(inv, Material.WATER_BUCKET, 1, 1, r);
            addLoot(inv, Material.PRISMARINE_SHARD, 2, 8, r); addLoot(inv, Material.BREAD, 1, 3, r);
        } else if (level % 10 == 1 || level % 10 == 0) {
            addLoot(inv, Material.BREAD, 1, 4, r); addLoot(inv, Material.TORCH, 2, 12, r);
            addLoot(inv, Material.GOLD_NUGGET, 1, 6, r); addLoot(inv, Material.PAPER, 1, 5, r);
        } else if (level % 10 == 2 || level % 10 == 3) {
            addLoot(inv, Material.IRON_INGOT, 1, 3, r); addLoot(inv, Material.COAL, 2, 10, r);
            addLoot(inv, Material.COOKED_BEEF, 1, 3, r); addLoot(inv, Material.REDSTONE, 1, 6, r);
        } else {
            addLoot(inv, Material.ROTTEN_FLESH, 1, 4, r); addLoot(inv, Material.STRING, 1, 5, r);
            addLoot(inv, Material.BONE, 1, 4, r); addLoot(inv, Material.GOLD_NUGGET, 1, 3, r);
        }
        if (r.nextInt(100) < Math.min(45, 12 + level)) {
            ItemStack book=new ItemStack(Material.WRITTEN_BOOK); BookMeta meta=(BookMeta)book.getItemMeta();
            meta.setTitle(level%2==0 ? "SYSTEM LOG" : "VIRUS NOTE"); meta.setAuthor("UNKNOWN");
            meta.addPage("BACKROOMS SYSTEM\nLevel "+level+"\n\nНе доверяй одинаковым коридорам.\nПроверяй двери и ищи припасы.");
            book.setItemMeta(meta); inv.addItem(book);
        }
        if (level >= 6 && r.nextInt(100) < 18) addLoot(inv, Material.GOLDEN_APPLE, 1, 2, r);
        container.update();
    }

    private void addLoot(org.bukkit.inventory.Inventory inv, Material material, int min, int max, Random r) {
        if (r.nextInt(100) < 72) inv.addItem(new ItemStack(material, min + r.nextInt(max-min+1)));
    }

    @EventHandler public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer(); Block b = event.getBlock();
        String k = key(b.getX(), b.getY(), b.getZ());
        if (protectedFloorCeilings.contains(k)) { event.setCancelled(true); if (player.isOp()) player.sendMessage(ChatColor.YELLOW+"Пол и потолок Backrooms нельзя ломать."); return; }
        Integer level = blockLevels.get(k);
        if (level == null || !isWallMaterial(b.getType())) return;
        event.setDropItems(false);
        Location origin = levelOrigins.get(level); if (origin == null) return;
        // A broken wall opens a passage and creates a connected room/corridor beyond it.
        // Expand away from the player, through the wall they just broke.
        BlockFace face = outwardFaceFromPlayer(b, player);
        b.setType(Material.AIR, false);
        String wallKey = k + ":" + face.name(); if (!expandedWalls.add(wallKey)) return;
        Location room = b.getLocation().add(face.getModX(), 0, face.getModZ());
        buildExpansion(room, level, face);
        player.sendMessage(ChatColor.DARK_PURPLE+"За стеной открылось новое помещение...");
    }

    @EventHandler public void onDoorUse(PlayerInteractEvent event) {
        if (event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.getClickedBlock()==null) return;
        Block door=event.getClickedBlock();
        if (!(door.getBlockData() instanceof org.bukkit.block.data.type.Door data)) return;
        Integer level=blockLevels.get(key(door.getX(),door.getY()-1,door.getZ()));
        if (level==null) level=blockLevels.get(key(door.getX(),door.getY(),door.getZ()));
        if (level==null) return;
        String dk=key(door.getX(),door.getY(),door.getZ()); if (!expandedDoors.add(dk)) return;
        BlockFace face=data.getFacing();
        Location beyond=door.getLocation().add(face.getModX()*2,0,face.getModZ()*2);
        buildExpansion(beyond,level,face);
        if (event.getPlayer().isOp()) event.getPlayer().sendMessage(ChatColor.DARK_PURPLE+"Дверь открывает путь в ещё одно помещение.");
    }

    private boolean isWallMaterial(Material m) {
        return m.name().contains("CONCRETE") || m.name().contains("TERRACOTTA") || m.name().contains("BRICKS") || m.name().contains("PLANKS") || m == Material.YELLOW_WOOL || m == Material.SMOOTH_SANDSTONE || m == Material.QUARTZ_BLOCK || m == Material.BLACKSTONE || m == Material.DEEPSLATE_TILES;
    }

    private BlockFace outwardFaceFromPlayer(Block wall, Player player) {
        double dx=player.getLocation().getX()-wall.getX()-0.5;
        double dz=player.getLocation().getZ()-wall.getZ()-0.5;
        // The player is inside; generate on the opposite side of the broken wall.
        if (Math.abs(dx)>Math.abs(dz)) return dx>=0 ? BlockFace.WEST : BlockFace.EAST;
        return dz>=0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private void buildExpansion(Location edge, int level, BlockFace face) {
        World w=edge.getWorld(); int ox=edge.getBlockX(), oy=edge.getBlockY(), oz=edge.getBlockZ();
        Material floor, wall, ceiling;
        switch (level % 5) {
            case 1 -> { floor=Material.YELLOW_WOOL; wall=Material.YELLOW_TERRACOTTA; ceiling=Material.SMOOTH_SANDSTONE; }
            case 2 -> { floor=Material.GRAY_CONCRETE; wall=Material.LIGHT_GRAY_CONCRETE; ceiling=Material.GRAY_CONCRETE; }
            case 3 -> { floor=Material.SMOOTH_STONE; wall=Material.WHITE_CONCRETE; ceiling=Material.QUARTZ_BLOCK; }
            case 4 -> { floor=Material.PRISMARINE_BRICKS; wall=Material.QUARTZ_BLOCK; ceiling=Material.SMOOTH_QUARTZ; }
            default -> { floor=Material.RED_CONCRETE; wall=Material.BLACKSTONE; ceiling=Material.BLACKSTONE; }
        }
        Random r=new Random(ThreadLocalRandom.current().nextLong()); int width=9+r.nextInt(7), length=11+r.nextInt(10);
        for (int x=-width/2; x<=width/2; x++) for (int z=0; z<=length; z++) {
            int wx=ox+face.getModX()*z + (face.getModZ()!=0 ? x : 0);
            int wz=oz+face.getModZ()*z + (face.getModX()!=0 ? x : 0);
            for (int y=0;y<=6;y++) w.getBlockAt(wx,oy+y,wz).setType(Material.AIR,false);
            w.getBlockAt(wx,oy,wz).setType(floor,false); w.getBlockAt(wx,oy+6,wz).setType(ceiling,false);
            if (x==-width/2 || x==width/2 || z==length) for (int y=1;y<6;y++) w.getBlockAt(wx,oy+y,wz).setType(wall,false);
            blockLevels.put(key(wx,oy,wz),level); blockLevels.put(key(wx,oy+6,wz),level);
            protectedFloorCeilings.add(key(wx,oy,wz)); protectedFloorCeilings.add(key(wx,oy+6,wz));
            for (int yy=1;yy<6;yy++) {
                Block part=w.getBlockAt(wx,oy+yy,wz);
                if (isWallMaterial(part.getType())) blockLevels.put(key(wx,oy+yy,wz),level);
            }
        }
        // Doors at the far end lead to the next generated room when opened/walked through.
        int ex=ox+face.getModX()*length, ez=oz+face.getModZ()*length;
        w.getBlockAt(ex,oy+1,ez).setType(Material.AIR,false); w.getBlockAt(ex,oy+2,ez).setType(Material.AIR,false);
        placeDoor(w.getBlockAt(ex,oy+1,ez), face.getOppositeFace());
        if (r.nextInt(100)<35) placeLoot(w.getBlockAt(ox+face.getModX()*(length/2),oy+1,oz+face.getModZ()*(length/2)),level);
    }

    private void placeDoor(Block bottom, BlockFace facing) {
        bottom.setType(Material.OAK_DOOR, false);
        org.bukkit.block.data.type.Door lower = (org.bukkit.block.data.type.Door) bottom.getBlockData();
        lower.setFacing(facing); lower.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM); bottom.setBlockData(lower,false);
        Block top=bottom.getRelative(BlockFace.UP); top.setType(Material.OAK_DOOR,false);
        org.bukkit.block.data.type.Door upper=(org.bukkit.block.data.type.Door)top.getBlockData();
        upper.setFacing(facing); upper.setHalf(org.bukkit.block.data.Bisected.Half.TOP); top.setBlockData(upper,false);
    }


    private void generateNextLevel(int target) {
        if (world==null || target!=generated+1 || levelSpawns.containsKey(target)) return;
        int index=target-1, x=1000+(index%4)*spacing, z=1000+(index/4)*spacing;
        int y=world.getHighestBlockYAt(x,z)+2;
        Location origin=new Location(world,x,y,z);
        buildLevel(origin,target,Theme.values()[index%Theme.values().length]);
        registerLevelBlocks(origin,target);
        levelOrigins.put(target,origin.clone());
        levelSpawns.put(target,origin.clone().add(0,1,0));
        generated++;
    }

    private String key(int x,int y,int z) { return x+":"+y+":"+z; }
    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (event.getTo()==null || (event.getFrom().getBlockX()==event.getTo().getBlockX() && event.getFrom().getBlockY()==event.getTo().getBlockY() && event.getFrom().getBlockZ()==event.getTo().getBlockZ())) return;
        Player p=event.getPlayer(); if (portalCooldown.contains(p.getUniqueId())) return;
        Integer target=portalTargets.get(key(event.getTo().getBlockX(),event.getTo().getBlockY()-1,event.getTo().getBlockZ()));
        if (target==null) target=portalTargets.get(key(event.getTo().getBlockX(),event.getTo().getBlockY(),event.getTo().getBlockZ()));
        if (target==null) return; Location destination=levelSpawns.get(target);
        if (destination==null && target==generated+1 && world!=null) {
            generateNextLevel(target);
            destination=levelSpawns.get(target);
        }
        if (destination==null) return;
        portalCooldown.add(p.getUniqueId()); p.teleport(destination); p.sendMessage(ChatColor.LIGHT_PURPLE+"Переход на уровень "+target);
        Bukkit.getScheduler().runTaskLater(this,()->portalCooldown.remove(p.getUniqueId()),30L);
    }
}
