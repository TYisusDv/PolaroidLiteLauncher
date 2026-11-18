package tv.polaroid.litehome.data.devices;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;

/** Calls the CVTE TV API used by the factory input selector. */
public final class CvteInputSourceController {
    public interface Callback {
        void onResult(boolean selected);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Object pendingConnectionListener;

    private CvteInputSourceController() { }

    public static void select(final Activity activity, final String label,
                              final int fallbackInputId, final Callback callback) {
        try {
            final Class<?> sdkClass = Class.forName("com.cvte.tv.api.TvApiSDKManager");
            Object sdk;
            try {
                sdk = sdkClass.getMethod("getInstance").invoke(null);
            } catch (Exception notInitialized) {
                sdkClass.getMethod("init", android.app.Application.class)
                        .invoke(null, activity.getApplication());
                sdk = sdkClass.getMethod("getInstance").invoke(null);
            }

            final Class<?> listenerClass = Class.forName("com.cvte.tv.api.TvServiceConnectListener");
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("OnConnected".equals(method.getName()) && args != null && args.length > 0) {
                                pendingConnectionListener = null;
                                selectFromApi(args[0], label, fallbackInputId, callback);
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
            pendingConnectionListener = listener;
            sdkClass.getMethod("getTvApi", listenerClass).invoke(sdk, listener);
        } catch (Exception error) {
            deliver(callback, false);
        }
    }

    private static void selectFromApi(Object manager, String label, int fallbackInputId,
                                      Callback callback) {
        try {
            Class<?> managerType = Class.forName("com.cvte.tv.api.aidl.ITvApiManager");
            Object inputApi = managerType.getMethod("getTVApiSystemInputSource").invoke(manager);
            Class<?> inputApiType = Class.forName("com.cvte.tv.api.aidl.ITVApiSystemInputSourceAidl");
            List<?> sources = (List<?>) inputApiType.getMethod("eventSystemInputSourceGetList").invoke(inputApi);
            int sourceId = findSourceId(sources, label, fallbackInputId);
            if (sourceId < 0) {
                deliver(callback, false);
                return;
            }
            Object result = inputApiType.getMethod("eventSystemInputSourceSetInputSource", int.class)
                    .invoke(inputApi, sourceId);
            deliver(callback, Boolean.TRUE.equals(result));
        } catch (Exception error) {
            deliver(callback, false);
        }
    }

    private static int findSourceId(List<?> sources, String label, int fallbackInputId) throws Exception {
        if (sources == null) return -1;
        String target = normalize(label).replace(" ", "");
        String targetCategory = "";
        if (target.startsWith("hdmi")) targetCategory = "INPUTSOURCE_HDMI";
        else if ("av".equals(target)) targetCategory = "INPUTSOURCE_AV";
        else if ("tv".equals(target)) targetCategory = "INPUTSOURCE_TV";
        else if ("vga".equals(target)) targetCategory = "INPUTSOURCE_VGA";
        else if ("ypbpr".equals(target)) targetCategory = "INPUTSOURCE_COMPONENT";

        int categoryFallback = -1;
        for (Object source : sources) {
            int id = intField(source, "id");
            String name = stringField(source, "name");
            String category = enumField(source, "category");
            String normalizedName = normalize(name).replace(" ", "");
            if (target.length() > 0 && normalizedName.equals(target)) return id;
            if (target.startsWith("hdmi") && normalizedName.contains(target)) return id;
            if ("ypbpr".equals(target) && (normalizedName.contains("YPBPR")
                    || normalizedName.contains("COMPONENT"))) return id;
            if (targetCategory.equals(category)) {
                if (!target.startsWith("hdmi") || id == fallbackInputId) return id;
                if (categoryFallback < 0) categoryFallback = id;
            }
            if ("tv".equals(target) && ("INPUTSOURCE_ATV".equals(category)
                    || "INPUTSOURCE_DVBT".equals(category) || "INPUTSOURCE_DVBC".equals(category)
                    || "INPUTSOURCE_ATSC".equals(category)) && categoryFallback < 0) {
                categoryFallback = id;
            }
        }
        if (categoryFallback >= 0) return categoryFallback;
        for (Object source : sources) if (intField(source, "id") == fallbackInputId) return fallbackInputId;
        return -1;
    }

    private static int intField(Object target, String name) throws Exception {
        return field(target, name).getInt(target);
    }

    private static String stringField(Object target, String name) throws Exception {
        Object value = field(target, name).get(target);
        return value == null ? "" : value.toString();
    }

    private static String enumField(Object target, String name) throws Exception {
        Field field = field(target, name);
        Object value = field.get(target);
        return value instanceof Enum ? ((Enum<?>) value).name() : value == null ? "" : value.toString();
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        field.setAccessible(true);
        return field;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.US).trim();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return '\0';
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0f;
        if (type == Double.TYPE) return 0d;
        return null;
    }

    private static void deliver(final Callback callback, final boolean selected) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() { callback.onResult(selected); }
        });
    }
}
