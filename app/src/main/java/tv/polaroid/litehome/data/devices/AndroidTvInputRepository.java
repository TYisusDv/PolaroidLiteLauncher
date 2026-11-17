package tv.polaroid.litehome.data.devices;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.ArrayList;
import java.util.List;

/** Reads inputs published by the TV manufacturer's Android TV input service. */
public final class AndroidTvInputRepository implements DevicesRepository {
    private final Context context;

    public AndroidTvInputRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public List<TvDevice> getInputs() {
        ArrayList<TvDevice> result = new ArrayList<>();
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return result;
        try {
            for (TvInputInfo input : manager.getTvInputList()) {
                boolean passthrough = input.isPassthroughInput();
                String label;
                try { label = input.loadLabel(context).toString(); }
                catch (Exception ignored) { label = "Entrada de TV"; }
                String type = typeName(input.getType());
                result.add(new TvDevice(input.getId(), label, type, passthrough));
            }
        } catch (RuntimeException ignored) {
            // Some OEM firmwares expose the TV Input API incompletely.
        }
        return result;
    }

    private String typeName(int type) {
        if (type == TvInputInfo.TYPE_HDMI) return "HDMI";
        if (type == TvInputInfo.TYPE_COMPONENT) return "Componente";
        if (type == TvInputInfo.TYPE_COMPOSITE) return "AV";
        if (type == TvInputInfo.TYPE_TUNER) return "Televisión";
        if (type == TvInputInfo.TYPE_DISPLAY_PORT) return "DisplayPort";
        return "Entrada";
    }
}
