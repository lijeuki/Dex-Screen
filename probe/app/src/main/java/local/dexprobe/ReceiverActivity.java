package local.dexprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import local.dexprobe.receiver.ReceiverEngine;
import rikka.shizuku.Shizuku;

/** Receiver UI (design: pen.dev "DexScreens" frames 03/02/05). Network/media stay in the ordinary
 * application UID. Three visual states share one screen: a connecting checklist before the first
 * frame decodes, a session pill + control dock once video is live, and a stop-confirmation overlay.
 * The elaborate fake-desktop mockup chrome from the source design is intentionally not reproduced
 * here: real DeX video already fills that space. */
public final class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    /** SurfaceView that letterboxes to the negotiated video's aspect ratio instead of stretching to fill. */
    private static final class AspectSurfaceView extends SurfaceView {
        private int videoWidth, videoHeight;
        AspectSurfaceView(Context c) { super(c); }
        void setVideoSize(int w, int h) {
            if (w <= 0 || h <= 0 || (w == videoWidth && h == videoHeight)) return;
            videoWidth = w; videoHeight = h;
            requestLayout();
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (videoWidth <= 0 || videoHeight <= 0) { super.onMeasure(widthSpec, heightSpec); return; }
            int pw = MeasureSpec.getSize(widthSpec), ph = MeasureSpec.getSize(heightSpec);
            float target = (float) videoWidth / videoHeight, avail = (float) pw / ph;
            int w, h;
            if (avail > target) { h = ph; w = Math.round(h * target); } else { w = pw; h = Math.round(w / target); }
            setMeasuredDimension(w, h);
        }
    }
    private AspectSurfaceView surface;
    private IProbe remote;
    private Shizuku.UserServiceArgs args;
    private ReceiverEngine engine;
    private boolean bound, stopping, destroyed, sessionLive;
    private String p2pReport = "", mediaReport = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // Connecting-checklist overlay
    private View connectingOverlay;
    private View[] checkDots; private TextView[] checkLabels;
    private TextView errorText; private View retryButton;
    private static final String[] CHECK_LABELS = {"Paired with device","RTSP connected","Decoder configured","Receiving video"};
    private final boolean[] checkDone = new boolean[CHECK_LABELS.length];

    // Live-session overlay
    private View sessionPill, controlDock, collapsedDock;
    private TextView pillLabel, fpsValue, resValue, lossValue, collapsedStats;

    // Stop-confirmation overlay
    private View stopDialogOverlay;

    private final IReceiverEvents events = new IReceiverEvents.Stub() {
        @Override public void onReport(String report) { runOnUiThread(() -> {
            p2pReport = report;
            if (report.contains("P2P group formed")) markDone(0);
            if (!sessionLive && report.contains("Session failed at")) showConnectingError(extractFailure(report));
            save();
        }); }
        @Override public void onGroup(String host, int port) {
            runOnUiThread(() -> {
                if (stopping || destroyed) return;
                if (engine != null) { log("Duplicate group callback ignored"); return; }
                engine = new ReceiverEngine(surface.getHolder().getSurface(), ReceiverActivity.this::log,
                        (w, h) -> runOnUiThread(() -> { surface.setVideoSize(w, h); resValue.setText(w + "×" + h); }),
                        (fps, total, loss) -> runOnUiThread(() -> {
                            fpsValue.setText(fps + " fps"); lossValue.setText(String.valueOf(loss));
                            lossValue.setTextColor(Ui.color(ReceiverActivity.this, loss > 0 ? R.color.warn : R.color.ok));
                            collapsedStats.setText(fps + " fps · loss " + loss);
                        }),
                        () -> runOnUiThread(ReceiverActivity.this::requestStop),
                        resolutionChoice());
                engine.start(host, port);
            });
        }
        @Override public void onEnded() { runOnUiThread(() -> { log("Shell P2P session ended"); release(); }); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (destroyed || stopping) { release(); return; }
            remote = IProbe.Stub.asInterface(service);
            io.execute(() -> {
                try { remote.startReceiver(events); }
                catch (Throwable t) { log("Session start failed: " + t); runOnUiThread(ReceiverActivity.this::release); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            log("Shell service disconnected; cleanup may be unconfirmed");
            release();
        }
    };
    /** Resolution cap chosen on the home screen (Intent extra "resolution", a ReceiverEngine.Resolution
     * enum name); defaults to 1080p if missing or unrecognized. */
    private ReceiverEngine.Resolution resolutionChoice() {
        String name = getIntent().getStringExtra("resolution");
        if (name == null) return ReceiverEngine.Resolution.FHD_1080P;
        try { return ReceiverEngine.Resolution.valueOf(name); } catch (IllegalArgumentException e) { return ReceiverEngine.Resolution.FHD_1080P; }
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getIntent().getBooleanExtra("stop", false)) { stopping = true; finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.overlay_bg));
        // Anchor to the top rather than centering: a 16:9 stream on this ~16:10 screen leaves one
        // letterbox gap instead of splitting it top+bottom, so the dock below has a real, undivided
        // strip to sit in without ever overlapping the actual video content.
        surface = new AspectSurfaceView(this); surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        root.addView(buildConnectingOverlay(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildSessionPill(), pillLayoutParams());
        root.addView(buildControlDock(), dockLayoutParams());
        root.addView(buildCollapsedDock(), dockLayoutParams());
        root.addView(buildStopDialog(), new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        args = new Shizuku.UserServiceArgs(new ComponentName(this, ProbeService.class))
                .daemon(false).processNameSuffix("shell_probe").debuggable(true).version(4);
        log("Waiting for a device to choose this tablet in Wireless DeX...");
    }

    // Stacked above the dock at the bottom instead of floating at the top: keeps every overlay in the
    // one letterbox strip so nothing sits on top of the actual video content.
    private FrameLayout.LayoutParams pillLayoutParams() { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); p.bottomMargin = Ui.dp(this, 66); return p; }
    // Hugs the bottom edge rather than floating with a big margin: with the video now top-anchored
    // (see onCreate), the letterbox gap for a 16:9 stream on this ~16:10 screen sits right at the
    // bottom edge, and this is sized to live inside that gap instead of overlapping the video above it.
    private FrameLayout.LayoutParams dockLayoutParams() { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); p.bottomMargin = Ui.dp(this, 6); return p; }

    private View buildConnectingOverlay() {
        FrameLayout wrap = new FrameLayout(this); wrap.setBackgroundColor(Ui.color(this, R.color.overlay_bg));
        LinearLayout card = Ui.card(this,Ui.color(this,R.color.overlay_card),Ui.color(this,R.color.overlay_border)); card.setGravity(Gravity.CENTER); card.setPadding(Ui.dp(this, 48), Ui.dp(this, 40), Ui.dp(this, 48), Ui.dp(this, 40));
        ProgressBar spinner = new ProgressBar(this); spinner.getIndeterminateDrawable().setColorFilter(Ui.color(this, R.color.overlay_accent), android.graphics.PorterDuff.Mode.SRC_IN);
        card.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        TextView title = Ui.text(this, "Connecting", 20, R.color.overlay_text_primary, 1);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2); titleLp.topMargin = Ui.dp(this, 20); card.addView(title, titleLp);
        TextView sub = Ui.text(this, "Open Wireless DeX on your phone and choose this tablet.", 13, R.color.overlay_text_muted, 0); sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(Ui.dp(this, 360), -2); subLp.topMargin = Ui.dp(this, 8); subLp.gravity = Gravity.CENTER_HORIZONTAL; card.addView(sub, subLp);
        LinearLayout steps = new LinearLayout(this); steps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(Ui.dp(this, 320), -2); stepsLp.topMargin = Ui.dp(this, 26); card.addView(steps, stepsLp);
        checkDots = new View[CHECK_LABELS.length]; checkLabels = new TextView[CHECK_LABELS.length];
        for (int i = 0; i < CHECK_LABELS.length; i++) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2); rowLp.topMargin = i == 0 ? 0 : Ui.dp(this, 14); steps.addView(row, rowLp);
            View dot = new View(this); dot.setBackground(Ui.circle(Ui.color(this, R.color.overlay_chip_bg)));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)); dotLp.rightMargin = Ui.dp(this, 14); row.addView(dot, dotLp);
            TextView label = Ui.text(this, CHECK_LABELS[i], 13, R.color.overlay_text_dim, 0); row.addView(label);
            checkDots[i] = dot; checkLabels[i] = label;
        }
        errorText = Ui.text(this, "", 13, R.color.danger, 1); errorText.setGravity(Gravity.CENTER); errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(Ui.dp(this, 360), -2); errLp.topMargin = Ui.dp(this, 20); errLp.gravity = Gravity.CENTER_HORIZONTAL; card.addView(errorText, errLp);
        LinearLayout backButton = Ui.button(this, 0, "Back to home", Ui.color(this, R.color.overlay_chip_bg), Ui.color(this, R.color.overlay_text_primary));
        backButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-2, -2); backLp.topMargin = Ui.dp(this, 20); card.addView(backButton, backLp);
        backButton.setOnClickListener(v -> {
            Intent home = new Intent(this, MainActivity.class);
            home.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
            finish();
        });
        this.retryButton = backButton;
        wrap.addView(card, new FrameLayout.LayoutParams(Ui.dp(this, 460), -2, Gravity.CENTER));
        connectingOverlay = wrap; return wrap;
    }
    private void showConnectingError(String message) {
        errorText.setText(message); errorText.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
    }
    /** Pulls the human-readable cause out of a P2pSession report: the exception message if present,
     * otherwise the "Session failed at <stage>" line itself. */
    private static String extractFailure(String report) {
        for (String line : report.split("\n")) {
            String t = line.trim();
            if (t.contains("Exception") && t.contains(": ")) return t.substring(t.indexOf(": ") + 2);
        }
        for (String line : report.split("\n")) if (line.contains("Session failed at")) return line.replaceFirst("^\\+\\d+ms ", "");
        return "Session ended unexpectedly.";
    }
    private void markDone(int index) {
        if (index < 0 || index >= checkDone.length || checkDone[index]) return;
        checkDone[index] = true;
        View dot = checkDots[index]; TextView label = checkLabels[index];
        dot.setBackground(Ui.circle(Ui.color(this, R.color.ok)));
        label.setTextColor(Ui.color(this, R.color.overlay_text_primary));
        if (index == CHECK_LABELS.length - 1) enterLiveSession();
    }
    private void enterLiveSession() {
        if (sessionLive) return; sessionLive = true;
        connectingOverlay.animate().alpha(0f).setDuration(220).withEndAction(() -> connectingOverlay.setVisibility(View.GONE)).start();
        sessionPill.setVisibility(View.VISIBLE); sessionPill.setAlpha(0f); sessionPill.animate().alpha(1f).setDuration(220).start();
        controlDock.setVisibility(View.VISIBLE); controlDock.setAlpha(0f); controlDock.animate().alpha(1f).setDuration(220).start();
    }

    private View buildSessionPill() {
        LinearLayout pill = new LinearLayout(this); pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.rounded(Ui.color(this, R.color.dock_bg), 24, this)); pill.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        pill.setVisibility(View.GONE); sessionPill = pill;
        View dot = new View(this); dot.setBackground(Ui.circle(Ui.color(this, R.color.ok)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8)); dotLp.rightMargin = Ui.dp(this, 10); pill.addView(dot, dotLp);
        pillLabel = Ui.text(this, "Receiving", 13, R.color.overlay_text_primary, 1); pill.addView(pillLabel);
        return pill;
    }

    private View buildControlDock() {
        LinearLayout dock = new LinearLayout(this); dock.setOrientation(LinearLayout.HORIZONTAL); dock.setGravity(Gravity.CENTER_VERTICAL);
        // Kept deliberately compact (vs. a taller "normal" bar) so it has a real chance of fitting inside
        // a 16:9-on-16:10 letterbox gap (~60-70dp on this hardware) without overlapping the video above it.
        dock.setBackground(Ui.rounded(Ui.color(this, R.color.dock_bg), 16, this)); dock.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        dock.setVisibility(View.GONE); controlDock = dock;
        LinearLayout stop = Ui.button(this, R.drawable.ic_stop, "Stop receiving", Ui.color(this, R.color.danger), 0xFFFFFFFF, 8, 12, 14);
        dock.addView(stop);
        dock.addView(Ui.divider(this, true), sideMargin(1, 12));
        fpsValue = statCell(dock, "0 fps", "FRAME RATE");
        dock.addView(Ui.divider(this, true), sideMargin(1, 12));
        resValue = statCell(dock, "—", "RESOLUTION");
        dock.addView(Ui.divider(this, true), sideMargin(1, 12));
        lossValue = statCell(dock, "0", "PACKET LOSS");
        dock.addView(Ui.divider(this, true), sideMargin(1, 12));
        View collapse = iconButton(R.drawable.ic_chevron_down);
        dock.addView(collapse);
        stop.setOnClickListener(v -> showStopDialog());
        collapse.setOnClickListener(v -> setDockCollapsed(true));
        return dock;
    }
    /** Compact bar (design: pen.dev "02b Active Session — Dock Collapsed"): icon-only stop, one combined
     * stat line, and an expand button — for when the full dock covers too much of the projected screen. */
    private View buildCollapsedDock() {
        LinearLayout dock = new LinearLayout(this); dock.setOrientation(LinearLayout.HORIZONTAL); dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(Ui.rounded(Ui.color(this, R.color.dock_bg), 20, this)); dock.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        dock.setVisibility(View.GONE); collapsedDock = dock;
        LinearLayout stop = new LinearLayout(this); stop.setGravity(Gravity.CENTER); stop.setBackground(Ui.circle(Ui.color(this, R.color.danger)));
        stop.addView(Ui.icon(this, R.drawable.ic_stop, 18, 0xFFFFFFFF));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)); stopLp.rightMargin = Ui.dp(this, 12); dock.addView(stop, stopLp);
        collapsedStats = Ui.text(this, "0 fps · loss 0", 13, R.color.overlay_text_primary, 1); dock.addView(collapsedStats);
        LinearLayout.LayoutParams divLp = sideMargin(1, 14); dock.addView(Ui.divider(this, true), divLp);
        View expand = iconButton(R.drawable.ic_chevron_down); expand.setRotation(180f);
        dock.addView(expand);
        stop.setOnClickListener(v -> showStopDialog());
        expand.setOnClickListener(v -> setDockCollapsed(false));
        return dock;
    }
    private View iconButton(int drawableRes) {
        FrameLayout btn = new FrameLayout(this); btn.setBackground(Ui.circle(Ui.color(this, R.color.overlay_chip_bg))); btn.setClickable(true); btn.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true); btn.setForeground(getDrawable(tv.resourceId));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)); iconLp.gravity = Gravity.CENTER;
        btn.addView(Ui.icon(this, drawableRes, 16, Ui.color(this, R.color.overlay_text_muted)), iconLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)); btn.setLayoutParams(lp);
        return btn;
    }
    private void setDockCollapsed(boolean collapsed) {
        controlDock.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        collapsedDock.setVisibility(collapsed ? View.VISIBLE : View.GONE);
    }
    private LinearLayout.LayoutParams sideMargin(int widthDp, int marginDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, widthDp), Ui.dp(this, 22)); p.leftMargin = p.rightMargin = Ui.dp(this, marginDp); return p; }
    private TextView statCell(LinearLayout dock, String value, String label) {
        LinearLayout col = Ui.dockStat(this, value, Ui.color(this, R.color.overlay_text_primary), label);
        dock.addView(col, new LinearLayout.LayoutParams(-2, -2));
        return (TextView) col.getChildAt(0);
    }

    private View buildStopDialog() {
        FrameLayout scrim = new FrameLayout(this); scrim.setBackgroundColor(0xD90F1730); scrim.setVisibility(View.GONE); scrim.setClickable(true);
        stopDialogOverlay = scrim;
        LinearLayout card = Ui.card(this,Ui.color(this,R.color.overlay_card),Ui.color(this,R.color.overlay_border));
        card.setPadding(Ui.dp(this, 32), Ui.dp(this, 32), Ui.dp(this, 32), Ui.dp(this, 28));
        TextView title = Ui.text(this, "Stop receiving?", 20, R.color.overlay_text_primary, 1); card.addView(title);
        TextView body = Ui.text(this, "The connection to this device will end and the tablet returns to standby.", 13, R.color.overlay_text_muted, 0);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(Ui.dp(this, 360), -2); bodyLp.topMargin = Ui.dp(this, 10); card.addView(body, bodyLp);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-2, -2); actionsLp.topMargin = Ui.dp(this, 26); card.addView(actions, actionsLp);
        LinearLayout keep = Ui.button(this, 0, "Keep receiving", Ui.color(this, R.color.overlay_chip_bg), Ui.color(this, R.color.overlay_text_primary));
        LinearLayout stopNow = Ui.button(this, R.drawable.ic_stop, "Stop now", Ui.color(this, R.color.danger), 0xFFFFFFFF);
        LinearLayout.LayoutParams stopNowLp = new LinearLayout.LayoutParams(-2, -2); stopNowLp.leftMargin = Ui.dp(this, 12);
        actions.addView(keep); actions.addView(stopNow, stopNowLp);
        keep.setOnClickListener(v -> stopDialogOverlay.setVisibility(View.GONE));
        stopNow.setOnClickListener(v -> { stopDialogOverlay.setVisibility(View.GONE); requestStop(); });
        scrim.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return scrim;
    }
    private void showStopDialog() { stopDialogOverlay.setVisibility(View.VISIBLE); }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (bound || stopping) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED || Shizuku.getUid() != 2000) {
                log("Authorize the diagnostic probe with shell Shizuku first."); return;
            }
            bound = true;
            Shizuku.bindUserService(args, connection);
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (bound && remote == null) { log("Receiver service bind TIMEOUT after 15s"); release(); }
            }, 15000);
        } catch (Throwable t) { log("Bind failure: " + t); release(); }
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { requestStop(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("stop", false)) requestStop();
    }
    private void requestStop() {
        if (stopping) return;
        stopping = true;
        log("Stopping media and restoring P2P configuration...");
        if (engine != null) { engine.stop(); engine = null; }
        IProbe current = remote;
        if (current != null) io.execute(() -> { try { current.stopReceiver(); } catch (Exception e) { log("Stop request failed: " + e); } });
        else release();
    }
    private void release() {
        stopping = true;
        if (engine != null) { engine.stop(); engine = null; }
        if (bound) {
            bound = false;
            try { Shizuku.unbindUserService(args, connection, true); } catch (Exception e) { log("Unbind failed: " + e); }
        }
        if (destroyed) io.shutdown();
        // Only auto-return home after a session that was genuinely live and ended on its own; an early
        // setup/negotiation failure instead leaves the connecting screen up with the real error visible
        // (see showConnectingError) and a manual "Back to home" button, rather than silently vanishing.
        // Navigates explicitly to MainActivity rather than relying on the implicit back stack — a plain
        // finish() could drop the user out of the app entirely depending on how this activity was entered.
        if (sessionLive) {
            Intent home = new Intent(this, MainActivity.class);
            home.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
            finish();
        }
    }
    private void log(String line) {
        runOnUiThread(() -> {
            String redacted = Report.redact(line);
            mediaReport += java.time.Instant.now() + " " + redacted + "\n";
            if (mediaReport.length() > 24000) mediaReport = mediaReport.substring(mediaReport.length() - 24000);
            if (redacted.contains("RTSP connected")) markDone(1);
            if (redacted.contains("Decoder configured")) markDone(2);
            if (redacted.contains("First decoded video output rendered")) { markDone(2); markDone(3); pillLabel.setText("Receiving · Wireless DeX"); }
            if (!sessionLive) {
                String lower = redacted.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("fail") || lower.contains("timeout") || lower.contains("authorize") || (lower.contains("disconnected") && !lower.contains("duplicate"))) showConnectingError(redacted);
            }
            save();
        });
    }
    private void save() {
        String text = "EXPERIMENTAL RECEIVER\n" + p2pReport + "\nMedia:\n" + mediaReport;
        if (!io.isShutdown()) io.execute(() -> {
            try { Files.write(new File(getFilesDir(), "receiver.txt").toPath(), text.getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        });
    }
    @Override public void onDestroy() { destroyed = true; requestStop(); if (!bound) io.shutdown(); super.onDestroy(); }
}
