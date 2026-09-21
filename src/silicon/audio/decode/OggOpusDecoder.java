package silicon.audio.decode;

import org.gagravarr.opus.OpusAudioData;
import org.gagravarr.opus.OpusFile;
import org.gagravarr.opus.OpusInfo;

import java.io.File;
import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * Ogg Opus 解码：vorbis-java 解 Ogg 容器 + Concentus 解 Opus 帧（纯 Java，跨平台）。
 * <p>
 * Opus 解码输出**固定 48kHz**（与原始采样率无关，OpusHead 里的输入率只是提示），
 * 因此产物统一写成 48kHz WAV；OpusHead 的 preSkip 需要丢弃，否则开头会有几十毫秒偏移。
 * <p>
 * SoLoud 不认识 opus（也无法处理非 ASCII 路径），所以与 mp3/flac/m4a 一样走
 * “解码成 WAV 再播”，拖动即采样级精确。
 */
public class OggOpusDecoder implements PcmDecoder {
    /** Opus 解码固定输出率 */
    private static final int OPUS_RATE = 48000;

    @Override
    public boolean accepts(String fileName, byte[] head) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.endsWith(".opus")) return true;
        // OggS 页头 + 偏移 28 处的 "OpusHead" 魔数
        return head != null && head.length >= 36
                && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S'
                && head[28] == 'O' && head[29] == 'p' && head[30] == 'u' && head[31] == 's';
    }

    @Override
    public long decodeToWav(File src, File outWav, IntConsumer onPercent) throws Exception {
        try (OpusFile file = new OpusFile(src)) {
            OpusInfo info = file.getInfo();
            int channels = Math.max(1, info == null ? 2 : info.getNumChannels());
            int preSkip = info == null ? 0 : Math.max(0, info.getPreSkip());
            io.github.jaredmdobson.concentus.OpusDecoder decoder =
                    new io.github.jaredmdobson.concentus.OpusDecoder(OPUS_RATE, channels);

            WavWriter wav = new WavWriter(outWav, OPUS_RATE, channels);
            // 每包最多 120ms → 5760 样本/声道
            short[] pcm = new short[5760 * channels];
            long frames = 0;
            long skipped = 0;
            try {
                OpusAudioData packet;
                while ((packet = file.getNextAudioPacket()) != null) {
                    byte[] data = packet.getData();
                    if (data == null || data.length == 0) continue;
                    int n = decoder.decode(data, 0, data.length, pcm, 0, pcm.length, false);
                    int start = 0;
                    if (skipped < preSkip) {
                        int sk = (int) Math.min(preSkip - skipped, n);
                        start = sk;
                        skipped += sk;
                        n -= sk;
                    }
                    if (n > 0) {
                        wav.writeSamples(pcm, start * channels, n * channels);
                        frames += n;
                    }
                }
            } finally {
                wav.close();
            }
            if (frames <= 0) throw new IOException("no opus frames decoded");
            return frames;
        }
    }
}
