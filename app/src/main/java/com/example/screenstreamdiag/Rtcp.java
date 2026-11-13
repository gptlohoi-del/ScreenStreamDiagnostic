package com.example.screenstreamdiag;

import java.util.ArrayList;
import java.util.List;

/** RFC 3550 RTCP receiver-report decoder. No Android dependencies. */
public final class Rtcp {
    private Rtcp() { }

    public static final class ReceiverReport {
        public final long reporterSsrc, mediaSsrc, extendedHighestSequence;
        public final int fractionLost; // 0..255; fractionLost / 256.0 is the fraction in one interval
        public final int cumulativeLost; // signed 24-bit value
        public final long jitter; // RTP timestamp ticks, not milliseconds
        public final long lastSenderReport, delaySinceLastSenderReport;
        ReceiverReport(long reporter, long media, int fraction, int lost, long highest,
                       long jitter, long lsr, long dlsr) {
            reporterSsrc=reporter;mediaSsrc=media;fractionLost=fraction;
            cumulativeLost=lost;extendedHighestSequence=highest;
            this.jitter=jitter;lastSenderReport=lsr;delaySinceLastSenderReport=dlsr;
        }
        public String compact(int clockRate) {
            return "lossFraction=" + fractionLost + "/256 (~" +
                    Math.round(fractionLost * 1000.0 / 256.0)/10.0 + "%)" +
                    " totalLost=" + cumulativeLost +
                    " jitterTicks=" + jitter +
                    " jitterMs=" + (clockRate > 0 ? Math.round(jitter * 1000.0 / clockRate) : -1) +
                    " highestSeq=" + extendedHighestSequence;
        }
    }

    private static long u32(byte[] b,int o) {
        return ((b[o]&255L)<<24) | ((b[o+1]&255L)<<16) |
                ((b[o+2]&255L)<<8) | (b[o+3]&255L);
    }

    /** Safe for compound packets, padding and malformed data. Only reports about our stream are returned. */
    public static List<ReceiverReport> receiverReports(byte[] data,int length,long expectedMediaSsrc) {
        List<ReceiverReport> result=new ArrayList<>();
        if(data==null || length<4 || length>data.length) return result;
        int pos=0;
        while(pos+4<=length) {
            int flags=data[pos]&255, type=data[pos+1]&255;
            if((flags>>>6)!=2)break;
            int packetSize=((((data[pos+2]&255)<<8)|(data[pos+3]&255))+1)*4;
            if(packetSize<4 || pos+packetSize>length)break;
            int end=pos+packetSize;
            if((flags&0x20)!=0) {
                int padding=data[end-1]&255;
                if(padding==0 || padding>packetSize)break;
                end-=padding;
            }
            int rc=flags&31;
            int blocksStart=(type==201?8:(type==200?28:-1));
            if(blocksStart>0 && end-pos>=blocksStart) {
                long reporter=u32(data,pos+4);
                int off=pos+blocksStart;
                for(int i=0;i<rc && off+24<=end;i++,off+=24) {
                    long media=u32(data,off);
                    if(media!=expectedMediaSsrc)continue;
                    int fraction=data[off+4]&255;
                    int lost=((data[off+5]&255)<<16)|((data[off+6]&255)<<8)|(data[off+7]&255);
                    if((lost&0x800000)!=0)lost|=0xff000000;
                    result.add(new ReceiverReport(reporter,media,fraction,lost,
                            u32(data,off+8),u32(data,off+12),u32(data,off+16),u32(data,off+20)));
                }
            }
            pos+=packetSize;
        }
        return result;
    }
}
