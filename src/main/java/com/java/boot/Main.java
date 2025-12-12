package com.java.boot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Simple Huffman archiver for UTF-8 text files.
 * One-class project.
 * <p>
 * File format:
 * - header
 * - bitstream (Huffman coded data)
 * <p>
 * CLI (in IDE it will be like: java Main ...):
 * Encode: -k -N pathToText
 * Decode: -d -N pathToArchive
 * <p>
 * Example:
 * -k -2 LOTR.txt
 * -d -2 LOTR.txt.huf
 */
public class Main {

    // "HUF1" in hex, just to recognize our own archive format
    private static final int MAGIC = 0x48554631;

    public static void main(String[] args) {
        try {
            // If user runs without args, we do a small test.
            if (args.length == 0 || (args.length == 1 && "--test".equalsIgnoreCase(args[0]))) {
                runSelfTest();
                return;
            }

            Cli cli = parseArgs(args);

            if (cli.mode == Mode.ENCODE) {
                Path input = cli.path;
                Path output = makeArchivePath(input);

                long t0 = System.nanoTime();
                encodeFile(input, output, cli.n);
                long t1 = System.nanoTime();

                long ms = (t1 - t0) / 1_000_000;

                long inSize = Files.size(input);
                long outSize = Files.size(output);

                System.out.println("OK: created archive: " + output.toAbsolutePath());
                System.out.println("Compression time: " + ms + " ms");
                System.out.println("Size: " + inSize + " bytes -> " + outSize + " bytes");
                System.out.println("Ratio: " + String.format(Locale.ROOT, "%.4f", (outSize * 1.0 / Math.max(1L, inSize))));
            } else {
                long t0 = System.nanoTime();
                Path extracted = decodeFile(cli.path, cli.n);
                long t1 = System.nanoTime();

                long ms = (t1 - t0) / 1_000_000;

                System.out.println("OK: extracted file: " + extracted.toAbsolutePath());
                System.out.println("Decompression time: " + ms + " ms");
            }

        } catch (Exception ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.err.println();
            printUsage();
            ex.printStackTrace(System.err);
        }
    }

    // ===================== CLI =====================

    private enum Mode {ENCODE, DECODE}

    private static class Cli {
        final Mode mode;
        final int n;
        final Path path;

        Cli(Mode mode, int n, Path path) {
            this.mode = mode;
            this.n = n;
            this.path = path;
        }
    }

    private static Cli parseArgs(String[] args) {
        // Expected:
        //  encode: -k -2 path
        //  decode: -d -2 path
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected 3 args, got " + args.length);
        }

        String key = args[0].trim().toLowerCase(Locale.ROOT);

        Mode mode;
        if (key.equals("-k") || key.equals("-c") || key.equals("-e")) {
            mode = Mode.ENCODE;
        } else if (key.equals("-d")) {
            mode = Mode.DECODE;
        } else {
            throw new IllegalArgumentException("Unknown mode key: " + args[0] + " (use -k or -d)");
        }

        int n = parseN(args[1]);
        Path path = Paths.get(args[2]);

        if (n <= 0) throw new IllegalArgumentException("N must be > 0");
        if (!Files.exists(path)) throw new IllegalArgumentException("File not found: " + path);

