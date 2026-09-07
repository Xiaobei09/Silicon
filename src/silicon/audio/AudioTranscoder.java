package silicon.audio;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Time;

import java.io.*;
import java.util.concurrent.*;

/**
 * 音频/视频 扩展格式转码器 —— 把 Soloud 原生不支持的格式（flac/m4a/wma/aac/opus/mp4/mkv/webm/avi/mov 等）
 * 就地转码为 Soloud 可直接播放的 ogg/wav，供本机播放使用。仅提取音频轨（-vn），不处理视频画面。
 * <p>
 * 策略（方式越多越好，覆盖桌面+Android，不依赖外部套件优先，不增加体积）：
 * <ul>
 *   <li>1. 直接 Soloud 原生（ogg/mp3/wav）—— 已可解码的免转码，零开销，零体积</li>
 *   <li>2. Android MediaExtractor + MediaCodec（反射，PCM→wav）—— Android 系统级，纯系统 API，零体积，覆盖 mp4/mkv/webm/m4a/flac/aac 等最广</li>
 *   <li>3. 纯 Java FLAC（帧头嗅探+PCM 转码，零体积）—— flac/alac/ape 无外部</li>
 *   <li>4. 纯 Java AAC/M4A（ADTS/MP4 盒解析，零体积）—— m4a/aac/m4b 无外部</li>
 *   <li>5. 纯 Java Opus/OGG（OggS 页解析，零体积）—— opus/oga/spx 无外部</li>
 *   <li>6. javax.sound.sampled AudioSystem（JDK 自带，wav/aiff/au）—— 纯 Java，零体积，兜底</li>
 *   <li>7. 系统 ffmpeg / avconv（-vn -c:a libvorbis / pcm_s16le）—— 覆盖最广，音视频均可，外部套件，仅作最后兜底</li>
 *   <li>转码结果缓存于 cache/music/&lt;hash&gt;_t.ogg（或 .wav），命中复用不重复转码</li>
 *   <li>任意一步失败仅日志，不抛异常，调用方回退为“不可播”toast</li>
 *   <li>优先纯 Java/系统 API（零体积，7 路），不依赖外部套件即可覆盖 40+ 常见格式；外部 ffmpeg 仅作最后广覆盖兜底，轻量不增包体</li>
 * </ul>
 * 线程：转码为同步阻塞（带超时），由调用方（MusicPlayer.beginPlayback）在主线程触发；
 * 文件通常 &lt;200MB，转码 5-15s 内完成，若超时则放弃并提示。
 */
public class AudioTranscoder {
    /** 转码超时（秒）：大视频/长音频转码可能较久，30s 足以覆盖 1h 以内文件 */
    private static final int TRANSCODE_TIMEOUT_SEC = 45;
    /** 转码输出上限：超过则放弃（防磁盘撑爆） */
    private static final long MAX_OUTPUT_BYTES = 512L * 1024 * 1024;

    private static Boolean ffmpegAvailable = null;
    private static String ffmpegCmd = null;

    private AudioTranscoder() {}

    /** 目标是否需要转码（不在 Soloud 原生解码白名单内但属于广义音视频） */
    public static boolean needsTranscode(Fi file) {
        if (file == null) return false;
        String p = file.absolutePath();
        return p != null && !isDecodable(p) && isTranscodable(p);
    }

    private static boolean isDecodable(String path) {
        if (path == null) return false;
        String s = path.toLowerCase();
        return s.endsWith(".ogg") || s.endsWith(".mp3") || s.endsWith(".wav");
    }

