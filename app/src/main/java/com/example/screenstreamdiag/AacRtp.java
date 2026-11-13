package com.example.screenstreamdiag;

/** RFC 3640 MPEG4-GENERIC (AAC-hbr) one AU per RTP packet; AAC-LC mono 44.1 kHz. */
public final class AacRtp {
    private AacRtp() {}
    public static final String CONFIG_HEX="1208"; // AAC LC (2), sample-rate index 4, mono (1)
    public static byte[] payload(byte[] accessUnit) {
        if(accessUnit==null || accessUnit.length==0 || accessUnit.length>1023)
            throw new IllegalArgumentException("Invalid AAC access unit length");
        byte[] out=new byte[4+accessUnit.length];
        out[0]=0;out[1]=16; // AU-headers-length: 16 bits
        int header=accessUnit.length<<3; // AU-size (13 bits) + AU-index (3 bits)
        out[2]=(byte)(header>>>8);out[3]=(byte)header;
        System.arraycopy(accessUnit,0,out,4,accessUnit.length);
        return out;
    }
}
