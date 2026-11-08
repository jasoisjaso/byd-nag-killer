package au.jaso.naglab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the constant values of static int fields of one class straight out of a jar's dex
 * files. Used to look up BYD's binder command numbers (TRANSACTION_setCpdStatus etc.) in the
 * car's own /system/framework/ts-framework.jar, because the numbers move between firmware
 * builds (EU cars: setCpdStatus is 190, the AU build we mapped has 185; issue #2).
 *
 * Why parse the file instead of reflection: those fields are flagged hiddenapi BLOCKED, so a
 * normal app can't read them through reflection. Reading the file is allowed (it is
 * world-readable) and needs no special access.
 *
 * Pure Java, no Android APIs, so it is tested on the Mac against the real jar.
 */
final class DexConsts {
    private DexConsts() {
    }

    /** descriptor like "Lcom/ts/lib/caradapter/assist/ICarAssistAdapterService$Stub;".
     *  Returns field name -> value for every static int field with a constant value.
     *  Empty map when the class isn't there. */
    static Map<String, Integer> read(File jar, String descriptor) throws IOException {
        try (ZipFile z = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                byte[] dex;
                try (InputStream in = z.getInputStream(e)) {
                    dex = readAll(in);
                }
                Map<String, Integer> m = fromDex(dex, descriptor);
                if (m != null) return m;
            }
        }
        return new LinkedHashMap<>();
    }

    /** null when the class isn't defined in this dex. */
    static Map<String, Integer> fromDex(byte[] b, String descriptor) {
        Dex d = new Dex(b);
        int stringIdsOff = d.u4(0x3C);
        int typeIdsSize = d.u4(0x40), typeIdsOff = d.u4(0x44);
        int fieldIdsOff = d.u4(0x54);
        int classDefsSize = d.u4(0x60), classDefsOff = d.u4(0x64);

        int typeIdx = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            int strIdx = d.u4(typeIdsOff + 4 * i);
            if (descriptor.equals(d.string(stringIdsOff, strIdx))) {
                typeIdx = i;
                break;
            }
        }
        if (typeIdx < 0) return null;

        for (int c = 0; c < classDefsSize; c++) {
            int def = classDefsOff + 32 * c;
            if (d.u4(def) != typeIdx) continue;
            Map<String, Integer> out = new LinkedHashMap<>();
            int classDataOff = d.u4(def + 24), staticValuesOff = d.u4(def + 28);
            if (classDataOff == 0 || staticValuesOff == 0) return out;

            d.pos = classDataOff;
            int staticFields = d.uleb();
            d.uleb();   // instance fields
            d.uleb();   // direct methods
            d.uleb();   // virtual methods
            int[] fieldIdx = new int[staticFields];
            int idx = 0;
            for (int i = 0; i < staticFields; i++) {
                idx += d.uleb();
                d.uleb();   // access flags
                fieldIdx[i] = idx;
            }

            d.pos = staticValuesOff;
            int n = d.uleb();   // may be fewer than staticFields: the rest are default values
            for (int i = 0; i < n && i < staticFields; i++) {
                int head = d.u1();
                int type = head & 0x1f, arg = head >>> 5;
                if (type == 0x04) {   // VALUE_INT: arg+1 bytes, little endian, sign-extended
                    int v = 0;
                    for (int k = 0; k <= arg; k++) v |= d.u1() << (8 * k);
                    int shift = 32 - 8 * (arg + 1);
                    if (shift > 0) v = (v << shift) >> shift;
                    int f = fieldIdx[i];
                    int nameIdx = d.u4(fieldIdsOff + 8 * f + 4);
                    out.put(d.string(stringIdsOff, nameIdx), v);
                } else {
                    d.skipValue(type, arg);
                }
            }
            return out;
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toByteArray();
    }

    private static final class Dex {
        final byte[] b;
        int pos;

        Dex(byte[] b) {
            this.b = b;
        }

        int u4(int off) {
            return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16
                    | (b[off + 3] & 0xff) << 24;
        }

        int u1() {
            return b[pos++] & 0xff;
        }

        int uleb() {
            int v = 0, shift = 0, x;
            do {
                x = u1();
                v |= (x & 0x7f) << shift;
                shift += 7;
            } while ((x & 0x80) != 0 && shift < 35);
            return v;
        }

        /** MUTF-8; fine for the plain ASCII names we compare. */
        String string(int stringIdsOff, int idx) {
            int save = pos;
            pos = u4(stringIdsOff + 4 * idx);
            int len = uleb();
            int start = pos, end = start;
            while (b[end] != 0) end++;
            pos = save;
            if (end - start != len) return new String(b, start, end - start, StandardCharsets.UTF_8);
            return new String(b, start, len, StandardCharsets.ISO_8859_1);
        }

        void skipValue(int type, int arg) {
            switch (type) {
                case 0x1c:   // array
                    skipArray();
                    break;
                case 0x1d: { // annotation
                    uleb();
                    int n = uleb();
                    for (int i = 0; i < n; i++) {
                        uleb();
                        int h = u1();
                        skipValue(h & 0x1f, h >>> 5);
                    }
                    break;
                }
                case 0x1e:   // null
                case 0x1f:   // boolean (value in arg)
                    break;
                default:     // byte/short/char/int/long/float/double/string/type/field/method/enum...
                    pos += arg + 1;
            }
        }

        void skipArray() {
            int n = uleb();
            for (int i = 0; i < n; i++) {
                int h = u1();
                skipValue(h & 0x1f, h >>> 5);
            }
        }
    }
}