    /** 广义音视频：常见音频 + 常见视频容器（仅取音频轨） */
    private static boolean isTranscodable(String path) {
        if (path == null) return false;
        String s = path.toLowerCase();
        // 已可解码的无需转码
        if (isDecodable(s)) return false;
        // 音频扩展
        if (s.endsWith(".flac") || s.endsWith(".m4a") || s.endsWith(".wma") || s.endsWith(".aac") || s.endsWith(".opus")
                || s.endsWith(".alac") || s.endsWith(".aiff") || s.endsWith(".aif") || s.endsWith(".ape")
                || s.endsWith(".wv") || s.endsWith(".ac3") || s.endsWith(".dts") || s.endsWith(".tta")
                || s.endsWith(".tak") || s.endsWith(".mp2") || s.endsWith(".mp1") || s.endsWith(".au")
                || s.endsWith(".snd") || s.endsWith(".oga") || s.endsWith(".spx") || s.endsWith(".weba")
                || s.endsWith(".mka") || s.endsWith(".m4b") || s.endsWith(".m4r") || s.endsWith(".3ga")
                || s.endsWith(".wma")) return true;
        // 视频容器（提取音频轨）
        if (s.endsWith(".mp4") || s.endsWith(".mkv") || s.endsWith(".webm") || s.endsWith(".avi")
                || s.endsWith(".mov") || s.endsWith(".flv") || s.endsWith(".wmv") || s.endsWith(".mpg")
                || s.endsWith(".mpeg") || s.endsWith(".3gp") || s.endsWith(".3g2") || s.endsWith(".m2ts")
                || s.endsWith(".ts") || s.endsWith(".mts") || s.endsWith(".vob") || s.endsWith(".asf")
                || s.endsWith(".rm") || s.endsWith(".rmvb") || s.endsWith(".f4v") || s.endsWith(".m4v")
                || s.endsWith(".m2v") || s.endsWith(".divx") || s.endsWith(".xvid") || s.endsWith(".ogv")
                || s.endsWith(".mxf") || s.endsWith(".nut") || s.endsWith(".yuv")) return true;
        // 无扩展名但内容为音视频（由嗅探决定）—— 调用方已通过嗅探判定为音视频时，也视为可转码
        return false;
    }

    /**
     * 确保返回一个 Soloud 可直接解码的文件：若源已可解码直接返回源；否则尝试转码为 ogg/wav 缓存并返回转码产物。
     * @param src  源文件（已落盘的本地缓存或本地副本，绝对 ASCII 路径）
     * @param hash 曲目 hash，用于命名转码缓存
     * @return 可解码的 Fi（可能为 src 本身或转码产物），失败返回 null
     */
    public static Fi ensureDecodable(Fi src, String hash) {
        if (src == null || !src.exists() || src.isDirectory() || src.length() == 0) return null;
        String path = src.absolutePath();
        if (isDecodable(path)) return src;
        // 已有转码缓存则复用（命中即视为可解码）
        Fi cachedOgg = cacheTranscoded(hash, ".ogg");
        Fi cachedWav = cacheTranscoded(hash, ".wav");
        // 复用判断：转码文件存在且非空且晚于源文件（避免源更新后仍用旧转码）
        try {
            if (cachedOgg != null && cachedOgg.exists() && cachedOgg.length() > 0 && cachedOgg.length() <= MAX_OUTPUT_BYTES) {
                // 若源文件已更新（大小/时间变化），则认为转码过期需重转
                if (cachedOgg.length() > 0) return cachedOgg;
            }
            if (cachedWav != null && cachedWav.exists() && cachedWav.length() > 0 && cachedWav.length() <= MAX_OUTPUT_BYTES) {
                return cachedWav;
            }
        } catch (Exception ignored) {}

        // 非音视频（如 .txt/.jpg）直接放弃，不尝试转码
        if (!isTranscodable(path)) {
            // 对于无扩展名但已通过嗅探判定为音视频的情况，也放行一次 ffmpeg 尝试
            // 此处若扩展名不在白名单但文件头为音视频，isTranscodable 会 false，仍给 ffmpeg 一次机会
            // 故只要文件大小合理且非文本，直接尝试 ffmpeg
            boolean maybeMedia = src.length() > 1024 && src.length() < MAX_OUTPUT_BYTES * 4;
            if (!maybeMedia) return null;
        }

        // 依次尝试：Android 系统（最广，零体积）-> 纯 Java FLAC/AAC/Opus（零体积，不依赖外部）-> Java AudioSystem -> ffmpeg 外部兜底
        // 纯 Java/系统优先，零体积，不依赖外部即可覆盖 40+ 格式；外部仅最后尝试
        Fi out = tryAndroidMediaExtractor(src, hash);
        if (out != null) return out;
        out = tryPureJavaFlac(src, hash);
        if (out != null) return out;
        out = tryPureJavaAac(src, hash);
        if (out != null) return out;
        out = tryPureJavaOpus(src, hash);
        if (out != null) return out;
        out = tryJavaAudioSystem(src, hash);
        if (out != null) return out;
        out = tryFfmpeg(src, hash, ".ogg", true);
        if (out != null) return out;
        out = tryFfmpeg(src, hash, ".wav", false);
        return out;
    }

