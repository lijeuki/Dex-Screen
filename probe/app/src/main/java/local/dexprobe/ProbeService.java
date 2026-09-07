package local.dexprobe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shell-only service. No bypasses, receiver registration, ContentResolver, or raw sockets. */
public final class ProbeService extends IProbe.Stub {
    private final Context suppliedContext;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean exitRequested = new AtomicBoolean();
    private volatile P2pSession session;
    public ProbeService(Context context) { suppliedContext = context; }

    @Override public String runProbe() {
        if (!running.compareAndSet(false, true)) return "BUSY: probe already running";
        long token = Binder.clearCallingIdentity();
        Report report = new Report();
        HandlerThread callbackThread = null;
        WifiP2pManager manager = null;
        WifiP2pManager.Channel channel = null;
        WifiP2pWfdInfo previous = null;
        boolean mutationAttempted = false;
        String stage = "identity";
        try {
            report.add("serviceUid=" + Process.myUid() + " binderUidAfterClear=" + Binder.getCallingUid() + " sdk=" + android.os.Build.VERSION.SDK_INT);
            if (Process.myUid() != 2000) throw new SecurityException("Requires shell UID 2000; root is refused");
            stage = "shellContext";
            // Shizuku's constructor Context belongs to the APK. Use the real shell package,
            // without CONTEXT_IGNORE_SECURITY or loading another package's code.
            Context context = new ShellContext(suppliedContext.createPackageContext("com.android.shell", 0));
            report.add("contextPackage=" + context.getPackageName() + " opPackage=" + context.getOpPackageName()
                    + " appUid=" + context.getApplicationInfo().uid
                    + " attributionUid=" + context.getAttributionSource().getUid()
                    + " attributionPackage=" + context.getAttributionSource().getPackageName());
            if (!"com.android.shell".equals(context.getOpPackageName())
                    || context.getApplicationInfo().uid != 2000
                    || context.getAttributionSource().getUid() != 2000
                    || !"com.android.shell".equals(context.getAttributionSource().getPackageName())) {
                throw new SecurityException("Invalid shell context attribution; refusing API calls");
            }
            boolean supported = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
            report.add("wifiDirectFeature=" + supported);
            for (String permission : new String[]{"ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CONFIGURE_WIFI_DISPLAY", "NEARBY_WIFI_DEVICES", "ACCESS_FINE_LOCATION", "NETWORK_SETTINGS", "NETWORK_STACK"}) {
                report.add("permission " + permission + "=" + (context.checkPermission("android.permission." + permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED));
            }
            if (!supported) throw new IllegalStateException("FEATURE_WIFI_DIRECT unavailable");
            stage = "initializeChannel";
            callbackThread = new HandlerThread("probe-p2p-callbacks");
            callbackThread.start();
            manager = context.getSystemService(WifiP2pManager.class);
            if (manager == null) throw new IllegalStateException("WifiP2pManager unavailable");
            channel = manager.initialize(context, callbackThread.getLooper(), () -> report.add("CHANNEL_DISCONNECTED"));
            if (channel == null) throw new IllegalStateException("initialize returned null channel");
            report.add("channel initialized; callback Looper alive=" + callbackThread.isAlive());
            stage = "requestP2pState";
            Value<Integer> state = new Value<>(stage, report);
            manager.requestP2pState(channel, state::accept);
            int p2pState = state.await(stage, report);
            report.add("p2pState=" + p2pState);
            if (p2pState != WifiP2pManager.WIFI_P2P_STATE_ENABLED) throw new IllegalStateException("P2P is not enabled; no settings changed");
            stage = "requestConnectionInfo";
            Value<WifiP2pInfo> connection = new Value<>(stage, report);
            manager.requestConnectionInfo(channel, connection::accept);
            WifiP2pInfo info = connection.await(stage, report);
            if (info == null) throw new IllegalStateException("No connection state snapshot");
            report.add("groupFormed=" + info.groupFormed);
            if (info.groupFormed) throw new IllegalStateException("Existing P2P group; refusing competing configuration");
            stage = "snapshotWfd";
            previous = snapshot(manager, channel, report, stage);
            report.add("priorWfd=" + describe(previous));
            if (previous == null) throw new IllegalStateException("Prior WFD unavailable; cannot restore exactly, no mutation");
            if (previous.isEnabled()) throw new IllegalStateException("Prior WFD enabled; close Xiaomi/other wireless display receiver before probing");
            WifiP2pWfdInfo candidate = new WifiP2pWfdInfo();
            candidate.setEnabled(true);
            candidate.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
            candidate.setSessionAvailable(false); // configuration-only probe does not offer a session
            candidate.setControlPort(7236);
            candidate.setMaxThroughput(20);
            stage = "setWfdInfo";
            mutationAttempted = true; // even failure can follow partial HAL mutation
            boolean accepted = set(manager, channel, candidate, report, stage);
            report.add("configurationAccepted=" + accepted + "; discovery/connection/video/DeX NOT tested");
            stage = "readbackWfd";
            WifiP2pWfdInfo readback = snapshot(manager, channel, report, stage);
            report.add("readbackWfd=" + describe(readback));
            report.add("candidateReadbackMatches=" + same(candidate, readback) + "; framework cache only, not on-air evidence");
        } catch (Throwable t) {
            report.error(stage, t);
        } finally {
            if (mutationAttempted && manager != null && channel != null && previous != null) {
                try {
                    boolean restored = set(manager, channel, previous, report, "restoreWfd");
                    WifiP2pWfdInfo actual = snapshot(manager, channel, report, "restoreReadback");
                    boolean equal = same(previous, actual);
                    report.add("restoreAccepted=" + restored + " restoreReadbackMatches=" + equal);
                    if (!restored || !equal) report.add("RESTORATION_UNCONFIRMED: inspect device before another test");
                } catch (Throwable t) { report.error("restoreWfd RESTORATION_UNCONFIRMED", t); }
            }
            if (channel != null) {
                try { channel.close(); report.add("channel closed"); }
                catch (Throwable t) { report.error("closeChannel", t); }
            }
            if (callbackThread != null) {
                callbackThread.quitSafely();
                try { callbackThread.join(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                report.add("callbackThreadStopped=" + !callbackThread.isAlive());
            }
            report.add("No discovery started, no group created/removed, no global settings changed. End of probe.");
            Binder.restoreCallingIdentity(token);
            running.set(false);
            if (exitRequested.get()) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> System.exit(0), 1000);
        }
        return report.toString();
    }

    private static WifiP2pWfdInfo snapshot(WifiP2pManager manager, WifiP2pManager.Channel channel, Report report, String stage) throws Exception {
        Value<WifiP2pDevice> value = new Value<>(stage, report);
        manager.requestDeviceInfo(channel, value::accept);
        WifiP2pDevice device = value.await(stage, report);
        report.add(stage + " deviceSnapshotPresent=" + (device != null) + " wfdInfoPresent=" + (device != null && device.getWfdInfo() != null));
        if (device == null) throw new IllegalStateException("Device snapshot unavailable; could be permission/attribution/service state, no baseline known");
        return device.getWfdInfo() == null ? null : new WifiP2pWfdInfo(device.getWfdInfo());
    }
    // The diagnostic intentionally attempts this under shell identity and records denial.
    // APK-manifest permission analysis cannot model the separate Shizuku UID.
    @android.annotation.SuppressLint("MissingPermission")
    private static boolean set(WifiP2pManager manager, WifiP2pManager.Channel channel, WifiP2pWfdInfo wfd, Report report, String stage) throws Exception {
        Value<Boolean> value = new Value<>(stage, report);
        manager.setWfdInfo(channel, wfd, new WifiP2pManager.ActionListener() {
            public void onSuccess() { report.add(stage + " callback SUCCESS" + (value.expired.get() ? " LATE" : "")); value.accept(true); }
            public void onFailure(int reason) {
                report.add(stage + " callback FAILURE code=" + reason + " (" + failure(reason) + ")" + (value.expired.get() ? " LATE" : ""));
                value.accept(false);
            }
        });
        return value.await(stage, report);
    }
    private static String failure(int reason) {
        switch (reason) { case 0: return "ERROR: generic service/driver failure; inspect service logs"; case 1: return "P2P_UNSUPPORTED"; case 2: return "BUSY"; default: return "other platform code"; }
    }
    private static String describe(WifiP2pWfdInfo wfd) {
        // WFD contains capability bits, ports and throughput, not peer identifiers.
        return wfd == null ? "null" : wfd.toString();
    }
    private static boolean same(WifiP2pWfdInfo a, WifiP2pWfdInfo b) {
        return a != null && b != null && a.isEnabled() == b.isEnabled()
                && a.getDeviceInfo() == b.getDeviceInfo() && a.getControlPort() == b.getControlPort()
                && a.getMaxThroughput() == b.getMaxThroughput() && a.getR2DeviceInfo() == b.getR2DeviceInfo();
    }
    private static final class Value<T> {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicBoolean expired = new AtomicBoolean();
        final String stage;
        final Report report;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Value(String stage, Report report) { this.stage = stage; this.report = report; }
        void accept(T value) {
            report.add(stage + " callback received" + ((expired.get() || System.nanoTime() > deadline) ? " LATE" : ""));
            result.set(value); latch.countDown();
        }
        T await(String stage, Report report) throws Exception {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                expired.set(true);
                report.add(stage + " TIMEOUT after 8000ms; outcome unknown");
                throw new java.util.concurrent.TimeoutException(stage);
            }
            return result.get();
        }
    }
    @Override public void destroy() {
        // Normally invoked after runProbe returned and its finally completed.
        exitRequested.set(true);
        if (session != null) session.stop();
        if (!running.get()) System.exit(0);
    }
    @Override public void startReceiver(IReceiverEvents events) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Probe/session already running");
        session = new P2pSession(suppliedContext, events, () -> {
            running.set(false); session = null;
            if (exitRequested.get()) System.exit(0);
        });
        try { events.asBinder().linkToDeath(() -> { P2pSession current = session; if (current != null) current.stop(); }, 0); }
        catch (android.os.RemoteException e) { running.set(false); session = null; return; }
        new Thread(session, "p2p-session").start();
    }
    @Override public void stopReceiver() { P2pSession current = session; if (current != null) current.stop(); }
}
