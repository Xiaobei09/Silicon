package silicon.audio;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Http;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static mindustry.Vars.net;
import static mindustry.Vars.netServer;

/**
 * 音乐播放器多人网络层：让其他玩家按距离远近听到本机播放的音乐。
 * <p>
 * 链路：播放者(owner) 上报服务端 → 服务端转发给所有客户端 → 各客户端按「自己到 owner 的距离」
 * 在本地 3D 定位播放同一曲目。多个 owner 可叠加多个声源。
 * <p>
 * 曲目资源分发：
 * - 内置原版音乐：所有人本地都有，仅广播元数据即可播放
 * - URL 曲目：广播 URL，接收端用 Http 下载到本地缓存后播放（本地缓存优先复用）
 * - 本地路径曲目：播放者读文件字节，二进制分块广播，接收端重组写缓存后播放
 *
 * 协议（自定义包）：
 * - "mp-sync" (String, 可靠)：op=play/pause/resume/stop/next + 曲目元数据
 * - "mp-meta"(String, 可靠)：本地路径曲目的二进制分块元数据
 * - "mp-chunk"(Binary, 可靠)：二进制分块（头部含 hash+总块数+块索引）
 * - "mp-pos"(String, 不可靠)：owner 世界坐标
 */
public class MusicNetwork {
    private static final int CHUNK_SIZE = 24 * 1024;
    private static final int HEADER_LEN = 16 + 4 + 4;
    private static final String MSG_SYNC = "mp-sync";
    private static final String MSG_META = "mp-meta";
    private static final String MSG_CHUNK = "mp-chunk";
    private static final String MSG_POS = "mp-pos";

    /** ownerUuid → 其本地路径曲目分块接收状态 */
    private static final ObjectMap<String, ChunkRecv> recv = new ObjectMap<>();
    /** ownerUuid → 播放曲目 hash（mp-pos 定位用） */
    private static final ObjectMap<String, String> ownerHash = new ObjectMap<>();
    /** ownerUuid → 最近已知坐标 */
    private static final ObjectMap<String, float[]> ownerPos = new ObjectMap<>();
    /** hash → 该 URL 下载完成后的待执行回调；命中即表示该 hash 正在下载中（去重，防止对同一 URL 并发多次 Http） */
    private static final ObjectMap<String, arc.struct.Seq<Runnable>> pendingDownloads = new ObjectMap<>();

    private static float lastPosTick = 0;
    private static boolean initialized = false;

    /** 本地路径曲目分块发送状态：流式读盘（复用单块缓冲，不整文件读入内存），帧速分片，避免一次灌爆可靠包队列 */
    private static Fi pendingFi;
    private static java.io.InputStream pendingStream;
    private static final byte[] chunkBuf = new byte[CHUNK_SIZE];
    private static long pendingTotal;
    private static String pendingHash;
    private static int pendingIdx;
    private static int pendingChunks;
    /** 每 tick 最多发送的分块数 */
    private static final int CHUNKS_PER_TICK = 12;

    // ------------------------------------------------------------------
    // 接收侧/中转侧安全上限
    //
    // 联机对端不可信：分块包由任意客户端发出、经服务端原样转发，因此所有
    // 「对方可控」的数值在落盘/分配内存之前必须先夹取，否则可被用于 OOM、
    // 磁盘写爆与越界写文件。
    // ------------------------------------------------------------------

    /** hash 唯一合法形态：SHA-256 前 16 位十六进制（大小写均可——实测本机生成的是大写，若只收小写会把自己/联机分块全拦掉）。
     *  严格白名单同时挡住路径穿越（`..`/分隔符）与任意扩展名写入。 */
    private static final java.util.regex.Pattern HASH_PATTERN = java.util.regex.Pattern.compile("^[0-9a-fA-F]{16}$");
    /** 分块数上限：24KB × 4096 ≈ 96MB；用于阻断 chunkCount=Integer.MAX_VALUE 造成的巨型数组分配 */
    private static final int MAX_CHUNK_COUNT = 4096;
    /** 单曲接收字节上限（64MB） */
    private static final long MAX_SHARE_BYTES = 64L * 1024 * 1024;
    /** 服务端转发速率上限：单玩家 2MB/s（1 秒滑动窗口） */
    private static final long MAX_RELAY_BYTES_PER_SEC = 2L * 1024 * 1024;
    /** 字符串控制包（mp-sync/mp-meta/mp-pos）的中继上限：单玩家 16KB/s。
     *  这些包很小（几百字节），原先**完全没有速率限制**——刷字符串包同样能让服务端替它向全场放大流量。 */
    private static final long MAX_RELAY_STRING_BYTES_PER_SEC = 16L * 1024;
    /** 同时进行的「分块接收」上限：每个条目会分配 boolean[chunkCount]（≤4KB）并建一个 ≤64MB 的 .part 文件，
     *  不限个数时单个客户端广播 N 个 hash 就能同时吃满内存与磁盘（.part 不参与 LRU 淘汰） */
    private static final int MAX_CONCURRENT_RECV = 4;
    /** 分块接收静默超时：超过该时间没有任何分块到达就丢弃状态并删除 .part（防挂死的分享长期占盘） */
    private static final long RECV_TIMEOUT_MS = 90_000L;
    /** 静默清理检查间隔（毫秒） */
    private static final long RECV_SWEEP_INTERVAL_MS = 5_000L;
    /** 单曲分享下载上限：与分块路径同一上限，防止对端给一个「超大 URL」把接收端内存打爆 */
    private static final long MAX_DOWNLOAD_BYTES = MAX_SHARE_BYTES;
    /** owner/坐标表的条目上限（owner 字符串由对端决定，不设上限就是无界增长） */
    private static final int MAX_OWNER_ENTRIES = 128;