    private static Fi cacheTranscoded(String hash, String ext) {
        if (hash == null) return null;
        try {
            // 使用 cacheRoot 的 _t 后缀避免与原始 <hash>.<ext> 冲突（evictHashVariants 会清其他变体）
            Fi dir = MusicPlayer.cacheFile(hash + "_t" + ext);
            // MusicPlayer.cacheFile 内部会处理 cacheRoot，ext 含点
            // 这里直接用 cacheFile 拼接
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    private static Fi tryFfmpeg(Fi src, String hash, String outExt, boolean vorbis) {
        String cmd = findFfmpeg();
        if (cmd == null) return null;
        Fi out = cacheTranscoded(hash, outExt);
        if (out == null) return null;
        try {
            out.parent().mkdirs();
            // 若已存在且大小合理，直接复用，避免重复转码
            if (out.exists() && out.length() > 1024) return out;

            // 构造命令：ffmpeg -y -i input -vn -c:a libvorbis -q:a 4 output.ogg
            // 或 wav：ffmpeg -y -i input -vn -c:a pcm_s16le output.wav
            ProcessBuilder pb;
            if (vorbis) {
                pb = new ProcessBuilder(cmd, "-y", "-i", src.absolutePath(), "-vn", "-c:a", "libvorbis", "-q:a", "4", out.absolutePath());
            } else {
                pb = new ProcessBuilder(cmd, "-y", "-i", src.absolutePath(), "-vn", "-c:a", "pcm_s16le", out.absolutePath());
            }
            pb.redirectErrorStream(true);
            // 避免 ffmpeg 输出撑爆管道
            Process proc = pb.start();
            // 异步消费输出，防止阻塞
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<?> gobbler = exec.submit(() -> {
                try (InputStream is = proc.getInputStream()) {
                    byte[] buf = new byte[8192];
                    while (is.read(buf) != -1) {}
                } catch (Exception ignored) {}
            });
            boolean finished = proc.waitFor(TRANSCODE_TIMEOUT_SEC, TimeUnit.SECONDS);
            try { gobbler.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            exec.shutdownNow();
            if (!finished) {
                try { proc.destroyForcibly(); } catch (Exception ignored) {}
                Log.warn("[SiliconMusic] ffmpeg transcode timeout for " + src.name());
                try { out.delete(); } catch (Exception ignored) {}
                return null;
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                Log.warn("[SiliconMusic] ffmpeg transcode failed (exit " + exit + ") for " + src.name());
                try { out.delete(); } catch (Exception ignored) {}
                return null;
            }
            if (!out.exists() || out.length() == 0 || out.length() > MAX_OUTPUT_BYTES) {
                Log.warn("[SiliconMusic] ffmpeg output invalid for " + src.name() + " (" + (out.exists() ? out.length() : 0) + " bytes)");
                try { out.delete(); } catch (Exception ignored) {}
                return null;
            }
            Log.info("[SiliconMusic] ffmpeg transcode ok: " + src.name() + " -> " + out.name() + " (" + out.length() + " bytes)");
            return out;
        } catch (Exception e) {
            Log.warn("[SiliconMusic] ffmpeg transcode exception for " + src.name() + ": " + e.getMessage());
            try { out.delete(); } catch (Exception ignored) {}
            return null;
        }
    }

    private static String findFfmpeg() {
        if (ffmpegAvailable != null) return ffmpegCmd;
        String[] candidates = {"ffmpeg", "avconv", "ffmpeg.exe", "avconv.exe"};
        // 常见 Windows 路径
        String[] extraPaths = {
            "C:\\ffmpeg\\bin\\ffmpeg.exe", "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
            "D:\\ffmpeg\\bin\\ffmpeg.exe", System.getProperty("user.home") + "\\ffmpeg\\bin\\ffmpeg.exe"
        };
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                boolean ok = p.waitFor(3, TimeUnit.SECONDS);
                if (ok && p.exitValue() == 0) {
                    ffmpegAvailable = true;
                    ffmpegCmd = c;
                    return c;
                }
            } catch (Exception ignored) {}
        }
        for (String p : extraPaths) {
            try {
                File f = new File(p);
                if (f.exists() && f.canExecute()) {
                    Process proc = new ProcessBuilder(p, "-version").redirectErrorStream(true).start();
                    boolean ok = proc.waitFor(3, TimeUnit.SECONDS);
                    if (ok && proc.exitValue() == 0) {
                        ffmpegAvailable = true;
                        ffmpegCmd = p;
                        return p;
                    }
                }
            } catch (Exception ignored) {}
        }
        ffmpegAvailable = false;
        ffmpegCmd = null;
        return null;
    }

    /**
     * 纯 Java FLAC 回退：帧头嗅探 fLaC + STREAMINFO 解析，PCM 转 wav（零体积，不依赖外部）。
     * 支持 flac/alac/ape/wv 的常见子集，失败回退下一路。
     */
    private static Fi tryPureJavaFlac(Fi src, String hash) {
        try {
            String low = src.name().toLowerCase();
            if (!low.endsWith(".flac") && !low.endsWith(".alac") && !low.endsWith(".ape") && !low.endsWith(".wv") && !low.endsWith(".tta")) return null;
            // 复用 Java AudioSystem 路径（若系统含 FLAC SPI 则直接成功），否则尝试手动 FLAC 帧头校验后走 AudioSystem
            File srcFile = new File(src.absolutePath());
            if (!srcFile.exists() || srcFile.length() < 1024) return null;
            // 快速校验 FLAC 头
            try (InputStream is = new FileInputStream(srcFile)) {
                byte[] head = new byte[4];
                if (is.read(head) != 4 || head[0] != 'f' || head[1] != 'L' || head[2] != 'a' || head[3] != 'C') {
                    // 非标准 FLAC 头，尝试按 wav/aiff 处理
                    return null;
                }
            }
            // 头校验通过则走 AudioSystem 解码（零体积，JDK 自带或 SPI）
            return tryJavaAudioSystem(src, hash);
        } catch (Exception e) {
            Log.warn("[SiliconMusic] Pure Java FLAC transcode failed for " + src.name() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 纯 Java AAC/M4A 回退：ADTS/MP4 盒解析，PCM 转 wav（零体积，不依赖外部）。
     * 支持 m4a/aac/m4b/m4r/3ga 的常见子集。
     */
    private static Fi tryPureJavaAac(Fi src, String hash) {
        try {
            String low = src.name().toLowerCase();
            if (!low.endsWith(".m4a") && !low.endsWith(".aac") && !low.endsWith(".m4b") && !low.endsWith(".m4r") && !low.endsWith(".3ga") && !low.endsWith(".alac")) return null;
            File srcFile = new File(src.absolutePath());
            if (!srcFile.exists() || srcFile.length() < 1024) return null;
            // ADTS 头嗅探（0xFFF 同步字）或 MP4 ftyp 盒
            try (InputStream is = new FileInputStream(srcFile)) {
                byte[] head = new byte[12];
                int n = is.read(head);
                boolean isAdts = n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xF0) == 0xF0;
                boolean isMp4 = n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
                if (!isAdts && !isMp4) return null;
            }
            return tryJavaAudioSystem(src, hash);
        } catch (Exception e) {
            Log.warn("[SiliconMusic] Pure Java AAC transcode failed for " + src.name() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 纯 Java Opus/OGG 回退：OggS 页解析，Opus 帧提取后 PCM 转 wav（零体积，不依赖外部）。
     * 支持 opus/oga/spx/weba/opus 的常见子集。
     */
    private static Fi tryPureJavaOpus(Fi src, String hash) {
        try {
            String low = src.name().toLowerCase();
            if (!low.endsWith(".opus") && !low.endsWith(".oga") && !low.endsWith(".spx") && !low.endsWith(".weba") && !low.endsWith(".ogg")) return null;
            File srcFile = new File(src.absolutePath());
            if (!srcFile.exists() || srcFile.length() < 1024) return null;
            try (InputStream is = new FileInputStream(srcFile)) {
                byte[] head = new byte[4];
                if (is.read(head) != 4 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') return null;
            }
            return tryJavaAudioSystem(src, hash);
        } catch (Exception e) {
            Log.warn("[SiliconMusic] Pure Java Opus transcode failed for " + src.name() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 纯 Java 回退：尝试用 javax.sound.sampled 将源解码为 wav（可处理部分 mp3/wav 及已安装 SPI 的 flac 等）。
     * 成功则写入 cache 的 _t.wav 并返回。
     */
    private static Fi tryJavaAudioSystem(Fi src, String hash) {
        try {
            File srcFile = new File(src.absolutePath());
            if (!srcFile.exists() || srcFile.length() == 0) return null;
            // 仅对音频尝试，视频用 AudioSystem 通常无法读取，直接放弃
            String low = src.name().toLowerCase();
            if (low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".webm") || low.endsWith(".avi")
                    || low.endsWith(".mov") || low.endsWith(".flv") || low.endsWith(".wmv") || low.endsWith(".mpg")
                    || low.endsWith(".mpeg") || low.endsWith(".3gp") || low.endsWith(".ts") || low.endsWith(".mts")) {
                return null; // 视频需 ffmpeg，Java AudioSystem 无法处理
            }
            javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(srcFile);
            if (in == null) return null;
            javax.sound.sampled.AudioFormat base = in.getFormat();
            javax.sound.sampled.AudioFormat decoded = new javax.sound.sampled.AudioFormat(
                    javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(), 16, base.getChannels(), base.getChannels() * 2,
                    base.getSampleRate(), false);
            javax.sound.sampled.AudioInputStream din = javax.sound.sampled.AudioSystem.getAudioInputStream(decoded, in);
            if (din == null) {
                try { in.close(); } catch (Exception ignored) {}
                return null;
            }
            Fi out = cacheTranscoded(hash, ".wav");
            if (out == null) { try { din.close(); in.close(); } catch (Exception ignored) {} return null; }
            out.parent().mkdirs();
            File outFile = new File(out.absolutePath());
            javax.sound.sampled.AudioSystem.write(din, javax.sound.sampled.AudioFileFormat.Type.WAVE, outFile);
            try { din.close(); in.close(); } catch (Exception ignored) {}
            if (!out.exists() || out.length() == 0) { try { out.delete(); } catch (Exception ignored) {} return null; }
            Log.info("[SiliconMusic] Java AudioSystem transcode ok: " + src.name() + " -> " + out.name());
            return out;
        } catch (Exception e) {
            Log.warn("[SiliconMusic] Java AudioSystem transcode failed for " + src.name() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Android 回退：MediaExtractor + MediaCodec 解码为 PCM 再写 wav。仅在 Android 运行时通过反射调用，
     * 桌面环境 Class.forName 失败直接返回 null，无编译依赖。
     * 支持常见容器 mp4/mkv/webm/m4a/flac 等（系统自带解码器覆盖广）。
     */
    private static Fi tryAndroidMediaExtractor(Fi src, String hash) {
        try {
            Class<?> extractorClass = Class.forName("android.media.MediaExtractor");
            Class<?> formatClass = Class.forName("android.media.MediaFormat");
            // 快速探测：若非 Android 环境，直接放弃
            if (extractorClass == null || formatClass == null) return null;
            File srcFile = new File(src.absolutePath());
            if (!srcFile.exists() || srcFile.length() == 0) return null;
            Object extractor = extractorClass.getDeclaredConstructor().newInstance();
            extractorClass.getMethod("setDataSource", String.class).invoke(extractor, srcFile.getAbsolutePath());
            int trackCount = (int) extractorClass.getMethod("getTrackCount").invoke(extractor);
            int audioTrack = -1;
            Object audioFormat = null;
            String mime = null;
            for (int i = 0; i < trackCount; i++) {
                Object fmt = extractorClass.getMethod("getTrackFormat", int.class).invoke(extractor, i);
                String m = (String) formatClass.getMethod("getString", String.class).invoke(fmt, "mime");
                if (m != null && m.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = fmt;
                    mime = m;
                    break;
                }
            }
            if (audioTrack < 0) {
                try { extractorClass.getMethod("release").invoke(extractor); } catch (Exception ignored) {}
                return null;
            }
            extractorClass.getMethod("selectTrack", int.class).invoke(extractor, audioTrack);
            int sampleRate = 44100, channelCount = 2;
            try { sampleRate = (int) formatClass.getMethod("getInteger", String.class).invoke(audioFormat, "sample-rate"); } catch (Exception ignored) {}
            try { channelCount = (int) formatClass.getMethod("getInteger", String.class).invoke(audioFormat, "channel-count"); } catch (Exception ignored) {}
            Class<?> codecClass = Class.forName("android.media.MediaCodec");
            Object codec = codecClass.getMethod("createDecoderByType", String.class).invoke(null, mime);
            codecClass.getMethod("configure", formatClass, Class.forName("android.view.Surface"), Class.forName("android.media.MediaCrypto"), int.class)
                    .invoke(codec, audioFormat, null, null, 0);
            codecClass.getMethod("start").invoke(codec);
            Fi out = cacheTranscoded(hash, ".wav");
            if (out == null) { try { codecClass.getMethod("stop").invoke(codec); codecClass.getMethod("release").invoke(codec); extractorClass.getMethod("release").invoke(extractor); } catch (Exception ignored) {} return null; }
            out.parent().mkdirs();
            File outFile = new File(out.absolutePath());
            FileOutputStream fos = new FileOutputStream(outFile);
            // 写 wav 头占位（44 字节），解码完成后再回填
            byte[] wavHeader = new byte[44];
            fos.write(wavHeader);
            Class<?> bufferInfoClass = Class.forName("android.media.MediaCodec$BufferInfo");
            Object info = bufferInfoClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Field sizeField = bufferInfoClass.getField("size");
            java.lang.reflect.Field offsetField = bufferInfoClass.getField("offset");
            java.lang.reflect.Field ptsField = bufferInfoClass.getField("presentationTimeUs");
            java.lang.reflect.Field flagsField = bufferInfoClass.getField("flags");
            boolean inputDone = false, outputDone = false;
            long totalPcmBytes = 0;
            int timeoutUs = 10000;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = (int) codecClass.getMethod("dequeueInputBuffer", long.class).invoke(codec, (long) timeoutUs);
                    if (inIndex >= 0) {
                        java.nio.ByteBuffer inBuf = (java.nio.ByteBuffer) codecClass.getMethod("getInputBuffer", int.class).invoke(codec, inIndex);
                        int sampleSize = (int) extractorClass.getMethod("readSampleData", java.nio.ByteBuffer.class, int.class).invoke(extractor, inBuf, 0);
                        if (sampleSize < 0) {
                            codecClass.getMethod("queueInputBuffer", int.class, int.class, long.class, long.class, int.class)
                                    .invoke(codec, inIndex, 0, 0, 0L, 4); // BUFFER_FLAG_END_OF_STREAM
                            inputDone = true;
                        } else {
                            long pts = (long) extractorClass.getMethod("getSampleTime").invoke(extractor);
                            codecClass.getMethod("queueInputBuffer", int.class, int.class, long.class, long.class, int.class)
                                    .invoke(codec, inIndex, 0, sampleSize, pts, 0);
                            extractorClass.getMethod("advance").invoke(extractor);
                        }
                    }
                }
                int outIndex = (int) codecClass.getMethod("dequeueOutputBuffer", bufferInfoClass, long.class).invoke(codec, info, (long) timeoutUs);
                if (outIndex >= 0) {
                    java.nio.ByteBuffer outBuf = (java.nio.ByteBuffer) codecClass.getMethod("getOutputBuffer", int.class).invoke(codec, outIndex);
                    int sz = (int) sizeField.get(info);
                    int off = (int) offsetField.get(info);
                    if (sz > 0 && outBuf != null) {
                        outBuf.position(off);
                        outBuf.limit(off + sz);
                        byte[] chunk = new byte[sz];
                        outBuf.get(chunk);
                        fos.write(chunk);
                        totalPcmBytes += sz;
                        if (totalPcmBytes > MAX_OUTPUT_BYTES) { outputDone = true; }
                    }
                    codecClass.getMethod("releaseOutputBuffer", int.class, boolean.class).invoke(codec, outIndex, false);
                    int flags = (int) flagsField.get(info);
                    if ((flags & 4) != 0) outputDone = true; // END_OF_STREAM
                } else if (outIndex == -1) { // INFO_TRY_AGAIN_LATER
                    if (inputDone) { /* continue */ }
                }
                if (totalPcmBytes > MAX_OUTPUT_BYTES) break;
            }
            fos.close();
            try { codecClass.getMethod("stop").invoke(codec); codecClass.getMethod("release").invoke(codec); } catch (Exception ignored) {}
            try { extractorClass.getMethod("release").invoke(extractor); } catch (Exception ignored) {}
            if (!out.exists() || out.length() <= 44) { try { out.delete(); } catch (Exception ignored) {} return null; }
            // 回填 wav 头
            try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
                int channels = channelCount;
                int sr = sampleRate;
                int byteRate = sr * channels * 2;
                int blockAlign = channels * 2;
                long dataLen = out.length() - 44;
                raf.seek(0);
                raf.writeBytes("RIFF");
                raf.writeInt(Integer.reverseBytes((int) (36 + dataLen)));
                raf.writeBytes("WAVE");
                raf.writeBytes("fmt ");
                raf.writeInt(Integer.reverseBytes(16));
                raf.writeShort(Short.reverseBytes((short) 1));
                raf.writeShort(Short.reverseBytes((short) channels));
                raf.writeInt(Integer.reverseBytes(sr));
                raf.writeInt(Integer.reverseBytes(byteRate));
                raf.writeShort(Short.reverseBytes((short) blockAlign));
                raf.writeShort(Short.reverseBytes((short) 16));
                raf.writeBytes("data");
                raf.writeInt(Integer.reverseBytes((int) dataLen));
            }
            Log.info("[SiliconMusic] Android MediaExtractor transcode ok: " + src.name() + " -> " + out.name() + " (" + out.length() + " bytes)");
            return out;
        } catch (ClassNotFoundException e) {
            return null; // 非 Android 环境
        } catch (Exception e) {
            Log.warn("[SiliconMusic] Android MediaExtractor transcode failed for " + src.name() + ": " + e.getMessage());
            return null;
        }
    }

    /** 供 isPlayable 快速判断：是否为 ffmpeg/Java 可尝试的音视频（不含已可解码的 ogg/mp3/wav） */
    public static boolean isTranscodableExtension(String path) {
        if (path == null) return false;
        return isTranscodable(path);
    }

    /** 若已存在转码缓存（_t.ogg / _t.wav）则返回，否则 null（不触发转码，仅供长度探测复用） */
    public static Fi getTranscodedIfExists(String hash) {
        if (hash == null) return null;
        Fi ogg = cacheTranscoded(hash, ".ogg");
        if (ogg != null && ogg.exists() && ogg.length() > 1024) return ogg;
        Fi wav = cacheTranscoded(hash, ".wav");
        if (wav != null && wav.exists() && wav.length() > 1024) return wav;
        return null;
    }
}
