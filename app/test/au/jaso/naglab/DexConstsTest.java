package au.jaso.naglab;

import java.io.File;
import java.util.Map;

/** Needs a copy of the car's ts-framework.jar (not in the repo: it's BYD's file).
 *  Pass its path as the first argument; skipped when absent. Numbers below are from the
 *  owner's AU Sealion 7 (checked with dexdump). */
public final class DexConstsTest {
    private static int fails;

    public static void main(String[] a) throws Exception {
        if (a.length == 0 || !new File(a[0]).isFile()) {
            System.out.println("DexConstsTest: skipped (no ts-framework.jar given)");
            return;
        }
        Map<String, Integer> m = DexConsts.read(new File(a[0]),
                "Lcom/ts/lib/caradapter/assist/ICarAssistAdapterService$Stub;");
        eq(m.get("TRANSACTION_getCpdStatus"), 187);
        eq(m.get("TRANSACTION_setCpdStatus"), 185);
        eq(m.get("TRANSACTION_getLaneAssistType"), 11);
        eq(m.get("TRANSACTION_setLaneAssistType"), 12);
        eq(m.get("TRANSACTION_getAiEmergencyBrakeState"), 46);
        eq(m.get("TRANSACTION_getEmergencyLaneAssistance2F0State"), 80);
        eq(m.get("TRANSACTION_getPairingProcess"), 195);
        int tx = 0;
        for (String k : m.keySet()) if (k.startsWith("TRANSACTION_")) tx++;
        eq(tx, 360);
        Map<String, Integer> root = DexConsts.read(new File(a[0]),
                "Lcom/ts/lib/caradapter/ICarAdapterService$Stub;");
        eq(root.get("TRANSACTION_getCarAdapterService"), 2);
        if (DexConsts.read(new File(a[0]), "Lno/such/Klass;").size() != 0) fail("missing class not empty");
        if (fails > 0) {
            System.out.println("DexConstsTest: " + fails + " FAILED");
            System.exit(1);
        }
        System.out.println("DexConstsTest: ok (" + m.size() + " constants)");
    }

    private static void eq(Integer got, int want) {
        if (got == null || got != want) fail("want " + want + " got " + got);
    }

    private static void fail(String s) {
        fails++;
        System.out.println("FAIL " + s);
    }
}
