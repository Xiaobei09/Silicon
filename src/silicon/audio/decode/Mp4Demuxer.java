package silicon.audio.decode;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 ISO-BMFF（MP4/M4A）解封装：只为取出「AAC 帧 + ASC」。
 * <p>
 * 为什么自己写：jaad 的容器解析对现代封装太脆弱（实测多个真实 mp4 报
 * {@code box too large for parent}），但它的 **AAC 解码核心是好的**。这里只做解封装，
 * 把裸 AAC 帧与 ASC 交给 jaad 的 Decoder，两边各取所长。
 * <p>
 * 需要的盒子：{@code moov→trak(soun)→mdia→minf→stbl} 下的
 * {@code stsd→esds}（取 ASC）、{@code stsz}（每帧长度）、{@code stsc}（块内帧数）、
 * {@code stco/co64}（块偏移）。
 */
public final class Mp4Demuxer {

    /** 解析结果：ASC + 帧表（帧在文件中的偏移与长度） */
    public static final class Audio {
        public byte[] asc;
        public int sampleRate;
        public int channels;
        public long[] frameOffsets;
        public int[] frameSizes;
    }

    private Mp4Demuxer() {}

    /**
     * 只读 {@code moov→mvhd} 的时长（不解封装、不解码，毫秒级）。
     * <p>
     * 列表时长必须能快速给出，所以这里不建立帧表（stsz 有几万条），只读 mvhd 的
     * timescale/duration。返回 -1 表示无法判定。
     */
    public static float durationSeconds(File f) {
        if (f == null || !f.isFile()) return -1f;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long fileLen = raf.length();
            long moov = findBox(raf, 0, fileLen, "moov", null);
            if (moov < 0) return -1f;
            long moovEnd = boxEnd(raf, moov - 8, fileLen);
            long mvhd = findBoxIn(raf, moov, moovEnd, "mvhd");
            if (mvhd < 0) return -1f;
            raf.seek(mvhd);
            int version = raf.readUnsignedByte();
            raf.skipBytes(3); // flags
            long timescale;
            long duration;
            if (version == 1) {
                raf.skipBytes(16); // creation + modification (64 位)
                timescale = raf.readInt() & 0xFFFFFFFFL;
                duration = raf.readLong();
            } else {
                raf.skipBytes(8); // creation + modification (32 位)
                timescale = raf.readInt() & 0xFFFFFFFFL;
                duration = raf.readInt() & 0xFFFFFFFFL;
            }
            if (timescale > 0 && duration > 0) return duration / (float) timescale;
        } catch (Exception ignored) {
        }
        return -1f;
    }

    public static Audio parse(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long fileLen = raf.length();

            long moov = findBox(raf, 0, fileLen, "moov", null);

            if (moov < 0) throw new IOException("no moov box");
            long moovEnd = boxEnd(raf, moov - 8, fileLen);

            java.util.List<Long> traks = findBoxes(raf, moov, moovEnd, "trak");

            for (long trak : traks) {
                long trakEnd = boxEnd(raf, trak - 8, moovEnd);
                // 只处理音频轨（hdlr.handlerType == 'soun'）
                long hdlr = findPath(raf, trak, trakEnd, "mdia", "hdlr");

                if (hdlr < 0 || !"soun".equals(readType(raf, hdlr + 8))) continue;
                long stbl = findPath(raf, trak, trakEnd, "mdia", "minf", "stbl");
                if (stbl < 0) continue;
                long stblEnd = boxEnd(raf, stbl - 8, trakEnd);

                Audio a = new Audio();
                if (!readSampleEntry(raf, stbl, stblEnd, a)) continue;
                a.frameOffsets = new long[0];
                long[] sizes = readStsz(raf, stbl, stblEnd);
                int[][] stsc = readStsc(raf, stbl, stblEnd);
                long[] chunkOffsets = readChunkOffsets(raf, stbl, stblEnd);
                if (sizes == null || stsc == null || chunkOffsets == null || sizes.length == 0) continue;
                buildFrameTable(a, sizes, stsc, chunkOffsets);
                return a;
            }
            throw new IOException("no AAC audio track found");
        }
    }

    /** stsd → (mp4a) 采样条目：取声道/采样率与 esds 里的 ASC */
    private static boolean readSampleEntry(RandomAccessFile raf, long stbl, long stblEnd, Audio out) throws IOException {
        long stsd = findBox(raf, stbl, stblEnd, "stsd", null);
        if (stsd < 0) return false;
        long stsdEnd = boxEnd(raf, stsd - 8, stblEnd);
        // stsd: version/flags(4) + entryCount(4) 之后是采样条目
        long entry = stsd + 8;
        if (entry + 8 > stsdEnd) return false;
        String type = readType(raf, entry + 4);
        long entryEnd = boxEnd(raf, entry, stsdEnd);
        if (!"mp4a".equals(type)) return false;
        // AudioSampleEntry: 8(头) + 8(reserved) + 2 channels + 2 sampleSize + 2 pre + 2 res + 4 sampleRate(16.16)
        // AudioSampleEntry：+8 保留(6)+dataRef(2)，+16 保留(8)，+24 channels，+26 sampleSize，
        // +28 preDefined，+30 reserved，+32 sampleRate(16.16)，+36 起为子盒（esds）
        raf.seek(entry + 24);
        out.channels = raf.readUnsignedShort();
        raf.skipBytes(2 + 2 + 2);
        long rateFixed = raf.readInt() & 0xFFFFFFFFL;
        out.sampleRate = (int) (rateFixed >>> 16);
        long childStart = entry + 36;
        // 采样条目内部字段长度各 muxer 不同（实测有比标准多 8 字节的），因此不硬编码偏移，
        // 而是在条目范围内按类型扫描 esds 盒。
        // 定位 esds 盒：不依赖各 muxer 的字段长度，直接在条目内按类型扫描，并要求
        // 「size 字段 + 类型字段」自洽、盒尾与条目尾一致（esds 通常是最后一个子盒）。
        long esdsStart = -1;
        long esdsSize = 0;
        for (long pos = entry + 12; pos + 8 <= entryEnd; pos++) {
            raf.seek(pos);
            if (raf.read() == 'e' && raf.read() == 's' && raf.read() == 'd' && raf.read() == 's') {
                long bs = pos - 4;
                if (bs < entry + 8) continue;
                raf.seek(bs);
                long size = raf.readInt() & 0xFFFFFFFFL;
                if (size >= 12 && bs + size <= entryEnd && bs + size >= entryEnd - 8) {
                    esdsStart = bs;
                    esdsSize = size;
                    break;
                }
            }
        }
        if (esdsStart < 0) return false;
        long esdsEnd = esdsStart + esdsSize;

        out.asc = extractAsc(raf, esdsStart + 12, esdsEnd); // 盒头(8) + version/flags(4)
        return out.asc != null && out.asc.length > 0;
    }

    /** 在 [start,end) 内按字节扫描 esds 盒，返回其「内容起点」；不同 muxer 的采样条目字段长度不一，硬编码偏移不可靠 */
    private static long findEsds(RandomAccessFile raf, long start, long end) throws IOException {
        byte[] needle = {'e', 's', 'd', 's'};
        long limit = end - 8;
        for (long pos = start; pos <= limit; pos++) {
            raf.seek(pos);
            if (raf.read() == needle[0] && raf.read() == needle[1] && raf.read() == needle[2] && raf.read() == needle[3]) {
                long boxStart = pos - 4;
                if (boxStart < start - 16) continue;
                raf.seek(boxStart);
                long size = raf.readInt() & 0xFFFFFFFFL;
                if (size >= 8 && boxStart + size <= end + 8) return pos + 4;
            }
        }
        return -1;
    }

    /** 在 esds 的描述符链里找 DecoderSpecificInfo（tag 0x05） */
    private static byte[] extractAsc(RandomAccessFile raf, long pos, long end) throws IOException {
        while (pos < end) {
            raf.seek(pos); // 必须显式定位：调用方的读取会移动文件指针
            int tag = raf.readUnsignedByte();
            int len = 0;
            for (int i = 0; i < 4; i++) { // 变长长度（最高位续位）
                int b = raf.readUnsignedByte();
                len = (len << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) break;
            }
            long body = raf.getFilePointer();


            if (tag == 0x03) { // ES_Descriptor：ES_ID(2) + flags(1) [+ 可选字段]
                raf.seek(body);
                raf.skipBytes(2);
                int flags = raf.readUnsignedByte();
                if ((flags & 0x80) != 0) raf.skipBytes(2);
                if ((flags & 0x40) != 0) {
                    int urlLen = raf.readUnsignedByte();
                    raf.skipBytes(urlLen);
                }
                if ((flags & 0x20) != 0) raf.skipBytes(2);
                pos = raf.getFilePointer(); // 继续在同一层找 0x04
                continue;
            }
            if (tag == 0x04) { // DecoderConfigDescriptor：13 字节固定字段后是 0x05
                raf.seek(body + 13);
                pos = body + 13;
                continue;
            }
            if (tag == 0x05) { // ASC
                byte[] asc = new byte[len];
                raf.readFully(asc);
                return asc;
            }
            pos = body + len;
            raf.seek(pos);
        }
        return null;
    }

    private static long[] readStsz(RandomAccessFile raf, long stbl, long stblEnd) throws IOException {
        long stsz = findBox(raf, stbl, stblEnd, "stsz", null);
        if (stsz < 0) return null;
        // findBox 返回的是「盒子内容起点」，stsz 内容是 version/flags(4) + sampleSize(4) + sampleCount(4) + 表
        raf.seek(stsz + 4); // version/flags 之后即 sampleSize
        int constant = raf.readInt();
        int count = raf.readInt();
        if (count <= 0 || count > 4_000_000) return null;
        long[] sizes = new long[count];
        if (constant > 0) {
            java.util.Arrays.fill(sizes, constant & 0xFFFFFFFFL);
        } else {
            for (int i = 0; i < count; i++) sizes[i] = raf.readInt() & 0xFFFFFFFFL;
        }
        return sizes;
    }

    private static int[][] readStsc(RandomAccessFile raf, long stbl, long stblEnd) throws IOException {
        long stsc = findBox(raf, stbl, stblEnd, "stsc", null);
        if (stsc < 0) return null;
        raf.seek(stsc + 4);
        int entries = raf.readInt();
        if (entries <= 0 || entries > 100000) return null;
        int[][] table = new int[entries][3];
        for (int i = 0; i < entries; i++) {
            table[i][0] = raf.readInt(); // firstChunk (1-based)
            table[i][1] = raf.readInt(); // samplesPerChunk
            table[i][2] = raf.readInt(); // sampleDescriptionIndex
        }
        return table;
    }

    private static long[] readChunkOffsets(RandomAccessFile raf, long stbl, long stblEnd) throws IOException {
        long stco = findBox(raf, stbl, stblEnd, "stco", null);
        boolean wide = false;
        if (stco < 0) {
            stco = findBox(raf, stbl, stblEnd, "co64", null);
            wide = true;
        }
        if (stco < 0) return null;
        raf.seek(stco + 4);
        int count = raf.readInt();
        if (count <= 0 || count > 4_000_000) return null;
        long[] offsets = new long[count];
        for (int i = 0; i < count; i++) offsets[i] = wide ? raf.readLong() : (raf.readInt() & 0xFFFFFFFFL);
        return offsets;
    }

    /** 按 stsc/stco/stsz 把每个 AAC 帧的偏移与长度算出来（块内顺序累加） */
    private static void buildFrameTable(Audio a, long[] sizes, int[][] stsc, long[] chunkOffsets) {
        List<Long> offs = new ArrayList<>(sizes.length);
        List<Integer> lens = new ArrayList<>(sizes.length);
        int sample = 0;
        for (int ci = 0; ci < chunkOffsets.length && sample < sizes.length; ci++) {
            int chunkNumber = ci + 1;
            int perChunk = 0;
            for (int[] e : stsc) {
                if (chunkNumber >= e[0]) perChunk = e[1];
                else break;
            }
            if (perChunk <= 0) continue;
            long offset = chunkOffsets[ci];
            for (int k = 0; k < perChunk && sample < sizes.length; k++, sample++) {
                offs.add(offset);
                lens.add((int) sizes[sample]);
                offset += sizes[sample];
            }
        }
        a.frameOffsets = new long[offs.size()];
        a.frameSizes = new int[lens.size()];
        for (int i = 0; i < offs.size(); i++) {
            a.frameOffsets[i] = offs.get(i);
            a.frameSizes[i] = lens.get(i);
        }
    }

    // ---------------------------------------------------------------- 盒子工具

    /** 在 [start,end) 里找第一个指定类型的子盒（可先用 path 逐级限定），返回「内容起点」，找不到 -1 */
    private static long findBox(RandomAccessFile raf, long start, long end, String type, String path) throws IOException {
        if (path == null) return findBoxIn(raf, start, end, type);
        long container = findBoxIn(raf, start, end, path);
        if (container < 0) return -1;
        long containerEnd = boxEnd(raf, container - 8, end);
        return findBoxIn(raf, container, containerEnd, type);
    }

    private static List<Long> findBoxes(RandomAccessFile raf, long start, long end, String type) throws IOException {
        List<Long> found = new ArrayList<>();
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            String t = readType(raf, pos + 4);
            int header = 8;
            if (size == 1) {
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < header || pos + size > end) break; // 越界/损坏：停止而不是崩溃
            if (size == 1) raf.seek(pos + 4);
            if (type.equals(t)) found.add(pos + header);
            pos += size;
        }
        return found;
    }

    private static long findBoxIn(RandomAccessFile raf, long start, long end, String type) throws IOException {
        List<Long> all = findBoxes(raf, start, end, type);
        return all.isEmpty() ? -1 : all.get(0);
    }

    /** 按路径逐级下钻（如 mdia→minf→stbl），返回最后一层盒子的「内容起点」 */
    private static long findPath(RandomAccessFile raf, long start, long end, String... types) throws IOException {
        long s = start, e = end;
        for (String type : types) {
            long box = findBoxIn(raf, s, e, type);
            if (box < 0) return -1;
            e = boxEnd(raf, box - 8, e);
            s = box;
        }
        return s;
    }

    /** 由「内容起点」反推该盒子的结束位置 */
    private static long boxEnd(RandomAccessFile raf, long boxStart, long limit) throws IOException {
        raf.seek(boxStart);
        long size = raf.readInt() & 0xFFFFFFFFL;
        int header = 8;
        if (size == 1) {
            size = raf.readLong();
            header = 16;
        } else if (size == 0) {
            return limit;
        }
        long end = boxStart + size;
        return Math.min(end, limit);
    }

    private static String readType(RandomAccessFile raf, long pos) throws IOException {
        byte[] b = new byte[4];
        raf.seek(pos);
        raf.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
