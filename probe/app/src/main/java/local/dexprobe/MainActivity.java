package local.dexprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** "Ready to receive" home screen (design: pen.dev "DexScreens", frame 01). Primary action starts
 * the receiver directly; the original Shizuku diagnostic probe stays available as a secondary,
 * low-emphasis link rather than a first-class button, since it is a debugging tool, not the
 * product's main flow. */
public final class MainActivity extends Activity {
    private TextView statusPillLabel,diagnosticOutput;
    private View statusDot;
    private boolean busy,connected,bound,destroyed,autoRunConsumed;
    private Runnable bindTimeout;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Shizuku.UserServiceArgs args;
    private final Shizuku.OnBinderReceivedListener received=()->{
        setReady(true);
        if(getIntent().getBooleanExtra("run",false)&&!autoRunConsumed) { autoRunConsumed=true; runDiagnostic(); }
    };
    private final Shizuku.OnBinderDeadListener dead=()->setReady(false);
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{
        if(code==10&&result==PackageManager.PERMISSION_GRANTED) bind();
        else { busy=false; showDiagnostic("Shizuku permission denied. No probe performed."); }
    };
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            if(destroyed||!busy||!bound) { unbind(); return; }
            connected=true;
            if(bindTimeout!=null) main.removeCallbacks(bindTimeout);
            showDiagnostic("Shell service connected. Running diagnostic probe...");
            worker.execute(()->{
                String report;
                try { report=IProbe.Stub.asInterface(binder).runProbe(); }
                catch(Throwable t) { Report r=new Report(); r.error("Binder call; restoration unknown",t); report=r.toString(); }
                boolean saved=false;
                try { Files.write(new File(getFilesDir(),"diagnostic.txt").toPath(),report.getBytes(StandardCharsets.UTF_8)); saved=true; }
                catch(Throwable t) { report+="\nSAVE_FAILED "+t.getClass().getName(); }
                final String result=report; final boolean stored=saved;
                main.post(()->{
                    showDiagnostic(result+(stored?"\nSaved: app-private files/diagnostic.txt":"\nNot saved. Any prior file is stale."));
                    unbind(); busy=false;
                    if(destroyed) worker.shutdown();
                });
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            connected=false;
            if(busy) showDiagnostic("UserService disconnected unexpectedly. Result/restoration unconfirmed.");
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(Ui.color(this,R.color.home_bg));
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Ui.color(this,R.color.home_bg));
        int padX=Ui.dp(this,48),padY=Ui.dp(this,32);

        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padX,padY,padX,padY);
        root.addView(content,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headerText=new LinearLayout(this); headerText.setOrientation(LinearLayout.VERTICAL);
        headerText.addView(Ui.caption(this,"This device",R.color.home_header_caption));
        TextView deviceTitle=Ui.text(this,Build.MODEL,26,R.color.home_header_text,1); deviceTitle.setPadding(0,Ui.dp(this,4),0,0);
        headerText.addView(deviceTitle);
        header.addView(headerText,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout pill=Ui.pill(this,Ui.color(this,R.color.home_header_dim),"Starting…",Ui.color(this,R.color.home_header_caption),Ui.color(this,R.color.home_header_chip_bg));
        statusDot=pill.getChildAt(0); statusPillLabel=(TextView)pill.getChildAt(1);
        header.addView(pill);
        content.addView(header,new LinearLayout.LayoutParams(-1,-2));

        FrameLayout cardWrap=new FrameLayout(this);
        LinearLayout.LayoutParams cardWrapLp=new LinearLayout.LayoutParams(-1,0,1); cardWrapLp.topMargin=Ui.dp(this,28);
        content.addView(cardWrap,cardWrapLp);
        LinearLayout card=Ui.card(this,Ui.color(this,R.color.home_card),Ui.color(this,R.color.home_border)); card.setGravity(Gravity.CENTER);
        cardWrap.addView(card,new FrameLayout.LayoutParams(-1,-1));

        FrameLayout emblemOuter=new FrameLayout(this); emblemOuter.setBackground(Ui.circle(0x1A4C6FFF)); emblemOuter.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this,96),Ui.dp(this,96)));
        FrameLayout emblemInner=new FrameLayout(this); emblemInner.setBackground(Ui.circle(Ui.color(this,R.color.home_accent)));
        FrameLayout.LayoutParams innerLp=new FrameLayout.LayoutParams(Ui.dp(this,62),Ui.dp(this,62)); innerLp.gravity=Gravity.CENTER; emblemOuter.addView(emblemInner,innerLp);
        FrameLayout.LayoutParams glyphLp=new FrameLayout.LayoutParams(Ui.dp(this,28),Ui.dp(this,28)); glyphLp.gravity=Gravity.CENTER;
        emblemInner.addView(Ui.icon(this,R.drawable.ic_cast,28,0xFFFFFFFF),glyphLp);
        card.addView(emblemOuter);

        TextView title=Ui.text(this,"Ready to receive",26,R.color.home_text_primary,1); title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(-2,-2); titleLp.topMargin=Ui.dp(this,24); card.addView(title,titleLp);

        TextView subtitle=Ui.text(this,"Open Wireless DeX on your phone and choose this tablet.",15,R.color.home_text_muted,0);
        subtitle.setGravity(Gravity.CENTER); LinearLayout.LayoutParams subLp=new LinearLayout.LayoutParams(Ui.dp(this,420),-2); subLp.topMargin=Ui.dp(this,10); subLp.gravity=Gravity.CENTER_HORIZONTAL;
        card.addView(subtitle,subLp);

        LinearLayout resRow=new LinearLayout(this); resRow.setOrientation(LinearLayout.HORIZONTAL); resRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams resRowLp=new LinearLayout.LayoutParams(-2,-2); resRowLp.topMargin=Ui.dp(this,24); card.addView(resRow,resRowLp);
        String[] resNames={"HD_720P","HDPLUS_900P","FHD_1080P"}; String[] resLabels={"720p","900p","1080p"};
        TextView[] resChips=new TextView[resNames.length];
        for(int i=0;i<resNames.length;i++) {
            TextView chip=Ui.text(this,resLabels[i],13,R.color.home_text_muted,1); chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this,20),Ui.dp(this,10),Ui.dp(this,20),Ui.dp(this,10));
            LinearLayout.LayoutParams chipLp=new LinearLayout.LayoutParams(-2,-2); if(i>0) chipLp.leftMargin=Ui.dp(this,8);
            resRow.addView(chip,chipLp); resChips[i]=chip;
        }
        final int[] selected={2}; // default 1080p, matching prior fixed behavior
        Runnable refreshChips=()->{
            for(int i=0;i<resChips.length;i++) {
                boolean on=i==selected[0];
                resChips[i].setBackground(on?Ui.rounded(Ui.color(this,R.color.home_accent),10,this):Ui.rounded(Ui.color(this,R.color.home_chip_bg),10,this));
                resChips[i].setTextColor(on?0xFFFFFFFF:Ui.color(this,R.color.home_text_muted));
            }
        };
        refreshChips.run();
        for(int i=0;i<resChips.length;i++) { int idx=i; resChips[i].setOnClickListener(v->{ selected[0]=idx; refreshChips.run(); }); }

        LinearLayout startButton=Ui.button(this,R.drawable.ic_play,"Start receiving",Ui.color(this,R.color.home_accent),0xFFFFFFFF);
        LinearLayout.LayoutParams startLp=new LinearLayout.LayoutParams(-2,-2); startLp.topMargin=Ui.dp(this,20); card.addView(startButton,startLp);
        startButton.setOnClickListener(v->{
            Intent i=new Intent(this,ReceiverActivity.class); i.putExtra("resolution",resNames[selected[0]]);
            startActivity(i);
        });

        TextView diagnosticLink=Ui.text(this,"Run diagnostic probe",13,R.color.home_text_dim,0); diagnosticLink.setPaintFlags(diagnosticLink.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams linkLp=new LinearLayout.LayoutParams(-2,-2); linkLp.topMargin=Ui.dp(this,36); card.addView(diagnosticLink,linkLp);
        diagnosticLink.setOnClickListener(v->runDiagnostic());

        diagnosticOutput=Ui.text(this,"",12,R.color.home_text_dim,0); diagnosticOutput.setGravity(Gravity.CENTER); diagnosticOutput.setTypeface(Typeface.MONOSPACE);
        diagnosticOutput.setMaxLines(2); diagnosticOutput.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams outLp=new LinearLayout.LayoutParams(Ui.dp(this,460),-2); outLp.topMargin=Ui.dp(this,14); outLp.gravity=Gravity.CENTER_HORIZONTAL;
        card.addView(diagnosticOutput,outLp);

        TextView copyright=Ui.text(this,"© lijeuki 2026",11,R.color.home_header_dim,0); copyright.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams copyrightLp=new LinearLayout.LayoutParams(-1,-2); copyrightLp.topMargin=Ui.dp(this,16); content.addView(copyright,copyrightLp);

        setContentView(root);
        args=new Shizuku.UserServiceArgs(new ComponentName(this,ProbeService.class)).daemon(false).processNameSuffix("shell_probe").debuggable(true).version(4);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }
    private void setReady(boolean ready) {
        statusDot.setBackground(Ui.circle(Ui.color(this,ready?R.color.ok:R.color.home_header_dim)));
        statusPillLabel.setText(ready?"Shizuku ready":"Waiting for Shizuku");
        statusPillLabel.setTextColor(Ui.color(this,ready?R.color.ok_light:R.color.home_header_caption));
    }
    private void runDiagnostic() {
        if(busy) return;
        try {
            if(!Shizuku.pingBinder()) { showDiagnostic("Shizuku is unavailable. Start Shizuku through ADB first."); return; }
            if(Shizuku.getUid()!=2000) { showDiagnostic("Refused: Shizuku must run with shell UID 2000."); return; }
            busy=true;
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED) bind();
            else { showDiagnostic("Please approve the normal Shizuku authorization dialog."); Shizuku.requestPermission(10); }
        } catch(Throwable t) { busy=false; showDiagnostic("Setup failure: "+Report.redact(t.toString())); }
    }
    private void bind() {
        try {
            showDiagnostic("Starting shell UserService...");
            connected=false;
            Shizuku.bindUserService(args,connection); bound=true;
            bindTimeout=()->{ if(busy&&!connected) { showDiagnostic("USER_SERVICE_BIND_TIMEOUT after 15s. No API result. Retry after checking Shizuku."); unbind(); busy=false; } };
            main.postDelayed(bindTimeout,15000);
        } catch(Throwable t) { busy=false; showDiagnostic("Bind failed: "+Report.redact(t.toString())); }
    }
    private void unbind() {
        if(bindTimeout!=null) main.removeCallbacks(bindTimeout);
        if(!bound) return; bound=false;
        try { Shizuku.unbindUserService(args,connection,true); } catch(Throwable t) { showDiagnostic("UserService cleanup: "+Report.redact(t.toString())); }
    }
    private void showDiagnostic(String message) {
        String[] lines=message.trim().split("\n");
        diagnosticOutput.setText(lines[lines.length-1]);
    }
    @Override public void onDestroy() {
        destroyed=true;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        if(!busy||!connected) { unbind(); worker.shutdown(); }
        super.onDestroy();
    }
}
