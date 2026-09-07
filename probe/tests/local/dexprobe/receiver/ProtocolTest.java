package local.dexprobe.receiver;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class ProtocolTest {
  static void check(boolean v) { if (!v) throw new AssertionError(); }
  public static void main(String[] args) throws Exception {
    String wire="GET_PARAMETER * RTSP/1.0\r\nCSeq: 9\r\nContent-Length: 3\r\n\r\nabcOPTIONS * RTSP/1.0\r\nCSeq: 10\r\n\r\n";
    InputStream in=new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII));
    Rtsp.Message m=Rtsp.read(in); check(m.body.equals("abc")); check(m.headers.get("cseq").equals("9"));
    check(Rtsp.read(in).line.startsWith("OPTIONS"));
    try { Rtsp.read(new ByteArrayInputStream("RTSP/1.0 200 OK\r\nContent-Length: 9999999\r\n\r\n".getBytes())); throw new AssertionError(); } catch(IOException expected) {}
    byte[] p=new byte[12+188]; p[0]=(byte)0x80; p[1]=33; p[12]=0x47;
    check(Transport.rtpPayload(p,p.length)==12); p[0]=0; check(Transport.rtpPayload(p,p.length)==-1);
    p[0]=(byte)0x90; check(Transport.rtpPayload(p,13)==-1);
    java.util.List<byte[]> frames=new java.util.ArrayList<>();
    Transport ts=new Transport((data,pts)->frames.add(data));
    // PAT maps program 1 to PMT PID 100; PMT maps AVC to PID 256.
    ts.accept(rtp(0,ts(0,0,true,new byte[]{0,0,(byte)0xb0,13,0,1,(byte)0xc1,0,0,0,1,(byte)0xe0,100,0,0,0,0})),200);
    ts.accept(rtp(1,ts(100,0,true,new byte[]{0,2,(byte)0xb0,18,0,1,(byte)0xc1,0,0,(byte)0xe1,0,(byte)0xf0,0,0x1b,(byte)0xe1,0,(byte)0xf0,0,0,0,0,0})),200);
    ts.accept(rtp(2,ts(256,0,true,new byte[]{0,0,1,(byte)0xe0,0,8,(byte)0x80,0,0,0,0,1,0x65,42})),200);
    check(frames.size()==1); check(java.util.Arrays.equals(frames.get(0),new byte[]{0,0,1,0x65,42}));
    byte[] lpcm=new byte[324]; lpcm[0]=(byte)0xa0; lpcm[1]=1; lpcm[3]=0x11; lpcm[4]=0x12; lpcm[5]=0x34; lpcm[6]=(byte)0xff; lpcm[7]=(byte)0xfe;
    short[] pcm=Lpcm.decode(lpcm); check(pcm.length==160); check(pcm[0]==0x1234&&pcm[1]==-2);
    lpcm[3]=0x09; check(Lpcm.decode(lpcm)==null); lpcm[3]=0x11;
    check(Lpcm.decode(java.util.Arrays.copyOf(lpcm,323))==null);
    check(Lpcm.decode(new byte[3])==null);
    java.util.List<byte[]> audio=new java.util.ArrayList<>();
    Transport av=new Transport((data,pts)->{},(data,pts)->audio.add(data));
    av.accept(rtp(0,ts(0,0,true,new byte[]{0,0,(byte)0xb0,13,0,1,(byte)0xc1,0,0,0,1,(byte)0xe0,100,0,0,0,0})),200);
    av.accept(rtp(1,ts(100,0,true,new byte[]{0,2,(byte)0xb0,18,0,1,(byte)0xc1,0,0,(byte)0xe1,0,(byte)0xf0,0,(byte)0x83,(byte)0xe1,1,(byte)0xf0,0,0,0,0,0})),200);
    byte[] apes=new byte[333]; apes[2]=1; apes[3]=(byte)0xbd; apes[4]=1; apes[5]=71; apes[6]=(byte)0x80; System.arraycopy(lpcm,0,apes,9,lpcm.length);
    av.accept(rtp(2,ts(257,0,true,java.util.Arrays.copyOfRange(apes,0,180))),200);
    av.accept(rtp(3,ts(257,1,false,java.util.Arrays.copyOfRange(apes,180,333))),200);
    check(audio.size()==1); check(java.util.Arrays.equals(audio.get(0),lpcm));
    av.accept(rtp(5,ts(257,2,true,java.util.Arrays.copyOfRange(apes,0,180))),200);
    check(av.rtpLoss()==1);
    av.accept(rtp(7,ts(257,3,false,java.util.Arrays.copyOfRange(apes,180,333))),200);
    check(audio.size()==1); check(av.rtpLoss()==2);
    av.accept(rtp(6,ts(257,4,false,new byte[20])),200); check(av.rtpLoss()==2);
    System.out.println("Protocol framing and malformed RTP checks passed");
  }
  static byte[] rtp(int seq,byte[] ts) { byte[] b=new byte[200]; b[0]=(byte)128; b[1]=33; b[3]=(byte)seq; System.arraycopy(ts,0,b,12,188); return b; }
  static byte[] ts(int pid,int cc,boolean start,byte[] payload) { byte[] b=new byte[188]; java.util.Arrays.fill(b,(byte)255); b[0]=0x47; b[1]=(byte)((pid>>8)|(start?64:0)); b[2]=(byte)pid; b[3]=(byte)(0x30|cc); int pad=183-payload.length; b[4]=(byte)pad; if(pad>0)b[5]=0; System.arraycopy(payload,0,b,5+pad,payload.length); return b; }
}
