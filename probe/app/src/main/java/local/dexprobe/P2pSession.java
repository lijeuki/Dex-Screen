package local.dexprobe;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.HandlerThread;
import android.os.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One user-initiated WFD session; standard Android pairing dialogs remain in control. */
final class P2pSession implements Runnable {
    private final Context supplied;
    private final IReceiverEvents events;
    private final Runnable ended;
    private final Report report = new Report();
    private volatile boolean stop;
    P2pSession(Context supplied, IReceiverEvents events, Runnable ended) {
        this.supplied = supplied; this.events = events; this.ended = ended;
    }
    void stop() { stop = true; }
    private void log(String value) {
        report.add(value);
        try { events.onReport(report.toString()); } catch (Exception e) { stop = true; }
    }
    // Lint models the APK UID; these calls run in a verified UID-2000 UserService.
    // Runtime shell permissions and Android service checks remain enforced.
    @android.annotation.SuppressLint("MissingPermission")
    @Override public void run() {
        WifiP2pManager manager = null;
        WifiP2pManager.Channel channel = null;
        HandlerThread callbacks = new HandlerThread("receiver-p2p-callbacks");
        WifiP2pWfdInfo previous = null;
        boolean changed = false, discovery = false, listening = false, listeningAttempted = false, formed = false;
        String stage = "session identity";
        try {
            if (Process.myUid() != 2000) throw new SecurityException("Only shell UID 2000 is supported");
            Context context = new ShellContext(supplied.createPackageContext("com.android.shell", 0));
            if (context.checkPermission("android.permission.CONFIGURE_WIFI_DISPLAY", Process.myPid(), Process.myUid()) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Shell lacks CONFIGURE_WIFI_DISPLAY");
            }
            log("Receiver session UID=2000, attribution=com.android.shell. Standard pairing approval required.");
            callbacks.start();
            manager = context.getSystemService(WifiP2pManager.class);
            if (manager == null) throw new IllegalStateException("No WifiP2pManager");
            channel = manager.initialize(context, callbacks.getLooper(), () -> { log("Channel disconnected"); stop = true; });
            if (channel == null) throw new IllegalStateException("Channel initialization failed");
            stage = "initial connection state";
            Result<WifiP2pInfo> initial = new Result<>(stage);
            manager.requestConnectionInfo(channel, initial::accept);
            WifiP2pInfo info = initial.get();
            if (info == null || info.groupFormed) throw new IllegalStateException("Missing connection state or existing group; no session started");
            stage = "WFD baseline";
            // After a prolonged idle period the framework fully disables P2P (dumpsys wifip2p:
            // P2pDisabledState) and, observed on hardware, this can wipe the local device's WfdInfo object
            // entirely rather than just disabling it (dumpsys wifip2p: "wfdInfo: null" on thisDevice, not a
            // disabled-but-present object). A brief retry covers the framework still cold-starting; if it
            // genuinely never appears, that means no prior WFD configuration exists, which is a safe state
            // to proceed from (nothing to restore later), not a fatal error.
            WifiP2pDevice baseline = null;
            for (int attempt = 1; attempt <= 5 && !stop; attempt++) {
                Result<WifiP2pDevice> device = new Result<>(stage);
                manager.requestDeviceInfo(channel, device::accept);
                baseline = device.get();
                if (baseline == null) throw new IllegalStateException("No local P2P device info; framework unavailable");
                if (baseline.getWfdInfo() != null) break;
                if (attempt < 5) { log("No WFD baseline yet (attempt " + attempt + "/5); P2P may still be cold-starting, retrying"); Thread.sleep(600); }
            }
            if (baseline.getWfdInfo() == null) log("No prior WFD configuration exists on this device; proceeding without a restore point");
            else {
                WifiP2pWfdInfo existing = new WifiP2pWfdInfo(baseline.getWfdInfo());
                if (existing.isEnabled()) {
                    // An enabled WfdInfo can be Android's own Wireless Display feature (real conflict, must
                    // not touch it) or leftover state from a prior run of this same probe that aborted
                    // before its own cleanup ran (previously a real bug here: this app enabled a sink but
                    // never disabled it on the no-prior-baseline path, so the next run saw its own state and
                    // refused to proceed forever). Settings.Global would be the authoritative way to check
                    // Android's own toggle, but it rejects this shell-attributed context's calling package
                    // ("Given calling package local.dexprobe does not match caller's uid 2000") even though
                    // WifiP2pManager calls work fine here — so use this app's own known sink signature
                    // instead: only treat it as foreign (and refuse to touch it) if it does not match.
                    boolean looksLikeOurOwnLeftover = existing.getControlPort() == 7236 && existing.getDeviceType() == WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK;
                    if (!looksLikeOurOwnLeftover) throw new IllegalStateException("Existing WFD advertisement: turn off Android Wireless display first");
                    log("Enabled WfdInfo matches this probe's own sink signature (control port 7236, primary sink); treating as leftover from an earlier aborted run and clearing it");
                } else previous = existing;
            }
            if (stop) return;
            WifiP2pWfdInfo sink = new WifiP2pWfdInfo();
            sink.setEnabled(true); sink.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
            sink.setSessionAvailable(true); sink.setControlPort(7236); sink.setMaxThroughput(20);
            stage = "enable sink WFD";
            changed = true;
            Result<Integer> configure = new Result<>(stage);
            manager.setWfdInfo(channel, sink, configure.listener());
            if (configure.get() != 0) throw new IllegalStateException("WFD configuration rejected");
            log("Sink configuration accepted. Discovery and DeX remain unverified.");
            stage = "startListening";
            Result<Integer> listen = new Result<>(stage);
            try {
                listeningAttempted = true;
                manager.startListening(channel, listen.listener());
                listening = listen.get() == 0;
            } catch (java.util.concurrent.TimeoutException e) {
                throw e; // unknown outcome: restore instead of starting a competing discovery
            } catch (Exception e) { log("startListening unavailable: " + e.getClass().getSimpleName()); }
            if (!listening) {
                stage = "discoverPeers";
                Result<Integer> discover = new Result<>(stage);
                discovery = true; // cleanup even after an unknown callback result
                manager.discoverPeers(channel, discover.listener());
                if (discover.get() != 0) throw new IllegalStateException("Peer discovery rejected");
            }
            log("Discovery/listening active. On Samsung choose this tablet in WIRELESS DeX. Accept Android's pairing invitation if shown.");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            boolean delivered = false;
            while (!stop && System.nanoTime() < deadline) {
                stage = "poll group";
                Result<WifiP2pInfo> connection = new Result<>(stage);
                manager.requestConnectionInfo(channel, connection::accept);
                WifiP2pInfo current = connection.get();
                if (current != null && current.groupFormed) {
                    formed = true;
                    if (!delivered) {
                        log("P2P group formed; tabletIsGroupOwner=" + current.isGroupOwner);
                        log(wifiBandDiagnostic(context));
                        sink.setSessionAvailable(false);
                        Result<Integer> occupied = new Result<>("mark sink session occupied");
                        manager.setWfdInfo(channel, sink, occupied.listener());
                        if (occupied.get() != 0) throw new IllegalStateException("Cannot mark sink occupied");
                        if (current.isGroupOwner || current.groupOwnerAddress == null) throw new IllegalStateException("Source is not group owner; source address discovery for this role is not implemented");
                        int port = 7236;
                        Result<WifiP2pGroup> groupResult = new Result<>("source WFD port");
                        manager.requestGroupInfo(channel, groupResult::accept);
                        WifiP2pGroup group = groupResult.get();
                        if (group != null && group.getOwner() != null && group.getOwner().getWfdInfo() != null) {
                            int advertised = group.getOwner().getWfdInfo().getControlPort();
                            if (advertised > 0 && advertised < 65536) port = advertised;
                        }
                        log("Source group-owner endpoint available; launching RTSP on control port " + port);
                        events.onGroup(current.groupOwnerAddress.getHostAddress(), port);
                        delivered = true;
                        deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    }
                } else if (formed) { log("P2P group lost"); break; }
                Thread.sleep(750);
            }
            log(stop ? "Session stop requested" : "Session deadline/group end reached");
        } catch (Throwable t) { report.error(stage, t); log("Session failed at " + stage); }
        finally {
            // Keep Looper alive throughout restoration and group cleanup.
            if (manager != null && channel != null) {
                if (listeningAttempted) try {
                    Result<Integer> r = new Result<>("stopListening"); manager.stopListening(channel, r.listener()); r.get();
                } catch (Exception e) { log("stopListening unconfirmed: " + e.getClass().getSimpleName()); }
                if (discovery) try {
                    Result<Integer> r = new Result<>("stopPeerDiscovery"); manager.stopPeerDiscovery(channel, r.listener()); r.get();
                } catch (Exception e) { log("stopPeerDiscovery unconfirmed: " + e.getClass().getSimpleName()); }
                if (changed) {
                    // Initial state had no group. Cancel outstanding user-approved negotiation
                    // before querying again, including groups formed between the last poll and stop.
                    try {
                        Result<Integer> r = new Result<>("cancel pending session connection"); manager.cancelConnect(channel, r.listener()); r.get();
                    } catch (Exception e) { log("Connection cancellation unconfirmed: " + e.getClass().getSimpleName()); }
                    try {
                        Result<WifiP2pInfo> r = new Result<>("cleanup group state"); manager.requestConnectionInfo(channel, r::accept);
                        WifiP2pInfo current = r.get();
                        if (current == null) log("Group cleanup state unavailable");
                        else if (current.groupFormed) {
                            Result<Integer> remove = new Result<>("remove session group"); manager.removeGroup(channel, remove.listener()); remove.get();
                            Result<WifiP2pInfo> check = new Result<>("verify group removed"); manager.requestConnectionInfo(channel, check::accept);
                            WifiP2pInfo after = check.get();
                            log("Session group removed=" + (after != null && !after.groupFormed));
                        } else log("No remaining P2P group");
                    } catch (Exception e) { log("Group cleanup unconfirmed: " + e.getClass().getSimpleName()); }
                }
                if (changed) try {
                    // If no prior baseline existed, the correct "restore" is an explicitly disabled sink,
                    // not skipping this step entirely — otherwise our own enabled sink config from this
                    // session lingers as the device's current WfdInfo and the next attempt fails with
                    // "Existing WFD advertisement" against our own leftover state.
                    WifiP2pWfdInfo target = previous != null ? previous : new WifiP2pWfdInfo();
                    Result<Integer> r = new Result<>("restore WFD"); manager.setWfdInfo(channel, target, r.listener());
                    boolean accepted = r.get() == 0;
                    Result<WifiP2pDevice> check = new Result<>("restore readback"); manager.requestDeviceInfo(channel, check::accept);
                    WifiP2pDevice actual = check.get();
                    boolean match = actual != null && actual.getWfdInfo() != null && target.toString().equals(actual.getWfdInfo().toString());
                    log("WFD restore accepted=" + accepted + " readbackMatches=" + match);
                    if (!accepted || !match) log("RESTORATION_UNCONFIRMED");
                } catch (Exception e) { report.error("RESTORATION_UNCONFIRMED", e); }
                try { channel.close(); } catch (Exception e) { log("Channel close unconfirmed"); }
            }
            callbacks.quitSafely();
            try { callbacks.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            log("P2P session ended; callbackThreadStopped=" + !callbacks.isAlive());
            try { events.onEnded(); } catch (Exception ignored) {}
            ended.run();
        }
    }
    /** Android's public WifiP2pManager/WifiP2pGroup API does not expose the P2P link's actual operating
     * frequency (only hidden/non-SDK getters do, which this project deliberately does not use). This reports
     * the two genuinely public signals instead: whether the radio/driver supports 5GHz P2P at all, and the
     * regular station Wi-Fi's frequency if one happens to be associated (caveated: that is the STA link,
     * not a confirmation of which band this P2P group actually negotiated). */
    private static String wifiBandDiagnostic(Context context) {
        try {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            if (wifi == null) return "Wi-Fi band: WifiManager unavailable";
            boolean supports5ghz = wifi.is5GHzBandSupported();
            String sta = "no associated station link";
            try {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null && info.getFrequency() > 0) {
                    int mhz = info.getFrequency();
                    sta = mhz + "MHz (" + (mhz >= 4900 ? "5GHz" : "2.4GHz") + ")";
                }
            } catch (Exception ignored) {}
            return "Wi-Fi band: device 5GHz-P2P-capable=" + supports5ghz + "; station link=" + sta + " (public API cannot report the P2P link's own band)";
        } catch (Exception e) { return "Wi-Fi band: unavailable (" + e.getClass().getSimpleName() + ")"; }
    }
    private final class Result<T> {
        private final String stage;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<T> value = new AtomicReference<>();
        private volatile boolean expired;
        Result(String stage) { this.stage = stage; }
        void accept(T result) { if (expired) log(stage + " LATE callback"); value.set(result); latch.countDown(); }
        T get() throws Exception {
            if (!latch.await(8, TimeUnit.SECONDS)) { expired = true; throw new java.util.concurrent.TimeoutException(stage + " callback >8000ms; outcome unknown"); }
            return value.get();
        }
        @SuppressWarnings("unchecked") WifiP2pManager.ActionListener listener() {
            return new WifiP2pManager.ActionListener() {
                public void onSuccess() { log(stage + " SUCCESS"); accept((T) Integer.valueOf(0)); }
                public void onFailure(int reason) { log(stage + " FAILURE code=" + reason + " (0=ERROR,1=UNSUPPORTED,2=BUSY)"); accept((T) Integer.valueOf(reason + 100)); }
            };
        }
    }
}
