import local.dexprobe.Report;
public class ReportTest {
    public static void main(String[] args) {
        String clean = Report.redact("peer aa:bb:cc:dd:ee:ff ip 192.168.49.1 uid 2000");
        if (clean.contains("aa:bb") || clean.contains("192.168") || !clean.contains("2000")) throw new AssertionError(clean);
        if (!Report.category(new SecurityException("package does not belong to uid")).equals("CONTEXT_ATTRIBUTION")) throw new AssertionError();
        if (!Report.category(new SecurityException("permission denied")).equals("PERMISSION_DENIAL")) throw new AssertionError();
        if (!Report.category(new IllegalStateException("context unavailable")).equals("SETUP_OR_API")) throw new AssertionError();
        System.out.println("Report tests passed");
    }
}
