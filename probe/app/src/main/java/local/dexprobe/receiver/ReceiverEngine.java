package local.dexprobe.receiver;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.view.Surface;
import local.dexprobe.Report;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Experimental video-only Wi-Fi Display sink using public Android application APIs. */
public final class ReceiverEngine {
    /** CEA-861 resolution table, index -> {width,height}. Only entries we advertise/accept are populated. */
    private static final Map<Integer,int[]> CEA = new HashMap<>();
    static { CEA.put(0,new int[]{640,480}); CEA.put(5,new int[]{1280,720}); CEA.put(7,new int[]{1920,1080}); }
    /** CEA index -> minimum AVC level bit (VideoFormats LevelType: 31=0x01,32=0x02,40=0x04,41=0x08,42=0x10). */
    private static final Map<Integer,Integer> CEA_LEVEL_BIT = new HashMap<>();
    static { CEA_LEVEL_BIT.put(0,0x01); CEA_LEVEL_BIT.put(5,0x01); CEA_LEVEL_BIT.put(7,0x04); }
    /** VESA resolution table (separate index space from CEA; native-byte low 3 bits select which table).
     * Only index 20 (1600x900p30) is populated — the one entry this sink can offer as "900p". */
    private static final Map<Integer,int[]> VESA = new HashMap<>();
    static { VESA.put(20,new int[]{1600,900}); }
    private static final Map<Integer,Integer> VESA_LEVEL_BIT = new HashMap<>();
    static { VESA_LEVEL_BIT.put(20,0x04); } // 1600x900 rounds to 5700 MB/frame; needs level 4.0, same as 1080p30.
    /** User-selectable cap on advertised resolution — the source still picks among whatever we advertise,
     * so this controls the ceiling, not a forced exact match. VGA + 720p30 (CEA 0/5) are always included
     * as the mandatory baseline regardless of tier. */
    public enum Resolution {
        HD_720P(0,0x00), HDPLUS_900P((1<<20),0x00), FHD_1080P(0,(1<<7));
        final int vesaBit,ceaExtraBit;
        Resolution(int vesaBit,int ceaExtraBit) { this.vesaBit=vesaBit; this.ceaExtraBit=ceaExtraBit; }
    }
    private static int levelBitToCodecLevel(int bit) {
        switch(bit) {
            case 0x01: return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
            case 0x02: return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
            case 0x08: return MediaCodecInfo.CodecProfileLevel.AVCLevel41;
            case 0x10: return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
            default: return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
        }
    }
    private final Surface surface;
    private final Consumer<String> log;
    private final Runnable onFatal;
    private volatile boolean running;
    private volatile Socket control;
    private volatile DatagramSocket rtp;
    private Thread rtspThread,mediaThread,statsThread;
    private int cseq=1;
    private String uri,session;
    private final Map<Integer,String> pending=new HashMap<>();
    private volatile MediaCodec decoder;
    private volatile Transport demux;
    private boolean decoded;
    private volatile long framesDecoded;
    private volatile long lastFrameNanos;
    private volatile long playStartedNanos;
    public interface SizeListener { void onVideoSize(int width,int height); }
    /** Structured playback stats for UI display, emitted alongside the human-readable log lines. */
    public interface StatsListener { void onStats(long fps,long totalFrames,long rtpLoss); }
    private final SizeListener sizeListener;
    private final StatsListener statsListener;
    private final int ceaSupport,vesaSupport,levelSupport;
    public ReceiverEngine(Surface surface,Consumer<String> log,SizeListener sizeListener,StatsListener statsListener,Runnable onFatal,Resolution maxResolution) {
        this.surface=surface; this.log=log; this.sizeListener=sizeListener; this.statsListener=statsListener; this.onFatal=onFatal;
        Resolution r=maxResolution!=null?maxResolution:Resolution.FHD_1080P;
        this.ceaSupport=(1<<0)|(1<<5)|r.ceaExtraBit;
        this.vesaSupport=r.vesaBit;
        this.levelSupport=0x01|(r==Resolution.HD_720P?0:0x04);
    }
    private void note(String s) { log.accept(Report.redact(s)); }
    public synchronized void start(String sourceHost,int controlPort) {
        if(rtspThread!=null) throw new IllegalStateException("Create a new receiver for each session");
        if(controlPort<1||controlPort>65535) throw new IllegalArgumentException("Control port");
        running=true;
        rtspThread=new Thread(()->run(sourceHost,controlPort),"wfd-rtsp"); rtspThread.start();
    }
    public synchronized void stop() {
        running=false;
        try { if(control!=null) control.close(); } catch(IOException ignored) {}
        if(rtp!=null) rtp.close();
    }
    private void configureDecoder(int width,int height,int levelBit) throws IOException {
        MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",width,height);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE,30);
        fmt.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        fmt.setInteger(MediaFormat.KEY_LEVEL,levelBitToCodecLevel(levelBit));
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024);
        String codecName=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt);
        if(codecName==null) throw new RuntimeException("No AVC baseline surface decoder for "+width+"x"+height);
        // Release any prior decoder's hold on the Surface before attaching a new one; a Surface can only
        // have one connected producer, so configuring the new codec first (as before) fails intermittently.
        MediaCodec old=decoder; decoder=null;
        if(old!=null) { try { old.stop(); } catch(Exception ignored) {} old.release(); }
        MediaCodec d=MediaCodec.createByCodecName(codecName); d.configure(fmt,surface,null,0); d.start();
        decoder=d;
        if(sizeListener!=null) sizeListener.onVideoSize(width,height);
        note("Decoder configured "+width+"x"+height+" level bit=0x"+Integer.toHexString(levelBit));
    }
    private void run(String host,int port) {
        try {
            // Default to the mandatory baseline (720p30) until the source's SET_PARAMETER selects a format.
            configureDecoder(1280,720,0x01);
            InetAddress peer=InetAddress.getByName(host);
            // Larger than the previous 2MB: 1080p30's higher packet rate means a burst of motion-heavy
            // frames can arrive faster than the receive loop drains them; more headroom here reduces
            // OS-level buffer-overflow drops that would otherwise look identical to real Wi-Fi loss.
            DatagramSocket udp=new DatagramSocket(0); rtp=udp; udp.setSoTimeout(1000); udp.setReceiveBufferSize(4*1024*1024);
            if(!running) { udp.close(); return; }
            note("RTP bound port="+udp.getLocalPort()+"; actual recv buffer="+udp.getReceiveBufferSize()+"; AVC baseline, CEA=0x"+Integer.toHexString(ceaSupport)+" VESA=0x"+Integer.toHexString(vesaSupport)+"; audio unsupported (advertise none)");
            mediaThread=new Thread(()->receive(peer,udp),"wfd-media"); mediaThread.start();
            statsThread=new Thread(this::statsLoop,"wfd-stats"); statsThread.setDaemon(true); statsThread.start();
            Socket tcp=new Socket(); control=tcp;
            if(!running) { tcp.close(); return; }
            tcp.connect(new InetSocketAddress(peer,port),10000); tcp.setSoTimeout(30000);
            uri="rtsp://"+(host.contains(":")?"["+host+"]":host)+":"+port+"/wfd1.0/streamid=0";
            InputStream in=new BufferedInputStream(tcp.getInputStream());
            note("RTSP connected; waiting source OPTIONS");
            while(running) handle(Rtsp.read(in));
        } catch(Exception e) { if(running) note("Receiver stopped: "+e.getClass().getSimpleName()+" "+e.getMessage()); }
        finally {
            boolean wasRunning=running;
            stop();
            if(mediaThread!=null&&mediaThread!=Thread.currentThread()) try { mediaThread.join(2000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(statsThread!=null) statsThread.interrupt();
            MediaCodec d=decoder; decoder=null;
            if(d!=null) { try { d.stop(); } catch(Exception ignored) {} d.release(); }
            if(wasRunning&&onFatal!=null) onFatal.run();
        }
    }
    /** Below this many newly-lost RTP packets in a 2s window, don't bother requesting a fresh IDR: an IDR
     * frame is much larger than a normal P-frame (many more packets), so requesting one for trivial loss
     * can add more congestion than it recovers from. */
    private static final long IDR_LOSS_THRESHOLD=8;
    /** Minimum gap between IDR requests, regardless of how often loss is detected — an I-frame takes real
     * time to arrive and decode; requesting another one before the last has landed just doubles the cost. */
    private static final long IDR_MIN_INTERVAL_NANOS=4_000_000_000L;
    private volatile long lastIdrRequestNanos;
    /** Every 2s, log decoded frame rate, flag stalls, and request a fresh IDR if loss grew significantly. */
    private void statsLoop() {
        long lastCount=0,lastLoss=0;
        try {
            while(running) {
                Thread.sleep(2000);
                long count=framesDecoded, now=System.nanoTime();
                long fps=(count-lastCount)/2; lastCount=count;
                long started=playStartedNanos;
                if(started!=0) {
                    Transport t=demux; long loss=t!=null?t.rtpLoss():0;
                    long sinceLastFrame=(now-lastFrameNanos)/1_000_000;
                    if(lastFrameNanos!=0&&sinceLastFrame>2000) note("STALL: no decoded frame for "+sinceLastFrame+"ms (fps="+fps+", rtpLoss="+loss+")");
                    else note("Playback stats: fps="+fps+" totalFrames="+count+" rtpLoss="+loss);
                    if(statsListener!=null) statsListener.onStats(fps,count,loss);
                    long newlyLost=loss-lastLoss;
                    if(newlyLost>=IDR_LOSS_THRESHOLD&&now-lastIdrRequestNanos>=IDR_MIN_INTERVAL_NANOS) { lastIdrRequestNanos=now; sendIdrRequest(newlyLost); }
                    lastLoss=loss;
                }
            }
        } catch(InterruptedException ignored) {}
    }
    /** WFD sink-initiated recovery: ask the source for a fresh IDR frame after detecting significant RTP
     * loss, per the WFA spec's wfd_idr_request (a bare flag in the SET_PARAMETER body, no "key: value" pair).
     * This can be called from the stats thread while the RTSP thread also sends/replies, hence synchronized
     * methods below. Rate-limited by the caller (see IDR_LOSS_THRESHOLD/IDR_MIN_INTERVAL_NANOS). */
    private void sendIdrRequest(long newlyLost) {
        if(session==null) return; // no session yet; nothing to recover
        try { request("SET_PARAMETER","Session: "+session+"\r\n","wfd_idr_request\r\n"); note("Requested IDR refresh after "+newlyLost+" newly lost RTP packet(s)"); }
        catch(IOException e) { note("IDR request failed: "+e); }
    }
    private synchronized void send(String first,String headers,String body) throws IOException {
        if(control==null) throw new IOException("No RTSP connection");
        byte[] bytes=body.getBytes(StandardCharsets.US_ASCII);
        String msg=first+"\r\n"+headers+(bytes.length>0?"Content-Type: text/parameters\r\n":"")+"Content-Length: "+bytes.length+"\r\n\r\n";
        OutputStream out=control.getOutputStream(); out.write(msg.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }
    private synchronized void request(String method,String headers,String body) throws IOException {
        int seq=cseq++; pending.put(seq,method);
        note("RTSP send "+method+" CSeq="+seq);
        send(method+" "+(method.equals("OPTIONS")?"*":uri)+" RTSP/1.0","CSeq: "+seq+"\r\n"+headers,body);
    }
    private synchronized void reply(Rtsp.Message m,int status,String headers,String body) throws IOException {
        String seq=m.headers.get("cseq"); if(seq==null||!seq.matches("[0-9]{1,10}")) throw new IOException("Invalid CSeq");
        send("RTSP/1.0 "+status+(status==200?" OK":" Not Acceptable"),"CSeq: "+seq+"\r\n"+headers,body);
    }
    private void handle(Rtsp.Message m) throws IOException {
        if(m.line.startsWith("RTSP/")) {
            int seq; try { seq=Integer.parseInt(m.headers.getOrDefault("cseq","-1")); } catch(NumberFormatException e) { throw new IOException("Bad response CSeq"); }
            String method=pending.remove(seq); note("RTSP response "+method+": "+m.line);
            if(!m.line.startsWith("RTSP/1.0 200 ")) {
                // The IDR-refresh request is best-effort recovery, not essential negotiation; a source that
                // doesn't support it should not tear down an otherwise-working session over it.
                if("SET_PARAMETER".equals(method)) { note("Source did not honor SET_PARAMETER (likely wfd_idr_request); continuing"); return; }
                throw new IOException("Source rejected "+method+": "+m.line);
            }
            if("SETUP".equals(method)) {
                session=m.headers.getOrDefault("session","").split(";",2)[0];
                if(!session.matches("[A-Za-z0-9._~-]{1,128}")) throw new IOException("Missing/invalid Session");
                request("PLAY","Session: "+session+"\r\n","");
            } else if("PLAY".equals(method)) { playStartedNanos=System.nanoTime(); note("PLAY accepted; waiting decoded frames (not yet proof of DeX)"); }
            return;
        }
        String method=m.line.split(" ",2)[0]; note("RTSP receive "+method);
        if(method.equals("OPTIONS")) { reply(m,200,"Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n",""); request("OPTIONS","Require: org.wfa.wfd1.0\r\n",""); }
        else if(method.equals("GET_PARAMETER")) {
            StringBuilder body=new StringBuilder();
            for(String line:m.body.split("\r?\n")) {
                String key=line.split(":",2)[0].trim(); String value;
                switch(key) {
                    // native=28 (CEA index 5, 720p30) kept as the safe fallback regardless of the user's
                    // chosen resolution cap; the CEA/VESA support bitmaps are what actually constrain which
                    // resolution the source can select.
                    case "wfd_video_formats": value=String.format(Locale.ROOT,"28 00 01 %02x %08x %08x 00000000 00 0000 0000 00 none none",levelSupport,ceaSupport,vesaSupport); break;
                    case "wfd_audio_codecs": value="none"; break;
                    case "wfd_client_rtp_ports": value="RTP/AVP/UDP;unicast "+rtp.getLocalPort()+" 0 mode=play"; break;
                    case "wfd_content_protection": case "wfd_uibc_capability": case "wfd_display_edid": case "wfd_coupled_sink": case "wfd_connector_type": value="none"; break;
                    default: continue;
                }
                body.append(key).append(": ").append(value).append("\r\n");
            }
            reply(m,200,"",body.toString());
        } else if(method.equals("SET_PARAMETER")) {
            Map<String,String> params=new HashMap<>();
            for(String line:m.body.split("\r?\n")) { int colon=line.indexOf(':'); if(colon>0) params.put(line.substring(0,colon).trim(),line.substring(colon+1).trim()); }
            if(params.containsKey("wfd_video_formats")) applySelectedFormat(params.get("wfd_video_formats"));
            if(params.containsKey("wfd_content_protection")&&!params.get("wfd_content_protection").equals("none")) { reply(m,406,"",""); throw new IOException("Source requested unsupported content protection"); }
            if(params.containsKey("wfd_audio_codecs")&&!params.get("wfd_audio_codecs").equals("none")) { reply(m,406,"",""); throw new IOException("Source selected unsupported audio: "+params.get("wfd_audio_codecs")); }
            String presentation=params.get("wfd_presentation_URL");
            if(presentation!=null) {
                String offered=presentation.split("\\s+",2)[0];
                try { URI parsed=URI.create(offered); if(!"rtsp".equals(parsed.getScheme())||parsed.getHost()==null||offered.length()>2048) throw new IllegalArgumentException(); uri=offered; }
                catch(IllegalArgumentException e) { throw new IOException("Invalid presentation URL"); }
            }
            reply(m,200,"","");
            String trigger=params.get("wfd_trigger_method");
            if("SETUP".equals(trigger)) request("SETUP","Transport: RTP/AVP/UDP;unicast;client_port="+rtp.getLocalPort()+"\r\n","");
            if("TEARDOWN".equals(trigger)) { note("Source requested teardown"); stop(); }
        } else if(method.equals("TEARDOWN")) { reply(m,200,"",""); stop(); }
        else reply(m,406,"","");
    }
    /** Parse the source's selected wfd_video_formats and (re)configure the decoder for the chosen resolution. */
    private void applySelectedFormat(String value) {
        note("Source selected video: "+value);
        try {
            String[] f=value.trim().split("\\s+");
            if(f.length<7) return;
            int cea=Integer.parseInt(f[4],16), vesa=Integer.parseInt(f[5],16), hh=Integer.parseInt(f[6],16);
            if(hh!=0) { note("Source selected an unsupported HH format; keeping fallback resolution"); return; }
            int[] res; int levelBit;
            if(cea!=0) {
                if(Integer.bitCount(cea)!=1) { note("Multiple CEA bits set in selection; keeping fallback resolution"); return; }
                int index=Integer.numberOfTrailingZeros(cea);
                if(!CEA.containsKey(index)) { note("Unrecognized/unsupported CEA index in selection; keeping fallback resolution"); return; }
                res=CEA.get(index); levelBit=CEA_LEVEL_BIT.getOrDefault(index,0x01);
            } else if(vesa!=0) {
                if(Integer.bitCount(vesa)!=1) { note("Multiple VESA bits set in selection; keeping fallback resolution"); return; }
                int index=Integer.numberOfTrailingZeros(vesa);
                if(!VESA.containsKey(index)) { note("Unrecognized/unsupported VESA index in selection; keeping fallback resolution"); return; }
                res=VESA.get(index); levelBit=VESA_LEVEL_BIT.getOrDefault(index,0x01);
            } else { note("No CEA/VESA bit set in selection; keeping fallback resolution"); return; }
            configureDecoder(res[0],res[1],levelBit);
        } catch(RuntimeException|IOException e) { note("Failed to apply selected video format, keeping fallback: "+e); }
    }
    private void receive(InetAddress peer,DatagramSocket socket) {
        // Do not drain the decoder here: this loop must keep reading the socket as fast as packets arrive.
        // Draining (and decode()'s input handoff) happens only when a full access unit completes, and never
        // blocks — see decode()'s use of a zero-timeout dequeueInputBuffer below.
        Transport demux=new Transport(this::decode); this.demux=demux;
        byte[] data=new byte[65535]; DatagramPacket packet=new DatagramPacket(data,data.length);
        long packets=0;
        try {
            while(running) {
                packet.setLength(data.length);
                try { socket.receive(packet); } catch(SocketTimeoutException timeout) { drain(); continue; }
                if(!peer.equals(packet.getAddress())) continue;
                if(++packets==1) note("First UDP media packet received");
                demux.accept(data,packet.getLength());
            }
        } catch(Exception e) { if(running) { note("Media failure: "+e.getClass().getSimpleName()+" "+e.getMessage()); stop(); } }
    }
    private void decode(byte[] bytes,long pts) {
        MediaCodec d=decoder; if(d==null) return;
        drain();
        // Zero timeout: never block the socket-reading thread waiting for a free input buffer. Fast motion
        // delivers bursts of access units in quick succession; blocking here (the previous 10ms timeout) let
        // the OS UDP receive buffer overflow and drop already-arrived packets, which looked like RTP/Wi-Fi
        // loss but was actually self-inflicted by this thread stalling instead of reading the socket.
        int index=d.dequeueInputBuffer(0);
        if(index<0) return;
        ByteBuffer buf=d.getInputBuffer(index);
        if(buf==null||bytes.length>buf.capacity()) { d.queueInputBuffer(index,0,0,pts,0); return; }
        buf.clear(); buf.put(bytes); d.queueInputBuffer(index,0,bytes.length,pts,0); drain();
    }
    private final MediaCodec.BufferInfo bufferInfo=new MediaCodec.BufferInfo();
    private void drain() {
        MediaCodec d=decoder; if(d==null) return;
        MediaCodec.BufferInfo info=bufferInfo;
        for(int i=0;i<32;i++) {
            int index; try { index=d.dequeueOutputBuffer(info,0); } catch(IllegalStateException e) { return; }
            if(index>=0) {
                d.releaseOutputBuffer(index,true);
                framesDecoded++; lastFrameNanos=System.nanoTime();
                if(!decoded) { decoded=true; note("First decoded video output rendered; verify visually whether DeX desktop or mirroring"); }
            }
            else if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out=d.getOutputFormat();
                note("Decoder output format: "+out);
                if(sizeListener!=null&&out.containsKey(MediaFormat.KEY_WIDTH)&&out.containsKey(MediaFormat.KEY_HEIGHT))
                    sizeListener.onVideoSize(out.getInteger(MediaFormat.KEY_WIDTH),out.getInteger(MediaFormat.KEY_HEIGHT));
            }
            else break;
        }
    }
}
