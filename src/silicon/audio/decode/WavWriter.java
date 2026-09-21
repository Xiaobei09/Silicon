package silicon.audio.decode;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 极简 16bit PCM WAV 写入器（LE）。
 * <p>
 * 先写占位头，边解码边追加数据，收尾时回填 RIFF/data 长度。用 RandomAccessFile 是因为
 * 长度只有解码完才知道；不依赖 javax.sound（Android 上没有），因此可在所有平台使用。
 */
public class WavWriter implements Closeable {
    private final File out;
    private final RandomAccessFile raf;
    private final int sampleRate;
    private final int channels;
    private long dataBytes;
    private boolean closed;

    public WavWriter(File out, int sampleRate, int channels) throws IOException {
        if (sampleRate <= 0 || channels <= 0) throw new IOException("bad wav params: " + sampleRate + "/" + channels);
        this.out = out;
        this.sampleRate = sampleRate;
        this.channels = channels;
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        this.raf = new RandomAccessFile(out, "rw");
        raf.setLength(0L);
        writeHeader();
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public long dataBytes() {
        return dataBytes;
    }

    /** 写入交错 16bit PCM 样本 */
    public void writeSamples(short[] buf, int off, int len) throws IOException {
        byte[] tmp = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            short s = buf[off + i];
            tmp[i * 2] = (byte) (s & 0xFF);
            tmp[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        raf.write(tmp);
        dataBytes += tmp.length;
    }

    /** 写入已经是 16bit LE 的原始字节（jFLAC 直接给字节流） */
    public void writeBytes(byte[] data, int off, int len) throws IOException {
        if (len <= 0) return;
        raf.write(data, off, len);
        dataBytes += len;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        raf.seek(0L);
        writeHeader();
        raf.setLength(44L + dataBytes);
        raf.close();
    }

    private void writeHeader() throws IOException {
        int byteRate = sampleRate * channels * 2;
        raf.writeBytes("RIFF");
        writeIntLE((int) (36 + dataBytes));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeIntLE(16);              // PCM 头长度
        writeShortLE((short) 1);     // PCM
        writeShortLE((short) channels);
        writeIntLE(sampleRate);
        writeIntLE(byteRate);
        writeShortLE((short) (channels * 2)); // block align
        writeShortLE((short) 16);    // bits per sample
        raf.writeBytes("data");
        writeIntLE((int) dataBytes);
    }

    private void writeIntLE(int v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    private void writeShortLE(short v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
    }

    @Override
    public String toString() {
        return "WavWriter{" + out.getName() + ", " + sampleRate + "Hz/" + channels + "ch, " + dataBytes + "B}";
    }
}
