package local.dexprobe.receiver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independently written, bounded RTSP message framing. */
final class Rtsp {
    static final class Message {
        String line, body;
        final Map<String,String> headers=new LinkedHashMap<>();
    }
    static Message read(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int state=0;
        while(state!=4) {
            int c=in.read(); if(c<0) throw new EOFException("RTSP EOF");
            b.write(c); if(b.size()>32768) throw new IOException("RTSP header limit");
            state=c==(state==0||state==2?'\r':'\n')?state+1:(c=='\r'?1:0);
        }
        String[] lines=b.toString("US-ASCII").split("\r\n");
        Message m=new Message(); m.line=lines[0];
        for(int i=1;i<lines.length;i++) {
            int colon=lines[i].indexOf(':'); if(colon<1) throw new IOException("Malformed RTSP header");
            String key=lines[i].substring(0,colon).trim().toLowerCase(Locale.ROOT);
            if(m.headers.put(key,lines[i].substring(colon+1).trim())!=null) throw new IOException("Duplicate RTSP header");
        }
        int n;
        try { n=Integer.parseInt(m.headers.getOrDefault("content-length","0")); }
        catch(NumberFormatException e) { throw new IOException("Bad content length"); }
        if(n<0||n>65536) throw new IOException("RTSP body limit");
        byte[] body=new byte[n]; int pos=0;
        while(pos<n) { int k=in.read(body,pos,n-pos); if(k<0) throw new EOFException(); pos+=k; }
        m.body=new String(body,StandardCharsets.US_ASCII); return m;
    }
}
