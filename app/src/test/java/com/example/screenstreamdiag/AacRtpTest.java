package com.example.screenstreamdiag;

import org.junit.Test;
import static org.junit.Assert.*;

public class AacRtpTest {
    @Test public void generatesRfc3640OneAuPayload() {
        byte[] result=AacRtp.payload(new byte[]{1,2,3,4});
        assertArrayEquals(new byte[]{0,16,0,32,1,2,3,4},result);
        assertEquals("1208",AacRtp.CONFIG_HEX);
    }
    @Test(expected=IllegalArgumentException.class)public void rejectsHugeAu() {
        AacRtp.payload(new byte[1024]);
    }
    @Test(expected=IllegalArgumentException.class)public void rejectsEmptyAu() {
        AacRtp.payload(new byte[0]);
    }
}
