package com.example.screenstreamdiag;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class H264Test {
    @Test public void annexBWithTwoNalUnits() {
        byte[] raw={0,0,0,1,0x67,0x42,(byte)0xe0,0x1f,0,0,1,0x68,0x01};
        List<byte[]> nals=H264.nalUnits(raw);
        assertEquals(2,nals.size());
        assertEquals(7,H264.type(nals.get(0)));
        assertEquals(8,H264.type(nals.get(1)));
        assertEquals("42e01f",H264.profileLevelId(nals.get(0)));
    }
    @Test public void avccWithTwoNalUnits() {
        byte[] raw={0,0,0,2,0x65,0x23,0,0,0,2,0x41,0x55};
        List<byte[]> nals=H264.nalUnits(raw);
        assertEquals(2,nals.size());
        assertEquals(5,H264.type(nals.get(0)));
        assertEquals(1,H264.type(nals.get(1)));
    }
    @Test public void emptyAndRawNal() {
        assertTrue(H264.nalUnits(new byte[0]).isEmpty());
        assertEquals(5,H264.type(H264.nalUnits(new byte[]{0x65,0x01}).get(0)));
    }
}
