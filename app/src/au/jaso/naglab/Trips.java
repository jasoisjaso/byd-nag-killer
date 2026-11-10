package au.jaso.naglab;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Trips, built from the car's OWN trip counters (BYD "current trip": distance, time and energy
 * since the car was switched on). The app never measures anything itself: it copies those
 * counters a few times a minute while the car is on, and a trip ends when the counters reset
 * (next car start). Cheap on purpose: no GPS, no extra polling, one small file.
 *
 * Pure Java (no Android APIs) so it is tested on the Mac.
 */
final class Trips {
    private Trips() {
    }

    /** Ignore "trips" shorter than this (car switched on and off in the driveway). */
    static final float MIN_KM = 0.1f;
    static final int GEAR_P = 1;

    /** One reading of the car. NaN / -1 = the car didn't give a usable value. */
    static final class Reading {
        long wall;
        float km = Float.NaN, kwh = Float.NaN, odo = Float.NaN, speed = Float.NaN;
        int min = -1, soc = -1, range = -1, gear = -1;

        /** BYD uses all-ones values (2047, 511, 1048575, ...) for "no data". */
        static float km(float v) {
            return v >= 0 && v < 5000 ? v : Float.NaN;
        }

        static float kwh(float v) {
            return v >= 0 && v < 500 ? v : Float.NaN;
        }

        static float odo(float v) {
            return v > 0 && v < 1_000_000 ? v : Float.NaN;
        }

        static float speed(float v) {
            return v >= 0 && v < 300 ? v : Float.NaN;
        }

        static int min(int v) {
            return v >= 0 && v < 10_000 ? v : -1;
        }

        static int soc(int v) {
            return v >= 0 && v <= 100 ? v : -1;
        }

        static int range(int v) {
            return v >= 0 && v < 2000 ? v : -1;
        }

        boolean hasTrip() {
            return !Float.isNaN(km) && min >= 0;
        }

        /** Not in P, or rolling. Unknown gear counts as parked unless the speed says otherwise. */
        boolean moving() {
            return (gear > 0 && gear != GEAR_P) || (!Float.isNaN(speed) && speed > 1);
        }
    }

    /** One trip. kwh may be NaN (car didn't report it). */
    static final class Trip {
        long start, end;
        float km, kwh = Float.NaN, odoStart = Float.NaN, odoEnd = Float.NaN;
        int min, socStart = -1, socEnd = -1;
        /** Shown only: made by merge(), or contains the trip in progress. Not stored. */
        boolean merged, live;

        static Trip start(Reading r) {
            Trip t = new Trip();
            t.start = r.wall - r.min * 60_000L;
            if (!Float.isNaN(r.odo)) t.odoStart = r.odo - r.km;
            t.socStart = r.soc;
            t.extend(r);
            return t;
        }

        void extend(Reading r) {
            end = r.wall;
            km = r.km;
            min = r.min;
            if (!Float.isNaN(r.kwh)) kwh = r.kwh;
            if (!Float.isNaN(r.odo)) odoEnd = r.odo;
            if (r.soc >= 0) socEnd = r.soc;
            if (socStart < 0) socStart = r.soc;
        }

        /** kWh/100km, NaN when it can't be worked out (too short or no energy figure). */
        float efficiency() {
            return km >= 1 && !Float.isNaN(kwh) ? kwh / km * 100f : Float.NaN;
        }

        String line() {
            return start + "\t" + end + "\t" + km + "\t" + min + "\t" + kwh + "\t" + odoStart + "\t"
                    + odoEnd + "\t" + socStart + "\t" + socEnd;
        }

        static Trip parse(String s) {
            String[] f = s.split("\t");
            if (f.length < 9) return null;
            try {
                Trip t = new Trip();
                t.start = Long.parseLong(f[0]);
                t.end = Long.parseLong(f[1]);
                t.km = Float.parseFloat(f[2]);
                t.min = Integer.parseInt(f[3]);
                t.kwh = Float.parseFloat(f[4]);
                t.odoStart = Float.parseFloat(f[5]);
                t.odoEnd = Float.parseFloat(f[6]);
                t.socStart = Integer.parseInt(f[7]);
                t.socEnd = Integer.parseInt(f[8]);
                return t;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Follows the car's trip counters. Not thread-safe: one thread (the service's). */
    static final class Tracker {
        Trip current;

        /** Feed a reading. Returns the trip that just ended (counters reset), else null. */
        Trip update(Reading r) {
            if (!r.hasTrip()) return null;
            if (current == null) {
                current = Trip.start(r);
                return null;
            }
            boolean reset = r.km + 0.05f < current.km || r.min < current.min;
            // The car drove further than this trip says: the counters reset while we weren't
            // looking (app killed between two drives). Close the old one where we last saw it.
            if (!reset && !Float.isNaN(r.odo) && !Float.isNaN(current.odoEnd)
                    && r.odo - current.odoEnd > (r.km - current.km) + 1f) {
                reset = true;
            }
            if (reset) {
                Trip done = current.km >= MIN_KM ? current : null;
                current = Trip.start(r);
                return done;
            }
            current.extend(r);
            return null;
        }
    }

    // ---- Storage: trips.tsv (finished, append-only) + trip-current.tsv (the one in progress).

    static List<Trip> load(File f) {
        List<Trip> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                Trip t = Trip.parse(l);
                if (t != null) out.add(t);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static void append(File f, Trip t) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(t.line() + "\n");
        }
    }

