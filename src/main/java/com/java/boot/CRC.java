//package com.java.boot;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

public class CRC {

    /*
      CRC (cyclic redundancy check) for files.
      Command style (similar to Huffman archiver task):
        java Main -k -2 <path_to_file>   // encode: append CRC to the end
        java Main -d -2 <path_to_file>   // decode: verify CRC and restore original bytes

      CRC size parameter:
        -1 => CRC-8  (1 byte)
        -2 => CRC-16 (2 bytes)
        -4 => CRC-32 (4 bytes)

      If you run without args -> test mode:
        it tries LOTR.txt in project root, encodes + decodes + compares bytes, prints timing.
    */

    static void usage() {
        System.out.println("CRC (cyclic redundancy check) for files");
        System.out.println("Commands:");
        System.out.println("  java Main -k -2 <path_to_file>     (encode: appends CRC to the end)");
        System.out.println("  java Main -d -2 <path_to_file>     (decode: verifies CRC and restores the file)");
        System.out.println("Parameter (-1/-2/-4) = how many CRC bytes to append (1/2/4).");
        System.out.println("Examples:");
        System.out.println("  java Main -k -2 LOTR.txt");
        System.out.println("  java Main -d -2 LOTR.txt.crc2");
    }


    public static void main(String[] args) {
        if (args.length == 0) {
            runFileTest("LOTR.txt", 2); // default test: CRC-16
            return;
        }

        if (args.length != 3) {
            usage();
            return;
        }

        String mode = args[0];
        int crcBytes = parseCrcBytes(args[1]);
        String pathStr = args[2];

        try {
            if ("-k".equals(mode)) {
                encodeFile(pathStr, crcBytes);
            } else if ("-d".equals(mode)) {
                decodeFile(pathStr, crcBytes);
            } else {
                usage();
            }
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    static int parseCrcBytes(String s) {
        if (!s.startsWith("-")) throw new IllegalArgumentException("Expected -1 or -2 or -4, got: " + s);
        int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad CRC parameter: " + s);
        }
        v = Math.abs(v);
        if (v != 1 && v != 2 && v != 4) throw new IllegalArgumentException("Allowed only -1, -2, -4");
        return v;
    }

    // ---------------- File encode/decode ----------------

    static void encodeFile(String inputPath, int crcBytes) throws IOException {
        Path in = Paths.get(inputPath);
        if (!Files.exists(in)) throw new IllegalArgumentException("File not found: " + inputPath);

        byte[] data = Files.readAllBytes(in);

        long t0 = System.nanoTime();
        long crc = computeCrcRemainder(data, crcBytes);
        byte[] crcTail = toBytesBigEndian(crc, crcBytes);
        long t1 = System.nanoTime();

        byte[] out = new byte[data.length + crcBytes];
        System.arraycopy(data, 0, out, 0, data.length);
        System.arraycopy(crcTail, 0, out, data.length, crcBytes);

        Path outPath = Paths.get(inputPath + ".crc" + crcBytes); // e.g. LOTR.txt.crc2
        Files.write(outPath, out);

        System.out.println("=== ENCODE ===");
        System.out.println("Input : " + in.toAbsolutePath());
        System.out.println("Output: " + outPath.toAbsolutePath());
        System.out.println("CRC(" + (crcBytes * 8) + "): 0x" + toHex(crc, crcBytes));
        System.out.println("Input size : " + data.length + " bytes");
        System.out.println("Output size: " + out.length + " bytes");
        System.out.println("Overhead   : +" + crcBytes + " bytes");
        System.out.printf("Time (encode+CRC): %.3f ms%n", (t1 - t0) / 1_000_000.0);
    }

    static void decodeFile(String inputPath, int crcBytes) throws IOException {
        Path in = Paths.get(inputPath);
        if (!Files.exists(in)) throw new IllegalArgumentException("File not found: " + inputPath);

        byte[] all = Files.readAllBytes(in);
        if (all.length < crcBytes) throw new IllegalArgumentException("File is too short for CRC");

        int dataLen = all.length - crcBytes;
        byte[] data = Arrays.copyOfRange(all, 0, dataLen);
        byte[] tail = Arrays.copyOfRange(all, dataLen, all.length);
        long storedCrc = fromBytesBigEndian(tail);

        long t0 = System.nanoTime();
        long calcCrc = computeCrcRemainder(data, crcBytes);
        long t1 = System.nanoTime();

        boolean ok = (storedCrc == calcCrc);

        System.out.println("=== DECODE / CHECK ===");
        System.out.println("Input : " + in.toAbsolutePath());
        System.out.println("Stored CRC: 0x" + toHex(storedCrc, crcBytes));
        System.out.println("Calc   CRC: 0x" + toHex(calcCrc, crcBytes));
        System.out.println("CRC OK? " + ok);
        System.out.println("Input size (with CRC): " + all.length + " bytes");
        System.out.println("Data size (restored) : " + data.length + " bytes");
        System.out.printf("Time (check): %.3f ms%n", (t1 - t0) / 1_000_000.0);

        if (!ok) {
            System.out.println("ERROR: Data is corrupted. Restore is not reliable, file will NOT be written.");
            return;
        }

        System.out.println("The file has not been corrupted");

        Path outPath = makeDecodedName(in);
        Files.write(outPath, data);
        System.out.println("Restored file: " + outPath.toAbsolutePath());
    }

    static Path makeDecodedName(Path input) {
        String name = input.getFileName().toString();
        // If ends with .crc1/.crc2/.crc4 -> strip it and add .decoded
        if (name.matches(".*\\.crc[124]$")) {
            String base = name.substring(0, name.length() - 5); // remove ".crcX"
            return input.resolveSibling(base + ".decoded");
        }
        return input.resolveSibling(name + ".decoded");
    }

    // ---------------- Test mode (LOTR.txt) ----------------

    static void runFileTest(String fileName, int crcBytes) {
        System.out.println("=== TEST MODE ===");
        Path p = Paths.get(fileName);

        if (!Files.exists(p)) {
            System.out.println("Test skipped: file not found -> " + p.toAbsolutePath());
            System.out.println("Put LOTR.txt in the project root and run again.");
            return;
        }

        try {
            long total0 = System.nanoTime();

            encodeFile(fileName, crcBytes);
            String encoded = fileName + ".crc" + crcBytes;

            decodeFile(encoded, crcBytes);

            // Compare original vs decoded bytes
            byte[] orig = Files.readAllBytes(Paths.get(fileName));
            byte[] dec = Files.readAllBytes(Paths.get(fileName + ".decoded"));
            boolean same = Arrays.equals(orig, dec);

            System.out.println("=== COMPARE ===");
            System.out.println("Original size: " + orig.length + " bytes");
            System.out.println("Decoded  size: " + dec.length + " bytes");
            System.out.println("Compare bytes: " + (same ? "SAME (OK)" : "DIFFERENT (FAIL)"));
            if (same) {
                System.out.println("The file has not been corrupted");
            } else {
                System.out.println("The file has been corrupted!");
            }

            long total1 = System.nanoTime();
            System.out.printf("Total test time: %.3f ms%n", (total1 - total0) / 1_000_000.0);

        } catch (Exception e) {
            System.out.println("TEST ERROR: " + e.getMessage());
        }
    }

    // ---------------- CRC core (bitwise, MSB-first, non-reflected) ----------------

    /*
      This computes the remainder of (data * x^r) / g(x) in GF(2),
      where r = crcBytes * 8.

      Polynomials (standard, non-reflected form):
        CRC-8 :  x^8  + x^2 + x + 1        => 0x07
        CRC-16: x^16 + x^12 + x^5 + 1      => 0x1021 (CRC-16/CCITT)
        CRC-32: x^32 + ... + 1             => 0x04C11DB7
    */
    static long computeCrcRemainder(byte[] data, int crcBytes) {
        int r = crcBytes * 8;
        if (r <= 0 || r > 32) throw new IllegalArgumentException("This student version supports r up to 32 bits.");

        long polyLow = generatorLowPart(crcBytes); // polynomial without the top x^r bit
        long polyTop = 1L << r;                    // x^r
        long mask = polyTop - 1;                   // r bits mask

        long reg = 0;

        // Feed data bits (MSB first)
        for (byte bb : data) {
            int b = bb & 0xFF;
            for (int i = 7; i >= 0; i--) {
                long inBit = (b >> i) & 1L;
                long top = (reg >> (r - 1)) & 1L;
                reg = ((reg << 1) | inBit) & mask;
                if (top == 1L) reg ^= polyLow;
            }
        }

        // Append r zero bits
        for (int i = 0; i < r; i++) {
            long top = (reg >> (r - 1)) & 1L;
            reg = (reg << 1) & mask;
            if (top == 1L) reg ^= polyLow;
        }

        return reg & mask;
    }

    static long generatorLowPart(int crcBytes) {
        if (crcBytes == 1) return 0x07L;
        if (crcBytes == 2) return 0x1021L;
        if (crcBytes == 4) return 0x04C11DB7L;
        throw new IllegalArgumentException("Unsupported crcBytes: " + crcBytes);
    }

    // ---------------- bytes <-> long (big-endian) ----------------

    static byte[] toBytesBigEndian(long value, int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int shift = 8 * (n - 1 - i);
            out[i] = (byte) ((value >> shift) & 0xFF);
        }
        return out;
    }

    static long fromBytesBigEndian(byte[] bytes) {
        long v = 0;
        for (byte b : bytes) {
            v = (v << 8) | (b & 0xFFL);
        }
        return v;
    }

    static String toHex(long value, int crcBytes) {
        int width = crcBytes * 2;
        String s = Long.toHexString(value).toUpperCase();
        if (s.length() < width) s = "0".repeat(width - s.length()) + s;
        return s;
    }
}