    /** hash 是否为合法形态（接收任何 hash 之前都必须过这一关） */
    static boolean isValidHash(String hash) {
        return hash != null && HASH_PATTERN.matcher(hash).matches();
    }

    /** 服务端：玩家 key → 该玩家当前广播的曲目 hash；分块必须与其匹配才转发 */
    private static final ObjectMap<String, String> relayHash = new ObjectMap<>();
    /** 服务端：玩家 key → [窗口起点(ms), 本窗口已转发字节] */
    private static final ObjectMap<String, long[]> relayQuota = new ObjectMap<>();

    private MusicNetwork() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        // netClient / netServer 在 mod init 阶段可能尚未创建：
        // 延迟到对应端加载完成事件注册处理器，确保已就绪且只注册一次。
        Events.on(EventType.ClientLoadEvent.class, e -> registerClientHandlers());
        Events.on(EventType.ServerLoadEvent.class, e -> registerServerHandlers());

        // 玩家离开时清理其声源与坐标，避免脱离后残留播放
        Events.on(EventType.PlayerLeave.class, e -> {
            String uuid = e.player == null ? null : e.player.uuid();
            if (uuid == null || uuid.isEmpty()) return;
            ownerPos.remove(uuid);
            String inFlight = ownerHash.remove(uuid); // 防离开玩家的在途下载/分块收齐后仍按旧挂账建立声源
            if (inFlight != null) recv.remove(inFlight); // recv 按 hash 建键（此前误用 uuid，等于没清）
            // 服务端转发层的账也要清（否则长寿命服务器上按 uuid 无界累积）
            relayHash.remove(uuid);
            relayQuota.remove(uuid);
            MusicPlayer.stopRemoteVoice(uuid);
        });

