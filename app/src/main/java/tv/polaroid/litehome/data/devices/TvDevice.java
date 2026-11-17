package tv.polaroid.litehome.data.devices;

/** A system TV input, such as HDMI, exposed by the manufacturer TV stack. */
public final class TvDevice {
    public final String id;
    public final String label;
    public final String typeLabel;
    public final boolean passthrough;

    public TvDevice(String id, String label, String typeLabel, boolean passthrough) {
        this.id = id;
        this.label = label;
        this.typeLabel = typeLabel;
        this.passthrough = passthrough;
    }
}
