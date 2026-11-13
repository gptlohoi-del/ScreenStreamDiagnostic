import com.example.screenstreamdiag.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** JDK-only smoke test. Does not test Android screen capture or the physical network. */
public final class RtspSmoke {
    static int seq=1;
    static String command(Socket socket, String method, String url, String extra)throws Exception {
        String request=method+" "+url+" RTSP/1.0\r\nCSeq: "+(seq++)+"\r\n"+extra+"\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        while(!bytes.toString("US-ASCII").contains("\r\n\r\n")){
            int n=socket.getInputStream().read();if(n<0)throw new EOFException();bytes.write(n);
            if(bytes.size()>10000)throw new IOException("Excessive RTSP headers");
        }
        String header=bytes.toString("US-ASCII");
        Matcher m=Pattern.compile("Content-Length: (\\d+)",Pattern.CASE_INSENSITIVE).matcher(header);
        int len=m.find()?Integer.parseInt(m.group(1)):0;
        for(int i=0;i<len;i++) if(socket.getInputStream().read()<0)throw new EOFException();
        if(!header.startsWith("RTSP/1.0 200"))throw new AssertionError("RTSP failed: "+header);
        return header;
    }
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static byte[] receiverReport(long ssrc){
        byte[] rr=new byte[32];rr[0]=(byte)0x81;rr[1]=(byte)201;rr[3]=7;
        rr[7]=5;
        rr[8]=(byte)(ssrc>>>24);rr[9]=(byte)(ssrc>>>16);rr[10]=(byte)(ssrc>>>8);rr[11]=(byte)ssrc;
        rr[12]=16;rr[15]=2;rr[23]=90;return rr;
    }
    static long ssrc(String setup){
        Matcher m=Pattern.compile("ssrc=([a-f0-9]+)",Pattern.CASE_INSENSITIVE).matcher(setup);
        if(!m.find())throw new AssertionError("Missing SSRC");
        return Long.parseLong(m.group(1),16);
    }
    public static void main(String[] args)throws Exception {
        RtspServer server=new RtspServer(true,()->{});
        server.setCodecConfig(new byte[]{0x67,0x42,(byte)0xe0,0x1f},new byte[]{0x68,0x01});
        try {
            server.start();
            String url="rtsp://127.0.0.1:8554/live";
            try(Socket socket=new Socket("127.0.0.1",8554)){
                socket.setSoTimeout(3000);
                check(command(socket,"OPTIONS",url,"").contains("Public: OPTIONS"),"OPTIONS");
                command(socket,"DESCRIBE",url,"");
                String s=command(socket,"SETUP",url+"/trackID=0","Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
                long mediaSsrc=ssrc(s);
                command(socket,"SETUP",url+"/trackID=1","Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n");
                command(socket,"PLAY",url,"");
                server.broadcast(Arrays.asList(new byte[]{0x65,1,2,3}),true,1_000_000L);
                check(socket.getInputStream().read()=='$',"Expected interleaved RTP");
                socket.getInputStream().read(); // channel
                int len=(socket.getInputStream().read()<<8)|socket.getInputStream().read();
                check(len>=12,"RTP length");
                for(int i=0;i<len;i++)check(socket.getInputStream().read()>=0,"Truncated RTP");
                byte[] rr=receiverReport(mediaSsrc);
                OutputStream out=socket.getOutputStream();
                out.write('$');out.write(1);out.write(0);out.write(rr.length);out.write(rr);out.flush();
                boolean seen=false;
                for(int retry=0;retry<30;retry++){
                    if(server.statistics().contains("rtcpVideoReports=1")){seen=true;break;}
                    Thread.sleep(30);
                }
                check(seen,"TCP RTCP receiver report not collected");
                System.out.println("SMOKE TCP RTP+RTCP PASSED");
            }
            try(Socket socket=new Socket("127.0.0.1",8554)){
                socket.setSoTimeout(3000);
                command(socket,"DESCRIBE",url,"");
                String setup=command(socket,"SETUP",url+"/trackID=0","Transport: RTP/AVP;unicast;client_port=41000-41001\r\n");
                Matcher m=Pattern.compile("server_port=(\\d+)-(\\d+)").matcher(setup);
                check(m.find(),"UDP server ports");
                int rtcpPort=Integer.parseInt(m.group(2));
                command(socket,"PLAY",url,"");
                byte[] rr=receiverReport(ssrc(setup));
                try(DatagramSocket send=new DatagramSocket()){
                    DatagramPacket packet=new DatagramPacket(rr,rr.length,InetAddress.getByName("127.0.0.1"),rtcpPort);
                    send.send(packet);
                }
                boolean seen=false;
                for(int retry=0;retry<30;retry++){
                    if(server.statistics().contains("rtcpVideoReports=1")){seen=true;break;}
                    Thread.sleep(30);
                }
                check(seen,"UDP RTCP receiver report not collected");
                System.out.println("SMOKE UDP RTCP PASSED");
            }
        } finally {server.stop();}
    }
}
