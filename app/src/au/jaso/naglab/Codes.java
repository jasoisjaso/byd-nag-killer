package au.jaso.naglab;

import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The binder command numbers for this car's firmware, looked up by name in the car's own
 * /system/framework/ts-framework.jar (the library BYD's settings screen uses). Read once per
 * process. Falls back to the numbers from the owner's AU car only if that file can't be read.
 */
final class Codes {
    static final String JAR = "/system/framework/ts-framework.jar";
    static final String ADAPTER_STUB = "Lcom/ts/lib/caradapter/ICarAdapterService$Stub;";
    static final String ASSIST_STUB = "Lcom/ts/lib/caradapter/assist/ICarAssistAdapterService$Stub;";
    static final String SETTING_STUB = "Lcom/ts/lib/caradapter/setting/ISettingService$Stub;";
    static final String BODY_STUB = "Lcom/ts/lib/caradapter/body/ICarBodyService$Stub;";

    /** "car" = read from the car, "built-in" = file unreadable, using the AU numbers. */
    final String source;
    final String error;
    final int adapterLookup;
    /** Every TRANSACTION_* in the assist interface on this car (name without prefix). */
    final Map<String, Integer> assist;
    /** Same for the "setting" (seat fans) and "body" (power level) interfaces. */
    final Map<String, Integer> setting, body;
    private final Map<String, Integer> builtIn = new HashMap<>();

    private static Codes instance;

    static synchronized Codes get() {
        if (instance == null) instance = load();
        return instance;
    }

    private Codes(String source, String error, int adapterLookup, Map<String, Integer> assist,
                  Map<String, Integer> setting, Map<String, Integer> body) {
        this.source = source;
        this.error = error;
        this.adapterLookup = adapterLookup;
        this.assist = assist;
        this.setting = setting;
        this.body = body;
        for (String[] p : AdasClient.BUILT_IN) builtIn.put(p[0], Integer.parseInt(p[1]));
        for (String[] p : AdasClient.BUILT_IN_SEAT) builtIn.put(p[0], Integer.parseInt(p[1]));
    }

    /** Command number in the setting or body interface, -1 when this firmware doesn't have it. */
    int code(Map<String, Integer> iface, String method) {
        if ("car".equals(source)) {
            Integer v = iface.get(method);
            return v == null ? -1 : v;
        }
        Integer v = builtIn.get(method);
        return v == null ? -1 : v;
    }

    /** Command number, or -1 when this firmware doesn't have the command. */
    int code(String method) {
        if ("car".equals(source)) {
            Integer v = assist.get(method);
            return v == null ? -1 : v;
        }
        Integer v = builtIn.get(method);
        return v == null ? -1 : v;
    }

    int builtIn(String method) {
        Integer v = builtIn.get(method);
        return v == null ? -1 : v;
    }

    private static Codes load() {
        long t0 = System.currentTimeMillis();
        try {
            File jar = new File(JAR);
            Map<String, Integer> a = strip(DexConsts.read(jar, ASSIST_STUB));
            Map<String, Integer> r = strip(DexConsts.read(jar, ADAPTER_STUB));
            Map<String, Integer> st = strip(DexConsts.read(jar, SETTING_STUB));
            Map<String, Integer> bo = strip(DexConsts.read(jar, BODY_STUB));
            Integer look = r.get("getCarAdapterService");
            if (a.isEmpty() || look == null) {
                throw new IllegalStateException("command list not found in " + JAR);
            }
            Log.w(DmsClient.TAG, "codes: " + a.size() + " assist commands read from the car in "
                    + (System.currentTimeMillis() - t0) + " ms, cpd get/set "
                    + a.get("getCpdStatus") + "/" + a.get("setCpdStatus"));
            return new Codes("car", null, look, a, st, bo);
        } catch (Throwable e) {
            Log.w(DmsClient.TAG, "codes: can't read the car's list, using built-in numbers: " + e);
            return new Codes("built-in", String.valueOf(e), 2, new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
    }

    private static Map<String, Integer> strip(Map<String, Integer> m) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getKey().startsWith("TRANSACTION_")) {
                out.put(e.getKey().substring("TRANSACTION_".length()), e.getValue());
            }
        }
        return out;
    }
}
