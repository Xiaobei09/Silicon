package silicon.audio.decode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 裸 ADTS（{@code .aac/.adts}）解封装：ADTS 没有容器，帧头自带长度与编码参数。
 * <p>
 * 与 {@link Mp4Demuxer} 并列：两者都把裸 AAC 帧 + ASC 交给 jaad 的 Decoder，只是来源不同——
 * m4a/mp4 走 moov/stbl 帧表，adts 走逐帧头解析。ASC 由 ADTS 头合成（
 * {@code audioObjectType = profile + 1} + samplingFrequencyIndex + channelConfiguration）。
 */
public final class AdtsDemuxer {

    /** ADTS 采样率索引表（ISO/IEC 13818-7） */
    private static final int[] RATES = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private AdtsDemuxer() {}

    /**
     * 文件头是否为 ADTS 流。
     * <p>
     * 必须做**强校验**：ADTS 的同步字 {@code FF F?} 同时满足 MPEG 音频同步字判定，光看前两字节
     * 会把 .aac 误判成 mp3（实测：时长被算成 0.036s、解码交给 jlayer 后失败）。区别在
     * {@code b1} 的 layer 位——ADTS 固定为 {@code 00}，而 MPEG 音频里 {@code 00} 是保留值，
     * 因此「layer==00 且帧长合法」不会与 mp3 冲突。
     * <p>
     * <b>绝不能把「以 ID3 开头」当作 ADTS 依据</b>：带 ID3v2 标签的 mp3 是常见情况，而 AacDecoder
     * 在解码器列表里排在 Mp3Decoder 之前，一旦这里对 ID3 返回 true，这些 mp3 会被交给 jaad 解码失败
     * （表现为「带标签的 mp3 播不了」）。带 ID3 的 .aac 由调用方按扩展名判断。
     */
    public static boolean looksLike(byte[] head) {
        if (head == null || head.length < 7) return false;
        if (head[0] != (byte) 0xFF || (head[1] & 0xF0) != 0xF0) return false;
        if ((head[1] & 0x06) != 0) return false; // layer 必须为 00（mp3 是 01/10）
        int freqIdx = (head[2] >> 2) & 0x0F;
        int frameLen = ((head[3] & 0x03) << 11) | ((head[4] & 0xFF) << 3) | ((head[5] & 0xE0) >> 5);
        return freqIdx < RATES.length && frameLen >= 7;
    }

    /** 收集每帧偏移/长度（供解码用）；失败抛 IOException */
    public static Mp4Demuxer.Audio parse(File f) throws IOException {
        Mp4Demuxer.Audio a = new Mp4Demuxer.Audio();
        List<Long> offs = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        long[] first = new long[4]; // rate, channels, asc0, asc1
        int frames = scan(f, offs, sizes, first);
        if (frames <= 0) throw new IOException("no ADTS frames found");
        a.sampleRate = (int) first[0];
        a.channels = (int) first[1];
        a.asc = new byte[]{(byte) first[2], (byte) first[3]};
        a.frameOffsets = new long[offs.size()];
        a.frameSizes = new int[sizes.size()];
        for (int i = 0; i < offs.size(); i++) {
            a.frameOffsets[i] = offs.get(i);
            a.frameSizes[i] = sizes.get(i);
        }
        return a;
    }

    /** 只算时长（列表显示用），不建帧表。
     *  时长探测跑在渲染线程上（列表每次重建都会问每首曲目），因此大文件不整文件扫描：
     *  先扫前 {@link #TIME_SCAN_BYTES}，用「平均帧长」外推总帧数（ADTS 是恒定帧长流，误差极小）。 */
    public static float durationSeconds(File f) {
        if (f == null || !f.isFile()) return -1f;
        long[] first = new long[4];
        int frames;
        long scanned;
        try {
            scanned = Math.min(f.length(), TIME_SCAN_BYTES);
            frames = scan(f, null, null, first, scanned);
        } catch (Exception e) {
            return -1f;
        }
        if (frames <= 0 || first[0] <= 0) return -1f;
        double totalFrames = frames;
        if (f.length() > scanned && scanned > 0) {
            // 外推：总帧数 ≈ 已扫帧数 × (文件长 / 已扫字节)。已扫字节里含帧头，比例一致，误差可忽略。
            totalFrames = frames * (double) f.length() / (double) scanned;
        }
        return (float) (totalFrames * 1024.0 / first[0]);
    }

