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
        /** kWh in the battery right now, as the car's battery computer counts it (0.1 steps). */
        float bat = Float.NaN;
        int min = -1, soc = -1, range = -1, gear = -1;

        /** BYD uses all-ones values (2047, 511, 1048575, ...) for "no data". */
        static float km(float v) {
            return v >= 0 && v < 5000 ? v : Float.NaN;
        }

        static float kwh(float v) {
            return v >= 0 && v < 500 ? v : Float.NaN;
        }

        static float bat(float v) {
            return v > 0 && v < 200 ? v : Float.NaN;
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
        /** kWh in the battery at the start and end (Reading.bat). NaN on trips from before 0.8.6. */
        float batStart = Float.NaN, batEnd = Float.NaN;
        int min, socStart = -1, socEnd = -1;
        /** Shown only: made by merge(), or contains the trip in progress. Not stored. */
        boolean merged, live;

        static Trip start(Reading r) {
            Trip t = new Trip();
            t.start = r.wall - r.min * 60_000L;
            if (!Float.isNaN(r.odo)) t.odoStart = r.odo - r.km;
            t.socStart = r.soc;
            t.batStart = r.bat;
            t.extend(r);
            return t;
        }

        void extend(Reading r) {
            // The end comes from the car's own trip time, not from when we happened to look. At
            // the next car start the car still shows the OLD trip for a few seconds before it
            // resets (and usually a bit further along than our last save, we poll once a minute
            // and get force-stopped at power-off). Stamping that reading made the trip "end"
            // hours later, right before the next one, and the short-stop merge then glued a
            // whole day of drives onto the first (12 Nov: a "23 h 23" trip).
            end = Math.min(r.wall, start + (r.min + 1) * 60_000L);
            km = r.km;
            min = r.min;
            if (!Float.isNaN(r.kwh)) kwh = r.kwh;
            if (!Float.isNaN(r.odo)) odoEnd = r.odo;
            // A late look (next start) has the right counters but tonight's charge in the battery %.
            boolean late = r.wall > start + (r.min + 2) * 60_000L;
            if (r.soc >= 0 && (!late || socEnd < 0)) socEnd = r.soc;
            if (!Float.isNaN(r.bat) && (!late || Float.isNaN(batEnd))) batEnd = r.bat;
            if (socStart < 0) socStart = r.soc;
            if (Float.isNaN(batStart)) batStart = r.bat;
        }

        /** kWh/100km, NaN when it can't be worked out (too short or no energy figure). */
        float efficiency() {
            return km >= 1 && !Float.isNaN(kwh) ? kwh / km * 100f : Float.NaN;
        }

        String line() {
            return start + "\t" + end + "\t" + km + "\t" + min + "\t" + kwh + "\t" + odoStart + "\t"
                    + odoEnd + "\t" + socStart + "\t" + socEnd + "\t" + batStart + "\t" + batEnd;
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
                if (f.length >= 11) {   // added in 0.8.6
                    t.batStart = Float.parseFloat(f[9]);
                    t.batEnd = Float.parseFloat(f[10]);
                }
                return t;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Follows the car's trip counters. Not thread-safe: one thread (the service's). */
    static final class Tracker {
        Trip current;
        /** After a reset the car doesn't zero its counters together: on the 11 Nov car test the
         *  minutes went to 0 about 10 s before km and kWh did. Until the counters are really back
         *  near zero (or HOLD_MS passes) we wait, else the old km get counted a second time. */
        static final long HOLD_MS = 60_000L;
        float holdKm = Float.NaN;
        int holdMin = -1;
        long holdUntil;

        /** Feed a reading. Returns the trip that just ended (counters reset), else null. */
        Trip update(Reading r) {
            if (!r.hasTrip()) return null;
            if (!Float.isNaN(holdKm)) {
                boolean settled = r.km + 0.05f < holdKm && r.min < holdMin;
                if (!settled && r.wall < holdUntil) return null;
                holdKm = Float.NaN;
                holdMin = -1;
                current = Trip.start(r);
                return null;
            }
            if (current == null) {
                current = Trip.start(r);
                return null;
            }
            boolean kmDrop = r.km + 0.05f < current.km, minDrop = r.min < current.min;
            if (kmDrop || minDrop) {
                Trip done = current.km >= MIN_KM ? current : null;
                if (kmDrop && minDrop) {
                    current = Trip.start(r);
                } else {
                    // Only one counter has reset so far: wait for the other.
                    holdKm = current.km;
                    holdMin = current.min;
                    holdUntil = r.wall + HOLD_MS;
                    current = null;
                }
                return done;
            }
            // The car drove further than this trip says: the counters reset while we weren't
            // looking (app killed between two drives). Close the old one where we last saw it.
            if (!Float.isNaN(r.odo) && !Float.isNaN(current.odoEnd)
                    && r.odo - current.odoEnd > (r.km - current.km) + 1f) {
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
                if (t == null) continue;
                Trip prev = out.isEmpty() ? null : out.get(out.size() - 1);
                // 0.8.5-test builds could store the same drive twice (see Tracker hold): a copy
                // with the same km, 0 min, starting right after the real one ended. Skip it.
                if (prev != null && t.min == 0 && t.km == prev.km && t.start - prev.end < 600_000L
                        && t.start >= prev.start) continue;
                // 0.8.5 stored ends that were stamped at the NEXT car start (see Trip.extend).
                // The car's own trip time says when it really ended.
                t.end = Math.min(t.end, t.start + (t.min + 1) * 60_000L);
                out.add(t);
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
            if (prev != null && t.start - prev.end < MERGE_GAP_MS && t.start >= prev.start
                    && Charge.between(prev, t) == null) {   // a fast-charge stop stays visible
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
        j.batStart = a.batStart;
        j.batEnd = b.batEnd;
        j.merged = true;
        j.live = b.live;
        return j;
    }

    /** The last finished trip as the list shows it (short stops merged), or null. */
    static Trip lastShown(List<Trip> done) {
        List<Trip> m = merge(done);
        return m.isEmpty() ? null : m.get(m.size() - 1);
    }

    // ---- Charging. The app is not running while the car charges (the car is off), so a charge
    // is what the battery % says: higher at the start of a trip than at the end of the one before.

    /** Sealion 7 pack, both AU models. Only used to turn battery % into "about N kWh". */
    static final float PACK_KWH = 82.56f;
    /** Smaller rises are rounding or the battery settling, not a charge. */
    static final int MIN_CHARGE_PCT = 2;

    static final class Charge {
        /** Between these two times (end of one trip, start of the next). */
        long from, to;
        int socFrom, socTo;
        /** kWh in the battery before and after, when the car gave it. */
        float batFrom = Float.NaN, batTo = Float.NaN;

        /** The car's own count of what went in (not what the charger billed: losses aren't in it). */
        boolean measured() {
            return !Float.isNaN(batFrom) && !Float.isNaN(batTo) && batTo > batFrom;
        }

        /** Measured when the car gave it, else worked out from the battery % and the pack size. */
        float kwh() {
            return measured() ? batTo - batFrom : (socTo - socFrom) * PACK_KWH / 100f;
        }

        static Charge between(Trip a, Trip b) {
            if (a.socEnd < 0 || b.socStart < 0 || b.socStart - a.socEnd < MIN_CHARGE_PCT) return null;
            Charge c = new Charge();
            c.from = a.end;
            c.to = b.start;
            c.socFrom = a.socEnd;
            c.socTo = b.socStart;
            c.batFrom = a.batEnd;
            c.batTo = b.batStart;
            return c;
        }
    }

    /** Oldest first, from the trips as stored (plus the one in progress). */
    static List<Charge> charges(List<Trip> done, Trip current) {
        List<Charge> out = new ArrayList<>();
        Trip prev = null;
        for (int i = 0; i <= done.size(); i++) {
            Trip t = i < done.size() ? done.get(i) : current;
            if (t == null) continue;
            if (prev != null) {
                Charge c = Charge.between(prev, t);
                if (c != null) out.add(c);
            }
            prev = t;
        }
        return out;
    }

    /** {number of charges, battery % added, kWh added, 1 if every kWh figure was measured} for
     *  charges that finished in the last 7 days. */
    static float[] chargeWeek(List<Charge> charges, long now, Calendar cal) {
        long from = dayStart(now, cal) - 6 * 86_400_000L;
        int n = 0, pct = 0;
        float kwh = 0;
        boolean measured = true;
        for (Charge c : charges) {
            if (c.to < from) continue;
            n++;
            pct += c.socTo - c.socFrom;
            kwh += c.kwh();
            measured &= c.measured();
        }
        return new float[]{n, pct, kwh, measured ? 1 : 0};
    }

    /** What a full battery holds, by the car's own battery computer: every time the battery %
     *  moves (driving down, charging up) we know how many kWh that many % were worth. Returns
     *  {kWh moved, battery % moved}; kWh / % x 100 = a full battery. The car only gives whole
     *  percent, so it needs a lot of % before it is worth showing (see CAPACITY_MIN_PCT). This
     *  is the number that shrinks as the battery ages; PACK_KWH is what it was new. */
    static float[] capacity(List<Trip> done, Trip current) {
        float kwh = 0, pct = 0;
        Trip prev = null;
        for (int i = 0; i <= done.size(); i++) {
            Trip t = i < done.size() ? done.get(i) : current;
            if (t == null) continue;
            if (prev != null) {
                Charge c = Charge.between(prev, t);
                if (c != null && c.measured()) {
                    kwh += c.batTo - c.batFrom;
                    pct += c.socTo - c.socFrom;
                }
            }
            if (!Float.isNaN(t.batStart) && !Float.isNaN(t.batEnd) && t.socStart > t.socEnd
                    && t.batStart > t.batEnd) {
                kwh += t.batStart - t.batEnd;
                pct += t.socStart - t.socEnd;
            }
            prev = t;
        }
        return new float[]{kwh, pct};
    }

    /** One battery's worth of % moved: each end is off by up to half a percent, this evens out. */
    static final float CAPACITY_MIN_PCT = 100f;

    /** For getting the log off the car: a spreadsheet-friendly copy of everything stored. */
    static void exportCsv(List<Trip> done, File f, Calendar cal) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("date,start,end,km,minutes,kWh,kWh_per_100km,battery_start,battery_end,odo_start,odo_end,battery_kWh_start,battery_kWh_end\n");
            for (Trip t : done) {
                w.write(isoDay(t.start, cal) + "," + hhmm(t.start, cal) + "," + hhmm(t.end, cal) + ","
                        + csv(t.km) + "," + t.min + "," + csv(t.kwh) + "," + csv(t.efficiency()) + ","
                        + (t.socStart < 0 ? "" : t.socStart) + "," + (t.socEnd < 0 ? "" : t.socEnd) + ","
                        + csv(t.odoStart) + "," + csv(t.odoEnd) + "," + csv(t.batStart) + "," + csv(t.batEnd) + "\n");
            }
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + tmp);
    }

    private static String csv(float v) {
        return Float.isNaN(v) ? "" : String.format(Locale.US, "%.1f", v);
    }

    private static String isoDay(long wall, Calendar cal) {
        cal.setTimeInMillis(wall);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
    }

    private static String hhmm(long wall, Calendar cal) {
        cal.setTimeInMillis(wall);
        return String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
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
