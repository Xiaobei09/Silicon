package silicon.audio.decode;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 内置解码器注册表：按「扩展名 + 文件头」挑选纯 Java 解码器。
 * <p>
 * 目前覆盖 mp3、flac（Soloud 能播但流式 seek 不可靠的两种）。
 * ogg/wav 由 SoLoud 原生播放（seek 表现正常），不需要在这里再解一遍；
 * m4a/aac/opus/wma 等则交给可选的 FFmpeg。
 */
public class InternalDecoders {
    /** 注意顺序：AacDecoder 在 Mp3Decoder 之前——ADTS 同步字（FF F1）也满足 MPEG 同步字判定，
     *  若 mp3 先匹配会把 .aac 当 MP3 解（实测表现为「时长 0.036s + 解码失败」）。
     *  AacDecoder 的 accepts 用强校验（layer 位必须为 00，MP3 不可能满足），因此不会反过来误吞 mp3。 */
    private static final PcmDecoder[] DECODERS = {
            new FlacDecoder(), new AacDecoder(), new Mp3Decoder(), new OggOpusDecoder()
    };

    private InternalDecoders() {}

    /** 读取文件头若干字节（失败返回 null） */
    public static byte[] readHead(File f, int n) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[n];
            int off = 0;
            int r;
            while (off < n && (r = in.read(buf, off, n - off)) > 0) off += r;
            if (off <= 0) return null;
            if (off == n) return buf;
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean supports(String fileName, byte[] head) {
        for (PcmDecoder d : DECODERS) {
            if (d.accepts(fileName, head)) return true;
        }
        return false;
    }

    /**
     * 尝试用内置解码器解码。
     *
     * @return true=已成功产出 outWav；false=没有匹配的解码器或解码失败（调用方可继续尝试 FFmpeg）
     */
    public static boolean decode(File src, File outWav, String fileName, byte[] head) {
        return decode(src, outWav, fileName, head, null);
    }

    /** 带解码进度（0~100，可能回调 -1 表示未知） */
    public static boolean decode(File src, File outWav, String fileName, byte[] head, java.util.function.IntConsumer onPercent) {
        // 按顺序尝试**所有**接受该文件的解码器，而不是第一个失败就整体失败：
        // accepts() 的判定只能靠扩展名/文件头，可能过宽（例如带 ID3 的 mp3 与 ADTS 同步字冲突），
        // 一旦第一个接受者解不了就 return false，会把后面本来能解的解码器永久挡住。
        String firstError = null;
        for (PcmDecoder d : DECODERS) {
            if (!d.accepts(fileName, head)) continue;
            try {
                long frames = d.decodeToWav(src, outWav, onPercent);
                if (frames > 0) {
                    lastError = null;
                    return true;
                }
                if (firstError == null) firstError = d.getClass().getSimpleName() + ": decoded 0 frames";
            } catch (Exception e) {
                // 本包刻意不引用 arc（便于用普通 JVM 单测）；失败原因由调用方记录
                String msg = d.getClass().getSimpleName() + ": " + e.getMessage();
                if (firstError == null) firstError = msg;
                cleanupFailedAttempt(outWav, d.getClass().getSimpleName(), e);
            }
        }
        lastError = firstError;
        return false;
    }

    /** 单个解码器失败时把「产物清掉」，避免下一个解码器看到上一个的半成品（WavWriter 会截断，但空文件会让
     *  AudioTranscoder 误判成功）；失败细节进日志便于排查。 */
    private static void cleanupFailedAttempt(File outWav, String name, Exception e) {
        try {
            if (outWav != null && outWav.exists() && outWav.length() > 44) outWav.delete();
        } catch (Exception ignored) {
        }
        System.out.println("[SiliconMusic] decoder " + name + " failed: " + e);
    }

    /** 上一次 decode 失败原因（供调用方记录/提示） */
    private static volatile String lastError;

    public static String lastError() {
        return lastError;
    }
}
