package local.dexprobe;
import local.dexprobe.IReceiverEvents;
interface IProbe {
    String runProbe() = 0;
    void startReceiver(IReceiverEvents events) = 1;
    void stopReceiver() = 2;
    void destroy() = 16777114;
}
