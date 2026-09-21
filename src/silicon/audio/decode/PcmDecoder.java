package silicon.audio.decode;

import java.io.File;

/**
 * 纯 Java PCM 解码器统一接口。
 * <p>
 * 实现只依赖 {@code java.io}（不碰 arc / javax.sound），因此既能被播放器调用，
 * 也能用普通 JVM 单独跑测试。产物固定为 16bit PCM WAV——交给 SoLoud 播放时
 * seek 是采样级精确，拖动/倒放/A-B 全部可用。
 */
public interface PcmDecoder {
    /** 是否能处理该输入：按文件名扩展名 + 文件头（可能为 null）判断 */
    boolean accepts(String fileName, byte[] head);

    /**
     * 解码 src 到 outWav（16bit PCM WAV）。
     *
     * @return 解码出的采样帧数（每声道计一帧）
     * @throws Exception 不支持 / 数据损坏 / 参数越界（如 24bit FLAC）
     */
    long decodeToWav(File src, File outWav, java.util.function.IntConsumer onPercent) throws Exception;

    /** 兼容旧签名（不需要进度时） */
    default long decodeToWav(File src, File outWav) throws Exception {
        return decodeToWav(src, outWav, null);
    }
}
