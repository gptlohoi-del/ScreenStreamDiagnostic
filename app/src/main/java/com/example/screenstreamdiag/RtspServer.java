package com.example.screenstreamdiag;

import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Basic RTSP 1.0 H.264 live server. RTP over TCP/interleaved and UDP unicast. */
public final class RtspServer {
    public static final int PORT = 8554;
    public static final int MAX_CLIENTS = 5;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile byte[] sps, pps;
    private final Object configLock = new Object();
    private ServerSocket listener;
    private Thread acceptThread;
    private final SecureRandom random = new SecureRandom();

    public static final class Frame {
        final List<byte[]> nals;
        final boolean key;
        final long ptsUs;
        Frame(List<byte[]> nals, boolean key, long ptsUs) {
            this.nals = nals; this.key = key; this.ptsUs = ptsUs;
        }
    }

    public synchronized void start() throws IOException {
        if (running) return;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        String wifiIp = DeviceInspector.wifiIpv4();
        if ("0.0.0.0".equals(wifiIp)) throw new IOException("Không tìm thấy IP Wi-Fi: hãy kết nối Wi-Fi trước");
        listener.bind(new InetSocketAddress(wifiIp, PORT));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "rtsp-accept");
        acceptThread.start();
        DiagnosticLog.event("RTSP", "LISTEN " + wifiIp + ":" + PORT + " maxClients=" + MAX_CLIENTS);
    }
    public synchronized void stop() {
        running = false;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        for (Client client : clients) client.close("server stopping");
        clients.clear();
        DiagnosticLog.event("RTSP", "STOP");
    }
    public int clientCount() { return clients.size(); }
    public int playingCount() {
        int count = 0;
        for (Client c : clients) if (c.playing) count++;
        return count;
    }
    public void setCodecConfig(byte[] sps, byte[] pps) {
        synchronized (configLock) {
            if (sps != null && sps.length > 0) this.sps = sps.clone();
            if (pps != null && pps.length > 0) this.pps = pps.clone();
            configLock.notifyAll();
        }
    }
    public void broadcast(List<byte[]> nals, boolean key, long ptsUs) {
        if (!running || nals == null || nals.isEmpty()) return;
        Frame frame = new Frame(nals, key, ptsUs);
        for (Client client : clients) client.enqueue(frame);
    }
    public String statistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("clients=").append(clients.size()).append(" playing=").append(playingCount());
        for (Client c : clients) stats.append("\n  ").append(c.ip).append(" transport=")
                .append(c.udp ? "UDP" : "TCP").append(" play=").append(c.playing)
                .append(" sentBytes=").append(c.bytesSent.get()).append(" droppedFrames=")
                .append(c.dropped.get()).append(" queue=").append(c.queue.size())
                .append(" lastSendAgoMs=").append(SystemClock.elapsedRealtime() - c.lastSendMs);
        return stats.toString();
    }
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = listener.accept();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                if (clients.size() >= MAX_CLIENTS) {
                    DiagnosticLog.event("RTSP", "REJECT too-many-clients ip=" + socket.getInetAddress().getHostAddress());
                    socket.close();
                    continue;
                }
                Client client = new Client(socket);
                clients.add(client);
                client.start();
            } catch (SocketException e) {
                if (running) DiagnosticLog.event("RTSP", "accept socket error=" + e);
            } catch (IOException e) {
                if (running) DiagnosticLog.event("RTSP", "accept IO error=" + e);
            }
        }
    }
    private String sdp() {
        synchronized (configLock) {
            if (sps == null || pps == null) {
                try { configLock.wait(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (sps == null || pps == null) return null;
            return "v=0\r\n" + "o=- 0 0 IN IP4 0.0.0.0\r\n" +
                    "s=RTSP Diagnostic\r\n" + "t=0 0\r\n" + "a=control:*\r\n" +
                    "m=video 0 RTP/AVP 96\r\n" + "c=IN IP4 0.0.0.0\r\n" +
                    "a=rtpmap:96 H264/90000\r\n" +
                    "a=fmtp:96 packetization-mode=1;profile-level-id=" + H264.profileLevelId(sps)
                    + ";sprop-parameter-sets=" + Base64.getEncoder().encodeToString(sps) + ","
                    + Base64.getEncoder().encodeToString(pps) + "\r\n" +
                    "a=control:trackID=0\r\n";
        }
    }

    private final class Client {
        final Socket socket;
        final String ip;
        final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        final Object writeLock = new Object();
        final AtomicLong bytesSent = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final String sessionId = Long.toHexString(random.nextLong() & Long.MAX_VALUE);
        final int ssrc = random.nextInt();
        int seq = random.nextInt(65536);
        volatile boolean playing, closed, udp;
        volatile long lastSendMs = SystemClock.elapsedRealtime();
        volatile long lastOverflowLogMs;
        volatile boolean needsKey = true;
        long rtpStart = random.nextInt() & 0xffffffffL;
        DatagramSocket udpRtp, udpRtcp;
        int destinationPort;
        Thread reader, sender;
        Client(Socket socket) { this.socket = socket; this.ip = socket.getInetAddress().getHostAddress(); }
        void start() {
            DiagnosticLog.event("CLIENT", "CONNECT ip=" + ip + " control=" + socket.getPort());
            reader = new Thread(this::readRequests, "rtsp-reader-" + ip);
            reader.start();
        }
        void readRequests() {
            String closeReason = "remote RTSP closed (EOF)";
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                while (!closed) {
                    String request = readLine(in);
                    if (request == null) break;
                    if (request.isEmpty()) continue;
                    if (request.charAt(0) == '$') { continue; } // interleaved RTCP already drained by readLine
                    String[] first = request.split(" ");
                    if (first.length < 3) { response(400, "Bad Request", "0", null, null); continue; }
                    String method = first[0].toUpperCase(Locale.US);
                    String url = first[1];
                    Map<String,String> headers = new HashMap<>();
                    String line;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) headers.put(line.substring(0,colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
                    }
                    if (line == null) break;
                    int bodyLength = parseInt(headers.get("content-length"),0);
                    if (bodyLength < 0 || bodyLength > 65536) { closeReason="malformed RTSP body length"; break; }
                    while (bodyLength-- > 0) if (in.read() < 0) throw new EOFException();
                    String cseq = headers.getOrDefault("cseq", "0");
                    DiagnosticLog.event("RTSP", "REQUEST ip=" + ip + " method=" + method + " cseq=" + cseq);
                    switch (method) {
                        case "OPTIONS":
                            response(200,"OK",cseq,"Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER\r\n",null);
                            break;
                        case "DESCRIBE": {
                            String body = sdp();
                            if (body == null) response(503,"Service Unavailable",cseq,"Retry-After: 2\r\n",null);
                            else response(200,"OK",cseq,"Content-Base: " + url.replaceAll("/+$", "") + "/\r\nContent-Type: application/sdp\r\n",body);
                            break;
                        }
                        case "SETUP":
                            setup(headers.getOrDefault("transport", ""),cseq);
                            break;
                        case "PLAY":
                            if (udpRtp == null && !configuredTcp) response(455,"Method Not Valid in This State",cseq,null,null);
                            else {
                                response(200,"OK",cseq,"Session: " + sessionId + ";timeout=60\r\nRange: npt=0.000-\r\n",null);
                                needsKey = true;
                                playing = true;
                                startSender();
                                DiagnosticLog.event("CLIENT", "PLAY ip=" + ip + " mode=" + (udp ? "UDP" : "TCP"));
                            }
                            break;
                        case "PAUSE":
                            playing = false;
                            queue.clear();
                            response(200,"OK",cseq,"Session: " + sessionId + "\r\n",null);
                            DiagnosticLog.event("CLIENT", "PAUSE ip=" + ip);
                            break;
                        case "GET_PARAMETER": case "SET_PARAMETER":
                            response(200,"OK",cseq,"Session: " + sessionId + "\r\n",null);
                            break;
                        case "TEARDOWN":
                            response(200,"OK",cseq,"Session: " + sessionId + "\r\n",null);
                            closeReason = "client TEARDOWN";
                            return;
                        default:
                            response(405,"Method Not Allowed",cseq,null,null);
                    }
                }
            } catch (SocketException e) { closeReason = "socket " + e.getMessage(); }
            catch (IOException e) { closeReason = "IO " + e; }
            catch (Exception e) { closeReason = "error " + e; DiagnosticLog.error("RTSP",e); }
            finally { close(closeReason); }
        }
        boolean configuredTcp;
        void setup(String transport, String cseq) throws IOException {
            if (transport.toUpperCase(Locale.US).contains("RTP/AVP/TCP")) {
                udp = false; configuredTcp = true;
                // Use the channel pair requested by VLC when provided.
                int interleaved = 0;
                java.util.regex.Matcher channel = java.util.regex.Pattern.compile("interleaved=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(transport);
                if (channel.find()) interleaved = parseInt(channel.group(1),0);
                if (interleaved < 0 || interleaved > 254) { response(461,"Unsupported Transport",cseq,null,null); return; }
                rtpChannel = interleaved;
                response(200,"OK",cseq,"Transport: RTP/AVP/TCP;unicast;interleaved=" + interleaved + "-" + (interleaved+1)
                        + "\r\nSession: " + sessionId + ";timeout=60\r\n",null);
                DiagnosticLog.event("CLIENT", "SETUP TCP ip=" + ip + " channel=" + rtpChannel);
                return;
            }
            java.util.regex.Matcher ports = java.util.regex.Pattern.compile("client_port=(\\d+)-(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(transport);
            if (!transport.toUpperCase(Locale.US).contains("RTP/AVP") || !ports.find()) {
                response(461,"Unsupported Transport",cseq,null,null);
                DiagnosticLog.event("CLIENT", "SETUP unsupported transport ip=" + ip + " value=" + transport);
                return;
            }
            int port = parseInt(ports.group(1),0);
            if (port < 1 || port > 65534) { response(461,"Unsupported Transport",cseq,null,null); return; }
            destinationPort = port;
            // Allocate an adjacent even/odd pair for RTP + RTCP, as expected by RTSP players.
            for (int attempt=0;attempt<20;attempt++) {
                DatagramSocket candidate = new DatagramSocket(0);
                int rtpPort = candidate.getLocalPort();
                if ((rtpPort & 1) != 0 || rtpPort >= 65535) { candidate.close(); continue; }
                try {
                    DatagramSocket rtcp = new DatagramSocket(rtpPort+1);
                    udpRtp = candidate;
                    udpRtcp = rtcp;
                    break;
                } catch (SocketException e) { candidate.close(); }
            }
            if (udpRtp == null) {
                response(500,"Internal Server Error",cseq,null,null);
                DiagnosticLog.event("CLIENT", "UDP port allocation failed ip=" + ip);
                return;
            }
            udp = true;
            response(200,"OK",cseq,"Transport: RTP/AVP;unicast;client_port=" + port + "-" + (port+1)
                    + ";server_port=" + udpRtp.getLocalPort() + "-" + udpRtcp.getLocalPort()
                    + ";ssrc=" + String.format(Locale.US,"%08x",ssrc) + "\r\nSession: " + sessionId + ";timeout=60\r\n",null);
            DiagnosticLog.event("CLIENT", "SETUP UDP ip=" + ip + " client_port=" + port + " server_port=" + udpRtp.getLocalPort());
        }
        int rtpChannel;
        private void response(int code, String reason, String cseq, String extra, String body) throws IOException {
            byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String header = "RTSP/1.0 " + code + " " + reason + "\r\nCSeq: " + cseq + "\r\nServer: RTSPDiagnostic/0.1\r\n"
                    + (extra == null ? "" : extra) + "Content-Length: " + data.length + "\r\n\r\n";
            synchronized (writeLock) {
                OutputStream out = socket.getOutputStream();
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                if (data.length > 0) out.write(data);
                out.flush();
            }
        }
        private String readLine(InputStream in) throws IOException {
            int first = in.read();
            if (first == -1) return null;
            if (first == '$') {
                int channel = in.read(), high = in.read(), low = in.read();
                if (channel < 0 || high < 0 || low < 0) throw new EOFException();
                int length = (high << 8) | low;
                while (length > 0) { long skipped = in.skip(length); if (skipped <= 0) { if (in.read() < 0) throw new EOFException(); skipped = 1; } length -= skipped; }
                return "$";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            buffer.write(first);
            while (buffer.size() < 8192) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                if (b == '\n') break;
                buffer.write(b);
            }
            if (buffer.size() >= 8192) throw new IOException("RTSP header line too large");
            byte[] bytes = buffer.toByteArray();
            int end = bytes.length;
            while (end > 0 && bytes[end-1] == '\r') end--;
            return new String(bytes,0,end,StandardCharsets.US_ASCII);
        }
        private int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (Exception e) { return fallback; } }
        void enqueue(Frame frame) {
            if (!playing || closed) return;
            if (needsKey && !frame.key) return;
            if (frame.key) needsKey = false;
            if (!queue.offer(frame)) {
                dropped.addAndGet(queue.size() + 1);
                queue.clear();
                needsKey = true;
                long now = SystemClock.elapsedRealtime();
                if (now - lastOverflowLogMs > 5000) {
                    lastOverflowLogMs = now;
                    DiagnosticLog.event("CLIENT", "SLOW_CLIENT ip=" + ip + " queue overflow; waiting keyframe mode=" + (udp?"UDP":"TCP"));
                }
            }
        }
        void startSender() {
            if (sender != null && sender.isAlive()) return;
            sender = new Thread(() -> {
                try {
                    while (!closed) {
                        Frame frame = queue.poll(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (frame == null || !playing) continue;
                        sendFrame(frame);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { DiagnosticLog.event("CLIENT", "SEND_ERROR ip="+ip+" cause="+e); close("RTP send failure: " + e); }
            }, "rtp-send-" + ip);
            sender.start();
        }
        void sendFrame(Frame frame) throws IOException {
            long ts = ((frame.ptsUs * 9L / 100L) + rtpStart) & 0xffffffffL;
            if (frame.key && sps != null && pps != null) {
                sendNal(sps, ts, false);
                sendNal(pps, ts, false);
            }
            for (int i = 0; i < frame.nals.size(); i++) sendNal(frame.nals.get(i), ts, i == frame.nals.size()-1);
        }
        void sendNal(byte[] nal, long ts, boolean marker) throws IOException {
            if (nal.length == 0) return;
            final int payloadLimit = 1150;
            if (nal.length <= payloadLimit) {
                sendRtp(nal, 0, nal.length, ts, marker, -1, -1);
                return;
            }
            int offset=1;
            int maxChunk=payloadLimit-2;
            while (offset < nal.length) {
                int length = Math.min(maxChunk,nal.length-offset);
                int fuIndicator = (nal[0] & 0xe0) | 28;
                int fuHeader = (nal[0] & 0x1f) | (offset==1 ? 0x80 : 0) | (offset+length==nal.length ? 0x40 : 0);
                boolean finalFragment = offset+length==nal.length;
                sendRtp(nal,offset,length,ts,marker && finalFragment,fuIndicator,fuHeader);
                offset += length;
            }
        }
        void sendRtp(byte[] nal,int offset,int length,long ts,boolean marker,int fuIndicator,int fuHeader) throws IOException {
            int payloadLength=length+(fuIndicator<0?0:2);
            byte[] pkt=new byte[12+payloadLength];
            pkt[0]=(byte)0x80;
            pkt[1]=(byte)(96 | (marker?0x80:0));
            pkt[2]=(byte)(seq>>>8); pkt[3]=(byte)seq;
            seq=(seq+1)&65535;
            pkt[4]=(byte)(ts>>>24); pkt[5]=(byte)(ts>>>16); pkt[6]=(byte)(ts>>>8); pkt[7]=(byte)ts;
            pkt[8]=(byte)(ssrc>>>24); pkt[9]=(byte)(ssrc>>>16); pkt[10]=(byte)(ssrc>>>8); pkt[11]=(byte)ssrc;
            int outputOffset=12;
            if(fuIndicator>=0) {pkt[12]=(byte)fuIndicator; pkt[13]=(byte)fuHeader; outputOffset=14;}
            System.arraycopy(nal,offset,pkt,outputOffset,length);
            if (udp) {
                udpRtp.send(new DatagramPacket(pkt,pkt.length,socket.getInetAddress(),destinationPort));
            } else {
                synchronized (writeLock) {
                    OutputStream stream=socket.getOutputStream();
                    stream.write('$'); stream.write(rtpChannel);
                    stream.write((pkt.length>>>8)&255); stream.write(pkt.length&255);
                    stream.write(pkt);
                }
            }
            bytesSent.addAndGet(pkt.length + (udp ? 0 : 4));
            lastSendMs=SystemClock.elapsedRealtime();
        }
        synchronized void close(String reason) {
            if (closed) return;
            closed=true; playing=false; queue.clear(); clients.remove(this);
            try { socket.close(); } catch (IOException ignored) { }
            if (udpRtp != null) udpRtp.close();
            if (udpRtcp != null) udpRtcp.close();
            DiagnosticLog.event("CLIENT", "DISCONNECT ip="+ip+" reason="+reason+" sentBytes="+bytesSent.get()+" droppedFrames="+dropped.get());
        }
    }
}
