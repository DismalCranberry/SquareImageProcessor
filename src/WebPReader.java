import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java WebP VP8L (lossless, including transparency) reader.
 * Supports simple VP8L and extended VP8X files containing a VP8L still image.
 * Handles lossy VP8 (including ALPH+VP8) using an optional FFmpeg executable.
 * Does not decode VP8 lossy entirely in Java, or support animation.
 * Based on RFC 9649, section 3.
 */
public final class WebPReader {
    private WebPReader() {
    }

    private static final int MAX_PIXELS = 40_000_000;
    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    // The exact 120 short-distance positions from WebP (RFC 9649).
    private static final int[] CODE_TO_PLANE = {0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a, 0x26, 0x2a, 0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a, 0x25, 0x2b, 0x48, 0x04, 0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b, 0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45, 0x4b, 0x34, 0x3c, 0x03, 0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d, 0x44, 0x4c, 0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e, 0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b, 0x32, 0x3e, 0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f, 0x64, 0x6c, 0x42, 0x4e, 0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b, 0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e, 0x00, 0x74, 0x7c, 0x41, 0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d, 0x51, 0x5f, 0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70};

    public static BufferedImage read(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length < 20 || !fourCC(data, 0).equals("RIFF") || !fourCC(data, 8).equals("WEBP")) {
            throw new IOException("Not a RIFF WebP file: " + file.getName());
        }
        long declaredEnd = unsigned32(data, 4) + 8;
        if (declaredEnd > data.length || declaredEnd < 20) {
            throw new IOException("Truncated WebP RIFF container");
        }
        int end = (int) declaredEnd;
        boolean animated = false;
        boolean alphaChunk = false;
        int vp8lStart = -1, vp8lLength = 0;
        boolean lossy = false;
        for (int pos = 12; pos < end; ) {
            if (end - pos < 8) throw new IOException("Truncated WebP chunk header");
            String type = fourCC(data, pos);
            long length = unsigned32(data, pos + 4);
            long next = (long) pos + 8 + length + (length & 1);
            if (next > end) throw new IOException("Invalid WebP chunk length");
            switch (type) {
                case "VP8X" -> {
                    if (length < 10) throw new IOException("Invalid VP8X chunk");
                    int flags = data[pos + 8] & 0xff;
                    animated |= (flags & 0x02) != 0;
                }
                case "ANIM", "ANMF" -> animated = true;
                case "ALPH" -> alphaChunk = true;
                case "VP8L" -> {
                    vp8lStart = pos + 8;
                    vp8lLength = (int) length;
                }
                case "VP8 " -> {
                    int start = pos + 8; // Start of VP8 chunk payload

                    if (length < 10 || (data[start] & 1) != 0 || (data[start + 3] & 0xff) != 0x9d || (data[start + 4] & 0xff) != 0x01 || (data[start + 5] & 0xff) != 0x2a) {
                        throw new IOException("Invalid VP8 key-frame header");
                    }

                    int width = ((data[start + 6] & 0xff) | ((data[start + 7] & 0xff) << 8)) & 0x3fff;
                    int height = ((data[start + 8] & 0xff) | ((data[start + 9] & 0xff) << 8)) & 0x3fff;

                    checkedCount(width, height); // Existing helper validates dimensions
                    lossy = true;
                }
            }
            pos = (int) next;
        }
        if (animated) throw new IOException("Animated WebP is not supported by this decoder");
        if (vp8lStart < 0) {
            if (lossy) return decodeLossyWithFFmpeg(file);
            throw new IOException("No VP8L image chunk found");
        }
        if (alphaChunk) throw new IOException("Unexpected ALPH chunk with VP8L data");
        if (vp8lLength < 5 || (data[vp8lStart] & 0xff) != 0x2f) {
            throw new IOException("Bad VP8L signature");
        }
        Bits bits = new Bits(data, vp8lStart + 1, vp8lStart + vp8lLength);
        int width = bits.read(14) + 1;
        int height = bits.read(14) + 1;
        bits.read(1); // alpha hint, not a guarantee
        if (bits.read(3) != 0) throw new IOException("Unsupported VP8L version");
        int[] argb = decodeMain(bits, width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    /**
     * Fallback for WebP VP8 lossy images, including VP8X/ALPH files.
     * Requires FFmpeg on PATH (or the full executable path in FFMPEG_COMMAND).
     * This is intentionally NOT a pure-Java VP8 decoder.
     */
    private static final String FFMPEG_COMMAND = "ffmpeg";

    private static BufferedImage decodeLossyWithFFmpeg(File file) throws IOException {
        Path png = Files.createTempFile("webp-decoded-", ".png");
        Path log = Files.createTempFile("webp-ffmpeg-", ".log");
        try {
            Process process = getProcess(file, png, log);
            boolean completed;
            try {
                completed = process.waitFor(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while decoding VP8 WebP", e);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("FFmpeg timed out decoding: " + file.getName());
            }
            if (process.exitValue() != 0) {
                String errors = Files.readString(log).trim();
                throw new IOException("FFmpeg could not decode " + file.getName() + (errors.isEmpty() ? "" : ": " + errors));
            }
            BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
            if (image == null) {
                throw new IOException("FFmpeg produced unreadable PNG for " + file.getName());
            }
            if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
                throw new IOException("Image exceeds safety limit of " + MAX_PIXELS + " pixels");
            }
            return image;
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(log);
        }
    }

    private static Process getProcess(File file, Path png, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(FFMPEG_COMMAND, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", file.getAbsolutePath(), "-frames:v", "1", "-f", "image2", png.toAbsolutePath().toString());
        // Avoid blocking due to full process pipes, and capture errors.
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Cannot decode lossy VP8 WebP without FFmpeg. " + "Install FFmpeg and add it to PATH (or configure FFMPEG_COMMAND).", e);
        }
        return process;
    }

    private static int[] decodeMain(Bits b, int width, int height) throws IOException {
        int codedWidth = width;
        List<Transform> transforms = new ArrayList<>();
        boolean[] used = new boolean[4];
        while (b.read(1) != 0) {
            int type = b.read(2);
            if (used[type]) throw new IOException("Duplicate VP8L transform: " + type);
            used[type] = true;
            Transform t = new Transform(type);
            if (type == 0 || type == 1) {
                t.bits = b.read(3) + 2;
                t.dataWidth = ceilDiv(codedWidth, 1 << t.bits);
                t.data = decodeImage(b, t.dataWidth, ceilDiv(height, 1 << t.bits), false);
            } else if (type == 3) {
                int colors = b.read(8) + 1;
                t.data = decodeImage(b, colors, 1, false);
                for (int i = 1; i < colors; i++) t.data[i] = addPixels(t.data[i], t.data[i - 1]);
                t.bits = colors <= 2 ? 3 : colors <= 4 ? 2 : colors <= 16 ? 1 : 0;
                t.dataWidth = colors;
                codedWidth = ceilDiv(codedWidth, 1 << t.bits);
            }
            transforms.add(t);
        }
        int[] pixels = decodeImage(b, codedWidth, height, true);
        for (int i = transforms.size() - 1; i >= 0; i--) {
            Transform t = transforms.get(i);
            if (t.type == 0) inversePredictor(pixels, codedWidth, height, t);
            else if (t.type == 1) inverseColor(pixels, codedWidth, height, t);
            else if (t.type == 2) inverseSubtractGreen(pixels);
            else if (t.type == 3) {
                pixels = inversePalette(pixels, codedWidth, width, height, t);
                codedWidth = width;
            }
        }
        if (codedWidth != width) throw new IOException("Inconsistent decoded width");
        return pixels;
    }

    private static int[] decodeImage(Bits b, int width, int height, boolean allowMeta) throws IOException {
        int count = checkedCount(width, height);
        int cacheBits = 0;
        if (b.read(1) != 0) {
            cacheBits = b.read(4);
            if (cacheBits < 1 || cacheBits > 11) throw new IOException("Invalid VP8L cache size");
        }
        int cacheSize = 1 << cacheBits;
        int[] cache = cacheBits == 0 ? null : new int[cacheSize];
        int metaBits = 0, metaWidth = 0;
        int[] meta = null;
        int groupsCount = 1;
        if (allowMeta && b.read(1) != 0) {
            metaBits = b.read(3) + 2;
            metaWidth = ceilDiv(width, 1 << metaBits);
            int metaHeight = ceilDiv(height, 1 << metaBits);
            meta = decodeImage(b, metaWidth, metaHeight, false);
            int max = 0;
            for (int v : meta) max = Math.max(max, (v >>> 8) & 0xffff);
            groupsCount = max + 1;
            if (groupsCount > 8192) throw new IOException("Too many Huffman groups");
        }
        Huffman[][] groups = new Huffman[groupsCount][5];
        for (int g = 0; g < groupsCount; g++) {
            groups[g][0] = readHuffman(b, 256 + 24 + (cache == null ? 0 : cacheSize));
            groups[g][1] = readHuffman(b, 256);
            groups[g][2] = readHuffman(b, 256);
            groups[g][3] = readHuffman(b, 256);
            groups[g][4] = readHuffman(b, 40);
        }
        int[] out = new int[count];
        for (int p = 0; p < count; ) {
            int groupIndex = 0;
            if (meta != null) {
                int x = p % width, y = p / width;
                groupIndex = (meta[(y >> metaBits) * metaWidth + (x >> metaBits)] >>> 8) & 0xffff;
            }
            Huffman[] h = groups[groupIndex];
            int green = h[0].read(b);
            if (green < 256) {
                int red = h[1].read(b), blue = h[2].read(b), alpha = h[3].read(b);
                int pixel = (alpha << 24) | (red << 16) | (green << 8) | blue;
                out[p++] = pixel;
                if (cache != null) cache[cacheIndex(pixel, cacheBits)] = pixel;
            } else if (green < 280) {
                int length = decodePrefix(b, green - 256);
                int distanceCode = decodePrefix(b, h[4].read(b));
                int distance;
                if (distanceCode <= 120) {
                    int packed = CODE_TO_PLANE[distanceCode - 1];
                    distance = Math.max(1, (packed >>> 4) * width + 8 - (packed & 15));
                } else {
                    distance = distanceCode - 120;
                }
                if (distance > p || p + (long) length > count || length > 4096)
                    throw new IOException("Invalid VP8L backward reference at pixel " + p);
                for (int k = 0; k < length; k++) {
                    int pixel = out[p - distance];
                    out[p++] = pixel;
                    if (cache != null) cache[cacheIndex(pixel, cacheBits)] = pixel;
                }
            } else {
                if (cache == null || green - 280 >= cacheSize) {
                    throw new IOException("Invalid VP8L color cache reference");
                }
                int pixel = cache[green - 280];
                out[p++] = pixel;
                cache[cacheIndex(pixel, cacheBits)] = pixel;
            }
        }
        return out;
    }

    private static int decodePrefix(Bits b, int code) throws IOException {
        if (code < 4) return code + 1;
        if (code > 39) throw new IOException("Invalid VP8L length/distance prefix");
        int extra = (code - 2) >> 1;
        return ((2 + (code & 1)) << extra) + b.read(extra) + 1;
    }

    private static int cacheIndex(int pixel, int bits) {
        return (int) ((0x1e35a7bdL * pixel & 0xffffffffL) >>> (32 - bits));
    }

    private static Huffman readHuffman(Bits b, int alphabet) throws IOException {
        int[] lengths = new int[alphabet];
        if (b.read(1) != 0) {
            int n = b.read(1) + 1;
            int first = b.read(b.read(1) == 0 ? 1 : 8);
            if (first >= alphabet) throw new IOException("Invalid simple Huffman symbol");
            lengths[first] = 1;
            if (n == 2) {
                int second = b.read(8);
                if (second >= alphabet || second == first) throw new IOException("Invalid second Huffman symbol");
                lengths[second] = 1;
            }
        } else {
            int[] codeLengths = new int[19];
            int n = 4 + b.read(4);
            for (int i = 0; i < n; i++) codeLengths[CODE_LENGTH_ORDER[i]] = b.read(3);
            Huffman prefix = Huffman.fromLengths(codeLengths);
            int maxSymbols = alphabet;
            if (b.read(1) != 0) {
                int bits = 2 + 2 * b.read(3);
                maxSymbols = 2 + b.read(bits);
                if (maxSymbols > alphabet) throw new IOException("Too many VP8L symbols");
            }
            int pos = 0, prev = 8;
            int commandsRemaining = maxSymbols;
            while (pos < alphabet && commandsRemaining-- > 0) {
                int val = prefix.read(b);
                if (val <= 15) {
                    lengths[pos++] = val;
                    if (val != 0) prev = val;
                } else {
                    int repeat;
                    int value;
                    if (val == 16) {
                        repeat = 3 + b.read(2);
                        value = prev;
                    } else if (val == 17) {
                        repeat = 3 + b.read(3);
                        value = 0;
                    } else if (val == 18) {
                        repeat = 11 + b.read(7);
                        value = 0;
                    } else throw new IOException("Invalid code-length symbol");
                    if (pos + repeat > alphabet) throw new IOException("Huffman length overflow");
                    Arrays.fill(lengths, pos, pos + repeat, value);
                    pos += repeat;
                }
            }
        }
        return Huffman.fromLengths(lengths);
    }

    private static final class Huffman {
        final List<int[]> nodes = new ArrayList<>();
        int single = -1;

        Huffman() {
            nodes.add(new int[]{-1, -1, -1});
        }

        static Huffman fromLengths(int[] lengths) throws IOException {
            Huffman h = new Huffman();
            int[] counts = new int[16];
            int symbols = 0, only = -1;
            for (int i = 0; i < lengths.length; i++) {
                int len = lengths[i];
                if (len < 0 || len > 15) throw new IOException("Invalid Huffman bit length");
                if (len != 0) {
                    counts[len]++;
                    only = i;
                    symbols++;
                }
            }
            if (symbols == 0) throw new IOException("Empty Huffman code");
            if (symbols == 1) {
                h.single = only;
                return h;
            }
            int[] next = new int[16];
            int code = 0;
            for (int len = 1; len <= 15; len++) {
                code = (code + counts[len - 1]) << 1;
                next[len] = code;
                if (code + counts[len] > (1 << len)) throw new IOException("Oversubscribed Huffman tree");
            }
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                int len = lengths[symbol];
                if (len == 0) continue;
                int c = next[len]++;
                int node = 0;
                for (int j = len - 1; j >= 0; j--) {
                    int bit = (c >> j) & 1;
                    int[] current = h.nodes.get(node);
                    int child = current[bit];
                    if (child == -1) {
                        child = h.nodes.size();
                        current[bit] = child;
                        h.nodes.add(new int[]{-1, -1, -1});
                    }
                    node = child;
                }
                h.nodes.get(node)[2] = symbol;
            }
            return h;
        }

        int read(Bits bits) throws IOException {
            if (single >= 0) return single;
            int node = 0;
            for (int i = 0; i < 15; i++) {
                node = nodes.get(node)[bits.read(1)];
                if (node < 0) throw new IOException("Invalid Huffman code in WebP stream");
                int value = nodes.get(node)[2];
                if (value >= 0) return value;
            }
            throw new IOException("Huffman code too long");
        }
    }

