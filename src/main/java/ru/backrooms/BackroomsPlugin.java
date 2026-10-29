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
        if (args.length == 0) { player.sendMessage(ChatColor.YELLOW + "/br generate | stop | info | level <номер>"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "generate" -> startGeneration(player);
            case "stop" -> {
                if (generationTask != null) generationTask.cancel();
                generationTask = null; generating = false;
                player.sendMessage(ChatColor.RED + "Генерация остановлена. Создано уровней: " + generated);
            }
            case "info" -> player.sendMessage(ChatColor.GOLD + "Backrooms: " + (generating ? "генерация" : "ожидание") + "; уровней: " + generated + "; мир: " + (world == null ? "не найден" : world.getName()));
            case "level" -> {
                if (args.length < 2) { player.sendMessage(ChatColor.YELLOW + "Использование: /br level <номер>"); return true; }
                try {
                    Location loc = levelSpawns.get(Integer.parseInt(args[1]));
                    if (loc == null) player.sendMessage(ChatColor.RED + "Уровень не найден. Сначала /br generate.");
                    else { player.teleport(loc); player.sendMessage(ChatColor.GREEN + "Телепорт на уровень."); }
                } catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Номер уровня должен быть числом."); }
            }
            default -> player.sendMessage(ChatColor.YELLOW + "/br generate | stop | info | level <номер>");
        }
        return true;
    }

    private void startGeneration(Player player) {
        if (world == null) world = Bukkit.getWorld(getConfig().getString("world-name", "world"));
        if (world == null) { player.sendMessage(ChatColor.RED + "Мир не найден. Укажи существующий мир в config.yml."); return; }
        if (generating) { player.sendMessage(ChatColor.RED + "Генерация уже идёт."); return; }
        generating = true; generated = 0; levelSpawns.clear(); portalTargets.clear();
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
            levelSpawns.put(n, origin.clone().add(0, 1, 0)); generated++; i[0]++;
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
        portalTargets.put(key(px,oy+1,pz), level < levelCount ? level+1 : 1);
        for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) for (int dy=1;dy<=3;dy++) o.getWorld().getBlockAt(ox+dx,oy+dy,oz+dz).setType(Material.AIR,false);
        o.getWorld().getBlockAt(ox,oy,oz).setType(floor,false);
        o.getWorld().getBlockAt(ox,oy+1,oz).setType(Material.AIR,false);
        o.getWorld().getBlockAt(ox,oy+2,oz).setType(Material.AIR,false);
    }

    private void placeLoot(Block block, int level) {
        block.setType(ThreadLocalRandom.current().nextBoolean() ? Material.CHEST : Material.BARREL, false);
        if (!(block.getState() instanceof Container container)) return;
        var inv=container.getInventory();
        inv.addItem(new ItemStack(Material.BREAD, ThreadLocalRandom.current().nextInt(1,5)));
        inv.addItem(new ItemStack(Material.TORCH, ThreadLocalRandom.current().nextInt(2,13)));
        if (ThreadLocalRandom.current().nextBoolean()) inv.addItem(new ItemStack(Material.GOLD_NUGGET, ThreadLocalRandom.current().nextInt(1,8)));
        if (ThreadLocalRandom.current().nextInt(4)==0) {
            ItemStack book=new ItemStack(Material.WRITTEN_BOOK); BookMeta meta=(BookMeta)book.getItemMeta();
            meta.setTitle(level%2==0 ? "SYSTEM LOG" : "VIRUS NOTE"); meta.setAuthor("UNKNOWN");
            meta.addPage("BACKROOMS SYSTEM\nLevel "+level+"\n\nНе доверяй одинаковым коридорам.\nИщи выход, но не верь каждой двери.");
            book.setItemMeta(meta); inv.addItem(book);
        }
        container.update();
    }

    private String key(int x,int y,int z) { return x+":"+y+":"+z; }
    @EventHandler public void onMove(PlayerMoveEvent event) {
        if (event.getTo()==null || (event.getFrom().getBlockX()==event.getTo().getBlockX() && event.getFrom().getBlockY()==event.getTo().getBlockY() && event.getFrom().getBlockZ()==event.getTo().getBlockZ())) return;
        Player p=event.getPlayer(); if (portalCooldown.contains(p.getUniqueId())) return;
        Integer target=portalTargets.get(key(event.getTo().getBlockX(),event.getTo().getBlockY()-1,event.getTo().getBlockZ()));
        if (target==null) target=portalTargets.get(key(event.getTo().getBlockX(),event.getTo().getBlockY(),event.getTo().getBlockZ()));
        if (target==null) return; Location destination=levelSpawns.get(target); if (destination==null) return;
        portalCooldown.add(p.getUniqueId()); p.teleport(destination); p.sendMessage(ChatColor.LIGHT_PURPLE+"Переход на уровень "+target);
        Bukkit.getScheduler().runTaskLater(this,()->portalCooldown.remove(p.getUniqueId()),30L);
    }
}
