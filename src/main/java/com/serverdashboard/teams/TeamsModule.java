package com.serverdashboard.teams;

import com.google.gson.*;
import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.api.DashboardModule;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

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
        String name;
        Rank   rank;
        Member(String name, Rank rank) { this.name = name; this.rank = rank; }
    }

    static class Team {
        String id;
        String name;
        boolean open = false;
        final Map<String, Member> members = new LinkedHashMap<>(); // uuid → Member
        final Map<String, String> banned  = new LinkedHashMap<>(); // uuid → last known name

        Team(String id, String name) { this.id = id; this.name = name; }

        static Team create(String name) { return new Team(UUID.randomUUID().toString(), name); }
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private DashboardPlugin plugin;
    private Path dataFile;

    // ── DashboardModule identity ───────────────────────────────────────────────

    @Override public String getId()   { return "teams"; }
    @Override public String getName() { return "Teams"; }
    @Override public String getIcon() { return "ti-users"; }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onLoad(DashboardPlugin plugin) {
        this.plugin = plugin;
        dataFile = plugin.getDataFolder().toPath().resolve("modules/teams/teams.yml");
        try { Files.createDirectories(dataFile.getParent()); }
        catch (IOException e) { plugin.getLogger().warning("[Teams] Cannot create data dir: " + e.getMessage()); }
        load();
        plugin.getLogger().info("[Teams] Loaded " + teams.size() + " team(s).");
    }

    @Override
    public void onUnload() { save(); }

    // ── HTTP routing ───────────────────────────────────────────────────────────

    @Override
    public void handleRoute(String path, String method, HttpExchange ex) throws Exception {
        if ("GET".equals(method)    && "/".equals(path))                                                        { listTeams(ex);                                 return; }
        if ("POST".equals(method)   && "/".equals(path))                                                        { createTeam(ex);                                return; }
        if ("DELETE".equals(method) && path.matches("/[^/]+"))                                                  { disbandTeam(ex, seg(path, 1));                 return; }
        if ("POST".equals(method)   && path.matches("/[^/]+/open"))                                             { toggleOpen(ex, seg(path, 1));                  return; }
        if ("POST".equals(method)   && path.matches("/[^/]+/members"))                                         { addMember(ex, seg(path, 1));                   return; }
        if ("POST".equals(method)   && path.matches("/[^/]+/members/[^/]+/(kick|promote|demote|ban|unban)")) {
            String[] p = path.split("/", -1); // ["", teamId, "members", uuid, action]
            memberAction(ex, p[1], p[3], p[4]);
            return;
        }
        send(ex, 404, err("Not Found"));
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private synchronized void listTeams(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        for (Team t : teams.values()) arr.add(toJson(t));
        send(ex, 200, arr);
    }

    private void createTeam(HttpExchange ex) throws Exception {
        JsonObject body   = readBody(ex);
        String name       = str(body, "name");
        String leaderName = str(body, "leader");

        if (blank(name))       { send(ex, 400, err("'name' 필드가 필요합니다"));   return; }
        if (blank(leaderName)) { send(ex, 400, err("'leader' 필드가 필요합니다")); return; }
        if (!name.matches("[a-zA-Z0-9_\\-]{3,20}"))
            { send(ex, 400, err("팀 이름은 3~20자 영문/숫자/밑줄/하이픈만 가능합니다")); return; }

        synchronized (this) {
            for (Team t : teams.values())
                if (t.name.equalsIgnoreCase(name)) { send(ex, 409, err("'" + name + "' 팀이 이미 존재합니다")); return; }
        }

        final String ln = leaderName;
        OfflinePlayer op = runOnMain(() -> Bukkit.getOfflinePlayer(ln));
        String resolvedName = (op.getName() != null) ? op.getName() : leaderName;

        Team t = Team.create(name);
        t.members.put(op.getUniqueId().toString(), new Member(resolvedName, Rank.LEADER));

        synchronized (this) { teams.put(t.id, t); }
        save();
        send(ex, 201, toJson(t));
    }

    private void disbandTeam(HttpExchange ex, String id) throws IOException {
        synchronized (this) {
            if (!teams.containsKey(id)) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            teams.remove(id);
        }
        save();
        send(ex, 200, ok("팀이 해체되었습니다."));
    }

    private void toggleOpen(HttpExchange ex, String id) throws IOException {
        boolean nowOpen;
        synchronized (this) {
            Team t = teams.get(id);
            if (t == null) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            t.open = !t.open;
            nowOpen = t.open;
        }
        save();
        send(ex, 200, ok(nowOpen ? "팀이 공개(Open) 상태로 변경되었습니다." : "팀이 비공개(Closed) 상태로 변경되었습니다."));
    }

    private void addMember(HttpExchange ex, String id) throws Exception {
        JsonObject body   = readBody(ex);
        String playerName = str(body, "player");
        if (blank(playerName)) { send(ex, 400, err("'player' 필드가 필요합니다")); return; }

        synchronized (this) {
            if (!teams.containsKey(id)) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
        }

        final String pn = playerName;
        OfflinePlayer op = runOnMain(() -> Bukkit.getOfflinePlayer(pn));
        String uuid         = op.getUniqueId().toString();
        String resolvedName = (op.getName() != null) ? op.getName() : playerName;

        synchronized (this) {
            Team t = teams.get(id);
            if (t == null)                   { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }
            if (t.members.containsKey(uuid)) { send(ex, 409, err("해당 플레이어는 이미 이 팀에 소속되어 있습니다")); return; }
            if (t.banned.containsKey(uuid))  { send(ex, 409, err("해당 플레이어는 이 팀에서 밴 상태입니다")); return; }
            for (Team other : teams.values()) {
                if (!other.id.equals(id) && other.members.containsKey(uuid))
                    { send(ex, 409, err("해당 플레이어는 이미 '" + other.name + "' 팀에 소속되어 있습니다")); return; }
            }
            t.members.put(uuid, new Member(resolvedName, Rank.MEMBER));
        }
        save();
        send(ex, 200, ok("플레이어를 팀에 추가했습니다."));
    }

    private void memberAction(HttpExchange ex, String teamId, String uuid, String action) throws IOException {
        synchronized (this) {
            Team t = teams.get(teamId);
            if (t == null) { send(ex, 404, err("팀을 찾을 수 없습니다")); return; }

            switch (action) {
                case "kick" -> {
                    Member m = t.members.get(uuid);
                    if (m == null)             { send(ex, 404, err("멤버를 찾을 수 없습니다")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("리더는 추방할 수 없습니다. 먼저 리더를 위임하세요")); return; }
                    t.members.remove(uuid);
                }
                case "promote" -> {
                    Member m = t.members.get(uuid);
                    if (m == null)             { send(ex, 404, err("멤버를 찾을 수 없습니다")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("이미 리더입니다")); return; }
                    if (m.rank == Rank.CO_LEADER) {
                        // 리더십 이양: 기존 리더를 Co-Leader로 강등
                        t.members.values().stream()
                                .filter(x -> x.rank == Rank.LEADER)
                                .forEach(x -> x.rank = Rank.CO_LEADER);
                    }
                    m.rank = m.rank.up();
                }
                case "demote" -> {
                    Member m = t.members.get(uuid);
                    if (m == null)             { send(ex, 404, err("멤버를 찾을 수 없습니다")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("리더는 강등할 수 없습니다. 먼저 리더를 위임하세요")); return; }
                    if (m.rank == Rank.MEMBER) { send(ex, 400, err("이미 최하위 등급입니다")); return; }
                    m.rank = m.rank.down();
                }
                case "ban" -> {
                    Member m = t.members.get(uuid);
                    if (m == null && !t.banned.containsKey(uuid)) { send(ex, 404, err("멤버를 찾을 수 없습니다")); return; }
                    if (m == null) { send(ex, 409, err("이미 밴 상태입니다")); return; }
                    if (m.rank == Rank.LEADER) { send(ex, 400, err("리더는 밴할 수 없습니다")); return; }
                    t.banned.put(uuid, m.name);
                    t.members.remove(uuid);
                }
                case "unban" -> {
                    if (!t.banned.containsKey(uuid)) { send(ex, 404, err("밴 목록에 없습니다")); return; }
                    t.banned.remove(uuid);
                }
                default -> { send(ex, 400, err("알 수 없는 액션: " + action)); return; }
            }
        }
        save();
        send(ex, 200, ok("완료되었습니다."));
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    private JsonObject toJson(Team t) {
        JsonObject o = new JsonObject();
        o.addProperty("id",          t.id);
        o.addProperty("name",        t.name);
        o.addProperty("open",        t.open);
        o.addProperty("memberCount", t.members.size());

        JsonArray mArr = new JsonArray();
        t.members.forEach((uuid, m) -> {
            JsonObject mo = new JsonObject();
            mo.addProperty("uuid", uuid);
            mo.addProperty("name", m.name);
            mo.addProperty("rank", m.rank.name());
            mo.addProperty("online", Bukkit.getPlayer(UUID.fromString(uuid)) != null);
            mArr.add(mo);
        });
        o.add("members", mArr);

        JsonArray bArr = new JsonArray();
        t.banned.forEach((uuid, name) -> {
            JsonObject bo = new JsonObject();
            bo.addProperty("uuid", uuid);
            bo.addProperty("name", name);
            bArr.add(bo);
        });
        o.add("banned", bArr);
        return o;
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Team t : teams.values()) {
            String base = "teams." + t.id;
            cfg.set(base + ".name", t.name);
            cfg.set(base + ".open", t.open);
            t.members.forEach((uuid, m) -> {
                cfg.set(base + ".members." + uuid + ".name", m.name);
                cfg.set(base + ".members." + uuid + ".rank", m.rank.name());
            });
            t.banned.forEach((uuid, name) -> cfg.set(base + ".banned." + uuid, name));
        }
        try { cfg.save(dataFile.toFile()); }
        catch (IOException e) { plugin.getLogger().warning("[Teams] 저장 오류: " + e.getMessage()); }
    }

    private synchronized void load() {
        if (!Files.exists(dataFile)) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile.toFile());
        ConfigurationSection root = cfg.getConfigurationSection("teams");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            String name = root.getString(id + ".name");
            if (name == null) continue;
            Team t = new Team(id, name);
            t.open = root.getBoolean(id + ".open", false);

            ConfigurationSection ms = root.getConfigurationSection(id + ".members");
            if (ms != null) {
                for (String uuid : ms.getKeys(false)) {
                    String mName   = ms.getString(uuid + ".name", uuid);
                    String rankStr = ms.getString(uuid + ".rank", "MEMBER");
                    Rank rank;
                    try { rank = Rank.valueOf(rankStr); } catch (Exception e) { rank = Rank.MEMBER; }
                    t.members.put(uuid, new Member(mName, rank));
                }
            }

            ConfigurationSection bs = root.getConfigurationSection(id + ".banned");
            if (bs != null) {
                for (String uuid : bs.getKeys(false)) {
                    t.banned.put(uuid, root.getString(id + ".banned." + uuid, uuid));
                }
            }

            teams.put(id, t);
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private <T> T runOnMain(Callable<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(task.call()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException e) { throw new RuntimeException("메인 스레드 타임아웃"); }
        catch (ExecutionException e) { throw (Exception) e.getCause(); }
    }

    private JsonObject readBody(HttpExchange ex) throws IOException {
        byte[] buf = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (buf.length > MAX_BODY) throw new IOException("요청 본문이 너무 큽니다");
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

    // ── HTML ───────────────────────────────────────────────────────────────────

    @Override public String getSectionHtml() { return HTML; }
    @Override public String getInitScript()  { return JS;   }

    private static final String HTML = """
        <style>
        .tm-list-item{
          display:flex;align-items:center;gap:10px;padding:9px 12px;
          border-radius:7px;cursor:pointer;transition:background .12s;
          border:1.5px solid transparent;
        }
        .tm-list-item:hover{background:var(--surface-2);}
        .tm-list-item.active{background:var(--accent-dim);border-color:rgba(99,102,241,.3);}
        .tm-list-item.active .tm-name{color:var(--accent-2);}
        .tm-badge{
          display:inline-flex;align-items:center;padding:2px 7px;border-radius:4px;
          font-size:11px;font-weight:600;letter-spacing:.3px;
        }
        .tm-badge-leader    {background:rgba(234,179,8,.2);color:#ca8a04;}
        .tm-badge-coleader  {background:rgba(249,115,22,.2);color:#ea580c;}
        .tm-badge-moderator {background:rgba(99,102,241,.2);color:var(--accent-2);}
        .tm-badge-member    {background:var(--surface-3);color:var(--text-2);}
        .tm-input{
          background:var(--surface-2);border:1px solid var(--border-2);
          border-radius:6px;padding:6px 10px;color:var(--text);
          font-size:13px;font-family:var(--font);
          width:100%;box-sizing:border-box;transition:border-color .12s;
        }
        .tm-input:focus{outline:none;border-color:var(--accent);}
        .tm-dot{
          width:7px;height:7px;border-radius:50%;flex-shrink:0;
        }
        .tm-dot.online{background:#22c55e;}
        .tm-dot.offline{background:var(--border-2);}
        .tm-empty{
          display:flex;flex-direction:column;align-items:center;justify-content:center;
          gap:10px;padding:60px 20px;color:var(--text-3);
        }
        .tm-empty i{font-size:2.5rem;}
        .tm-modal-overlay{
          display:none;position:fixed;inset:0;background:rgba(0,0,0,.5);
          z-index:9998;align-items:center;justify-content:center;
        }
        .tm-modal-overlay.open{display:flex;}
        .tm-modal{
          background:var(--surface-1);border:1px solid var(--border);border-radius:12px;
          padding:22px;width:340px;max-width:90vw;
          box-shadow:0 8px 40px rgba(0,0,0,.3);
        }
        .tm-modal h4{margin:0 0 16px;font-size:14.5px;font-weight:600;}
        .tm-modal-footer{display:flex;justify-content:flex-end;gap:8px;margin-top:16px;}
        .tm-lbl{font-size:10.5px;font-weight:600;text-transform:uppercase;letter-spacing:.6px;color:var(--text-3);margin-bottom:5px;}
        </style>

        <div style="display:grid;grid-template-columns:300px 1fr;gap:18px;align-items:start">

          <!-- Left: team list -->
          <div class="card" style="padding:14px">
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
              <div style="font-size:13.5px;font-weight:600;display:flex;align-items:center;gap:7px">
                <i class="ti ti-users" style="font-size:16px;color:var(--accent-2)"></i> 팀 목록
              </div>
              <button class="btn btn-ghost btn-sm" onclick="tmShowCreate()">
                <i class="ti ti-plus"></i> 새 팀
              </button>
            </div>
            <div id="tm-list" style="display:flex;flex-direction:column;gap:3px;min-height:60px">
              <div style="color:var(--text-2);font-size:13px;padding:8px 4px">불러오는 중...</div>
            </div>
          </div>

          <!-- Right: team detail -->
          <div id="tm-detail" class="card" style="padding:14px;display:none">
            <!-- Header -->
            <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">
              <div style="flex:1;min-width:0">
                <div id="tm-d-name" style="font-size:15px;font-weight:700;"></div>
                <div id="tm-d-meta" style="font-size:11.5px;color:var(--text-2);margin-top:2px"></div>
              </div>
              <button id="tm-d-open-btn" class="btn btn-ghost btn-sm" onclick="tmToggleOpen()"></button>
              <button class="btn btn-sm" style="background:rgba(239,68,68,.15);color:#ef4444;border:none" onclick="tmDisband()">
                <i class="ti ti-trash"></i> 해체
              </button>
            </div>

            <!-- Add member -->
            <div style="display:flex;gap:8px;margin-bottom:14px">
              <input id="tm-d-add-input" class="tm-input" placeholder="플레이어 이름으로 추가…" style="flex:1"
                onkeydown="if(event.key==='Enter')tmAddMember()">
              <button class="btn btn-ghost btn-sm" onclick="tmAddMember()" style="flex-shrink:0">
                <i class="ti ti-user-plus"></i> 추가
              </button>
            </div>

            <!-- Members table -->
            <div class="tm-lbl">멤버 (<span id="tm-d-count">0</span>명)</div>
            <div id="tm-d-members" style="display:flex;flex-direction:column;gap:3px;margin-bottom:10px"></div>

            <!-- Banned section -->
            <div id="tm-d-banned-wrap" style="display:none">
              <div class="tm-lbl" style="margin-top:12px">밴된 플레이어</div>
              <div id="tm-d-banned" style="display:flex;flex-direction:column;gap:3px"></div>
            </div>
          </div>

          <!-- Right: empty state -->
          <div id="tm-empty" class="card">
            <div class="tm-empty">
              <i class="ti ti-users-group"></i>
              <div style="font-size:13px">팀을 선택하면 상세 정보를 볼 수 있습니다</div>
            </div>
          </div>

        </div>

        <!-- Create Team Modal -->
        <div class="tm-modal-overlay" id="tm-create-modal">
          <div class="tm-modal">
            <h4><i class="ti ti-users" style="margin-right:6px;color:var(--accent-2)"></i>새 팀 만들기</h4>
            <div style="display:flex;flex-direction:column;gap:12px">
              <div>
                <div class="tm-lbl">팀 이름</div>
                <input id="tm-c-name" class="tm-input" placeholder="RedClan (3~20자, 영문/숫자/_-)"
                  maxlength="20" onkeydown="if(event.key==='Enter')tmCreate()">
              </div>
              <div>
                <div class="tm-lbl">리더 (플레이어 이름)</div>
                <input id="tm-c-leader" class="tm-input" placeholder="플레이어 이름"
                  onkeydown="if(event.key==='Enter')tmCreate()">
              </div>
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

          let currentId  = null;
          let allTeams   = [];

          // ── Utils ───────────────────────────────────────────────────────────

          function esc(s) {
            return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
          }

          function rankLabel(r) {
            return { LEADER:'리더', CO_LEADER:'부리더', MODERATOR:'운영자', MEMBER:'멤버' }[r] || r;
          }

          function rankBadge(r) {
            return { LEADER:'tm-badge-leader', CO_LEADER:'tm-badge-coleader',
                     MODERATOR:'tm-badge-moderator', MEMBER:'tm-badge-member' }[r] || 'tm-badge-member';
          }

          function rankOrder(r) {
            return { LEADER:0, CO_LEADER:1, MODERATOR:2, MEMBER:3 }[r] ?? 9;
          }

          function tmToast(msg, type) {
            if (typeof toast === 'function') { toast(msg, type); return; }
            console.log('[Teams]', type, msg);
          }

          // ── Rendering ───────────────────────────────────────────────────────

          function renderList() {
            const el = document.getElementById('tm-list');
            if (!allTeams.length) {
              el.innerHTML = '<div style="color:var(--text-2);font-size:13px;padding:8px 4px">팀이 없습니다.</div>';
              return;
            }
            el.innerHTML = allTeams.map(t => `
              <div class="tm-list-item ${t.id === currentId ? 'active' : ''}" onclick="tmSelect('${esc(t.id)}')">
                <i class="ti ${t.open ? 'ti-lock-open-2' : 'ti-lock'}"
                   style="font-size:14px;color:var(--text-2);flex-shrink:0"></i>
                <div class="tm-name" style="flex:1;font-size:13px;font-weight:500;
                  overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(t.name)}</div>
                <span style="font-size:11px;color:var(--text-2)">${t.memberCount}명</span>
              </div>`).join('');
          }

          function renderDetail(t) {
            document.getElementById('tm-empty').style.display  = 'none';
            document.getElementById('tm-detail').style.display = '';

            document.getElementById('tm-d-name').textContent  = t.name;
            document.getElementById('tm-d-meta').textContent  =
              (t.open ? '🔓 공개팀' : '🔒 비공개팀') + ' · ' + t.memberCount + '명';
            document.getElementById('tm-d-count').textContent = t.memberCount;

            const openBtn = document.getElementById('tm-d-open-btn');
            openBtn.innerHTML = t.open
              ? '<i class="ti ti-lock"></i> 비공개로 변경'
              : '<i class="ti ti-lock-open-2"></i> 공개로 변경';

            // Members
            const sorted = [...t.members].sort((a, b) => rankOrder(a.rank) - rankOrder(b.rank));
            const mEl = document.getElementById('tm-d-members');
            mEl.innerHTML = sorted.map(m => `
              <div style="display:flex;align-items:center;gap:8px;padding:7px 10px;
                border-radius:7px;background:var(--surface-2)">
                <span class="tm-dot ${m.online ? 'online' : 'offline'}"></span>
                <span style="font-size:13px;flex:1">${esc(m.name)}</span>
                <span class="tm-badge ${rankBadge(m.rank)}">${rankLabel(m.rank)}</span>
                ${m.rank !== 'LEADER' ? `
                <div style="display:flex;gap:3px;flex-shrink:0">
                  <button class="btn btn-ghost btn-sm" title="승급" style="padding:3px 6px"
                    onclick="tmMemberAction('${esc(t.id)}','${esc(m.uuid)}','promote')">
                    <i class="ti ti-chevron-up" style="font-size:12px"></i>
                  </button>
                  <button class="btn btn-ghost btn-sm" title="강등" style="padding:3px 6px"
                    onclick="tmMemberAction('${esc(t.id)}','${esc(m.uuid)}','demote')">
                    <i class="ti ti-chevron-down" style="font-size:12px"></i>
                  </button>
                  <button class="btn btn-ghost btn-sm" title="추방" style="padding:3px 6px"
                    onclick="tmMemberAction('${esc(t.id)}','${esc(m.uuid)}','kick')">
                    <i class="ti ti-user-minus" style="font-size:12px;color:#f97316"></i>
                  </button>
                  <button class="btn btn-ghost btn-sm" title="밴" style="padding:3px 6px"
                    onclick="tmMemberAction('${esc(t.id)}','${esc(m.uuid)}','ban')">
                    <i class="ti ti-ban" style="font-size:12px;color:#ef4444"></i>
                  </button>
                </div>` : '<span style="font-size:11px;color:var(--text-3);padding:0 4px">리더</span>'}
              </div>`).join('') || '<div style="color:var(--text-2);font-size:12.5px;padding:6px 4px">멤버가 없습니다.</div>';

            // Banned
            const bWrap = document.getElementById('tm-d-banned-wrap');
            const bEl   = document.getElementById('tm-d-banned');
            if (t.banned.length) {
              bWrap.style.display = '';
              bEl.innerHTML = t.banned.map(b => `
                <div style="display:flex;align-items:center;gap:8px;padding:7px 10px;
                  border-radius:7px;background:rgba(239,68,68,.08);border:1px solid rgba(239,68,68,.2)">
                  <i class="ti ti-ban" style="font-size:13px;color:#ef4444;flex-shrink:0"></i>
                  <span style="font-size:13px;flex:1;color:var(--text-2)">${esc(b.name)}</span>
                  <button class="btn btn-ghost btn-sm" style="padding:3px 7px"
                    onclick="tmMemberAction('${esc(t.id)}','${esc(b.uuid)}','unban')">
                    <i class="ti ti-user-check" style="font-size:12px;color:#22c55e"></i> 언밴
                  </button>
                </div>`).join('');
            } else {
              bWrap.style.display = 'none';
            }
          }

          // ── Actions ─────────────────────────────────────────────────────────

          window.tmSelect = function(id) {
            currentId = id;
            renderList();
            const t = allTeams.find(x => x.id === id);
            if (t) renderDetail(t);
          };

          window.tmShowCreate = function() {
            document.getElementById('tm-c-name').value   = '';
            document.getElementById('tm-c-leader').value = '';
            document.getElementById('tm-create-modal').classList.add('open');
            setTimeout(() => document.getElementById('tm-c-name').focus(), 50);
          };

          window.tmCloseCreate = function() {
            document.getElementById('tm-create-modal').classList.remove('open');
          };

          document.getElementById('tm-create-modal').addEventListener('click', e => {
            if (e.target === e.currentTarget) tmCloseCreate();
          });

          window.tmCreate = async function() {
            const name   = document.getElementById('tm-c-name').value.trim();
            const leader = document.getElementById('tm-c-leader').value.trim();
            if (!name || !leader) { tmToast('팀 이름과 리더를 입력하세요', 'error'); return; }
            const r = await apiFetch('POST', '/', { name, leader });
            const j = await r.json().catch(() => ({}));
            if (r.ok) {
              tmCloseCreate();
              tmToast('"' + name + '" 팀이 생성되었습니다', 'success');
              await tmLoad();
              const created = allTeams.find(t => t.name === name);
              if (created) tmSelect(created.id);
            } else {
              tmToast(j.error || '팀 생성 실패', 'error');
            }
          };

          window.tmDisband = async function() {
            if (!currentId) return;
            const t = allTeams.find(x => x.id === currentId);
            if (!t) return;
            if (!confirm('"' + t.name + '" 팀을 해체하시겠습니까? 이 작업은 되돌릴 수 없습니다.')) return;
            const r = await apiFetch('DELETE', '/' + currentId);
            const j = await r.json().catch(() => ({}));
            if (r.ok) {
              tmToast('팀이 해체되었습니다', 'success');
              currentId = null;
              document.getElementById('tm-detail').style.display = 'none';
              document.getElementById('tm-empty').style.display  = '';
              await tmLoad();
            } else {
              tmToast(j.error || '해체 실패', 'error');
            }
          };

          window.tmToggleOpen = async function() {
            if (!currentId) return;
            const r = await apiFetch('POST', '/' + currentId + '/open');
            const j = await r.json().catch(() => ({}));
            if (r.ok) { tmToast(j.message, 'success'); await tmLoad(); if (currentId) { const t = allTeams.find(x => x.id === currentId); if (t) renderDetail(t); } }
            else tmToast(j.error || '실패', 'error');
          };

          window.tmAddMember = async function() {
            if (!currentId) return;
            const input = document.getElementById('tm-d-add-input');
            const name  = input.value.trim();
            if (!name) return;
            const r = await apiFetch('POST', '/' + currentId + '/members', { player: name });
            const j = await r.json().catch(() => ({}));
            if (r.ok) { input.value = ''; tmToast(j.message, 'success'); await tmLoad(); if (currentId) { const t = allTeams.find(x => x.id === currentId); if (t) renderDetail(t); } }
            else tmToast(j.error || '추가 실패', 'error');
          };

          window.tmMemberAction = async function(teamId, uuid, action) {
            const labels = { kick:'추방', ban:'밴', promote:'승급', demote:'강등', unban:'언밴' };
            if ((action === 'kick' || action === 'ban') && !confirm(labels[action] + ' 하시겠습니까?')) return;
            const r = await apiFetch('POST', '/' + teamId + '/members/' + uuid + '/' + action);
            const j = await r.json().catch(() => ({}));
            if (r.ok) { tmToast(j.message, 'success'); await tmLoad(); if (currentId) { const t = allTeams.find(x => x.id === currentId); if (t) renderDetail(t); } }
            else tmToast(j.error || '실패', 'error');
          };

          // ── Load ────────────────────────────────────────────────────────────

          window.tmLoad = async function() {
            const r = await apiFetch('GET', '/');
            if (!r.ok) { tmToast('팀 목록을 불러오지 못했습니다', 'error'); return; }
            allTeams = await r.json();
            renderList();
          };

          tmLoad();
        })();
        """;
}
