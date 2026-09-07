package local.dexprobe;
oneway interface IReceiverEvents {
    void onReport(String report);
    void onGroup(String sourceHost, int controlPort);
    void onEnded();
}