    private static final class Bits {
        final byte[] data;
        final int end;
        long position;

        Bits(byte[] data, int start, int end) {
            this.data = data;
            this.end = end;
            this.position = (long) start * 8;
        }

        int read(int count) throws IOException {
            if (count < 0 || count > 24 || position + count > (long) end * 8)
                throw new IOException("Truncated or invalid VP8L bitstream");
            int value = 0;
            for (int i = 0; i < count; i++) {
                int index = (int) (position >>> 3);
                value |= ((data[index] >>> (position & 7)) & 1) << i;
                position++;
            }
            return value;
        }
    }

    private static final class Transform {
        int type, bits, dataWidth;
        int[] data;

        Transform(int type) {
            this.type = type;
        }
    }

    private static int[] inversePalette(int[] in, int codedWidth, int width, int height, Transform t) {
        int[] out = new int[width * height];
        int groupBits = t.bits;
        int perPixelBits = 8 >> groupBits;
        int mask = (1 << perPixelBits) - 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = in[y * codedWidth + (x >> groupBits)];
                int index = ((pixel >>> 8) >>> ((x & ((1 << groupBits) - 1)) * perPixelBits)) & mask;
                out[y * width + x] = index < t.data.length ? t.data[index] : 0;
            }
        }
        return out;
    }

    private static void inverseSubtractGreen(int[] data) {
        for (int i = 0; i < data.length; i++) {
            int p = data[i], g = (p >>> 8) & 255;
            int red = ((p >>> 16) + g) & 255;
            int blue = ((p & 255) + g) & 255;
            data[i] = (p & 0xff00ff00) | (red << 16) | blue;
        }
    }

    private static void inverseColor(int[] data, int width, int height, Transform t) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pos = y * width + x;
                int p = data[pos];
                int c = t.data[(y >> t.bits) * t.dataWidth + (x >> t.bits)];
                int g = (p >>> 8) & 255;
                int r = ((p >>> 16) & 255) + (((byte) (c & 255)) * (byte) g >> 5);
                int blue = (p & 255) + (((byte) ((c >>> 8) & 255)) * (byte) g >> 5);
                blue += (((byte) ((c >>> 16) & 255)) * (byte) r >> 5);
                data[pos] = (p & 0xff00ff00) | ((r & 255) << 16) | (blue & 255);
            }
        }
    }

    private static void inversePredictor(int[] data, int width, int height, Transform t) throws IOException {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pos = y * width + x;
                int predicted;
                if (y == 0 && x == 0) predicted = 0xff000000;
                else if (y == 0) predicted = data[pos - 1];
                else if (x == 0) predicted = data[pos - width];
                else {
                    int mode = (t.data[(y >> t.bits) * t.dataWidth + (x >> t.bits)] >>> 8) & 255;
                    int left = data[pos - 1], top = data[pos - width];
                    int topLeft = data[pos - width - 1];
                    int topRight = (x == width - 1) ? data[y * width] : data[pos - width + 1];
                    predicted = predict(mode, left, top, topLeft, topRight);
                }
                data[pos] = addPixels(data[pos], predicted);
            }
        }
    }

    private static int predict(int mode, int l, int t, int tl, int tr) throws IOException {
        switch (mode) {
            case 0:
                return 0xff000000;
            case 1:
                return l;
            case 2:
                return t;
            case 3:
                return tr;
            case 4:
                return tl;
            case 5:
                return avg(avg(l, tr), t);
            case 6:
                return avg(l, tl);
            case 7:
                return avg(l, t);
            case 8:
                return avg(tl, t);
            case 9:
                return avg(t, tr);
            case 10:
                return avg(avg(l, tl), avg(t, tr));
            case 11: {
                int leftDist = 0, topDist = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    int a = (l >>> shift) & 255, b = (t >>> shift) & 255, c = (tl >>> shift) & 255;
                    int estimate = a + b - c;
                    leftDist += Math.abs(estimate - a);
                    topDist += Math.abs(estimate - b);
                }
                return leftDist < topDist ? l : t;
            }
            case 12: {
                int result = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    int a = (l >>> shift) & 255, b = (t >>> shift) & 255, c = (tl >>> shift) & 255;
                    result |= clamp(a + b - c) << shift;
                }
                return result;
            }
            case 13: {
                int a = avg(l, t), result = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    int av = (a >>> shift) & 255, tv = (tl >>> shift) & 255;
                    result |= clamp(av + (av - tv) / 2) << shift;
                }
                return result;
            }
            default: throw new IOException("Invalid VP8L predictor mode: " + mode);
        }
    }

    private static int avg(int a, int b) {
        return (((a & 0xff) + (b & 0xff)) / 2) | ((((a >>> 8 & 255) + (b >>> 8 & 255)) / 2) << 8) | ((((a >>> 16 & 255) + (b >>> 16 & 255)) / 2) << 16) | ((((a >>> 24 & 255) + (b >>> 24 & 255)) / 2) << 24);
    }

    private static int addPixels(int a, int b) {
        return (((a & 0x00ff00ff) + (b & 0x00ff00ff)) & 0x00ff00ff) | (((((a >>> 8) & 0x00ff00ff) + ((b >>> 8) & 0x00ff00ff)) & 0x00ff00ff) << 8);
    }

    private static int clamp(int x) {
        return x < 0 ? 0 : Math.min(255, x);
    }

    private static int checkedCount(int w, int h) throws IOException {
        long total = (long) w * h;
        if (w < 1 || h < 1 || total > MAX_PIXELS) throw new IOException("Invalid WebP dimensions");
        return (int) total;
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private static String fourCC(byte[] d, int p) {
        return new String(d, p, 4, StandardCharsets.US_ASCII);
    }

    private static long unsigned32(byte[] d, int p) {
        return (d[p] & 255L) | ((d[p + 1] & 255L) << 8) | ((d[p + 2] & 255L) << 16) | ((d[p + 3] & 255L) << 24);
    }
}
