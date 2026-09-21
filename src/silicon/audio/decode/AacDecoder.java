package silicon.audio.decode;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.function.IntConsumer;

/**
 * m4a / mp4 / aac 解码：**自写最小 MP4 解封装**（{@link Mp4Demuxer}）+ **jaad 的 AAC 解码核心**。
 * <p>
 * 为什么这样拼：SoLoud 完全不认识这些格式；jaad 的 AAC 解码核心可用，但它的**容器解析**对现代
 * 封装过于脆弱（实测多个真实 mp4 报 {@code box too large for parent}），因此解封装自己做：
 * 从 stsd/esds 取 ASC，从 stsz/stsc/stco 算出每帧偏移与长度，再把裸 AAC 帧交给 jaad 解码。
 * <p>
 * 产物是 16bit PCM WAV（与 mp3/flac/opus 同一条“统一解码”路径）→ 拖动采样级精确；
 * 进度按「已解帧数 / 总帧数」上报，因此 m4a 现在也有真实百分比进度。
 */
public class AacDecoder implements PcmDecoder {

    @Override
    public boolean accepts(String fileName, byte[] head) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        // ISO-BMFF 的 ftyp box：偏移 4 起为 'ftyp'
        if (head != null && head.length >= 12
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return true;
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return true;
        // 裸 ADTS：强校验（layer 位必须为 00），因此不会被 mp3 抢走、也不会误吞 mp3
        if (AdtsDemuxer.looksLike(head)) return true;
        return n.endsWith(".aac") || n.endsWith(".adts");
    }

    @Override
    public long decodeToWav(File src, File outWav, IntConsumer onPercent) throws Exception {
        // 两条解封装路径：m4a/mp4 走 moov/stbl 帧表；裸 ADTS（.aac）走逐帧头
        byte[] head = new byte[12];
        int n = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(src)) {
            int r;
            while (n < 12 && (r = in.read(head, n, 12 - n)) > 0) n += r;
        }
        Mp4Demuxer.Audio a = (n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p')
                ? Mp4Demuxer.parse(src)
                : AdtsDemuxer.parse(src);
        if (a == null || a.asc == null || a.frameOffsets.length == 0) {
            throw new IOException("no AAC track/ASC found");
        }
        Decoder decoder = new Decoder(a.asc);
        SampleBuffer sb = new SampleBuffer();
        sb.setBigEndian(false); // 我们的 WavWriter 写小端

        WavWriter wav = null;
        long frames = 0;
        int lastPercent = -1;
        int total = a.frameOffsets.length;
        try (RandomAccessFile raf = new RandomAccessFile(src, "r")) {
            int maxSize = 0;
            for (int s : a.frameSizes) maxSize = Math.max(maxSize, s);
            byte[] frame = new byte[Math.max(1024, maxSize)];
            for (int i = 0; i < total; i++) {
                int size = a.frameSizes[i];
                if (size <= 0 || size > frame.length) continue;
                raf.seek(a.frameOffsets[i]);
                raf.readFully(frame, 0, size);
                decoder.decodeFrame(frame, sb);
                byte[] pcm = sb.getData();
                if (wav == null) {
                    int rate = sb.getSampleRate() > 0 ? sb.getSampleRate() : a.sampleRate;
                    int ch = sb.getChannels() > 0 ? sb.getChannels() : a.channels;
                    if (rate <= 0 || ch <= 0) throw new IOException("bad AAC stream info");
                    wav = new WavWriter(outWav, rate, ch);
                }
                if (pcm != null && pcm.length > 0) {
                    wav.writeBytes(pcm, 0, pcm.length);
                    frames += pcm.length / (2L * Math.max(1, wav.channels()));
                }
                if (onPercent != null) {
                    int p = (int) ((i + 1L) * 100L / total);
                    if (p != lastPercent) {
                        lastPercent = p;
                        onPercent.accept(p);
                    }
                }
            }
        } finally {
            if (wav != null) wav.close();
        }
        if (frames <= 0) throw new IOException("no AAC frames decoded");
        return frames;
    }
}
