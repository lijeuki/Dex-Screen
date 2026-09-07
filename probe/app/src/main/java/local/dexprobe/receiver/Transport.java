package local.dexprobe.receiver;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** RTP MPEG-TS demux. Loss discards incomplete PES rather than feeding damaged pictures. */
final class Transport {
    interface Video { void packet(byte[] annexB,long ptsUs); }
    private final Pes video,audio;
    private int pmt=-1,pid=-1,audioPid=-1,sequence=-1;
    private long lost;
    Transport(Video video) { this(video,(data,pts)->{}); }
    Transport(Video video,Video audio) { this.video=new Pes(video,false); this.audio=new Pes(audio,true); }
    long rtpLoss() { return lost; }
    static int rtpPayload(byte[] d,int n) {
        if(n<12||(d[0]&0xc0)!=0x80||(d[1]&127)!=33) return -1;
        int off=12+(d[0]&15)*4;
        if(off>n) return -1;
        if((d[0]&16)!=0) { if(off+4>n) return -1; off+=4+(((d[off+2]&255)<<8)|(d[off+3]&255))*4; }
        return off<=n?off:-1;
    }
    void accept(byte[] d,int n) {
        int off=rtpPayload(d,n); if(off<0) return;
        if((d[0]&32)!=0) { int padding=d[n-1]&255; if(padding==0||padding>n-off) return; n-=padding; }
        int seq=((d[2]&255)<<8)|(d[3]&255);
        if(sequence==seq) return;
        if(sequence>=0) {
            int delta=(seq-sequence)&65535;
            if(delta>32767) return; // stale/out-of-order; do not move the receive sequence backwards
            if(delta>1) { lost+=delta-1; video.reset(); audio.reset(); }
        }
        sequence=seq;
        if((n-off)%188!=0) return;
        for(;off+188<=n;off+=188) ts(d,off);
    }
    private void ts(byte[] d,int base) {
        int end=base+188;
        if(d[base]!=0x47||(d[base+1]&0x80)!=0) return;
        int id=((d[base+1]&31)<<8)|(d[base+2]&255), flags=(d[base+3]>>4)&3;
        boolean start=(d[base+1]&64)!=0;
        if(flags==0||flags==2||(d[base+3]&0xc0)!=0) return;
        int at=base+4;
        if(flags==3) at+=1+(d[at]&255);
        if(at>=end) return;
        if(id==0||id==pmt) {
            // PSI sections fitting one TS packet; fragmented tables are intentionally unsupported.
            if(!start) return; at+=1+(d[at]&255); if(at+8>end) return;
            int length=((d[at+1]&15)<<8)|(d[at+2]&255), limit=at+3+length-4;
            if(limit>end||limit<at+8) return;
            if(id==0&&d[at]==0) {
                for(int j=at+8;j+4<=limit;j+=4) if(d[j]!=0||d[j+1]!=0) { pmt=((d[j+2]&31)<<8)|(d[j+3]&255); break; }
            } else if(id==pmt&&d[at]==2&&at+12<=limit) {
                int j=at+12+(((d[at+10]&15)<<8)|(d[at+11]&255));
                while(j+5<=limit) {
                    int type=d[j]&255, streamPid=((d[j+1]&31)<<8)|(d[j+2]&255);
                    int next=j+5+(((d[j+3]&15)<<8)|(d[j+4]&255));
                    if(next>limit) return;
                    if(type==0x1b) pid=streamPid;
                    if(type==0x83) audioPid=streamPid;
                    j=next;
                }
            }
        } else if(id==pid) video.append(d,base,at,end,start);
        else if(id==audioPid) audio.append(d,base,at,end,start);
    }
    private static final class Pes {
        private final Video target;
        private final boolean audio;
        private final ByteArrayOutputStream pes=new ByteArrayOutputStream();
        private int continuity=-1,expectedPes;
        Pes(Video target,boolean audio) { this.target=target; this.audio=audio; }
        void reset() { pes.reset(); continuity=-1; expectedPes=0; }
        void append(byte[] d,int base,int at,int end,boolean start) {
            int cc=d[base+3]&15;
            if(continuity==cc) return;
            if(continuity>=0&&cc!=((continuity+1)&15)) pes.reset();
            continuity=cc;
            if(start) { emit(); pes.reset(); expectedPes=0; }
            if(!start&&pes.size()==0) return;
            if(pes.size()+end-at>2*1024*1024) { pes.reset(); return; }
            pes.write(d,at,end-at);
            if(start&&end-at>=6) expectedPes=((d[at+4]&255)<<8)|(d[at+5]&255);
            if(expectedPes>0&&pes.size()>=expectedPes+6) { emit(); pes.reset(); }
        }
        private void emit() {
            byte[] b=pes.toByteArray();
            if(b.length<9||b[0]!=0||b[1]!=0||b[2]!=1) return;
            if(audio?(b[3]&255)!=0xbd:(b[3]&0xf0)!=0xe0) return;
            int off=9+(b[8]&255); if(off>=b.length) return;
            long pts=0;
            if((b[7]&128)!=0&&b.length>=14) pts=(((long)(b[9]&14))<<29)|((long)(b[10]&255)<<22)|((long)(b[11]&254)<<14)|((long)(b[12]&255)<<7)|((b[13]&254)>>1);
            int size=((b[4]&255)<<8)|(b[5]&255); int end=size==0?b.length:Math.min(b.length,size+6);
            if(size>0&&b.length<size+6) return;
            if(end>off) target.packet(Arrays.copyOfRange(b,off,end),pts*100/9);
        }
    }
}
