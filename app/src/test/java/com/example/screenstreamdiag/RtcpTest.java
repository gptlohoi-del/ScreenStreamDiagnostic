package com.example.screenstreamdiag;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class RtcpTest {
    private static byte[] rr() {
        byte[] p=new byte[32];p[0]=(byte)0x81;p[1]=(byte)201;p[3]=7;
        p[7]=4; // reporter SSRC 4
        p[11]=42; // media SSRC 42
        p[12]=64; // fraction=25%
        p[15]=3; // cumulative lost 3
        p[19]=9; // highest seq 9
        p[23]=90; // jitter 90 ticks
        return p;
    }
    @Test public void decodesMatchingReport() {
        List<Rtcp.ReceiverReport> parsed=Rtcp.receiverReports(rr(),32,42);
        assertEquals(1,parsed.size());
        assertEquals(64,parsed.get(0).fractionLost);
        assertEquals(3,parsed.get(0).cumulativeLost);
        assertEquals(90,parsed.get(0).jitter);
        assertTrue(parsed.get(0).compact(90000).contains("25.0%"));
    }
    @Test public void ignoresOtherSourcesAndCorruptHeaders() {
        assertTrue(Rtcp.receiverReports(rr(),32,43).isEmpty());
        byte[] p=rr();p[3]=100;
        assertTrue(Rtcp.receiverReports(p,32,42).isEmpty());
        assertTrue(Rtcp.receiverReports(new byte[1],1,42).isEmpty());
    }
    @Test public void supportsCompoundPackets() {
        byte[] data=new byte[36];data[0]=(byte)0x80;data[1]=(byte)202;data[3]=0;
        System.arraycopy(rr(),0,data,4,32);
        assertEquals(1,Rtcp.receiverReports(data,36,42).size());
    }
}
