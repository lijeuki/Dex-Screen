package local.dexprobe;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

public final class Report {
    private final StringBuilder text = new StringBuilder("DeX feasibility probe 0.1\nRun UTC: " + java.time.Instant.now() + "\n");
    private final long start = System.nanoTime();
    public synchronized void add(String line) {
        text.append(String.format(Locale.ROOT, "+%dms %s%n", (System.nanoTime() - start) / 1000000, redact(line)));
    }
    public void error(String stage, Throwable t) {
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        add(stage + " " + category(t) + "\n" + trace);
    }
    public static String category(Throwable t) {
        String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("attribution") || message.contains("belong") || message.contains("calling package")) return "CONTEXT_ATTRIBUTION";
        if (t instanceof SecurityException) return "PERMISSION_DENIAL";
        return "SETUP_OR_API";
    }
    public static String redact(String s) {
        return s.replaceAll("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", "[MAC]")
                .replaceAll("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b", "[IPv4]");
    }
    @Override public synchronized String toString() { return text.toString(); }
}
