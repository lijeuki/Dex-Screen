package local.dexprobe.receiver;

/** Wi-Fi Display LPCM payload: four header bytes, then network-endian signed PCM. */
final class Lpcm {
    private Lpcm() {}
    static short[] decode(byte[] payload) {
        if(payload.length<4||(payload[0]&255)!=0xa0||(payload[3]&255)!=0x11) return null;
        // 48 kHz, 16-bit stereo: 80 sample frames (320 bytes) per audio access unit.
        int bytes=(payload[1]&255)*320;
        if(bytes==0||payload.length!=bytes+4||(payload[2]&1)!=0) return null;
        short[] pcm=new short[bytes/2];
        for(int i=0;i<pcm.length;i++) pcm[i]=(short)(((payload[4+2*i]&255)<<8)|(payload[5+2*i]&255));
        return pcm;
    }
}
