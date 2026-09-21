package silicon.audio.decode;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Obuffer;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * MP3 解码（JLayer，LGPL-2.1）。
 * <p>
 * 为什么自己解：SoLoud 能放 mp3，但对「流式声源」的 idSeek 会落到错误位置
 * （项目里为此外挂了 0.35s 读回校验与禁用拖动）。这里把 mp3 解成 WAV 再播，
 * 拖动自然精确——不再依赖流式 seek。
 * <p>
 * 注意：JLayer 的 SynthesisFilter 通过类加载器资源读取 {@code sfd.ser}
 * （{@code JavaLayerUtils.getResourceAsStream}），该文件随库打进 mod jar，
 * 桌面端 URLClassLoader 可正常读取；若某平台读不到，可回退到 FFmpeg。
 */
public class Mp3Decoder implements PcmDecoder {

    @Override
    public boolean accepts(String fileName, byte[] head) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.endsWith(".mp3")) return true;
        if (head == null || head.length < 4) return false;
        // ID3v2 头
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return true;
        // MPEG 帧同步：0xFF 后高 3 位全 1
        for (int i = 0; i + 1 < head.length; i++) {
            if ((head[i] & 0xFF) == 0xFF && (head[i + 1] & 0xE0) == 0xE0) return true;
        }
        return false;
    }

    @Override
    public long decodeToWav(File src, File outWav, java.util.function.IntConsumer onPercent) throws Exception {
        Bitstream bitstream = null;
        WavWriter wav = null;
        long frames = 0;
        long fileLen = Math.max(1L, src.length());
        int lastPercent = -1;
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 1 << 16)) {
            bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                Obuffer ob = decoder.decodeFrame(header, bitstream);
                if (ob instanceof SampleBuffer sb) {
                    if (wav == null) {
                        int rate = sb.getSampleFrequency();
                        int ch = sb.getChannelCount();
                        if (rate <= 0 || ch <= 0) throw new IOException("bad mp3 stream info");
                        wav = new WavWriter(outWav, rate, ch);
                    }
                    short[] pcm = sb.getBuffer();
                    int len = sb.getBufferLength();
                    if (pcm != null && len > 0) {
                        wav.writeSamples(pcm, 0, len);
                        frames += len / Math.max(1, wav.channels());
                    }
                }
                bitstream.closeFrame();
                if (onPercent != null) {
                    int p = (int) Math.min(100L, Math.max(0L, bitstream.header_pos()) * 100L / fileLen);
                    if (p != lastPercent) {
                        lastPercent = p;
                        onPercent.accept(p);
                    }
                }
            }
            if (wav == null) throw new IOException("no mp3 frames decoded");
        } finally {
            if (wav != null) wav.close();
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (Exception ignored) {
                }
            }
        }
        return frames;
    }
}
