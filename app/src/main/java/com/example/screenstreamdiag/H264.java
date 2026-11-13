package com.example.screenstreamdiag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure Java H.264 utilities. Supports Annex B and MP4-style length-prefixed NALs. */
public final class H264 {
    private H264() { }
    private static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 > data.length || data[offset] != 0 || data[offset + 1] != 0) return 0;
        if (data[offset + 2] == 1) return 3;
        return offset + 4 <= data.length && data[offset + 2] == 0 && data[offset + 3] == 1 ? 4 : 0;
    }
    private static int findStartCode(byte[] data, int from) {
        for (int i = from; i <= data.length - 3; i++) if (startCodeLength(data, i) > 0) return i;
        return -1;
    }
    public static List<byte[]> nalUnits(byte[] encoded) {
        ArrayList<byte[]> result = new ArrayList<>();
        if (encoded == null || encoded.length == 0) return result;
        int start = findStartCode(encoded, 0);
        if (start >= 0) {
            while (start >= 0) {
                int off = start + startCodeLength(encoded, start);
                int next = findStartCode(encoded, off);
                int end = next < 0 ? encoded.length : next;
                while (end > off && encoded[end - 1] == 0) end--;
                if (end > off) result.add(Arrays.copyOfRange(encoded, off, end));
                start = next;
            }
            return result;
        }
        // Some vendors return AVC samples with 4-byte NAL length headers.
        int off = 0;
        while (off + 4 <= encoded.length) {
            long len = ((encoded[off] & 255L) << 24) | ((encoded[off + 1] & 255L) << 16)
                    | ((encoded[off + 2] & 255L) << 8) | (encoded[off + 3] & 255L);
            if (len < 1 || len > encoded.length - off - 4) break;
            result.add(Arrays.copyOfRange(encoded, off + 4, (int)(off + 4 + len)));
            off += 4 + (int)len;
        }
        if (off == encoded.length && !result.isEmpty()) return result;
        result.clear();
        result.add(Arrays.copyOf(encoded, encoded.length));
        return result;
    }
    public static int type(byte[] nal) { return nal.length == 0 ? 0 : nal[0] & 0x1f; }
    public static String profileLevelId(byte[] sps) {
        if (sps == null || sps.length < 4) return "42e01f";
        return String.format(java.util.Locale.US, "%02x%02x%02x", sps[1] & 255, sps[2] & 255, sps[3] & 255);
    }
}