    static void saveCurrent(File f, Trip t) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            if (t != null) w.write(t.line() + "\n");
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + tmp);
    }

    static Trip loadCurrent(File f) {
        List<Trip> l = load(f);
        return l.isEmpty() ? null : l.get(0);
    }

    // ---- Summaries

    /** A stop shorter than this (servo, drop-off) doesn't split a trip. Owners expect one trip
     *  per journey; Polestar/Kia/BYD Trip Stats all merge short stops (trips-research). */
    static final long MERGE_GAP_MS = 10 * 60_000L;

    /** Newest first: the current trip (if it has gone anywhere) then the finished ones, with
     *  short stops merged. The stored trips stay as recorded; merging is only for showing. */
    static List<Trip> newestFirst(List<Trip> done, Trip current) {
        List<Trip> chrono = new ArrayList<>(done);
        if (current != null && current.km >= MIN_KM) chrono.add(current);
        List<Trip> merged = merge(chrono);
        List<Trip> out = new ArrayList<>();
        for (int i = merged.size() - 1; i >= 0; i--) out.add(merged.get(i));
        return out;
    }

    /** Oldest first in, oldest first out. A merged trip is a new object; a trip with nothing to
     *  merge is passed through as is (so the screen can still tell the current one). */
    static List<Trip> merge(List<Trip> chrono) {
        List<Trip> out = new ArrayList<>();
        for (Trip t : chrono) {
            Trip prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && t.start - prev.end < MERGE_GAP_MS && t.start >= prev.start) {
                out.set(out.size() - 1, join(prev, t));
            } else {
                out.add(t);
            }
        }
        return out;
    }

    static Trip join(Trip a, Trip b) {
        Trip j = new Trip();
        j.start = a.start;
        j.end = b.end;
        j.km = a.km + b.km;
        j.min = (int) ((b.end - a.start) / 60_000L);
        j.kwh = Float.isNaN(a.kwh) || Float.isNaN(b.kwh) ? Float.NaN : a.kwh + b.kwh;
        j.odoStart = a.odoStart;
        j.odoEnd = b.odoEnd;
        j.socStart = a.socStart >= 0 ? a.socStart : b.socStart;
        j.socEnd = b.socEnd >= 0 ? b.socEnd : a.socEnd;
        j.merged = true;
        j.live = b.live;
        return j;
    }

    /** km per day for the 7 days ending today (index 6 = today), by trip start time. */
    static float[] week(List<Trip> trips, long now, Calendar cal) {
        float[] km = new float[7];
        long today = dayStart(now, cal);
        for (Trip t : trips) {
            long d = dayStart(t.start, cal);
            int ago = (int) Math.round((today - d) / 86_400_000.0);
            if (ago >= 0 && ago < 7) km[6 - ago] += t.km;
        }
        return km;
    }

    /** {km, kWh} over trips that started in the last 7 days (kWh only from trips that have it). */
    static float[] weekTotals(List<Trip> trips, long now, Calendar cal) {
        long from = dayStart(now, cal) - 6 * 86_400_000L;
        float km = 0, kmE = 0, kwh = 0;
        for (Trip t : trips) {
            if (t.start < from) continue;
            km += t.km;
            if (!Float.isNaN(t.kwh)) {
                kmE += t.km;
                kwh += t.kwh;
            }
        }
        return new float[]{km, kmE >= 1 ? kwh / kmE * 100f : Float.NaN};
    }

    static long dayStart(long wall, Calendar cal) {
        cal.setTimeInMillis(wall);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // ---- Formatting (AU): "12.4", "148", "16.1", "23 min", "1 h 05", "7:42 am", "Mon 9 Nov".

    static String km(float v) {
        if (Float.isNaN(v)) return "–";
        return v < 100 ? String.format(Locale.US, "%.1f", v) : String.format(Locale.US, "%.0f", v);
    }

    static String one(float v) {
        return Float.isNaN(v) ? "–" : String.format(Locale.US, "%.1f", v);
    }

    static String duration(int min) {
        if (min < 0) return "–";
        if (min < 60) return min + " min";
        return String.format(Locale.US, "%d h %02d", min / 60, min % 60);
    }

    static String clock(long wall, Calendar cal) {
        cal.setTimeInMillis(wall);
        int h = cal.get(Calendar.HOUR_OF_DAY), m = cal.get(Calendar.MINUTE);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m, h < 12 ? "am" : "pm");
    }

    private static final String[] DAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug",
            "Sep", "Oct", "Nov", "Dec"};

    static String day(long wall, long now, Calendar cal) {
        long d = dayStart(wall, cal), today = dayStart(now, cal);
        int ago = (int) Math.round((today - d) / 86_400_000.0);
        if (ago == 0) return "Today";
        if (ago == 1) return "Yesterday";
        cal.setTimeInMillis(wall);
        return DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(Calendar.DAY_OF_MONTH) + " "
                + MONTHS[cal.get(Calendar.MONTH)];
    }

    /** One-letter day labels for the week chart, oldest first (index 6 = today). */
    static String[] weekLabels(long now, Calendar cal) {
        String[] out = new String[7];
        for (int i = 0; i < 7; i++) {
            cal.setTimeInMillis(dayStart(now, cal) - (6 - i) * 86_400_000L + 12 * 3_600_000L);
            out[i] = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1].substring(0, 1);
        }
        return out;
    }
}
