package com.example.screenstreamdiag;

import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** RTSP 1.0 server with optional H264 video track (0) and MPEG4-GENERIC/AAC track (1).
 * Each RTSP connection has independent RTP transports, bounded buffers and diagnostic counters.
 * LAN only. No authentication, encryption, or RTCP sender-reports in this experimental version.
 */
public final class RtspServer {
    public static final int PORT=8554, MAX_CLIENTS=5;
    private final Set<Client> clients=ConcurrentHashMap.newKeySet();
    private final SecureRandom random=new SecureRandom();
    private final Object configLock=new Object();
    private final boolean audioEnabled;
    private final Runnable requestKeyframe;
    private volatile boolean running;
    private volatile byte[] sps,pps;
    private ServerSocket listener;
    private Thread acceptThread;
    private static final class VideoFrame {
        final List<byte[]> nals; final boolean key; final long ptsUs;
        VideoFrame(List<byte[]> n,boolean k,long p){nals=n;key=k;ptsUs=p;}
    }
    private static final class AudioFrame {
        final byte[] aac; final long ptsUs;
        AudioFrame(byte[] a,long p){aac=a;ptsUs=p;}
    }
    public RtspServer(boolean audioEnabled, Runnable requestKeyframe){
        this.audioEnabled=audioEnabled;this.requestKeyframe=requestKeyframe;
    }
    public synchronized void start() throws IOException {
        if(running)return;
        String ip=DeviceInspector.wifiIpv4();
        if("0.0.0.0".equals(ip))throw new IOException("Không tìm thấy IP Wi-Fi/hotspot");
        listener=new ServerSocket();listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(ip,PORT));running=true;
        acceptThread=new Thread(this::acceptLoop,"rtsp-accept");acceptThread.start();
        DiagnosticLog.event("RTSP","LISTEN "+ip+":"+PORT+" audio="+audioEnabled+" max="+MAX_CLIENTS);
    }
    public synchronized void stop(){
        running=false;
        if(listener!=null)try{listener.close();}catch(IOException ignored){}
        for(Client c:clients)c.close("server stopping");
        clients.clear();DiagnosticLog.event("RTSP","STOP");
    }
    public int playingCount(){int n=0;for(Client c:clients)if(c.playing())n++;return n;}
    public int clientCount(){return clients.size();}
    public boolean hasAudio(){return audioEnabled;}
    public void setCodecConfig(byte[] newSps,byte[] newPps){
        synchronized(configLock){
            if(newSps!=null && newSps.length>0)sps=newSps.clone();
            if(newPps!=null && newPps.length>0)pps=newPps.clone();
            configLock.notifyAll();
        }
    }
    public void broadcast(List<byte[]> nals,boolean key,long ptsUs){
        if(!running || nals==null || nals.isEmpty())return;
        VideoFrame f=new VideoFrame(nals,key,ptsUs);
        for(Client c:clients)c.enqueueVideo(f);
    }
    public void broadcastAudio(byte[] aac,long ptsUs){
        if(!running || !audioEnabled || aac==null || aac.length==0)return;
        AudioFrame f=new AudioFrame(aac,ptsUs);
        for(Client c:clients)c.enqueueAudio(f);
    }
    public String statistics(){
        StringBuilder s=new StringBuilder("clients=").append(clientCount()).append(" playing=").append(playingCount());
        for(Client c:clients){
            s.append("\n  ip=").append(c.ip).append(" session=").append(c.sessionId)
                    .append(" video=").append(c.video.mode()).append(" audio=").append(c.audio.mode())
                    .append(" play=").append(c.playing()).append(" bytesVideo=").append(c.video.bytes.get())
                    .append(" bytesAudio=").append(c.audio.bytes.get())
                    .append(" videoRtpPkts=").append(c.video.packetCount.get())
                    .append(" audioRtpPkts=").append(c.audio.packetCount.get())
                    .append(" drop=").append(c.dropped.get())
                    .append(" queue=").append(c.queue.size()).append(" waitingKey=").append(c.waitKey)
                    .append(" lastVideoSendAgoMs=").append(SystemClock.elapsedRealtime()-c.video.lastSendMs);
        }
        return s.toString();
    }
    private void acceptLoop(){
        while(running){
            try{
                Socket socket=listener.accept();socket.setKeepAlive(true);socket.setTcpNoDelay(true);
                if(clients.size()>=MAX_CLIENTS){
                    DiagnosticLog.event("RTSP","REJECT max-clients ip="+socket.getInetAddress().getHostAddress());
                    socket.close();continue;
                }
                Client c=new Client(socket);clients.add(c);c.start();
            }catch(IOException e){if(running)DiagnosticLog.event("RTSP","accept error="+e);}
        }
    }
    private String sdp(){
        synchronized(configLock){
            if(sps==null || pps==null)try{configLock.wait(2500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(sps==null || pps==null)return null;
            String text="v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=RTSP Diagnostic\r\nt=0 0\r\na=control:*\r\n"+
                    "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:96 H264/90000\r\n"+
                    "a=fmtp:96 packetization-mode=1;profile-level-id="+H264.profileLevelId(sps)+
                    ";sprop-parameter-sets="+Base64.getEncoder().encodeToString(sps)+","+
                    Base64.getEncoder().encodeToString(pps)+"\r\na=control:trackID=0\r\n";
            if(audioEnabled)text+="m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\n"+
                    "a=rtpmap:97 MPEG4-GENERIC/44100/1\r\n"+
                    "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;config="+AacRtp.CONFIG_HEX+
                    ";SizeLength=13;IndexLength=3;IndexDeltaLength=3\r\na=control:trackID=1\r\n";
            return text;
        }
    }
    private final class Client {
        final Socket socket;final String ip;final String sessionId=Long.toHexString(random.nextLong()&Long.MAX_VALUE);
        final Object writeLock=new Object();
        final Track video=new Track(0,96),audio=new Track(1,97);
        final ArrayBlockingQueue<Object> queue=new ArrayBlockingQueue<>(24);
        final AtomicLong dropped=new AtomicLong();
        volatile boolean closed,waitKey=true;
        volatile long lastOverflowLogMs,lastWaitingLogMs,playAtMs;
        volatile boolean sentFirstKey;
        Thread reader,sender;
        Client(Socket sock){socket=sock;ip=sock.getInetAddress().getHostAddress();}
        boolean playing(){return video.playing||audio.playing;}
        void start(){
            DiagnosticLog.event("CLIENT","CONNECT ip="+ip+" session="+sessionId+" controlPort="+socket.getPort());
            reader=new Thread(this::readRequests,"rtsp-reader-"+sessionId);reader.start();
        }
        void readRequests(){
            String closeReason="RTSP socket EOF";
            try{
                InputStream in=new BufferedInputStream(socket.getInputStream());
                while(!closed){
                    String request=readLine(in);
                    if(request==null)break;
                    if(request.isEmpty()||"$".equals(request))continue;
                    String[] first=request.split(" ");
                    if(first.length<3){respond(400,"Bad Request","0",null,null);continue;}
                    String method=first[0].toUpperCase(Locale.US),url=first[1];
                    Map<String,String> headers=new HashMap<>();String line;
                    while((line=readLine(in))!=null && !line.isEmpty()){
                        int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).trim().toLowerCase(Locale.US),line.substring(colon+1).trim());
                    }
                    if(line==null)break;
                    int length=parseInt(headers.get("content-length"),0);
                    if(length<0||length>65536)throw new IOException("Bad RTSP body length");
                    while(length-->0)if(in.read()<0)throw new EOFException();
                    String cseq=headers.getOrDefault("cseq","0");
                    DiagnosticLog.event("RTSP","REQUEST ip="+ip+" session="+sessionId+" method="+method+" cseq="+cseq+" url="+url);
                    switch(method){
                        case "OPTIONS":respond(200,"OK",cseq,"Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER\r\n",null);break;
                        case "DESCRIBE":{
                            String body=sdp();
                            if(body==null)respond(503,"Service Unavailable",cseq,"Retry-After: 2\r\n",null);
                            else respond(200,"OK",cseq,"Content-Base: "+url.replaceAll("/+$","")+"/\r\nContent-Type: application/sdp\r\n",body);
                            break;
                        }
                        case "SETUP":{
                            int track=url.contains("trackID=1")?1:0;
                            if(track==1 && !audioEnabled){respond(404,"Not Found",cseq,null,null);break;}
                            setup(track==0?video:audio,headers.getOrDefault("transport",""),cseq);break;
                        }
                        case "PLAY":{
                            if(!video.configured&&!audio.configured){respond(455,"Method Not Valid in This State",cseq,null,null);break;}
                            respond(200,"OK",cseq,"Session: "+sessionId+";timeout=60\r\nRange: npt=0.000-\r\n",null);
                            video.playing=video.configured;audio.playing=audio.configured;
                            queue.clear();waitKey=true;sentFirstKey=false;playAtMs=SystemClock.elapsedRealtime();
                            video.lastRtcpMs=playAtMs;audio.lastRtcpMs=playAtMs;
                            startSender();if(video.playing && requestKeyframe!=null)requestKeyframe.run();
                            DiagnosticLog.event("CLIENT","PLAY ip="+ip+" session="+sessionId+" video="+video.mode()+" audio="+audio.mode());
                            break;
                        }
                        case "PAUSE":{
                            video.playing=false;audio.playing=false;queue.clear();
                            respond(200,"OK",cseq,"Session: "+sessionId+"\r\n",null);
                            DiagnosticLog.event("CLIENT","PAUSE ip="+ip+" session="+sessionId);break;
                        }
                        case "GET_PARAMETER":case "SET_PARAMETER":respond(200,"OK",cseq,"Session: "+sessionId+"\r\n",null);break;
                        case "TEARDOWN":{
                            respond(200,"OK",cseq,"Session: "+sessionId+"\r\n",null);
                            closeReason="client TEARDOWN";return;
                        }
                        default:respond(405,"Method Not Allowed",cseq,null,null);
                    }
                }
            }catch(SocketException e){closeReason="socket "+e.getMessage();}
            catch(Exception e){closeReason="IO/error "+e;DiagnosticLog.event("RTSP","READ_ERROR ip="+ip+" exception="+e);}
            finally{close(closeReason);}
        }
        void setup(Track track,String transport,String cseq)throws IOException {
            if(track.configured){respond(455,"Method Not Valid in This State",cseq,null,null);return;}
            String upper=transport.toUpperCase(Locale.US);
            if(upper.contains("RTP/AVP/TCP")){
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("interleaved=(\\d+)",2).matcher(transport);
                int channel=m.find()?parseInt(m.group(1),-1):track.id*2;
                if(channel<0||channel>254){respond(461,"Unsupported Transport",cseq,null,null);return;}
                track.tcp=true;track.channel=channel;track.configured=true;
                respond(200,"OK",cseq,"Transport: RTP/AVP/TCP;unicast;interleaved="+channel+"-"+(channel+1)+
                        ";ssrc="+String.format(Locale.US,"%08x",track.ssrc)+"\r\nSession: "+sessionId+";timeout=60\r\n",null);
            }else{
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("client_port=(\\d+)-(\\d+)",2).matcher(transport);
                if(!upper.contains("RTP/AVP")||!m.find()){respond(461,"Unsupported Transport",cseq,null,null);return;}
                int port=parseInt(m.group(1),-1);
                if(port<1||port>65534){respond(461,"Unsupported Transport",cseq,null,null);return;}
                int rtcpPort=parseInt(m.group(2),port+1);
                if(rtcpPort<1||rtcpPort>65535){respond(461,"Unsupported Transport",cseq,null,null);return;}
                track.port=port;track.rtcpPort=rtcpPort;track.tcp=false;
                for(int attempt=0;attempt<30;attempt++){
                    DatagramSocket rtp=new DatagramSocket(0);
                    int p=rtp.getLocalPort();
                    if((p&1)!=0||p>=65535){rtp.close();continue;}
                    try{DatagramSocket rtcp=new DatagramSocket(p+1);track.rtp=rtp;track.rtcp=rtcp;break;}
                    catch(SocketException e){rtp.close();}
                }
                if(track.rtp==null){respond(500,"Internal Server Error",cseq,null,null);return;}
                track.configured=true;
                respond(200,"OK",cseq,"Transport: RTP/AVP;unicast;client_port="+port+"-"+rtcpPort+
                        ";server_port="+track.rtp.getLocalPort()+"-"+track.rtcp.getLocalPort()+
                        ";ssrc="+String.format(Locale.US,"%08x",track.ssrc)+"\r\nSession: "+sessionId+";timeout=60\r\n",null);
            }
            DiagnosticLog.event("CLIENT","SETUP ip="+ip+" session="+sessionId+" track="+track.id+" transport="+track.mode());
        }
        void respond(int code,String reason,String cseq,String extra,String body)throws IOException {
            byte[] data=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
            String h="RTSP/1.0 "+code+" "+reason+"\r\nCSeq: "+cseq+"\r\nServer: RTSPDiagnostic/0.2\r\n"+
                    (extra==null?"":extra)+"Content-Length: "+data.length+"\r\n\r\n";
            synchronized(writeLock){
                OutputStream out=socket.getOutputStream();out.write(h.getBytes(StandardCharsets.US_ASCII));
                if(data.length>0)out.write(data);out.flush();
            }
        }
        void enqueueVideo(VideoFrame f){
            if(!video.playing||closed)return;
            if(waitKey && !f.key){
                long now=SystemClock.elapsedRealtime();
                if(now-lastWaitingLogMs>3000){
                    lastWaitingLogMs=now;
                    DiagnosticLog.event("CLIENT","WAITING_KEYFRAME ip="+ip+" session="+sessionId+
                            " waitedMs="+(now-playAtMs));
                    if(requestKeyframe!=null)requestKeyframe.run();
                }
                return;
            }
            if(f.key)waitKey=false;
            if(!queue.offer(f)){
                long lost=queue.size()+1;dropped.addAndGet(lost);queue.clear();waitKey=true;
                long now=SystemClock.elapsedRealtime();
                if(now-lastOverflowLogMs>3000){
                    lastOverflowLogMs=now;
                    DiagnosticLog.event("CLIENT","SLOW_CLIENT ip="+ip+" session="+sessionId+
                            " dropped="+lost+" mode="+video.mode()+"; requesting keyframe");
                }
                if(requestKeyframe!=null)requestKeyframe.run();
            }
        }
        void enqueueAudio(AudioFrame f){
            if(!audio.playing||closed)return;
            if(!queue.offer(f)){
                dropped.incrementAndGet();
                long now=SystemClock.elapsedRealtime();
                if(now-lastOverflowLogMs>3000){
                    lastOverflowLogMs=now;
                    DiagnosticLog.event("CLIENT","SLOW_CLIENT_AUDIO ip="+ip+" session="+sessionId);
                }
            }
        }
        void startSender(){
            if(sender!=null && sender.isAlive())return;
            sender=new Thread(()->{
                try{
                    while(!closed){
                        Object frame=queue.poll(1000,TimeUnit.MILLISECONDS);
                        if(frame instanceof VideoFrame && video.playing)sendVideo((VideoFrame)frame);
                        else if(frame instanceof AudioFrame && audio.playing)sendAudio((AudioFrame)frame);
                        long now=SystemClock.elapsedRealtime();
                        if(video.playing && now-video.lastRtcpMs>=5000)sendRtcpSenderReport(video);
                        if(audio.playing && now-audio.lastRtcpMs>=5000)sendRtcpSenderReport(audio);
                    }
                }catch(InterruptedException e){Thread.currentThread().interrupt();}
                catch(Exception e){DiagnosticLog.event("CLIENT","SEND_ERROR ip="+ip+" session="+sessionId+" cause="+e);close("RTP send failure "+e);}
            },"rtp-sender-"+sessionId);sender.start();
        }
        void sendVideo(VideoFrame f)throws IOException {
            long ts=(f.ptsUs*9L/100L+video.tsBase)&0xffffffffL;
            if(f.key && sps!=null && pps!=null){sendNal(sps,ts,false);sendNal(pps,ts,false);}
            for(int i=0;i<f.nals.size();i++)sendNal(f.nals.get(i),ts,i==f.nals.size()-1);
            if(f.key && !sentFirstKey){
                sentFirstKey=true;
                DiagnosticLog.event("CLIENT","FIRST_VIDEO_KEYFRAME ip="+ip+" session="+sessionId+
                        " delayMs="+(SystemClock.elapsedRealtime()-playAtMs));
            }
        }
        void sendNal(byte[] nal,long ts,boolean marker)throws IOException {
            if(nal==null||nal.length==0)return;
            final int mtu=1150;
            if(nal.length<=mtu){sendPacket(video,nal,ts,marker);return;}
            int offset=1;
            while(offset<nal.length){
                int count=Math.min(mtu-2,nal.length-offset);
                int indicator=(nal[0]&0xe0)|28;
                int fu=(nal[0]&0x1f)|(offset==1?0x80:0)|(offset+count==nal.length?0x40:0);
                byte[] fragment=new byte[count+2];fragment[0]=(byte)indicator;fragment[1]=(byte)fu;
                System.arraycopy(nal,offset,fragment,2,count);
                sendPacket(video,fragment,ts,marker && offset+count==nal.length);
                offset+=count;
            }
        }
        void sendAudio(AudioFrame f)throws IOException {
            byte[] payload;
            try{payload=AacRtp.payload(f.aac);}
            catch(IllegalArgumentException e){
                DiagnosticLog.event("AUDIO","AAC_AU_DROP size="+f.aac.length+" session="+sessionId);
                return;
            }
            long ts=(f.ptsUs*AudioEngine.RATE/1000000L+audio.tsBase)&0xffffffffL;
            sendPacket(audio,payload,ts,true);
        }
        void sendRtcpSenderReport(Track t)throws IOException {
            // RFC 3550 sender report: V=2/PT=200/length=6, SSRC, NTP, RTP timestamp, counts.
            // Reports can help clients distinguish a live RTSP session from a dead socket.
            byte[] b=new byte[28];b[0]=(byte)0x80;b[1]=(byte)200;b[2]=0;b[3]=6;
            put32(b,4,t.ssrc&0xffffffffL);
            long unixMs=System.currentTimeMillis();
            long ntpSeconds=unixMs/1000L+2208988800L;
            long ntpFraction=(unixMs%1000L)*4294967296L/1000L;
            put32(b,8,ntpSeconds);put32(b,12,ntpFraction);
            put32(b,16,t.lastRtpTimestamp);
            put32(b,20,t.packetCount.get());put32(b,24,t.octets.get());
            if(t.tcp){
                synchronized(writeLock){
                    OutputStream out=socket.getOutputStream();out.write('$');out.write(t.channel+1);
                    out.write(0);out.write(b.length);out.write(b);
                }
            } else if(t.rtcp!=null){
                t.rtcp.send(new DatagramPacket(b,b.length,socket.getInetAddress(),t.rtcpPort));
            }
            t.lastRtcpMs=SystemClock.elapsedRealtime();
        }
        void put32(byte[] b,int off,long value){
            b[off]=(byte)(value>>>24);b[off+1]=(byte)(value>>>16);
            b[off+2]=(byte)(value>>>8);b[off+3]=(byte)value;
        }
        void sendPacket(Track track,byte[] payload,long ts,boolean marker)throws IOException {
            byte[] pkt=new byte[12+payload.length];pkt[0]=(byte)0x80;
            pkt[1]=(byte)(track.payloadType|(marker?128:0));
            pkt[2]=(byte)(track.seq>>>8);pkt[3]=(byte)track.seq;track.seq=(track.seq+1)&0xffff;
            pkt[4]=(byte)(ts>>>24);pkt[5]=(byte)(ts>>>16);pkt[6]=(byte)(ts>>>8);pkt[7]=(byte)ts;
            pkt[8]=(byte)(track.ssrc>>>24);pkt[9]=(byte)(track.ssrc>>>16);pkt[10]=(byte)(track.ssrc>>>8);pkt[11]=(byte)track.ssrc;
            System.arraycopy(payload,0,pkt,12,payload.length);
            if(track.tcp){
                synchronized(writeLock){
                    OutputStream out=socket.getOutputStream();out.write('$');out.write(track.channel);
                    out.write((pkt.length>>>8)&255);out.write(pkt.length&255);out.write(pkt);
                }
            }else if(track.rtp!=null){
                track.rtp.send(new DatagramPacket(pkt,pkt.length,socket.getInetAddress(),track.port));
            }else throw new IOException("RTP transport not initialized");
            track.bytes.addAndGet(pkt.length+(track.tcp?4:0));
            track.packetCount.incrementAndGet();track.octets.addAndGet(payload.length);
            track.lastRtpTimestamp=ts;track.lastSendMs=SystemClock.elapsedRealtime();
        }
        String readLine(InputStream in)throws IOException {
            int first=in.read();if(first<0)return null;
            if(first=='$'){
                int ch=in.read(),hi=in.read(),lo=in.read();if(ch<0||hi<0||lo<0)throw new EOFException();
                int left=(hi<<8)|lo;
                while(left>0){long skipped=in.skip(left);if(skipped<=0){if(in.read()<0)throw new EOFException();skipped=1;}left-=skipped;}
                return "$";
            }
            ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(first);
            while(b.size()<8192){int c=in.read();if(c<0)throw new EOFException();if(c=='\n')break;b.write(c);}
            if(b.size()>=8192)throw new IOException("RTSP header too large");
            byte[] bytes=b.toByteArray();int end=bytes.length;while(end>0&&bytes[end-1]=='\r')end--;
            return new String(bytes,0,end,StandardCharsets.US_ASCII);
        }
        int parseInt(String s,int fallback){try{return Integer.parseInt(s);}catch(Exception e){return fallback;}}
        synchronized void close(String reason){
            if(closed)return;
            closed=true;video.playing=false;audio.playing=false;queue.clear();clients.remove(this);
            try{socket.close();}catch(IOException ignored){}
            video.close();audio.close();
            DiagnosticLog.event("CLIENT","DISCONNECT ip="+ip+" session="+sessionId+" reason="+reason+
                    " sentVideoBytes="+video.bytes.get()+" sentAudioBytes="+audio.bytes.get()+" dropped="+dropped.get());
        }
    }
    private final class Track {
        final int id,payloadType,ssrc;
        final long tsBase;
        final AtomicLong bytes=new AtomicLong(),packetCount=new AtomicLong(),octets=new AtomicLong();
        volatile boolean configured,playing,tcp;
        volatile long lastSendMs=SystemClock.elapsedRealtime(),lastRtcpMs;
        volatile long lastRtpTimestamp;
        int channel,port,rtcpPort,seq;
        DatagramSocket rtp,rtcp;
        Track(int id,int pt){this.id=id;payloadType=pt;ssrc=random.nextInt();seq=random.nextInt(65536);tsBase=random.nextInt()&0xffffffffL;}
        String mode(){return !configured?"OFF":(tcp?"TCP":"UDP")+(playing?"/PLAY":"/READY");}
        void close(){if(rtp!=null)rtp.close();if(rtcp!=null)rtcp.close();}
    }
}
