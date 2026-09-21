package silicon.audio;

import silicon.audio.decode.WavWriter;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 超长曲目降采样：把解码出的 16bit PCM WAV 按整数倍抽取（必要时并成单声道），把体积压回预算内。
 * <p>
 * <b>为什么需要</b>：本机播放统一走「解码成 WAV → SoLoud 播放」，而 PCM 体积 = 秒 × 采样率 × 声道 × 2。
 * 实测一首 44 分钟的 m4a 解出 <b>471MB</b>；用户缓存里已有 449.8MB + 246.2MB 两首长曲，合计 <b>696MB</b>，
 * 已经超过 512MB 缓存预算——于是 LRU 会把别的曲目挤掉（下次再听又要重新解码）。
 * （注：SoLoud 对 wav 走的是流式 `wavStreamLoad`，所以瓶颈是磁盘缓存/IO 而不是内存；
 * 只有 flac 的全量加载路径才会整曲进内存。）
 * 长曲目基本都是语音/ASMR/长篇录音，降到 22.05kHz 单声道的听感损失很小，却能把体积压到 1/4。
 * <p>
 * <b>策略</b>（按顺序取第一个能塞进预算的方案，尽量少降）：
 * <ol>
 *   <li>原采样率 + 原声道（不动）</li>
 *   <li>采样率 /2 + 原声道（优先保住立体声像——ASMR/音乐都比高频更依赖声像）</li>
 *   <li>采样率 /2 + 单声道</li>
 *   <li>采样率 /4 + 单声道、/8 + 单声道</li>
 * </ol>
 * 采样率不会降到 {@link #MIN_RATE} 以下（继续降会明显发闷）；抽取用 factor 点平均（盒式滤波）抗混叠，
 * 实现极简、够用。整个过程是流式的，471MB → 118MB 约几秒，跑在转码线程上，不占渲染线程。
 */
public final class WavDownsampler {

    /** 单个曲目 WAV 的体积上限：超过就降采样。512MB 缓存预算下给多首曲目留余量 */
    public static final long MAX_BYTES = 160L * 1024 * 1024;
    /** 降采样后允许的最低采样率（再低人耳会明显发闷，宁可超预算也不再降） */
    private static final int MIN_RATE = 16000;
    /** 可用的抽取倍数（整数倍，纯 Java 极简实现） */
    private static final int[] FACTORS = {2, 4, 8};

    private WavDownsampler() {}

    /** 处理结果（供日志与调用方判断） */
    public static final class Result {
        public boolean changed;
        public int rate;
        public int channels;
        public long bytes;
        public String text = "";

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * 超过 {@code maxBytes} 就降采样（原地替换 wav 文件）。
     * <p>
     * 失败只记录在 {@link Result#text} 里，绝不抛异常打断转码流程：宁可留着大文件能播，
     * 也不能因为「优化」让曲目播不出来。
     */
    public static Result shrinkIfNeeded(File wav, long maxBytes) {
        Result res = new Result();
        if (wav == null || !wav.isFile()) {
            res.text = "no file";
            return res;
        }
        res.bytes = wav.length();
        Header h;
        try {
            h = readHeader(wav);
        } catch (Exception e) {
            res.text = "skip (" + e.getMessage() + ")";
            return res;
        }
        res.rate = h.rate;
        res.channels = h.channels;
        if (h.bits != 16) {
            res.text = "skip (not 16bit: " + h.bits + ")";
            return res;
        }
        if (wav.length() <= maxBytes) {
            res.text = "within budget";
            return res;
        }
        long frames = h.dataSize / (2L * h.channels); // 每声道采样点数
        int[] plan = plan(frames, h.rate, h.channels, maxBytes);
        int factor = plan[0], outCh = plan[1];
        if (factor == 1 && outCh == h.channels) {
            res.text = "over budget but no safe plan";
            return res;
        }
        int outRate = h.rate / factor;
        File tmp = new File(wav.getParentFile(), wav.getName() + ".shrink");
        try {
            convert(wav, tmp, h, factor, outCh, outRate);
        } catch (Exception e) {
            if (tmp.exists()) tmp.delete();
            res.text = "convert failed: " + e;
            return res;
        }
        try {
            if (!wav.delete()) throw new IOException("cannot delete original");
            if (!tmp.renameTo(wav)) throw new IOException("cannot rename tmp");
        } catch (Exception e) {
            if (tmp.exists() && !wav.exists()) tmp.renameTo(wav);
            res.text = "replace failed: " + e;
            return res;
        }
        res.changed = true;
        res.rate = outRate;
        res.channels = outCh;
        res.bytes = wav.length();
        res.text = String.format(java.util.Locale.US,
                "%.1fs %s -> %dHz/%dch %s (x%d%s)",
                frames / (double) h.rate, mb(h.dataSize), outRate, outCh, mb(res.bytes),
                factor, outCh == h.channels ? "" : ", mono");
        return res;
    }

    /** 选抽取倍数与目标声道数：优先保采样率、其次保声道，最后才并单声道 */
    static int[] plan(long frames, int rate, int channels, long maxBytes) {
        if (frames <= 0 || rate <= 0 || channels <= 0) return new int[]{1, Math.max(1, channels)};
        if (bytesFor(frames, channels) <= maxBytes) return new int[]{1, channels};
        for (int factor : FACTORS) {
            int r = rate / factor;
            if (r < MIN_RATE) break;
            long f = frames / factor;
            if (bytesFor(f, channels) <= maxBytes) return new int[]{factor, channels};
            if (channels > 1 && bytesFor(f, 1) <= maxBytes) return new int[]{factor, 1};
        }
        // 兜底：允许范围内最小的配置（可能仍超预算，日志里会写清楚）
        int best = 1;
        for (int factor : FACTORS) {
            if (rate / factor >= MIN_RATE) best = factor;
        }
        return new int[]{best, channels > 1 ? 1 : channels};
    }

    private static long bytesFor(long framesPerChannel, int channels) {
        return framesPerChannel * channels * 2L + 44L;
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0);
    }

    /** 流式抽取：按 factor 点平均降采样，必要时把多声道并成单声道 */
    private static void convert(File src, File dst, Header h, int factor, int outCh, int outRate) throws IOException {
        final int blockOutFrames = 4096;
        final int blockInFrames = blockOutFrames * factor;
        final int ch = h.channels;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(src), 1 << 20))) {
            skipFully(in, h.dataOffset);
            try (WavWriter out = new WavWriter(dst, outRate, outCh)) {
                byte[] raw = new byte[blockInFrames * ch * 2];
                short[] samples = new short[blockInFrames * ch];
                short[] conv = new short[blockOutFrames * outCh];
                while (true) {
                    int read = readSome(in, raw);
                    if (read < ch * 2) break;
                    int inFrames = read / (ch * 2);
                    int inSamples = inFrames * ch;
                    for (int i = 0; i < inSamples; i++) {
                        samples[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
                    }
                    int outFrames = 0;
                    for (int f = 0; f + factor <= inFrames; f += factor) {
                        if (outCh == ch) {
                            for (int c = 0; c < ch; c++) {
                                int acc = 0;
                                for (int k = 0; k < factor; k++) acc += samples[(f + k) * ch + c];
                                conv[outFrames * outCh + c] = (short) (acc / factor);
                            }
                        } else {
                            int acc = 0;
                            for (int k = 0; k < factor; k++) {
                                for (int c = 0; c < ch; c++) acc += samples[(f + k) * ch + c];
                            }
                            conv[outFrames] = (short) (acc / (factor * ch));
                        }
                        outFrames++;
                    }
                    if (outFrames > 0) out.writeSamples(conv, 0, outFrames * outCh);
                }
            }
        }
    }

    /** WAV 关键字段：fmt 的采样率/声道/位深 + data 的起点与长度 */
    private static final class Header {
        int rate;
        int channels;
        int bits;
        long dataOffset = -1;
        long dataSize;
    }

    static Header readHeader(File f) throws IOException {
        Header out = new Header();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            if (len < 44) throw new IOException("too small");
            byte[] riff = new byte[12];
            raf.readFully(riff);
            if (!(riff[0] == 'R' && riff[1] == 'I' && riff[2] == 'F' && riff[3] == 'F'
                    && riff[8] == 'W' && riff[9] == 'A' && riff[10] == 'V' && riff[11] == 'E')) {
                throw new IOException("not RIFF/WAVE");
            }
            long pos = 12;
            byte[] chunk = new byte[8];
            while (pos + 8 <= len) {
                raf.seek(pos);
                raf.readFully(chunk);
                long size = (chunk[4] & 0xFFL) | ((chunk[5] & 0xFFL) << 8) | ((chunk[6] & 0xFFL) << 16) | ((chunk[7] & 0xFFL) << 24);
                String id = new String(chunk, 0, 4, StandardCharsets.US_ASCII);
                long body = pos + 8;
                if (id.equals("fmt ") && size >= 16) {
                    byte[] fmt = new byte[16];
                    raf.seek(body);
                    raf.readFully(fmt);
                    out.channels = (fmt[2] & 0xFF) | ((fmt[3] & 0xFF) << 8);
                    out.rate = (fmt[4] & 0xFF) | ((fmt[5] & 0xFF) << 8) | ((fmt[6] & 0xFF) << 16) | ((fmt[7] & 0xFF) << 24);
                    out.bits = (fmt[14] & 0xFF) | ((fmt[15] & 0xFF) << 8);
                } else if (id.equals("data")) {
                    out.dataOffset = body;
                    out.dataSize = Math.max(0, Math.min(size, len - body));
                    if (out.rate > 0) break; // fmt 已读到，够了
                }
                if (size <= 0) break;
                pos = body + size + (size & 1L); // 块按偶数字节对齐
            }
        }
        if (out.rate <= 0 || out.channels <= 0 || out.dataOffset < 0 || out.dataSize <= 0) {
            throw new IOException("bad wav header");
        }
        return out;
    }

    private static int readSome(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }
}