    /**
     * 扫描 ADTS 帧。
     *
     * @param offs  非 null 时收集每帧数据偏移
     * @param sizes 非 null 时收集每帧数据长度
     * @param first 输出 [采样率, 声道数, ASC 高字节, ASC 低字节]（第一帧决定）
     * @return 帧数
     */
    /** 时长探测的扫描上限：超过就只扫这一段并外推（探测在渲染线程上跑，不能整文件扫） */
    private static final long TIME_SCAN_BYTES = 8L * 1024 * 1024;

    private static int scan(File f, List<Long> offs, List<Integer> sizes, long[] first) throws IOException {
        return scan(f, offs, sizes, first, Long.MAX_VALUE);
    }

    /** @param maxBytes 最多扫描的字节数（只用于时长探测，避免大文件整扫） */
    private static int scan(File f, List<Long> offs, List<Integer> sizes, long[] first, long maxBytes) throws IOException {
        int frames = 0;
        try (PushbackInputStream in = new PushbackInputStream(
                new BufferedInputStream(new FileInputStream(f), 1 << 16), 10)) {
            long pos = skipId3(in);
            byte[] h = new byte[7];
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                pos++;
                if (b0 != 0xFF) continue;
                int b1 = in.read();
                if (b1 < 0) break;
                pos++;
                if ((b1 & 0xF0) != 0xF0) {
                    // b1 本身可能是下一个同步字的首字节，退回重扫
                    in.unread(b1);
                    pos--;
                    continue;
                }
                if (!readFully(in, h, 2, 5)) break;
                pos += 5;
                int profile = (h[2] >> 6) & 0x03;
                int freqIdx = (h[2] >> 2) & 0x0F;
                int channels = ((h[2] & 0x01) << 2) | ((h[3] >> 6) & 0x03);
                int frameLen = ((h[3] & 0x03) << 11) | ((h[4] & 0xFF) << 3) | ((h[5] & 0xE0) >> 5);
                int headerLen = ((b1 & 0x01) != 0) ? 7 : 9; // protection_absent=1 → 无 CRC
                if (freqIdx >= RATES.length || frameLen <= headerLen) continue;
                if (first != null && frames == 0) {
                    first[0] = RATES[freqIdx];
                    first[1] = channels > 0 ? channels : 2;
                    int objType = profile + 1; // ADTS profile 0=Main,1=LC,2=SSR,3=LTP
                    first[2] = ((objType << 3) | (freqIdx >> 1)) & 0xFF;
                    first[3] = (((freqIdx & 0x01) << 7) | ((channels > 0 ? channels : 2) << 3)) & 0xFF;
                }
                int payload = frameLen - headerLen;
                if (offs != null) {
                    offs.add(pos + (headerLen - 7)); // 已消费 7 字节头，CRC 情况下还差 2 字节
                    sizes.add(payload);
                }
                frames++;
                long skip = (long) payload + (headerLen - 7);
                if (!skipFully(in, skip)) {
                    pos += skip;
                    break;
                }
                pos += skip;
            }
        }
        return frames;
    }

    /** 跳过文件头的 ID3v2 标签（若有），返回跳过的字节数；非 ID3 时把探测用的字节全部退回 */
    private static long skipId3(PushbackInputStream in) throws IOException {
        byte[] h = new byte[10];
        int n = 0;
        while (n < 10) {
            int r = in.read(h, n, 10 - n);
            if (r < 0) break;
            n += r;
        }
        if (n == 10 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
            long size = ((h[6] & 0x7FL) << 21) | ((h[7] & 0x7FL) << 14) | ((h[8] & 0x7FL) << 7) | (h[9] & 0x7FL);
            skipFully(in, size);
            return 10 + size;
        }
        if (n > 0) in.unread(h, 0, n); // 退回，交给主循环找同步字
        return 0;
    }

    private static boolean readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(buf, off + n, len - n);
            if (r < 0) return false;
            n += r;
        }
        return true;
    }

    private static boolean skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return false;
                s = 1;
            }
            n -= s;
        }
        return true;
    }
}