        return new Cli(mode, n, path);
    }

    private static int parseN(String s) {
        // Accepting "-2" or "2".
        s = s.trim();
        if (s.startsWith("-")) s = s.substring(1);
        return Integer.parseInt(s);
    }

    private static void printUsage() {
        System.out.println("Usage (arguments only):");
        System.out.println("  Encode: -k -N <path_to_text_file>");
        System.out.println("  Decode: -d -N <path_to_archive_file>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  -k -2 LOTR.txt");
        System.out.println("  -d -2 LOTR.txt.huf");
        System.out.println();
        System.out.println("Note: -N is the number of Unicode code points per token.");
    }

    // ===================== Encode / Decode =====================

    private static Path makeArchivePath(Path input) {
        // Archive is created next to the input file, with .huf extension.
        return Paths.get(input.toString() + ".huf");
    }

    private static void encodeFile(Path input, Path output, int n) throws IOException {
        // Read whole file as UTF-8 string.
        String text = Files.readString(input, StandardCharsets.UTF_8);

        // We use Unicode code points so emojis and non-ASCII chars are handled correctly.
        int[] cps = text.codePoints().toArray();
        long originalCodePointCount = cps.length;

        // Tokenize into chunks of N code points.
        List<String> tokens = tokenize(cps, n);

        // Frequency map (token -> count).
        Map<String, Integer> freq = new HashMap<>();
        for (String t : tokens) {
            freq.merge(t, 1, Integer::sum);
        }

        // Build Huffman codes.
        Node root = buildHuffmanTree(freq);
        Map<String, String> codeMap = new HashMap<>();
        buildCodes(root, "", codeMap);

        // Store only the original filename (without directories).
        String originalFileNameOnly = input.getFileName().toString();

        // Write header + bitstream into one file.
        try (OutputStream fos = Files.newOutputStream(output);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(bos)) {

            // --- Header ---
            dos.writeInt(MAGIC);
            dos.writeInt(n);
            dos.writeLong(originalCodePointCount);
            dos.writeUTF(originalFileNameOnly);

            dos.writeInt(freq.size());
            for (Map.Entry<String, Integer> e : freq.entrySet()) {
                byte[] tokenBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(tokenBytes.length);
                dos.write(tokenBytes);
                dos.writeInt(e.getValue());
            }

            dos.flush(); // Important: finish DataOutputStream part before writing raw bits.

            // --- Bitstream ---
            try (BitOutputStream bout = new BitOutputStream(bos)) {
                for (String t : tokens) {
                    String bits = codeMap.get(t);
                    if (bits == null) throw new IllegalStateException("No code for token: " + t);

                    for (int i = 0; i < bits.length(); i++) {
                        bout.writeBit(bits.charAt(i) == '1' ? 1 : 0);
                    }
                }
                // Align to byte boundary to finish file nicely.
                bout.flushToByteBoundary();
            }
        }
    }

    /**
     * Decodes archive and writes extracted file next to the archive.
     * Returns the path of extracted file.
     */
    private static Path decodeFile(Path archive, int nFromCli) throws IOException {
        try (InputStream fis = Files.newInputStream(archive);
             BufferedInputStream bis = new BufferedInputStream(fis);
             DataInputStream dis = new DataInputStream(bis)) {

            // --- Read header ---
            int magic = dis.readInt();
            if (magic != MAGIC) throw new IllegalArgumentException("Not a .huf file (bad magic)");

            int nInArchive = dis.readInt();
            long originalCodePointCount = dis.readLong();
            String originalName = dis.readUTF();

            if (nInArchive != nFromCli) {
                throw new IllegalArgumentException(
                        "N mismatch: archive was created with N=" + nInArchive + ", but you passed N=" + nFromCli);
            }

            int tokenCount = dis.readInt();
            Map<String, Integer> freq = new HashMap<>(Math.max(16, tokenCount * 2));

            for (int i = 0; i < tokenCount; i++) {
                int len = dis.readInt();
                if (len < 0 || len > 50_000_000) throw new IllegalArgumentException("Bad token length: " + len);

                byte[] buf = dis.readNBytes(len);
                if (buf.length != len) throw new EOFException("Unexpected EOF in header");

                String token = new String(buf, StandardCharsets.UTF_8);

                int f = dis.readInt();
                if (f <= 0) throw new IllegalArgumentException("Bad frequency: " + f);

                freq.put(token, f);
            }

            // Rebuild Huffman tree exactly from the same frequency table.
            Node root = buildHuffmanTree(freq);

            // Output file is written next to the archive.
            Path outPath = makeOutputPath(archive.getParent(), originalName);

            // If original is empty, we just write empty output and exit.
            if (originalCodePointCount == 0) {
                Files.writeString(outPath, "", StandardCharsets.UTF_8);
                return outPath;
            }

            StringBuilder sb = new StringBuilder();
            long produced = 0;

            // --- Read bitstream and decode ---
            try (BitInputStream bin = new BitInputStream(bis)) {

                if (root.isLeaf()) {
                    // If file has only one token, then encoded stream is basically repeats of it.
                    String only = root.token;
                    long tokenCps = only.codePointCount(0, only.length());

                    // Safety: avoid infinite loop if token length is 0 (should not happen for non-empty original).
                    if (tokenCps == 0)
                        throw new IllegalStateException("Corrupted archive: zero-length token for non-empty text");

                    while (produced < originalCodePointCount) {
                        sb.append(only);
                        produced += tokenCps;
                    }
                } else {
                    while (produced < originalCodePointCount) {
                        Node cur = root;

                        // Walk tree by reading bits until we reach a leaf.
                        while (!cur.isLeaf()) {
                            int bit = bin.readBit();
                            if (bit == -1) throw new EOFException("Unexpected EOF in bitstream");
                            cur = (bit == 0) ? cur.left : cur.right;
                            if (cur == null) throw new IllegalStateException("Corrupted bitstream/tree");
                        }

                        sb.append(cur.token);
                        produced += cur.token.codePointCount(0, cur.token.length());
                    }
                }
            }

            // Cut to exact amount of code points.
            String result = trimToCodePoints(sb.toString(), originalCodePointCount);

            Files.writeString(outPath, result, StandardCharsets.UTF_8);
            return outPath;
        }
    }

    private static Path makeOutputPath(Path dir, String originalName) {
        if (dir == null) dir = Paths.get(".");

        Path candidate = dir.resolve(originalName);
        if (!Files.exists(candidate)) return candidate;

        // If original exists, write as decoded_<name>.
        Path decoded = dir.resolve("decoded_" + originalName);
        if (!Files.exists(decoded)) return decoded;

        // If even that exists, add counter.
        for (int i = 1; i <= 9999; i++) {
            Path p = dir.resolve("decoded_" + i + "_" + originalName);
            if (!Files.exists(p)) return p;
        }

        // Fallback.
        return dir.resolve("decoded_" + System.currentTimeMillis() + "_" + originalName);
    }

    // ===================== Tokenization  =====================

    private static List<String> tokenize(int[] cps, int n) {
        List<String> tokens = new ArrayList<>();

        for (int i = 0; i < cps.length; i += n) {
            int len = Math.min(n, cps.length - i);
            tokens.add(new String(cps, i, len));
        }

        // If file is empty, we create one "empty token" to build a tree.
        if (tokens.isEmpty()) tokens.add("");

        return tokens;
    }

    private static String trimToCodePoints(String s, long need) {
        if (need <= 0) return "";

        long have = s.codePoints().count();
        if (have <= need) return s;

        int[] cp = s.codePoints().toArray();
        int[] cut = Arrays.copyOf(cp, (int) need);
        return new String(cut, 0, cut.length);
    }

    // ===================== Huffman tree =====================

    private static class Node {
        final String token;     // non-null only for leaf
        final int freq;
        final Node left;
        final Node right;

        final String minToken;

        Node(String token, int freq, Node left, Node right) {
            this.token = token;
            this.freq = freq;
            this.left = left;
            this.right = right;

            if (isLeaf()) {
                this.minToken = token;
            } else {
                String a = (left != null) ? left.minToken : null;
                String b = (right != null) ? right.minToken : null;

                if (a == null) this.minToken = b;
                else if (b == null) this.minToken = a;
                else this.minToken = (a.compareTo(b) <= 0) ? a : b;
            }
        }

        boolean isLeaf() {
            return left == null && right == null;
        }
    }

    private static Node buildHuffmanTree(Map<String, Integer> freq) {
        if (freq.isEmpty()) {
            // Safety fallback
            Map<String, Integer> tmp = new HashMap<>();
            tmp.put("", 1);
            freq = tmp;
        }

        PriorityQueue<Node> pq = new PriorityQueue<>((a, b) -> {
            if (a.freq != b.freq) return Integer.compare(a.freq, b.freq);
            String am = (a.minToken == null) ? "" : a.minToken;
            String bm = (b.minToken == null) ? "" : b.minToken;
            return am.compareTo(bm);
        });

        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            pq.add(new Node(e.getKey(), e.getValue(), null, null));
        }

        // If only one symbol exists, tree is just one node.
        if (pq.size() == 1) return pq.poll();

        while (pq.size() > 1) {
            Node a = pq.poll();
            Node b = pq.poll();

            // We keep left/right order deterministic
            Node left = a;
            Node right = b;
            if (compareNodes(left, right) > 0) {
                left = b;
                right = a;
            }

            Node parent = new Node(null, left.freq + right.freq, left, right);
            pq.add(parent);
        }

        return pq.poll();
    }

    private static int compareNodes(Node a, Node b) {
        if (a.freq != b.freq) return Integer.compare(a.freq, b.freq);
        String am = (a.minToken == null) ? "" : a.minToken;
        String bm = (b.minToken == null) ? "" : b.minToken;
        return am.compareTo(bm);
    }

    private static void buildCodes(Node node, String prefix, Map<String, String> codeMap) {
        if (node == null) return;

        if (node.isLeaf()) {
            // If there is only one symbol in whole file, give it some code (we use "0").
            codeMap.put(node.token, prefix.isEmpty() ? "0" : prefix);
            return;
        }

        buildCodes(node.left, prefix + "0", codeMap);
        buildCodes(node.right, prefix + "1", codeMap);
    }

    // ===================== Bit streams =====================

    private static class BitOutputStream implements Closeable {
        private final OutputStream out;
        private int currentByte = 0;
        private int bitCount = 0; // number of bits already written into currentByte (0..7)

        BitOutputStream(OutputStream out) {
            this.out = out;
        }

        void writeBit(int bit) throws IOException {
            if (bit != 0 && bit != 1) throw new IllegalArgumentException("bit must be 0 or 1");

            currentByte = (currentByte << 1) | bit;
            bitCount++;

            if (bitCount == 8) {
                out.write(currentByte);
                bitCount = 0;
                currentByte = 0;
            }
        }

        void flushToByteBoundary() throws IOException {
            if (bitCount > 0) {
                currentByte <<= (8 - bitCount);
                out.write(currentByte);
                bitCount = 0;
                currentByte = 0;
            }
            out.flush();
        }

        @Override
        public void close() throws IOException {
            flushToByteBoundary();
        }
    }

    private static class BitInputStream implements Closeable {
        private final InputStream in;
        private int currentByte = 0;
        private int bitPos = 8; // 8 means "need to read a new byte"

        BitInputStream(InputStream in) {
            this.in = in;
        }

        int readBit() throws IOException {
            if (bitPos == 8) {
                currentByte = in.read();
                if (currentByte == -1) return -1;
                bitPos = 0;
            }

            int bit = (currentByte >> (7 - bitPos)) & 1;
            bitPos++;
            return bit;
        }

        @Override
        public void close() {
        }
    }

    // ===================== Test =====================

    private static void runSelfTest() throws IOException {
        System.out.println("Running self-test...");

        // If LOTR.txt exists in the working directory, we test on it
        Path lotr = Paths.get("LOTR.txt");
        if (Files.exists(lotr)) {
            int n = 2;
            System.out.println("Found LOTR.txt in working directory: " + lotr.toAbsolutePath());

            Path arch = makeArchivePath(lotr);

            long t0 = System.nanoTime();
            encodeFile(lotr, arch, n);
            long t1 = System.nanoTime();

            long msC = (t1 - t0) / 1_000_000;
            System.out.println("Compressed: " + arch.toAbsolutePath());
            System.out.println("Compression time: " + msC + " ms");
            System.out.println("Size: " + Files.size(lotr) + " -> " + Files.size(arch) + " bytes");

            long t2 = System.nanoTime();
            Path extracted = decodeFile(arch, n);
            long t3 = System.nanoTime();

            long msD = (t3 - t2) / 1_000_000;
            System.out.println("Decompressed to: " + extracted.toAbsolutePath());
            System.out.println("Decompression time: " + msD + " ms");

            // Simple correctness check: compare text content.
            String a = Files.readString(lotr, StandardCharsets.UTF_8);
            String b = Files.readString(extracted, StandardCharsets.UTF_8);

            if (a.equals(b)) System.out.println("SELF-TEST (LOTR) OK ✅");
            else System.out.println("SELF-TEST (LOTR) FAILED ❌");

            return;
        }

        // Otherwise I do a small temporary test.
        String sample = ""
                        + "Hello! This is a Huffman test\n"
                        + "Huffman coding should compress repeated patterns.\n"
                        + "aaaaaa bbbbbb cccccc\n";

        Path dir = Files.createTempDirectory("huf_test_");
        Path in = dir.resolve("input.txt");
        Files.writeString(in, sample, StandardCharsets.UTF_8);

        int n = 2;
        Path arch = makeArchivePath(in);

        long t0 = System.nanoTime();
        encodeFile(in, arch, n);
        long t1 = System.nanoTime();

        long msC = (t1 - t0) / 1_000_000;

        long t2 = System.nanoTime();
        Path extracted = decodeFile(arch, n);
        long t3 = System.nanoTime();

        long msD = (t3 - t2) / 1_000_000;

        String decoded = Files.readString(extracted, StandardCharsets.UTF_8);

        if (sample.equals(decoded)) {
            System.out.println("SELF-TEST OK ✅");
        } else {
            System.out.println("SELF-TEST FAILED ❌");
        }

        System.out.println("Compression time: " + msC + " ms");
        System.out.println("Decompression time: " + msD + " ms");
        System.out.println("Temp dir: " + dir.toAbsolutePath());
        System.out.println("Try manually:");
        System.out.println("  -k -2 " + in.toAbsolutePath());
        System.out.println("  -d -2 " + arch.toAbsolutePath());
    }
}
