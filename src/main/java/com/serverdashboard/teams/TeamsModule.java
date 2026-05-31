package com.serverdashboard.teams;

import com.google.gson.*;
import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.api.DashboardModule;
import com.sun.net.httpserver.HttpExchange;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class TeamsModule implements DashboardModule {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_BODY = 8 * 1024;

    // ── Data model ─────────────────────────────────────────────────────────────

    enum Rank {
        MEMBER, MODERATOR, CO_LEADER, LEADER;
        Rank up()   { Rank[] v = values(); return v[Math.min(ordinal() + 1, v.length - 1)]; }
        Rank down() { return values()[Math.max(ordinal() - 1, 0)]; }
    }

    static class Member {
        String name; Rank rank;
        Member(String name, Rank rank) { this.name = name; this.rank = rank; }
    }

    static class Team {
        String id; String name; boolean open = false;
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, String> banned  = new LinkedHashMap<>();
        Team(String id, String name) { this.id = id; this.name = name; }
        static Team create(String name) { return new Team(UUID.randomUUID().toString(), name); }
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private final Map<String, Team>       teams          = new LinkedHashMap<>();
    private final Map<UUID, List<String>> pendingInvites = new HashMap<>();
    private final Set<UUID>               teamChat       = Collections.synchronizedSet(new HashSet<>());

    private DashboardPlugin plugin;
    private Path            dataFile;
    private Listener        chatListener;
    private Command         registeredCmd;

    // ── DashboardModule ────────────────────────────────────────────────────────

    @Override public String getId()   { return "teams"; }
    @Override public String getName() { return "Teams"; }
    @Override public String getIcon() { return "ti-users"; }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onLoad(DashboardPlugin plugin) {
        this.plugin = plugin;
        dataFile = plugin.getDataFolder().toPath().resolve("modules/teams/teams.yml");
        try { Files.createDirectories(dataFile.getParent()); } catch (IOException ignored) {}
        load();
        registerCommand();
        chatListener = new TeamChatListener();
        Bukkit.getPluginManager().registerEvents(chatListener, plugin);
        plugin.getLogger().info("[Teams] " + teams.size() + "개 팀 로드됨.");
    }

    @Override
    public void onUnload() {
        save();
        if (chatListener != null) org.bukkit.event.HandlerList.unregisterAll(chatListener);
        unregisterCommand();
    }

    // ── Command registration ───────────────────────────────────────────────────

    private void registerCommand() {
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            CommandMap map = (CommandMap) f.get(Bukkit.getServer());
            registeredCmd = new TeamCommand();
            map.register("teams", registeredCmd);
        } catch (Exception e) {
            plugin.getLogger().warning("[Teams] 명령어 등록 실패: " + e.getMessage());
        }
    }

    private void unregisterCommand() {
        if (registeredCmd == null) return;
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            Object map = f.get(Bukkit.getServer());
            Field kf = map.getClass().getDeclaredField("knownCommands");
            kf.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> known = (Map<String, Command>) kf.get(map);
            known.values().removeIf(c -> c == registeredCmd);
        } catch (Exception ignored) {}
    }

    // ── Chat listener ──────────────────────────────────────────────────────────

    private class TeamChatListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onChat(AsyncChatEvent event) {
            Player p = event.getPlayer();
            if (!teamChat.contains(p.getUniqueId())) return;

            Team t;
            synchronized (TeamsModule.this) { t = teamOf(p.getUniqueId().toString()); }
            if (t == null) { teamChat.remove(p.getUniqueId()); return; }

            event.setCancelled(true);

            Component msg = Component.text("[" + t.name + "] ", NamedTextColor.GOLD)
                    .append(Component.text(p.getName() + ": ", NamedTextColor.YELLOW))
                    .append(event.message());

            final String tid = t.id;
            synchronized (TeamsModule.this) {
                Team team = teams.get(tid);
                if (team == null) return;
                for (String uuid : team.members.keySet()) {
                    Player m = Bukkit.getPlayer(UUID.fromString(uuid));
                    if (m != null) m.sendMessage(msg);
                }
            }
        }
    }

    // ── /team command ──────────────────────────────────────────────────────────

    private class TeamCommand extends org.bukkit.command.Command {
        TeamCommand() {
            super("team", "팀 관리", "/team <서브명령어>", List.of("t"));
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player p)) { sender.sendMessage("§c플레이어 전용 명령어입니다."); return true; }
            if (args.length == 0) { help(p); return true; }
            switch (args[0].toLowerCase()) {
                case "create"  -> create(p, args);
                case "disband" -> disband(p);
                case "invite"  -> invite(p, args);
                case "accept"  -> accept(p, args);
                case "deny"    -> deny(p);
                case "join"    -> join(p, args);
                case "leave"   -> leave(p);
                case "info"    -> info(p, args);
                case "list"    -> list(p);
                case "top"     -> top(p);
                case "kick"    -> kick(p, args);
                case "promote" -> promote(p, args);
                case "demote"  -> demote(p, args);
                case "ban"     -> ban(p, args);
                case "unban"   -> unban(p, args);
                case "open"    -> open(p);
                case "chat"    -> chat(p);
                case "admin"   -> admin(p, args);
                default        -> help(p);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!(sender instanceof Player p)) return List.of();
            List<String> subs = new ArrayList<>(List.of(
                "create","disband","invite","accept","deny","join","leave",
                "info","list","top","kick","promote","demote","ban","unban","open","chat"
            ));
            if (p.isOp()) subs.add("admin");

            if (args.length == 1)
                return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());

            if (args.length == 2) {
                return switch (args[0].toLowerCase()) {
                    case "join", "info" -> {
                        synchronized (TeamsModule.this) {
                            yield teams.values().stream().map(t -> t.name)
                                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                    .collect(Collectors.toList());
                        }
                    }
                    case "invite", "kick", "promote", "demote", "ban" ->
                        Bukkit.getOnlinePlayers().stream().map(Player::getName)
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    case "unban" -> {
                        Team t = synchronized_teamOf(p);
                        if (t == null) yield List.of();
                        yield t.banned.values().stream()
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                    default -> List.of();
                };
            }
            return List.of();
        }

        // ── subcommands ────────────────────────────────────────────────────────

        void help(Player p) {
            p.sendMessage("§6━━━━━ Teams 명령어 ━━━━━");
            p.sendMessage("§e/team create §7<이름>  §f팀 생성 (리더 됨)");
            p.sendMessage("§e/team invite §7<플레이어>  §f팀원 초대");
            p.sendMessage("§e/team accept §7[팀]  §f초대 수락");
            p.sendMessage("§e/team deny  §f초대 거절");
            p.sendMessage("§e/team join §7<팀>  §f공개팀 입장");
            p.sendMessage("§e/team leave  §f팀 탈퇴");
            p.sendMessage("§e/team info §7[팀]  /  §e/team list  /  §e/team top");
            p.sendMessage("§e/team kick/promote/demote/ban/unban §7<플레이어>");
            p.sendMessage("§e/team open  §f공개↔비공개  §7|  §e/team chat  §f팀채팅 토글");
            p.sendMessage("§e/team disband  §f팀 해체 (리더)");
            if (p.isOp()) p.sendMessage("§c/team admin kick|disband|setleader ...");
        }

        void create(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team create <이름>"); return; }
            String name = args[1];
            if (!name.matches("[a-zA-Z0-9_\\-]{3,20}"))
                { p.sendMessage("§c팀 이름은 3~20자 영문/숫자/_- 만 가능합니다."); return; }
            String uuid = p.getUniqueId().toString();
            synchronized (TeamsModule.this) {
                for (Team t : teams.values()) {
                    if (t.members.containsKey(uuid)) { p.sendMessage("§c이미 팀에 소속되어 있습니다."); return; }
                    if (t.name.equalsIgnoreCase(name)) { p.sendMessage("§c'" + name + "' 팀이 이미 존재합니다."); return; }
                }
                Team t = Team.create(name);
                t.members.put(uuid, new Member(p.getName(), Rank.LEADER));
                teams.put(t.id, t);
            }
            save();
            p.sendMessage("§a팀 §e" + name + " §a이(가) 생성되었습니다. 당신은 리더입니다!");
        }

        void disband(Player p) {
            String uuid = p.getUniqueId().toString();
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            if (t.members.get(uuid).rank != Rank.LEADER) { p.sendMessage("§c리더만 팀을 해체할 수 있습니다."); return; }
            List<String> uuids;
            String tName;
            synchronized (TeamsModule.this) {
                uuids  = new ArrayList<>(t.members.keySet());
                tName  = t.name;
                teams.remove(t.id);
            }
            uuids.forEach(u -> { try { teamChat.remove(UUID.fromString(u)); } catch (Exception ignored) {} });
            save();
            p.sendMessage("§a팀 §e" + tName + " §a이(가) 해체되었습니다.");
            uuids.stream().filter(u -> !u.equals(uuid)).forEach(u -> {
                Player m = Bukkit.getPlayer(UUID.fromString(u));
                if (m != null) m.sendMessage("§c팀 §e" + tName + " §c이(가) 해체되었습니다.");
            });
        }

        void invite(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team invite <플레이어>"); return; }
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            if (t.members.get(p.getUniqueId().toString()).rank.ordinal() < Rank.MODERATOR.ordinal())
                { p.sendMessage("§c운영자 이상만 초대할 수 있습니다."); return; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { p.sendMessage("§c'" + args[1] + "' 플레이어가 온라인이 아닙니다."); return; }
            String tu = target.getUniqueId().toString();
            synchronized (TeamsModule.this) {
                for (Team ot : teams.values())
                    if (ot.members.containsKey(tu)) { p.sendMessage("§c이미 다른 팀에 소속되어 있습니다."); return; }
                if (t.banned.containsKey(tu)) { p.sendMessage("§c해당 플레이어는 밴 상태입니다."); return; }
            }
            pendingInvites.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>()).add(t.id);
            p.sendMessage("§a" + target.getName() + " §a님에게 초대를 보냈습니다.");
            target.sendMessage("§6[Teams] §e" + p.getName() + " §f님이 §e" + t.name + " §f팀에 초대했습니다.");
            target.sendMessage("§7수락: §f/team accept  §7거절: §f/team deny");
        }

        void accept(Player p, String[] args) {
            UUID uuid = p.getUniqueId();
            List<String> invites = pendingInvites.getOrDefault(uuid, List.of());
            synchronized (TeamsModule.this) { invites = new ArrayList<>(invites); invites.removeIf(id -> !teams.containsKey(id)); }
            if (invites.isEmpty()) { p.sendMessage("§c대기 중인 초대가 없습니다."); pendingInvites.remove(uuid); return; }

            if (invites.size() > 1 && args.length < 2) {
                p.sendMessage("§6여러 초대가 있습니다:");
                for (String id : invites) {
                    synchronized (TeamsModule.this) { Team t = teams.get(id); if (t != null) p.sendMessage("§e- " + t.name + " §7(/team accept " + t.name + ")"); }
                }
                return;
            }

            String teamId;
            if (args.length >= 2) {
                final String targ = args[1];
                teamId = invites.stream().filter(id -> { synchronized (TeamsModule.this) { Team t = teams.get(id); return t != null && t.name.equalsIgnoreCase(targ); } }).findFirst().orElse(null);
                if (teamId == null) { p.sendMessage("§c'" + args[1] + "' 팀의 초대를 찾을 수 없습니다."); return; }
            } else {
                teamId = invites.get(0);
            }

            String tName;
            synchronized (TeamsModule.this) {
                if (teamOf(uuid.toString()) != null) { p.sendMessage("§c이미 팀에 소속되어 있습니다."); pendingInvites.remove(uuid); return; }
                Team t = teams.get(teamId);
                if (t == null) { p.sendMessage("§c팀을 찾을 수 없습니다."); return; }
                t.members.put(uuid.toString(), new Member(p.getName(), Rank.MEMBER));
                tName = t.name;
                for (String mu : t.members.keySet()) {
                    if (mu.equals(uuid.toString())) continue;
                    Player m = Bukkit.getPlayer(UUID.fromString(mu));
                    if (m != null) m.sendMessage("§a" + p.getName() + " §f님이 팀에 합류했습니다!");
                }
            }
            pendingInvites.remove(uuid);
            save();
            p.sendMessage("§a팀 §e" + tName + " §a에 합류했습니다!");
        }

        void deny(Player p) {
            if (!pendingInvites.containsKey(p.getUniqueId())) { p.sendMessage("§c대기 중인 초대가 없습니다."); return; }
            pendingInvites.remove(p.getUniqueId());
            p.sendMessage("§7초대를 거절했습니다.");
        }

        void join(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team join <팀이름>"); return; }
            String uuid = p.getUniqueId().toString();
            synchronized (TeamsModule.this) {
                if (teamOf(uuid) != null) { p.sendMessage("§c이미 팀에 소속되어 있습니다."); return; }
                Team t = teams.values().stream().filter(x -> x.name.equalsIgnoreCase(args[1])).findFirst().orElse(null);
                if (t == null) { p.sendMessage("§c'" + args[1] + "' 팀을 찾을 수 없습니다."); return; }
                if (!t.open) { p.sendMessage("§c이 팀은 비공개입니다. 초대를 통해서만 가입 가능합니다."); return; }
                if (t.banned.containsKey(uuid)) { p.sendMessage("§c이 팀에서 밴 상태입니다."); return; }
                t.members.put(uuid, new Member(p.getName(), Rank.MEMBER));
                for (String mu : t.members.keySet()) {
                    if (mu.equals(uuid)) continue;
                    Player m = Bukkit.getPlayer(UUID.fromString(mu)); if (m != null) m.sendMessage("§a" + p.getName() + " §f님이 팀에 합류했습니다!");
                }
                save(); p.sendMessage("§a팀 §e" + t.name + " §a에 합류했습니다!");
            }
        }

        void leave(Player p) {
            String uuid = p.getUniqueId().toString();
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            if (t.members.get(uuid).rank == Rank.LEADER) { p.sendMessage("§c리더는 탈퇴할 수 없습니다. 리더십을 위임하거나 팀을 해체하세요."); return; }
            String tName;
            synchronized (TeamsModule.this) {
                tName = t.name; t.members.remove(uuid); teamChat.remove(p.getUniqueId());
                for (String mu : t.members.keySet()) { Player m = Bukkit.getPlayer(UUID.fromString(mu)); if (m != null) m.sendMessage("§7" + p.getName() + " §f님이 팀을 탈퇴했습니다."); }
            }
            save(); p.sendMessage("§a팀 §e" + tName + " §a을(를) 탈퇴했습니다.");
        }

        void info(Player p, String[] args) {
            synchronized (TeamsModule.this) {
                Team t;
                if (args.length >= 2) {
                    final String n = args[1];
                    t = teams.values().stream().filter(x -> x.name.equalsIgnoreCase(n)).findFirst().orElse(null);
                    if (t == null) { p.sendMessage("§c'" + args[1] + "' 팀을 찾을 수 없습니다."); return; }
                } else {
                    t = teamOf(p.getUniqueId().toString());
                    if (t == null) { p.sendMessage("§c소속된 팀이 없습니다. /team info <팀이름>"); return; }
                }
                p.sendMessage("§6━━━━━ §e" + t.name + " §6━━━━━");
                p.sendMessage("§7상태: " + (t.open ? "§a공개" : "§c비공개") + " §7· 멤버 §e" + t.members.size() + "명");
                String[] rLabels = {"§6리더", "§e부리더", "§b운영자", "§f멤버"};
                Rank[]   ranks   = {Rank.LEADER, Rank.CO_LEADER, Rank.MODERATOR, Rank.MEMBER};
                for (int i = 0; i < ranks.length; i++) {
                    final int fi = i;
                    String names = t.members.values().stream().filter(m -> m.rank == ranks[fi]).map(m -> m.name).collect(Collectors.joining("§7, §f"));
                    if (!names.isEmpty()) p.sendMessage(rLabels[i] + "§7: §f" + names);
                }
                if (!t.banned.isEmpty()) p.sendMessage("§c밴§7: " + String.join(", ", t.banned.values()));
            }
        }

        void list(Player p) {
            synchronized (TeamsModule.this) {
                if (teams.isEmpty()) { p.sendMessage("§7등록된 팀이 없습니다."); return; }
                p.sendMessage("§6━ §f팀 목록 §7(" + teams.size() + "개) §6━");
                int i = 1;
                for (Team t : teams.values())
                    p.sendMessage("§f" + i++ + ". §e" + t.name + " §7(" + t.members.size() + "명) " + (t.open ? "§a공개" : "§c비공개"));
            }
        }

        void top(Player p) {
            synchronized (TeamsModule.this) {
                if (teams.isEmpty()) { p.sendMessage("§7등록된 팀이 없습니다."); return; }
                p.sendMessage("§6━ §f팀 순위 (멤버 수) §6━");
                teams.values().stream().sorted((a, b) -> b.members.size() - a.members.size()).limit(10).forEach(t ->
                    p.sendMessage("§e" + t.name + " §7- §f" + t.members.size() + "명 " + (t.open ? "§a공개" : "§c비공개")));
            }
        }

        void kick(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team kick <플레이어>"); return; }
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            Member me = t.members.get(p.getUniqueId().toString());
            if (me.rank.ordinal() < Rank.MODERATOR.ordinal()) { p.sendMessage("§c운영자 이상만 추방 가능합니다."); return; }
            synchronized (TeamsModule.this) {
                Map.Entry<String, Member> entry = findMemberByName(t, args[1]);
                if (entry == null) { p.sendMessage("§c'" + args[1] + "' 멤버를 찾을 수 없습니다."); return; }
                if (entry.getValue().rank == Rank.LEADER) { p.sendMessage("§c리더는 추방할 수 없습니다."); return; }
                if (entry.getValue().rank.ordinal() >= me.rank.ordinal()) { p.sendMessage("§c자신보다 높거나 같은 등급은 추방 불가합니다."); return; }
                t.members.remove(entry.getKey()); teamChat.remove(UUID.fromString(entry.getKey()));
                Player tgt = Bukkit.getPlayer(UUID.fromString(entry.getKey())); if (tgt != null) tgt.sendMessage("§c" + t.name + " §c팀에서 추방되었습니다.");
                t.members.forEach((u, m) -> { if (!u.equals(p.getUniqueId().toString())) { Player mm = Bukkit.getPlayer(UUID.fromString(u)); if (mm != null) mm.sendMessage("§7" + entry.getValue().name + " §f님이 추방되었습니다."); } });
            }
            save(); p.sendMessage("§a" + args[1] + " §a님을 추방했습니다.");
        }

        void promote(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team promote <플레이어>"); return; }
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            Member me = t.members.get(p.getUniqueId().toString());
            if (me.rank.ordinal() < Rank.CO_LEADER.ordinal()) { p.sendMessage("§c부리더 이상만 승급 가능합니다."); return; }
            synchronized (TeamsModule.this) {
                Map.Entry<String, Member> entry = findMemberByName(t, args[1]);
                if (entry == null) { p.sendMessage("§c'" + args[1] + "' 멤버를 찾을 수 없습니다."); return; }
                Member tm = entry.getValue();
                if (tm.rank == Rank.LEADER) { p.sendMessage("§c이미 리더입니다."); return; }
                if (tm.rank == Rank.CO_LEADER && me.rank != Rank.LEADER) { p.sendMessage("§c리더만 리더십을 위임할 수 있습니다."); return; }
                if (tm.rank == Rank.CO_LEADER) t.members.values().stream().filter(m -> m.rank == Rank.LEADER).forEach(m -> m.rank = Rank.CO_LEADER);
                tm.rank = tm.rank.up();
                Player tgt = Bukkit.getPlayer(UUID.fromString(entry.getKey())); if (tgt != null) tgt.sendMessage("§a" + t.name + " 팀에서 §e" + rankName(tm.rank) + " §a로 승급했습니다!");
            }
            save(); p.sendMessage("§a" + args[1] + " §a님을 승급했습니다.");
        }

        void demote(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team demote <플레이어>"); return; }
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            Member me = t.members.get(p.getUniqueId().toString());
            if (me.rank.ordinal() < Rank.CO_LEADER.ordinal()) { p.sendMessage("§c부리더 이상만 강등 가능합니다."); return; }
            synchronized (TeamsModule.this) {
                Map.Entry<String, Member> entry = findMemberByName(t, args[1]);
                if (entry == null) { p.sendMessage("§c'" + args[1] + "' 멤버를 찾을 수 없습니다."); return; }
                Member tm = entry.getValue();
                if (tm.rank == Rank.LEADER) { p.sendMessage("§c리더는 강등 불가합니다."); return; }
                if (tm.rank == Rank.MEMBER)  { p.sendMessage("§c이미 최하위 등급입니다."); return; }
                if (tm.rank.ordinal() >= me.rank.ordinal()) { p.sendMessage("§c자신보다 높거나 같은 등급은 강등 불가합니다."); return; }
                tm.rank = tm.rank.down();
                Player tgt = Bukkit.getPlayer(UUID.fromString(entry.getKey())); if (tgt != null) tgt.sendMessage("§c" + t.name + " 팀에서 §e" + rankName(tm.rank) + " §c으로 강등되었습니다.");
            }
            save(); p.sendMessage("§a" + args[1] + " §a님을 강등했습니다.");
        }

        void ban(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team ban <플레이어>"); return; }
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            Member me = t.members.get(p.getUniqueId().toString());
            if (me.rank.ordinal() < Rank.CO_LEADER.ordinal()) { p.sendMessage("§c부리더 이상만 밴 가능합니다."); return; }
            synchronized (TeamsModule.this) {
                Map.Entry<String, Member> entry = findMemberByName(t, args[1]);
                if (entry == null) { p.sendMessage("§c'" + args[1] + "' 멤버를 찾을 수 없습니다."); return; }
                if (entry.getValue().rank == Rank.LEADER) { p.sendMessage("§c리더는 밴 불가합니다."); return; }
                if (entry.getValue().rank.ordinal() >= me.rank.ordinal()) { p.sendMessage("§c자신보다 높거나 같은 등급은 밴 불가합니다."); return; }
                t.banned.put(entry.getKey(), entry.getValue().name);
                t.members.remove(entry.getKey()); teamChat.remove(UUID.fromString(entry.getKey()));
                Player tgt = Bukkit.getPlayer(UUID.fromString(entry.getKey())); if (tgt != null) tgt.sendMessage("§c" + t.name + " §c팀에서 밴되었습니다.");
            }
            save(); p.sendMessage("§a" + args[1] + " §a님을 밴했습니다.");
        }

        void unban(Player p, String[] args) {
            if (args.length < 2) { p.sendMessage("§c사용법: /team unban <플레이어>"); return; }
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            if (t.members.get(p.getUniqueId().toString()).rank.ordinal() < Rank.CO_LEADER.ordinal()) { p.sendMessage("§c부리더 이상만 언밴 가능합니다."); return; }
            synchronized (TeamsModule.this) {
                String found = t.banned.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(args[1])).map(Map.Entry::getKey).findFirst().orElse(null);
                if (found == null) { p.sendMessage("§c'" + args[1] + "' 은(는) 밴 목록에 없습니다."); return; }
                t.banned.remove(found);
            }
            save(); p.sendMessage("§a" + args[1] + " §a님의 밴을 해제했습니다.");
        }

        void open(Player p) {
            Team t = synchronized_teamOf(p);
            if (t == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            if (t.members.get(p.getUniqueId().toString()).rank != Rank.LEADER) { p.sendMessage("§c리더만 공개 설정을 변경할 수 있습니다."); return; }
            boolean now; synchronized (TeamsModule.this) { t.open = !t.open; now = t.open; }
            save(); p.sendMessage(now ? "§a팀이 §a공개§a 상태로 변경되었습니다." : "§a팀이 §c비공개§a 상태로 변경되었습니다.");
        }

        void chat(Player p) {
            UUID uuid = p.getUniqueId();
            if (synchronized_teamOf(p) == null) { p.sendMessage("§c소속된 팀이 없습니다."); return; }
            if (teamChat.contains(uuid)) { teamChat.remove(uuid); p.sendMessage("§7팀 채팅 §c꺼짐§7. 이제 일반 채팅으로 전송됩니다."); }
            else { teamChat.add(uuid); p.sendMessage("§7팀 채팅 §a켜짐§7. 메시지가 팀원에게만 전송됩니다. 다시 치려면 §f/team chat"); }
        }

        void admin(Player p, String[] args) {
            if (!p.isOp()) { p.sendMessage("§c권한이 없습니다."); return; }
            if (args.length < 3) { p.sendMessage("§c/team admin kick|disband|setleader <대상>"); return; }
            switch (args[1].toLowerCase()) {
                case "kick" -> {
                    synchronized (TeamsModule.this) {
                        for (Team t : teams.values()) {
                            Map.Entry<String, Member> e = findMemberByName(t, args[2]);
                            if (e != null) {
                                t.members.remove(e.getKey()); teamChat.remove(UUID.fromString(e.getKey()));
                                Player tgt = Bukkit.getPlayer(UUID.fromString(e.getKey())); if (tgt != null) tgt.sendMessage("§c관리자에 의해 팀에서 추방되었습니다.");
                                save(); p.sendMessage("§a" + args[2] + " §a님을 추방했습니다."); return;
                            }
                        }
                    }
                    p.sendMessage("§c해당 플레이어를 팀에서 찾을 수 없습니다.");
                }
                case "disband" -> {
                    synchronized (TeamsModule.this) {
                        Team t = teams.values().stream().filter(x -> x.name.equalsIgnoreCase(args[2])).findFirst().orElse(null);
                        if (t == null) { p.sendMessage("§c'" + args[2] + "' 팀을 찾을 수 없습니다."); return; }
                        t.members.keySet().forEach(u -> { teamChat.remove(UUID.fromString(u)); Player m = Bukkit.getPlayer(UUID.fromString(u)); if (m != null) m.sendMessage("§c팀이 관리자에 의해 해체되었습니다."); });
                        teams.remove(t.id);
                    }
                    save(); p.sendMessage("§a팀 §e" + args[2] + " §a을(를) 해체했습니다.");
                }
                case "setleader" -> {
                    synchronized (TeamsModule.this) {
                        for (Team t : teams.values()) {
                            Map.Entry<String, Member> e = findMemberByName(t, args[2]);
                            if (e != null) {
                                t.members.values().stream().filter(m -> m.rank == Rank.LEADER).forEach(m -> m.rank = Rank.CO_LEADER);
                                e.getValue().rank = Rank.LEADER;
                                Player tgt = Bukkit.getPlayer(UUID.fromString(e.getKey())); if (tgt != null) tgt.sendMessage("§a" + t.name + " 팀의 리더로 지정되었습니다.");
                                save(); p.sendMessage("§a" + args[2] + " §a님을 리더로 지정했습니다."); return;
                            }
                        }
                    }
                    p.sendMessage("§c해당 플레이어를 팀에서 찾을 수 없습니다.");
                }
                default -> p.sendMessage("§c/team admin kick|disband|setleader <대상>");
            }
        }
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private Team teamOf(String uuid) {
        for (Team t : teams.values()) if (t.members.containsKey(uuid)) return t;
        return null;
    }

    private Team synchronized_teamOf(Player p) {
        synchronized (this) { return teamOf(p.getUniqueId().toString()); }
    }

    private Map.Entry<String, Member> findMemberByName(Team t, String name) {
        for (Map.Entry<String, Member> e : t.members.entrySet())
            if (e.getValue().name.equalsIgnoreCase(name)) return e;
        return null;
    }

    private String rankName(Rank r) {
        return switch (r) { case LEADER -> "리더"; case CO_LEADER -> "부리더"; case MODERATOR -> "운영자"; case MEMBER -> "멤버"; };
    }

    // ── HTTP routing ───────────────────────────────────────────────────────────

    @Override
    public void handleRoute(String path, String method, HttpExchange ex) throws Exception {
        if ("GET".equals(method)    && "/".equals(path))                                                        { listTeams(ex);                                 return; }
        if ("POST".equals(method)   && "/".equals(path))                                                        { createTeam(ex);                                return; }
        if ("DELETE".equals(method) && path.matches("/[^/]+"))                                                  { disbandTeam(ex, seg(path, 1));                 return; }
        if ("POST".equals(method)   && path.matches("/[^/]+/open"))                                             { toggleOpen(ex, seg(path, 1));                  return; }
        if ("POST".equals(method)   && path.matches("/[^/]+/members"))                                         { addMember(ex, seg(path, 1));                   return; }
        if ("POST".equals(method)   && path.matches("/[^/]+/members/[^/]+/(kick|promote|demote|ban|unban)")) {
            String[] p = path.split("/", -1);
            memberAction(ex, p[1], p[3], p[4]);
            return;
        }
        send(ex, 404, err("Not Found"));
    }

    private synchronized void listTeams(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        for (Team t : teams.values()) arr.add(toJson(t));
        send(ex, 200, arr);
    }

    private void createTeam(HttpExchange ex) throws Exception {
        JsonObject body = readBody(ex);
        String name = str(body, "name"), leaderName = str(body, "leader");
        if (blank(name))       { send(ex, 400, err("'name' 필드가 필요합니다"));   return; }
        if (blank(leaderName)) { send(ex, 400, err("'leader' 필드가 필요합니다")); return; }
        if (!name.matches("[a-zA-Z0-9_\\-]{3,20}")) { send(ex, 400, err("팀 이름은 3~20자 영문/숫자/_- 만 가능합니다")); return; }
        synchronized (this) {
            for (Team t : teams.values()) {
                if (t.name.equalsIgnoreCase(name)) { send(ex, 409, err("'" + name + "' 팀이 이미 존재합니다")); return; }
            }
        }
        final String ln = leaderName;
        OfflinePlayer op = runOnMain(() -> Bukkit.getOfflinePlayer(ln));
        String rName = op.getName() != null ? op.getName() : leaderName;
        Team t = Team.create(name);
        t.members.put(op.getUniqueId().toString(), new Member(rName, Rank.LEADER));
        synchronized (this) { teams.put(t.id, t); }
        save(); send(ex, 201, toJson(t));
    }

    private void disbandTeam(HttpExchange ex, String id) throws IOException {
        synchronized (this) {
            if (!teams.containsKey(id)) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            teams.get(id).members.keySet().forEach(u -> { try { teamChat.remove(UUID.fromString(u)); } catch (Exception ignored) {} });
            teams.remove(id);
        }
        save(); send(ex, 200, ok("팀이 해체되었습니다."));
    }

    private void toggleOpen(HttpExchange ex, String id) throws IOException {
        boolean now;
        synchronized (this) {
            Team t = teams.get(id);
            if (t == null) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            t.open = !t.open; now = t.open;
        }
        save(); send(ex, 200, ok(now ? "팀이 공개(Open) 상태로 변경되었습니다." : "팀이 비공개(Closed) 상태로 변경되었습니다."));
    }

    private void addMember(HttpExchange ex, String id) throws Exception {
        JsonObject body = readBody(ex);
        String playerName = str(body, "player");
        if (blank(playerName)) { send(ex, 400, err("'player' 필드가 필요합니다")); return; }
        synchronized (this) { if (!teams.containsKey(id)) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; } }
        final String pn = playerName;
        OfflinePlayer op = runOnMain(() -> Bukkit.getOfflinePlayer(pn));
        String uuid = op.getUniqueId().toString(), rName = op.getName() != null ? op.getName() : playerName;
        synchronized (this) {
            Team t = teams.get(id);
            if (t == null)                   { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            if (t.members.containsKey(uuid)) { send(ex, 409, err("이미 이 팀에 소속되어 있습니다")); return; }
            if (t.banned.containsKey(uuid))  { send(ex, 409, err("밴 상태입니다")); return; }
            for (Team other : teams.values())
                if (!other.id.equals(id) && other.members.containsKey(uuid))
                    { send(ex, 409, err("'" + other.name + "' 팀에 소속되어 있습니다")); return; }
            t.members.put(uuid, new Member(rName, Rank.MEMBER));
        }
        save(); send(ex, 200, ok("플레이어를 추가했습니다."));
    }

    private void memberAction(HttpExchange ex, String teamId, String uuid, String action) throws IOException {
        synchronized (this) {
            Team t = teams.get(teamId);
            if (t == null) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            switch (action) {
                case "kick" -> {
                    Member m = t.members.get(uuid); if (m == null) { send(ex, 404, err("멤버 없음")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("리더는 추방 불가")); return; }
                    t.members.remove(uuid); try { teamChat.remove(UUID.fromString(uuid)); } catch (Exception ignored) {}
                }
                case "promote" -> {
                    Member m = t.members.get(uuid); if (m == null) { send(ex, 404, err("멤버 없음")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("이미 리더")); return; }
                    if (m.rank == Rank.CO_LEADER) t.members.values().stream().filter(x -> x.rank == Rank.LEADER).forEach(x -> x.rank = Rank.CO_LEADER);
                    m.rank = m.rank.up();
                }
                case "demote" -> {
                    Member m = t.members.get(uuid); if (m == null) { send(ex, 404, err("멤버 없음")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("리더 강등 불가")); return; }
                    if (m.rank == Rank.MEMBER) { send(ex, 400, err("이미 최하위")); return; }
                    m.rank = m.rank.down();
                }
                case "ban" -> {
                    Member m = t.members.get(uuid); if (m == null && !t.banned.containsKey(uuid)) { send(ex, 404, err("멤버 없음")); return; }
                    if (m == null) { send(ex, 409, err("이미 밴")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("리더 밴 불가")); return; }
                    t.banned.put(uuid, m.name); t.members.remove(uuid); try { teamChat.remove(UUID.fromString(uuid)); } catch (Exception ignored) {}
                }
                case "unban" -> { if (!t.banned.containsKey(uuid)) { send(ex, 404, err("밴 목록에 없음")); return; } t.banned.remove(uuid); }
                default      -> { send(ex, 400, err("알 수 없는 액션")); return; }
            }
        }
        save(); send(ex, 200, ok("완료되었습니다."));
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    private JsonObject toJson(Team t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id); o.addProperty("name", t.name);
        o.addProperty("open", t.open); o.addProperty("memberCount", t.members.size());
        JsonArray mArr = new JsonArray();
        t.members.forEach((uuid, m) -> {
            JsonObject mo = new JsonObject();
            mo.addProperty("uuid", uuid); mo.addProperty("name", m.name); mo.addProperty("rank", m.rank.name());
            mo.addProperty("online", Bukkit.getPlayer(UUID.fromString(uuid)) != null);
            mArr.add(mo);
        });
        o.add("members", mArr);
        JsonArray bArr = new JsonArray();
        t.banned.forEach((uuid, name) -> { JsonObject bo = new JsonObject(); bo.addProperty("uuid", uuid); bo.addProperty("name", name); bArr.add(bo); });
        o.add("banned", bArr);
        return o;
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Team t : teams.values()) {
            String base = "teams." + t.id;
            cfg.set(base + ".name", t.name); cfg.set(base + ".open", t.open);
            t.members.forEach((u, m) -> { cfg.set(base + ".members." + u + ".name", m.name); cfg.set(base + ".members." + u + ".rank", m.rank.name()); });
            t.banned.forEach((u, n) -> cfg.set(base + ".banned." + u, n));
        }
        try { cfg.save(dataFile.toFile()); } catch (IOException e) { plugin.getLogger().warning("[Teams] 저장 오류: " + e.getMessage()); }
    }

    private synchronized void load() {
        if (!Files.exists(dataFile)) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile.toFile());
        ConfigurationSection root = cfg.getConfigurationSection("teams");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            String name = root.getString(id + ".name"); if (name == null) continue;
            Team t = new Team(id, name); t.open = root.getBoolean(id + ".open", false);
            ConfigurationSection ms = root.getConfigurationSection(id + ".members");
            if (ms != null) for (String u : ms.getKeys(false)) {
                String mName = ms.getString(u + ".name", u), rs = ms.getString(u + ".rank", "MEMBER");
                Rank rank; try { rank = Rank.valueOf(rs); } catch (Exception e) { rank = Rank.MEMBER; }
                t.members.put(u, new Member(mName, rank));
            }
            ConfigurationSection bs = root.getConfigurationSection(id + ".banned");
            if (bs != null) for (String u : bs.getKeys(false)) t.banned.put(u, root.getString(id + ".banned." + u, u));
            teams.put(id, t);
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private <T> T runOnMain(Callable<T> task) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> { try { f.complete(task.call()); } catch (Exception e) { f.completeExceptionally(e); } });
        try { return f.get(5, TimeUnit.SECONDS); } catch (TimeoutException e) { throw new RuntimeException("Main thread timeout"); } catch (ExecutionException e) { throw (Exception) e.getCause(); }
    }

    private JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] buf = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (buf.length > MAX_BODY) throw new IOException("Request body too large");
        String text = new String(buf, StandardCharsets.UTF_8);
        return text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
    }

    private void send(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    private JsonObject err(String msg) { JsonObject o = new JsonObject(); o.addProperty("error",   msg); return o; }
    private JsonObject ok(String msg)  { JsonObject o = new JsonObject(); o.addProperty("message", msg); return o; }
    private String str(JsonObject o, String key) { return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null; }
    private boolean blank(String s) { return s == null || s.isBlank(); }
    private String seg(String path, int n) { return path.split("/")[n]; }

    // ── HTML / JS ──────────────────────────────────────────────────────────────

    @Override public String getSectionHtml() { return HTML; }
    @Override public String getInitScript()  { return JS;   }

    private static final String HTML = """
        <style>
        .tm-list-item{display:flex;align-items:center;gap:10px;padding:9px 12px;border-radius:7px;cursor:pointer;transition:background .12s;border:1.5px solid transparent;}
        .tm-list-item:hover{background:var(--surface-2);}
        .tm-list-item.active{background:var(--accent-dim);border-color:rgba(99,102,241,.3);}
        .tm-list-item.active .tm-name{color:var(--accent-2);}
        .tm-badge{display:inline-flex;align-items:center;padding:2px 7px;border-radius:4px;font-size:11px;font-weight:600;letter-spacing:.3px;}
        .tm-badge-leader{background:rgba(234,179,8,.2);color:#ca8a04;}
        .tm-badge-coleader{background:rgba(249,115,22,.2);color:#ea580c;}
        .tm-badge-moderator{background:rgba(99,102,241,.2);color:var(--accent-2);}
        .tm-badge-member{background:var(--surface-3);color:var(--text-2);}
        .tm-input{background:var(--surface-2);border:1px solid var(--border-2);border-radius:6px;padding:6px 10px;color:var(--text);font-size:13px;font-family:var(--font);width:100%;box-sizing:border-box;transition:border-color .12s;}
        .tm-input:focus{outline:none;border-color:var(--accent);}
        .tm-dot{width:7px;height:7px;border-radius:50%;flex-shrink:0;}
        .tm-dot.online{background:#22c55e;} .tm-dot.offline{background:var(--border-2);}
        .tm-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:60px 20px;color:var(--text-3);}
        .tm-empty i{font-size:2.5rem;}
        .tm-modal-overlay{display:none;position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:9998;align-items:center;justify-content:center;}
        .tm-modal-overlay.open{display:flex;}
        .tm-modal{background:var(--surface-1);border:1px solid var(--border);border-radius:12px;padding:22px;width:340px;max-width:90vw;box-shadow:0 8px 40px rgba(0,0,0,.3);}
        .tm-modal h4{margin:0 0 16px;font-size:14.5px;font-weight:600;}
        .tm-modal-footer{display:flex;justify-content:flex-end;gap:8px;margin-top:16px;}
        .tm-lbl{font-size:10.5px;font-weight:600;text-transform:uppercase;letter-spacing:.6px;color:var(--text-3);margin-bottom:5px;}
        </style>
        <div style="display:grid;grid-template-columns:300px 1fr;gap:18px;align-items:start">
          <div class="card" style="padding:14px">
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
              <div style="font-size:13.5px;font-weight:600;display:flex;align-items:center;gap:7px"><i class="ti ti-users" style="font-size:16px;color:var(--accent-2)"></i> 팀 목록</div>
              <button class="btn btn-ghost btn-sm" onclick="tmShowCreate()"><i class="ti ti-plus"></i> 새 팀</button>
            </div>
            <div id="tm-list" style="display:flex;flex-direction:column;gap:3px;min-height:60px"><div style="color:var(--text-2);font-size:13px;padding:8px 4px">불러오는 중...</div></div>
          </div>
          <div id="tm-detail" class="card" style="padding:14px;display:none">
            <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">
              <div style="flex:1;min-width:0">
                <div id="tm-d-name" style="font-size:15px;font-weight:700;"></div>
                <div id="tm-d-meta" style="font-size:11.5px;color:var(--text-2);margin-top:2px"></div>
              </div>
              <button id="tm-d-open-btn" class="btn btn-ghost btn-sm" onclick="tmToggleOpen()"></button>
              <button class="btn btn-sm" style="background:rgba(239,68,68,.15);color:#ef4444;border:none" onclick="tmDisband()"><i class="ti ti-trash"></i> 해체</button>
            </div>
            <div style="display:flex;gap:8px;margin-bottom:14px">
              <input id="tm-d-add-input" class="tm-input" placeholder="플레이어 이름으로 추가…" style="flex:1" onkeydown="if(event.key==='Enter')tmAddMember()">
              <button class="btn btn-ghost btn-sm" onclick="tmAddMember()" style="flex-shrink:0"><i class="ti ti-user-plus"></i> 추가</button>
            </div>
            <div class="tm-lbl">멤버 (<span id="tm-d-count">0</span>명)</div>
            <div id="tm-d-members" style="display:flex;flex-direction:column;gap:3px;margin-bottom:10px"></div>
            <div id="tm-d-banned-wrap" style="display:none">
              <div class="tm-lbl" style="margin-top:12px">밴된 플레이어</div>
              <div id="tm-d-banned" style="display:flex;flex-direction:column;gap:3px"></div>
            </div>
          </div>
          <div id="tm-empty" class="card"><div class="tm-empty"><i class="ti ti-users-group"></i><div style="font-size:13px">팀을 선택하면 상세 정보를 볼 수 있습니다</div></div></div>
        </div>
        <div class="tm-modal-overlay" id="tm-create-modal">
          <div class="tm-modal">
            <h4><i class="ti ti-users" style="margin-right:6px;color:var(--accent-2)"></i>새 팀 만들기</h4>
            <div style="display:flex;flex-direction:column;gap:12px">
              <div><div class="tm-lbl">팀 이름</div><input id="tm-c-name" class="tm-input" placeholder="RedClan (3~20자, 영문/숫자/_-)" maxlength="20" onkeydown="if(event.key==='Enter')tmCreate()"></div>
              <div><div class="tm-lbl">리더 (플레이어 이름)</div><input id="tm-c-leader" class="tm-input" placeholder="플레이어 이름" onkeydown="if(event.key==='Enter')tmCreate()"></div>
            </div>
            <div class="tm-modal-footer">
              <button class="btn btn-ghost btn-sm" onclick="tmCloseCreate()">취소</button>
              <button class="btn btn-primary btn-sm" onclick="tmCreate()">만들기</button>
            </div>
          </div>
        </div>
        """;

    private static final String JS = """
        (function(){
          const BASE = '/api/module/teams';
          const tok  = () => localStorage.getItem('sd_token') || '';
          async function apiFetch(method, path, body) {
            const opts = { method, headers: { Authorization: 'Bearer ' + tok() } };
            if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
            return fetch(BASE + path, opts);
          }
          let currentId = null, allTeams = [];
          function esc(s) { return String(s??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
          function rankLabel(r) { return {LEADER:'리더',CO_LEADER:'부리더',MODERATOR:'운영자',MEMBER:'멤버'}[r]||r; }
          function rankBadge(r) { return {LEADER:'tm-badge-leader',CO_LEADER:'tm-badge-coleader',MODERATOR:'tm-badge-moderator',MEMBER:'tm-badge-member'}[r]||'tm-badge-member'; }
          function rankOrder(r) { return {LEADER:0,CO_LEADER:1,MODERATOR:2,MEMBER:3}[r]??9; }
          function tmToast(msg, type) { if (typeof toast==='function') toast(msg,type); }
          function renderList() {
            const el = document.getElementById('tm-list');
            if (!allTeams.length) { el.innerHTML='<div style="color:var(--text-2);font-size:13px;padding:8px 4px">팀이 없습니다.</div>'; return; }
            el.innerHTML = allTeams.map(t => `<div class="tm-list-item ${t.id===currentId?'active':''}" onclick="tmSelect('${esc(t.id)}')"><i class="ti ${t.open?'ti-lock-open-2':'ti-lock'}" style="font-size:14px;color:var(--text-2);flex-shrink:0"></i><div class="tm-name" style="flex:1;font-size:13px;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(t.name)}</div><span style="font-size:11px;color:var(--text-2)">${t.memberCount}명</span></div>`).join('');
          }
          function renderDetail(t) {
            document.getElementById('tm-empty').style.display='none';
            document.getElementById('tm-detail').style.display='';
            document.getElementById('tm-d-name').textContent=t.name;
            document.getElementById('tm-d-meta').textContent=(t.open?'🔓 공개팀':'🔒 비공개팀')+' · '+t.memberCount+'명';
            document.getElementById('tm-d-count').textContent=t.memberCount;
            document.getElementById('tm-d-open-btn').innerHTML=t.open?'<i class="ti ti-lock"></i> 비공개로':'<i class="ti ti-lock-open-2"></i> 공개로';
            const sorted=[...t.members].sort((a,b)=>rankOrder(a.rank)-rankOrder(b.rank));
            document.getElementById('tm-d-members').innerHTML=sorted.map(m=>`<div style="display:flex;align-items:center;gap:8px;padding:7px 10px;border-radius:7px;background:var(--surface-2)"><span class="tm-dot ${m.online?'online':'offline'}"></span><span style="font-size:13px;flex:1">${esc(m.name)}</span><span class="tm-badge ${rankBadge(m.rank)}">${rankLabel(m.rank)}</span>${m.rank!=='LEADER'?`<div style="display:flex;gap:3px;flex-shrink:0"><button class="btn btn-ghost btn-sm" title="승급" style="padding:3px 6px" onclick="tmMA('${esc(t.id)}','${esc(m.uuid)}','promote')"><i class="ti ti-chevron-up" style="font-size:12px"></i></button><button class="btn btn-ghost btn-sm" title="강등" style="padding:3px 6px" onclick="tmMA('${esc(t.id)}','${esc(m.uuid)}','demote')"><i class="ti ti-chevron-down" style="font-size:12px"></i></button><button class="btn btn-ghost btn-sm" title="추방" style="padding:3px 6px" onclick="tmMA('${esc(t.id)}','${esc(m.uuid)}','kick')"><i class="ti ti-user-minus" style="font-size:12px;color:#f97316"></i></button><button class="btn btn-ghost btn-sm" title="밴" style="padding:3px 6px" onclick="tmMA('${esc(t.id)}','${esc(m.uuid)}','ban')"><i class="ti ti-ban" style="font-size:12px;color:#ef4444"></i></button></div>`:'<span style="font-size:11px;color:var(--text-3);padding:0 4px">리더</span>'}</div>`).join('')||'<div style="color:var(--text-2);font-size:12.5px;padding:6px 4px">멤버가 없습니다.</div>';
            const bWrap=document.getElementById('tm-d-banned-wrap'), bEl=document.getElementById('tm-d-banned');
            if (t.banned.length) { bWrap.style.display=''; bEl.innerHTML=t.banned.map(b=>`<div style="display:flex;align-items:center;gap:8px;padding:7px 10px;border-radius:7px;background:rgba(239,68,68,.08);border:1px solid rgba(239,68,68,.2)"><i class="ti ti-ban" style="font-size:13px;color:#ef4444;flex-shrink:0"></i><span style="font-size:13px;flex:1;color:var(--text-2)">${esc(b.name)}</span><button class="btn btn-ghost btn-sm" style="padding:3px 7px" onclick="tmMA('${esc(t.id)}','${esc(b.uuid)}','unban')"><i class="ti ti-user-check" style="font-size:12px;color:#22c55e"></i> 언밴</button></div>`).join(''); }
            else bWrap.style.display='none';
          }
          window.tmSelect = function(id) { currentId=id; renderList(); const t=allTeams.find(x=>x.id===id); if(t) renderDetail(t); };
          window.tmShowCreate = function() { document.getElementById('tm-c-name').value=''; document.getElementById('tm-c-leader').value=''; document.getElementById('tm-create-modal').classList.add('open'); setTimeout(()=>document.getElementById('tm-c-name').focus(),50); };
          window.tmCloseCreate = function() { document.getElementById('tm-create-modal').classList.remove('open'); };
          document.getElementById('tm-create-modal').addEventListener('click',e=>{if(e.target===e.currentTarget)tmCloseCreate();});
          window.tmCreate = async function() {
            const name=document.getElementById('tm-c-name').value.trim(), leader=document.getElementById('tm-c-leader').value.trim();
            if (!name||!leader) { tmToast('팀 이름과 리더를 입력하세요','error'); return; }
            const r=await apiFetch('POST','/',{name,leader}), j=await r.json().catch(()=>({}));
            if (r.ok) { tmCloseCreate(); tmToast('"'+name+'" 팀이 생성되었습니다','success'); await tmLoad(); const c=allTeams.find(t=>t.name===name); if(c) tmSelect(c.id); }
            else tmToast(j.error||'팀 생성 실패','error');
          };
          window.tmDisband = async function() {
            if (!currentId) return;
            const t=allTeams.find(x=>x.id===currentId); if(!t) return;
            if (!confirm('"'+t.name+'" 팀을 해체하시겠습니까?')) return;
            const r=await apiFetch('DELETE','/'+currentId), j=await r.json().catch(()=>({}));
            if (r.ok) { tmToast('팀이 해체되었습니다','success'); currentId=null; document.getElementById('tm-detail').style.display='none'; document.getElementById('tm-empty').style.display=''; await tmLoad(); }
            else tmToast(j.error||'해체 실패','error');
          };
          window.tmToggleOpen = async function() {
            if (!currentId) return;
            const r=await apiFetch('POST','/'+currentId+'/open'), j=await r.json().catch(()=>({}));
            if (r.ok) { tmToast(j.message,'success'); await tmLoad(); if(currentId){const t=allTeams.find(x=>x.id===currentId);if(t)renderDetail(t);} }
            else tmToast(j.error||'실패','error');
          };
          window.tmAddMember = async function() {
            if (!currentId) return;
            const input=document.getElementById('tm-d-add-input'), name=input.value.trim(); if(!name) return;
            const r=await apiFetch('POST','/'+currentId+'/members',{player:name}), j=await r.json().catch(()=>({}));
            if (r.ok) { input.value=''; tmToast(j.message,'success'); await tmLoad(); if(currentId){const t=allTeams.find(x=>x.id===currentId);if(t)renderDetail(t);} }
            else tmToast(j.error||'추가 실패','error');
          };
          window.tmMA = async function(teamId, uuid, action) {
            if ((action==='kick'||action==='ban')&&!confirm({kick:'추방',ban:'밴'}[action]+' 하시겠습니까?')) return;
            const r=await apiFetch('POST','/'+teamId+'/members/'+uuid+'/'+action), j=await r.json().catch(()=>({}));
            if (r.ok) { tmToast(j.message,'success'); await tmLoad(); if(currentId){const t=allTeams.find(x=>x.id===currentId);if(t)renderDetail(t);} }
            else tmToast(j.error||'실패','error');
          };
          window.tmLoad = async function() {
            const r=await apiFetch('GET','/'); if(!r.ok){tmToast('팀 목록 로드 실패','error');return;}
            allTeams=await r.json(); renderList();
          };
          tmLoad();
        })();
        """;
}
