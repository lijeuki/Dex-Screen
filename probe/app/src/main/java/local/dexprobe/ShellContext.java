package local.dexprobe;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/** Truthful attribution for this shell process, not impersonation of an application UID. */
final class ShellContext extends ContextWrapper {
    private final AttributionSource attribution;
    ShellContext(Context shellPackageContext) {
        super(shellPackageContext);
        if (Process.myUid() != 2000 || shellPackageContext.getApplicationInfo().uid != Process.myUid()
                || !"com.android.shell".equals(shellPackageContext.getPackageName())) {
            throw new SecurityException("Shell package must belong to actual process UID 2000");
        }
        // Android 15 ContextImpl keeps its container's opPackageName in createPackageContext.
        // Correct all attribution fields handed to WifiP2pManager.initialize together.
        // The framework still validates the package against the actual Binder sender UID.
        attribution = new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
    }
    @Override public String getOpPackageName() { return "com.android.shell"; }
    @Override public String getAttributionTag() { return null; }
    @Override public AttributionSource getAttributionSource() { return attribution; }
}