        // 周期性上报本机坐标（若本机正在本地播放）
        Events.run(EventType.Trigger.update, MusicNetwork::tick);
    }

    private static boolean clientRegistered = false;

    private static void registerClientHandlers() {
        if (clientRegistered) return;
        mindustry.core.NetClient nc = mindustry.Vars.netClient;
        if (nc == null) return;
        clientRegistered = true;
        nc.addPacketHandler(MSG_SYNC, MusicNetwork::onSync);
        nc.addPacketHandler(MSG_META, MusicNetwork::onMeta);
        nc.addPacketHandler(MSG_POS, MusicNetwork::onPos);
        nc.addBinaryPacketHandler(MSG_CHUNK, MusicNetwork::onChunk);
    }

    private static boolean serverRegistered = false;

    private static void registerServerHandlers() {
        if (serverRegistered) return;
        if (netServer == null) return;
        serverRegistered = true;
        // 服务端收到客户端上报并转发给所有客户端。
        // 这里同时充当信任边界：记录「谁在广播哪个 hash」，随后只转发与其匹配的分块，
        // 并施加速率上限——否则任意客户端都能让服务端替它向全场推送任意字节。
        netServer.addPacketHandler(MSG_SYNC, (p, data) -> {
            if (p == null || data == null) return;
            String key = playerKey(p);
            try {
                // 信任边界其一：owner 必须等于发送者本人。此前原样转发客户端给的 owner，
                // 任意客户端都能把声源挂到别人名下（冒名 + 任意坐标）。
                String declaredOwner = extract(data, "owner");
                if (declaredOwner == null || !declaredOwner.equals(key)) return;
                String op = extract(data, "op");
                String hash = extract(data, "hash");
                if ("stop".equals(op)) {
                    relayHash.remove(key);
                } else if (op != null && (op.equals("play") || op.equals("next"))) {
                    if (isValidHash(hash)) relayHash.put(key, hash);
                    else relayHash.remove(key);
                }
            } catch (Exception ignored) {
            }
            if (!allowStringRelay(key, data.length())) return; // 字符串控制包也要限速
        });
        netServer.addPacketHandler(MSG_META, (p, data) -> {
            if (p == null || data == null) return;
            try {
                // 信任边界其二：meta 的 owner 同样必须等于发送者
                String declaredOwner = extract(data, "owner");
                if (declaredOwner == null || !declaredOwner.equals(playerKey(p))) return;
                String hash = extract(data, "hash");
                if (isValidHash(hash)) relayHash.put(playerKey(p), hash);
            } catch (Exception ignored) {
            }
            if (!allowStringRelay(playerKey(p), data.length())) return;
        });
        netServer.addPacketHandler(MSG_POS, (p, data) -> {
            if (p == null || data == null) return;
            // 信任边界其三：坐标包的 owner（"owner|hash|x|y"）必须等于发送者，
            // 否则任意客户端都能把别人的声源坐标刷到任意位置
            try {
                int bar = data == null ? -1 : data.indexOf('|');
                if (bar <= 0 || !data.substring(0, bar).equals(playerKey(p))) return;
            } catch (Exception ignored) {
                return;
            }
            if (!allowStringRelay(playerKey(p), data.length())) return;
        });
        netServer.addBinaryPacketHandler(MSG_CHUNK, (p, bytes) -> {
            if (p == null || bytes == null) return;
            if (bytes.length <= HEADER_LEN || bytes.length > HEADER_LEN + CHUNK_SIZE) return; // 块长越界
            String hash = readHash(bytes);
            if (!isValidHash(hash)) return;
            String key = playerKey(p);
            String announced = relayHash.get(key);
            if (announced == null || !announced.equals(hash)) {
                // 该玩家并未广播此曲目（或已停止）→ 丢弃，防冒名/无主数据
                return;
            }
            if (!allowRelay(key, bytes.length)) return; // 速率上限
            Call.clientBinaryPacketReliable(MSG_CHUNK, bytes);
        });
    }

    /** 玩家在转发层的唯一 key（uuid 优先，回退 id） */
    private static String playerKey(Player p) {
        if (p == null) return "none";
        String uuid = p.uuid();
        return (uuid != null && !uuid.isEmpty()) ? uuid : ("id:" + p.id());
    }

    /** 从分块包头读出 hash（16 字节 UTF-8） */
    private static String readHash(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_LEN) return null;
        byte[] hashBytes = new byte[16];
        System.arraycopy(bytes, 0, hashBytes, 0, 16);
        return new String(hashBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** 每秒转发字节配额（简单滑动窗口，超限即丢包，避免服务端被单玩家刷爆上行） */
    private static boolean allowRelay(String key, int bytes) {
        return allowRelay(key, bytes, MAX_RELAY_BYTES_PER_SEC);
    }

    /** 字符串控制包的中继配额（上限更小；与分块共用同一张窗口表，key 加前缀区分） */
    private static boolean allowStringRelay(String key, int bytes) {
        return allowRelay("s:" + key, bytes, MAX_RELAY_STRING_BYTES_PER_SEC);
    }

    private static boolean allowRelay(String key, int bytes, long limit) {
        long now = System.currentTimeMillis();
        long[] q = relayQuota.get(key);
        if (q == null || now - q[0] >= 1000L) {
            q = new long[]{now, 0L};
            relayQuota.put(key, q);
        }
        q[1] += bytes;
        return q[1] <= limit;
    }

    // ------------------------------------------------------------------
    // 发送侧（本机为播放者时触发）
    // ------------------------------------------------------------------

    /** 本机本地播放状态变化时由 MusicPlayer 回调：广播给其他玩家 */
    static void notifyLocalChanged(String op) {
        if (!net.active()) return;
        // 发送侧门控：canShare（enabled 且 shareEnabled）——enabled 关或「不播放给他人」时都不广播。
        // 例外："stop" 是关总开关/关共享时的显式停机信号，必须无条件送达（此时 canShare 已翻 false，
        // 若也被拦，对方会一直按旧声源播我的曲子直到自然播完）。
        if (!"stop".equals(op) && !MusicPlayer.canShare()) return;

        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null && !op.equals("stop")) return;

        String owner = ownerKey();
        switch (op) {
            case "play":
            case "next":
                emitSync(owner, op, t);
                if (t != null && t.isLocal()) {
                    // 本地路径曲目：二进制分块广播
                    sendLocalFile(owner, t);
                }
                break;
            case "pause":
            case "resume":
            case "stop":
                emitSyncSimple(owner, op);
                break;
        }
    }

    private static void emitSyncSimple(String owner, String op) {
        String payload = "{\"owner\":\"" + escape(owner) + "\",\"op\":\"" + escape(op) + "\"}";
        sendReliable(MSG_SYNC, payload);
    }

    private static void emitSync(String owner, String op, MusicTrack t) {
        StringBuilder sb = new StringBuilder();
        // 所有字符串字段一律转义：hash/type 此前未转义，任何含引号的值都会破坏 JSON 结构，
        // 接收端的手写提取器随之被注入伪造字段（如 ext）。
        sb.append("{\"owner\":\"").append(escape(owner))
          .append("\",\"op\":\"").append(escape(op))
          .append("\",\"hash\":\"").append(escape(t.cacheHash))
          .append("\",\"name\":\"").append(escape(t.name))
          .append("\",\"type\":").append(t.type);
        if (t.isUrl() && t.source != null) {
            sb.append(",\"url\":\"").append(escape(t.source)).append('"');
        } else if (t.isLocal() && t.source != null) {
            sb.append(",\"src\":\"").append(escape(t.source)).append('"');
        }
        // 附带 owner 当前坐标，接收端首帧即可准确定位（mp-pos 稍后到达以期精确）
        sb.append(",\"x\":").append(playerX()).append(",\"y\":").append(playerY());
        sb.append('}');
        sendReliable(MSG_SYNC, sb.toString());
    }

    /** 坐标上报（周期调用） */
    private static void tick() {
        if (!net.active()) return;
        // 帧速送出队列中的二进制分块（本地路径曲目）。
        // 修复（2026-09-03 rev8）：flushPendingChunks 此前在 canShare/isPlaying 守卫之前无条件执行，
        // 且 stop/pause/切曲不会 closePending —— 停止/暂停一首大的本地曲目后，剩余分块仍每帧继续发给远端
        // （远端继续拼装一首 owner 已不播放的文件）。改为与坐标上报同守卫：只有本机确实在播放且共享时才
        // flush，停止/暂停后残留队列停止发送；下一次 sendLocalFile 开头仍会 closePending 收尾旧流。
        if (!MusicPlayer.canShare() || !MusicPlayer.isPlaying()) return;
        flushPendingChunks();
        if (Time.time - lastPosTick < 2f) return;
        lastPosTick = Time.time;
        String owner = ownerKey();
        String hash = MusicPlayer.currentTrack() == null ? "" : MusicPlayer.currentTrack().cacheHash;
        ownerHash.put(owner, hash);
        sendUnreliable(MSG_POS, owner + "|" + hash + "|" + playerX() + "|" + playerY());
    }

    /** 本机作为播放者时，向服务端上报或直接广播 */
    private static void sendReliable(String type, String data) {
        if (net.server()) {
            Call.clientPacketReliable(type, data);
        } else {
            Call.serverPacketReliable(type, data);
        }
    }

    private static void sendUnreliable(String type, String data) {
        if (net.server()) {
            Call.clientPacketUnreliable(type, data);
        } else {
            Call.serverPacketUnreliable(type, data);
        }
    }

    private static void broadcastBinary(String type, byte[] data) {
        if (net.server()) {
            Call.clientBinaryPacketReliable(type, data);
        } else {
            Call.serverBinaryPacketReliable(type, data);
        }
    }

    /** 本地路径曲目：读取文件字节，分块广播 */
    private static void sendLocalFile(String owner, MusicTrack t) {
        Fi file = MusicPlayer.resolveToPlayableFile(t);
        if (file == null || !file.exists()) {
            Log.info("[SiliconMusic] local file missing: " + t.source);
            return;
        }
        try {
            closePending(); // 上一轮发送未完时先关掉旧流，避免文件描述符泄漏
            long total = file.length();
            int chunkCount = (int) ((total + CHUNK_SIZE - 1) / CHUNK_SIZE);
            String hash = t.cacheHash;

            // 先广播元数据
            StringBuilder meta = new StringBuilder();
            meta.append("{\"owner\":\"").append(owner)
                .append("\",\"hash\":\"").append(hash)
                .append("\",\"name\":\"").append(escape(t.name))
                .append("\",\"type\":2")
                .append(",\"total\":").append(total)
                .append(",\"chunks\":").append(chunkCount)
                .append(",\"ext\":\"").append(MusicPlayer.extensionFrom(t.source).replace(".", ""))
                .append('}');
            sendReliable(MSG_META, meta.toString());

            // 流式分块（每次只读一块进复用缓冲），由 tick() 每帧限量发出，避免大文件整读内存与包队列暴涨
            pendingFi = file;
            pendingTotal = total;
            pendingHash = hash;
            pendingIdx = 0;
            pendingChunks = chunkCount;
            flushPendingChunks();
            Log.info("[SiliconMusic] broadcast local file " + t.name + " (" + total + "B, " + chunkCount + " chunks)");
        } catch (Exception e) {
            Log.info("[SiliconMusic] local file read fail: " + e.getMessage());
        }
    }

    /** 每帧发出一批待发送分块，直到一次性发完；分块从磁盘按块读取，不整载入内存 */
    private static void flushPendingChunks() {
        if (pendingFi == null || pendingHash == null) return;
        try {
            if (pendingStream == null) pendingStream = pendingFi.read();
            int sent = 0;
            while (pendingIdx < pendingChunks && sent < CHUNKS_PER_TICK) {
                int off = (int) ((long) pendingIdx * CHUNK_SIZE);
                int len = pendingIdx == pendingChunks - 1 ? (int) (pendingTotal - (long) pendingIdx * CHUNK_SIZE) : CHUNK_SIZE;
                int got = 0;
                while (got < len) {
                    int n = pendingStream.read(chunkBuf, got, len - got);
                    if (n < 0) break;
                    got += n;
                }
                byte[] header;
                try {
                    header = buildHeader(pendingHash, pendingChunks, pendingIdx);
                } catch (IOException e) {
                    break;
                }
                byte[] payload = new byte[HEADER_LEN + got];
                System.arraycopy(header, 0, payload, 0, HEADER_LEN);
                System.arraycopy(chunkBuf, 0, payload, HEADER_LEN, got);
                broadcastBinary(MSG_CHUNK, payload);
                pendingIdx++;
                sent++;
            }
            if (pendingIdx >= pendingChunks) {
                closePending();
            }
        } catch (IOException e) {
            Log.info("[SiliconMusic] chunk read fail: " + e.getMessage());
        }
    }

    /** 关闭并复位分块发送状态（发送完成或世界重置时调用） */
    private static void closePending() {
        if (pendingStream != null) {
            try {
                pendingStream.close();
            } catch (IOException ignored) {
            }
            pendingStream = null;
        }
        pendingFi = null;
        pendingHash = null;
        pendingTotal = 0;
        pendingIdx = 0;
        pendingChunks = 0;
    }

    private static byte[] buildHeader(String hash, int chunkCount, int idx) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        byte[] hashBytes = new byte[16];
        byte[] src = hash.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(src, 0, hashBytes, 0, Math.min(16, src.length));
        dos.write(hashBytes);
        dos.writeInt(chunkCount);
        dos.writeInt(idx);
        dos.flush();
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // 客户端接收侧
    // ------------------------------------------------------------------

    private static void onSync(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String owner = extract(data, "owner");
            String op = extract(data, "op");
            if (owner == null || isSelf(owner)) return;

            String hash = extract(data, "hash");
            int type = parseInt(extract(data, "type"), -1);
            String name = extract(data, "name");
            String url = extract(data, "url");
            String src = extract(data, "src");
            // 首帧坐标（emitSync 附带）
            String sx = extract(data, "x");
            String sy = extract(data, "y");
            float ox = sx == null ? Float.NaN : parseFloatSafe(sx);
            float oy = sy == null ? Float.NaN : parseFloatSafe(sy);
            if (!Float.isNaN(ox) && !Float.isNaN(oy)) {
                ownerPos.put(owner, new float[]{ox, oy});
            }

            if ("stop".equals(op)) {
                MusicPlayer.stopRemoteVoice(owner);
                ownerHash.remove(owner);
                recv.remove(owner);
                return;
            }
            if ("pause".equals(op)) { MusicPlayer.pauseRemoteVoice(owner); return; }
            if ("resume".equals(op)) { MusicPlayer.resumeRemoteVoice(owner); return; }

            // play / next：字段必须合法才继续（对端可控，非法值会流进缓存命名与下载路径）
            if (!"play".equals(op) && !"next".equals(op)) return;
            if (hash == null) return;
            if (type != MusicTrack.INTERNAL && !isValidHash(hash)) return; // 内置曲目用 key，其余必须是合法 hash
            if (type != MusicTrack.INTERNAL && type != MusicTrack.URL && type != MusicTrack.LOCAL) return;
            if (url != null && !isHttpUrl(url)) return;
            ownerHash.put(owner, hash);
            MusicPlayer.stopRemoteVoice(owner); // 切换曲目时先停旧的

            if (type == MusicTrack.INTERNAL || MusicPlayer.hasCache(hash)) {
                // 可立即播放（内置或已有缓存）
                float[] pos = ownerPos.get(owner);
                MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
            } else if (type == MusicTrack.URL && url != null) {
                downloadAndPlay(owner, hash, url, name);
            } else if (type == MusicTrack.LOCAL) {
                // 等 mp-meta / mp-chunk；若本地已有同名曲目（本机也加过同源），可能已在 tracks 里
                if (MusicPlayer.trackByHash(hash) != null && MusicPlayer.hasCache(hash)) {
                    float[] pos = ownerPos.get(owner);
                    MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
                }
            }
        } catch (Exception e) {
            Log.info("[SiliconMusic] onSync err: " + e.getMessage());
        }
    }

    private static void downloadAndPlay(String owner, String hash, String url, String name) {
        // 绑定校验：URL 曲目的 hash 必须等于「URL 字符串的 sha256 前 16 位」。
        // 否则对端可以拿一个受害者也有的 hash、配上自己的 URL，让受害者把攻击者内容缓存成那个 hash
        // （缓存投毒：受害者自己曲库里的该曲目会被换成攻击者的文件）。
        String expect = MusicPlayer.hashOf(url);
        if (expect == null || !expect.equalsIgnoreCase(hash)) {
            Log.warn("[SiliconMusic] reject URL share: hash " + hash + " != hash(url) " + expect);
            return;
        }
        // 保证已有曲目记录（接收方本地建立一条 URL 元数据，便于缓存查找）
        if (MusicPlayer.trackByHash(hash) == null) {
            int dup = MusicPlayer.indexOfHash(hash);
            if (dup < 0) MusicPlayer.addTrack(MusicTrack.URL, url, name);
        }
        if (MusicPlayer.hasCache(hash)) {
            playRemoteIfStillCurrent(owner, hash);
            return;
        }
        MusicPlayer.registerHashExt(hash, MusicPlayer.extensionFrom(url));
        // 同一 URL 已在下载中时仅登记回调（去重，避免并发多次 Http）；下载完成统一触发各自回调
        downloadHash(hash, url, () -> playRemoteIfStillCurrent(owner, hash),
                err -> Log.warn("[SiliconMusic] URL share download failed: " + hash + " " + err));
    }

    /** 回调里检查 owner 是否仍在播放该 hash：避免下载完成/分块收齐时，owner 已 stop/切曲却仍建立声源 */
    private static void playRemoteIfStillCurrent(String owner, String hash) {
        if (!hash.equals(ownerHash.get(owner))) return;
        float[] pos = ownerPos.get(owner);
        MusicPlayer.playRemoteVoice(owner, hash, pos == null ? 0f : pos[0], pos == null ? 0f : pos[1]);
    }

    /** 本机播放 URL 曲目但尚未下载缓存时，先从网络下载到本地缓存，完成后回调 onDone（主线程）。 */
    static void fetchLocalThenPlay(MusicTrack t, Runnable onDone) {
        if (t == null || !t.isUrl() || t.source == null) return;
        // 登记真实扩展名：writeCacheBytes 才会写入 <hash>.<真实ext>，避免 mp3/wav 被写成 .ogg 而解码失败、反复重下
        MusicPlayer.registerHashExt(t.cacheHash, MusicPlayer.extensionFrom(t.source));
        if (MusicPlayer.hasCache(t.cacheHash)) {
            Core.app.post(onDone);
            return;
        }
        // 失败必须让用户看到（否则「点了播放永远没反应」）
        downloadHash(t.cacheHash, t.source, onDone,
                err -> Core.app.post(() -> MusicPlayer.notifyDownloadFailed(t.name, err)));
    }

    /** 按 URL 下载到缓存并去重：同一 hash 已在下载中时只追加回调、不重复发起请求；完成后统一在主线程触发回调。
     *  @param onFail 失败回调（主线程）：调用方必须能收到失败，否则用户点了播放会「永远没反应、也没有提示」 */
    private static void downloadHash(String hash, String url, Runnable onDone, java.util.function.Consumer<String> onFail) {
        if (MusicPlayer.hasCache(hash)) {
            Core.app.post(onDone);
            return;
        }
        arc.struct.Seq<Runnable> pend = pendingDownloads.get(hash);
        if (pend != null) {
            pend.add(onDone);
            return; // 已在下载中，去重：只登记回调，不重复发起请求
        }
        pend = new arc.struct.Seq<>();
        pend.add(onDone);
        pendingDownloads.put(hash, pend);
        Log.info("[SiliconMusic] downloading " + url);
        downloadCapped(url,
                bytes -> Core.app.post(() -> {
                    boolean ok = MusicPlayer.writeCacheBytes(hash, bytes);
                    arc.struct.Seq<Runnable> done = pendingDownloads.remove(hash);
                    if (ok) {
                        if (done != null) {
                            for (Runnable r : done) r.run();
                        }
                        // 弹窗开着则刷新曲目行（时长/大小立即落位，无需重开弹窗）
                        silicon.ui.MusicPlayerDialog.refreshIfOpen();
                    } else {
                        Log.warn("[SiliconMusic] cache write failed: " + url);
                        if (onFail != null) onFail.accept("cache write failed");
                    }
                }),
                err -> {
                    Log.info("[SiliconMusic] download fail: " + err + " (" + url + ")");
                    Core.app.post(() -> {
                        pendingDownloads.remove(hash);
                        if (onFail != null) onFail.accept(err);
                    });
                });
    }

    /**
     * 流式下载并限长。
     * <p>
     * 为什么不用 {@code Http.get}：arc 的实现会把整个响应体读成一个 byte[]，对端只要在 mp-sync 里塞一个
     * 「超大 URL」就能让每个开着模组的客户端 OOM。这里用 {@link java.net.HttpURLConnection} 边读边限长，
     * 超限立即中断；并拒绝本机/内网地址字面量（防 SSRF）。全程在后台线程，回调由调用方 post 回主线程。
     */
    private static void downloadCapped(String url, java.util.function.Consumer<byte[]> ok, java.util.function.Consumer<String> fail) {
        Thread t = new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try {
                if (!isPublicHttpUrl(url)) {
                    fail.accept("blocked address");
                    return;
                }
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(false); // 不跟随跳转：否则可借 302 绕到内网
                c.setRequestProperty("User-Agent", "Silicon-music/1.0");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    fail.accept("HTTP " + code);
                    return;
                }
                long declared = c.getContentLengthLong();
                if (declared > MAX_DOWNLOAD_BYTES) {
                    fail.accept("too large (" + declared + " bytes)");
                    return;
                }
                try (java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_DOWNLOAD_BYTES) {
                            fail.accept("over size limit (" + MAX_DOWNLOAD_BYTES + " bytes)");
                            return;
                        }
                        bos.write(buf, 0, n);
                    }
                    byte[] bytes = bos.toByteArray();
                    if (bytes.length == 0) {
                        fail.accept("empty response");
                        return;
                    }
                    ok.accept(bytes);
                }
            } catch (Throwable e) {
                fail.accept(String.valueOf(e));
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "silicon-music-download");
        t.setDaemon(true);
        t.start();
    }

    /** 只允许 http(s) 且主机不是本机/内网字面量（挡住常见 SSRF 目标；不做 DNS 解析，够用且不引入阻塞） */
    private static boolean isPublicHttpUrl(String url) {
        if (!isHttpUrl(url)) return false;
        try {
            String host = new java.net.URL(url).getHost();
            if (host == null || host.isEmpty()) return false;
            String h = host.toLowerCase();
            if (h.equals("localhost") || h.endsWith(".local") || h.equals("::1") || h.equals("[::1]")) return false;
            if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return false;
            if (h.startsWith("172.")) {
                int dot = h.indexOf('.', 4);
                if (dot > 4) {
                    try {
                        int second = Integer.parseInt(h.substring(4, dot));
                        if (second >= 16 && second <= 31) return false;
                    } catch (Exception ignored) {
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void onMeta(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String owner = extract(data, "owner");
            String hash = extract(data, "hash");
            int chunks = parseInt(extract(data, "chunks"), 0);
            String ext = extract(data, "ext");
            if (owner == null || isSelf(owner)) return;
            // 与 onChunk 同一套闸门：hash 白名单 + 分块数上限（此处同样会分配 boolean[]，
            // 未校验的 chunks 是第二个 OOM 入口）
            if (!isValidHash(hash)) return;
            if (chunks <= 0 || chunks > MAX_CHUNK_COUNT) return;
            if ((long) chunks * CHUNK_SIZE > MAX_SHARE_BYTES) return;
            ownerHash.put(owner, hash);
            String safeExt = sanitizeExt(ext);
            if (safeExt != null) MusicPlayer.registerHashExt(hash, safeExt);
            if (MusicPlayer.hasCache(hash)) return; // 已有缓存，无需接收分块
            // 不覆盖在途接收状态：否则对端只要补发一条 chunks=1 的 meta，就能让合法分享的后续分块全部
            // 因长度不匹配被丢弃（永久卡死别人的分享）
            if (recv.containsKey(hash)) return;
            // 并发接收数封顶：每条会分配 boolean[chunks] 并建一个 ≤64MB 的 .part（.part 不参与 LRU 淘汰），
            // 不封顶就能单客户端刷爆内存与磁盘
            if (recv.size >= MAX_CONCURRENT_RECV) {
                Log.info("[SiliconMusic] drop share " + hash + ": concurrent receive limit (" + recv.size + ")");
                return;
            }
            // 建接收缓冲与缓存文件（先写占位）
            ChunkRecv r = new ChunkRecv();
            r.hash = hash;
            r.ext = safeExt;
            r.chunkCount = chunks;
            r.received = new boolean[chunks];
            r.total = chunks;
            r.lastAt = System.currentTimeMillis();
            recv.put(hash, r); // 与 onChunk 统一按 hash 建键（此前按 owner，依赖线性兜底查找）
        } catch (Exception e) {
            Log.info("[SiliconMusic] onMeta err: " + e.getMessage());
        }
    }

    /** 扩展名白名单：仅允许 1~5 位小写字母数字，返回带点形式；非法返回 null。
     *  扩展名会成为缓存文件名的一部分，必须收紧。 */
    static String sanitizeExt(String ext) {
        if (ext == null) return null;
        String e = ext.trim().toLowerCase();
        if (e.startsWith(".")) e = e.substring(1);
        return e.matches("[a-z0-9]{1,5}") ? "." + e : null;
    }

    /** 仅接受 http/https 下载地址（避免对端塞入 file:/jar: 等本地协议） */
    static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static void onChunk(byte[] payload) {
        if (!MusicPlayer.canReceive()) return;
        if (payload == null || payload.length < HEADER_LEN) return;
        if (payload.length > HEADER_LEN + CHUNK_SIZE) return; // 块长越界
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(payload, 0, HEADER_LEN);
            DataInputStream dis = new DataInputStream(bis);
            byte[] hashBytes = new byte[16];
            dis.readFully(hashBytes);
            int chunkCount = dis.readInt();
            int idx = dis.readInt();
            String hash = new String(hashBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            // 闸门 1：hash 必须是 SHA-256 前 16 位十六进制。
            // 它同时是缓存文件名的一部分，严格白名单可一次性挡住路径穿越与任意扩展名写入。
            if (!isValidHash(hash)) return;
            // 闸门 2：分块数由对方提供，直接用于分配数组会是 OOM 入口（如 0x7FFFFFFF）
            if (chunkCount <= 0 || chunkCount > MAX_CHUNK_COUNT) return;
            int dataLen = payload.length - HEADER_LEN;
            if (dataLen <= 0) return;
            // 闸门 3：已有缓存不接受任何覆盖（防用同 hash 替换本机已缓存音频）
            if (MusicPlayer.hasCache(hash)) return;

            ChunkRecv r = recvByHash(hash);
            if (r == null) {
                // 未收到 meta（乱序）：按头部信息重建
                r = new ChunkRecv();
                r.hash = hash;
                r.chunkCount = chunkCount;
                r.total = -1;
                r.received = new boolean[chunkCount];
                recv.put(hash, r);
            } else if (r.received == null || r.received.length != chunkCount) {
                return; // 与已声明的块数不一致 → 放弃，防用错块数把文件拼坏
            }
            if (idx < 0 || idx >= r.received.length || r.received[idx]) return;
            // 闸门 4：单曲累计字节上限（防无限刷盘）
            if (r.bytes + dataLen > MAX_SHARE_BYTES) {
                recvRemoveByHash(hash);
                try { MusicPlayer.stagingFile(hash).delete(); } catch (Exception ignored) {}
                Log.info("[SiliconMusic] drop oversized share " + hash + " (> " + MAX_SHARE_BYTES + " bytes)");
                return;
            }

            // 追加写暂存文件：reliable 包有序，按到达顺序 append；未收齐前不视为正式缓存
            Fi staging = MusicPlayer.stagingFile(hash);
            staging.parent().mkdirs();
            if (idx == 0 || !staging.exists()) staging.write(false).close(); // 首块/不存在时截断，避免旧残留
            try (java.io.OutputStream out = staging.write(true)) {
                out.write(payload, HEADER_LEN, dataLen);
            }
            r.received[idx] = true;
            r.receivedCount++;
            r.bytes += dataLen;

            if (r.receivedCount >= r.received.length) {
                // 收齐 → 把暂存文件 moveTo 转正式缓存，再尝试按 owner 播放
                recvRemoveByHash(hash);
                MusicPlayer.finalizeCache(hash, r.ext);
                // 弹窗开着则刷新曲目行（本地文件共享收齐后时长/大小立即落位）
                silicon.ui.MusicPlayerDialog.refreshIfOpen();
                String owner = ownerOfHash(hash);
                if (owner != null && !isSelf(owner)) {
                    playRemoteIfStillCurrent(owner, hash);
                }
            }
        } catch (Exception e) {
            Log.info("[SiliconMusic] onChunk err: " + e.getMessage());
        }
    }

    private static ChunkRecv recvByHash(String hash) {
        ChunkRecv direct = recv.get(hash);
        if (direct != null) return direct;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) return e.value;
        }
        return null;
    }

    private static String ownerOfHash(String hash) {
        for (ObjectMap.Entries<String, String> it = ownerHash.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, String> e = it.next();
            if (hash.equals(e.value)) return e.key;
        }
        String fallback = null;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (e.value != null && hash.equals(e.value.hash)) { fallback = e.key; break; }
        }
        return fallback;
    }

    private static void recvRemoveByHash(String hash) {
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) { it.remove(); }
        }
    }

    private static void onPos(String data) {
        if (!MusicPlayer.canReceive()) return;
        try {
            String[] parts = data.split("\\|");
            if (parts.length < 4) return;
            String owner = parts[0];
            String hash = parts[1];
            if (owner == null || owner.isEmpty() || hash == null || hash.isEmpty()) return;
            float x = Float.parseFloat(parts[2]);
            float y = Float.parseFloat(parts[3]);
            if (isSelf(owner)) return;
            ownerHash.put(owner, hash);
            ownerPos.put(owner, new float[]{x, y});
            MusicPlayer.updateRemotePosition(owner, x, y);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String ownerKey() {
        Player p = mindustry.Vars.player;
        if (p == null) return "none";
        return (p.uuid() != null && !p.uuid().isEmpty()) ? p.uuid() : ("id:" + p.id());
    }

    private static boolean isSelf(String owner) {
        return owner != null && owner.equals(ownerKey());
    }

    private static float playerX() {
        Player p = mindustry.Vars.player;
        return p == null ? 0f : p.x;
    }

    private static float playerY() {
        Player p = mindustry.Vars.player;
        return p == null ? 0f : p.y;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String extract(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            String patNum = "\"" + key + "\":";
            int j = json.indexOf(patNum);
            if (j < 0) return null;
            int end = json.indexOf(',', j);
            if (end < 0) end = json.indexOf('}', j);
            if (end < 0) return null;
            return json.substring(j + patNum.length(), end).trim();
        }
        int start = i + pat.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"') {
                // 统计前面连续的反斜杠个数：偶数个才是真正的结束引号（奇数个表示被转义）。
                // 原实现只看前一个字符，值以 \ 结尾时会把后面的分隔符一起吞掉（字段串味）。
                int bs = 0;
                for (int k = end - 1; k >= start && json.charAt(k) == '\\'; k--) bs++;
                if ((bs & 1) == 0) break;
            }
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static float parseFloatSafe(String s) {
        if (s == null) return Float.NaN;
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private static class ChunkRecv {
        String hash;
        String ext;
        int chunkCount;
        int total;
        boolean[] received;
        int receivedCount;
        /** 已接收字节数（用于单曲上限，防无限刷盘） */
        long bytes;
        /** 最近一次收到分块的时刻（毫秒）；用于静默超时清理（.part 不参与 LRU 淘汰） */
        long lastAt;
    }

    /** 是否正在下载该 hash 的 URL 曲目（供 UI 显示“下载中”） */
    public static boolean isDownloading(String hash) {
        return hash != null && pendingDownloads.containsKey(hash);
    }

    /** 是否正在接收该 hash 的本地文件分块（供 UI 显示“接收中”） */
    public static boolean isReceiving(String hash) {
        if (hash == null) return false;
        if (recv.containsKey(hash)) return true;
        for (ObjectMap.Entries<String, ChunkRecv> it = recv.entries().iterator(); it.hasNext(); ) {
            ObjectMap.Entry<String, ChunkRecv> e = it.next();
            if (hash.equals(e.value.hash)) return true;
        }
        // 分块接收已开始但 recv 条目已被清理的极短窗口内，暂存文件仍存在也视为接收中
        try { if (MusicPlayer.stagingFile(hash).exists()) return true; } catch (Exception ignored) {}
        return false;
    }

    public static int receiveProgress(String hash) {
        if (hash == null) return -1;
        ChunkRecv r = recv.get(hash);
        if (r == null) r = recvByHash(hash);
        if (r == null || r.received == null || r.received.length == 0) return -1;
        return (int) (r.receivedCount * 100f / r.received.length);
    }

    /** 世界加载/地图切换时清空网络残局 */
    public static void reset() {
        recv.clear();
        ownerHash.clear();
        ownerPos.clear();
        pendingDownloads.clear();
        closePending();
        MusicPlayer.clearRemoteVoices();
        MusicPlayer.cleanupStagingFiles();
        // 本机仍在播放时，切换地图后重广播当前曲目，避免新地图玩家失去该声源
        MusicPlayer.reBroadcastIfPlaying();
    }
}
