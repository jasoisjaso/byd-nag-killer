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
        /** kWh in the battery right now by the car's battery computer (0.2 steps). The dash
         *  percent is this figure over what the computer thinks a full battery holds: on the
         *  12 Nov charge the percent ticked 53, 54, 55 exactly as this passed 53/54/55% of 81.0. */
        float bat = Float.NaN;
        /** The car's other "remaining energy" figure (0.1 steps). Rises faster than bat while
         *  charging (about the power at the wall, not what reaches the battery), so it is kept
         *  only to compare with a charger's own kWh count. Not used for anything shown yet. */
        float grid = Float.NaN;
        /** kWh the car has used since new (its own counter). */
        float life = Float.NaN;
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
        float batStart = Float.NaN, batEnd = Float.NaN, gridStart = Float.NaN, gridEnd = Float.NaN;
        int min, socStart = -1, socEnd = -1;
        /** When the battery last went UP before this trip moved (charged with the car on): the
         *  real end of that charge. 0 = it didn't. */
        long rise;
        /** Shown only: made by merge(), or contains the trip in progress. Not stored. */
        boolean merged, live;

        static Trip start(Reading r) {
            Trip t = new Trip();
            t.start = r.wall - r.min * 60_000L;
            if (!Float.isNaN(r.odo)) t.odoStart = r.odo - r.km;
            t.socStart = r.soc;
            t.batStart = r.bat;
            t.gridStart = r.grid;
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
            if (!Float.isNaN(r.grid) && (!late || Float.isNaN(gridEnd))) gridEnd = r.grid;
            if (socStart < 0) socStart = r.soc;
            if (Float.isNaN(batStart)) batStart = r.bat;
            // Charged with the car switched on, then driven off without a restart: the trip
            // starts from the charged battery, so the charge shows up before it.
            if (r.km < MIN_KM && r.soc > socStart && socStart >= 0) {
                socStart = r.soc;
                rise = r.wall;
                if (!Float.isNaN(r.bat)) batStart = r.bat;
                if (!Float.isNaN(r.grid)) gridStart = r.grid;
            }
        }

        /** kWh/100km, NaN when it can't be worked out (too short or no energy figure). */
        float efficiency() {
            return km >= 1 && !Float.isNaN(kwh) ? kwh / km * 100f : Float.NaN;
        }

        String line() {
            return start + "\t" + end + "\t" + km + "\t" + min + "\t" + kwh + "\t" + odoStart + "\t"
                    + odoEnd + "\t" + socStart + "\t" + socEnd + "\t" + batStart + "\t" + batEnd + "\t" + gridStart + "\t" + gridEnd + "\t" + rise;
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
                if (f.length >= 13) {   // 0.8.6. (11 columns = an early test build that stored the wrong reading)
                    t.batStart = Float.parseFloat(f[9]);
                    t.batEnd = Float.parseFloat(f[10]);
                    t.gridStart = Float.parseFloat(f[11]);
                    t.gridEnd = Float.parseFloat(f[12]);
                    if (f.length >= 14) t.rise = Long.parseLong(f[13]);
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
        j.gridStart = a.gridStart;
        j.gridEnd = b.gridEnd;
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
    /** What this car's battery held new. Not final: the Sealion 7 comes with two packs (most
     *  cars 82.56 kWh; the European Excellence AWD 91.3 kWh) and the car doesn't say which, so
     *  it is worked out from readings or picked by the owner (see guessPack). */
    static float PACK_KWH = 82.56f;
    static final float PACK_STD = 82.56f, PACK_BIG = 91.3f;
    /** How PACK_KWH was settled, in words, for the report: a buyer has to be able to check it. */
    static String PACK_NOTE = "";

    /** Which pack the readings point to: PACK_BIG, PACK_STD, or NaN when they can't tell.
     *  full = what a full battery holds now by the car's own readings; odo in km.
     *  A standard pack can't hold much more than it did new, so a clearly bigger reading is the
     *  big pack. A smaller reading is the standard pack on a young car; on an older car it
     *  could also be a worn big pack, so then it isn't called. */
    static float guessPack(float full, float odo) {
        if (Float.isNaN(full)) return Float.NaN;
        if (full >= 87.0f) return PACK_BIG;
        if (!Float.isNaN(odo) && odo < 40_000f) return PACK_STD;
        return Float.NaN;
    }
    /** The Sealion 7's on-board charger: 11 kW three-phase, 7 kW single-phase (BYD AU). */
    static final float AC_MAX_KW = 11f;
    /** A battery test starts at or below this. */
    static final int TEST_FROM_PCT = 10;
    /** Smaller rises are rounding or the battery settling, not a charge. */
    static final int MIN_CHARGE_PCT = 2;

    static final class Charge {
        /** Between these two times (end of one trip, start of the next). */
        long from, to;
        /** When it was last seen still going up (car on while charging), else 0. */
        long seenUntil;
        int socFrom, socTo;
        /** kWh in the battery before and after, when the car gave it. */
        float batFrom = Float.NaN, batTo = Float.NaN, gridFrom = Float.NaN, gridTo = Float.NaN;

        /** The car's own count of what went in (not what the charger billed: losses aren't in it). */
        boolean measured() {
            return !Float.isNaN(batFrom) && !Float.isNaN(batTo) && batTo > batFrom;
        }

        /** Average kW if the whole gap between the two trips had been spent charging: the least
         *  the charger can have been doing. */
        float minKw() {
            float hours = (to - from) / 3_600_000f;
            return hours > 0.01f ? kwh() / hours : Float.NaN;
        }

        /** More than an AC charger can give this car (11 kW), so it must have been a DC fast
         *  charger. False only means "can't tell": a fast charge followed by hours parked looks
         *  the same as a slow one, so the owner can change it. */
        boolean mustBeDc() {
            float kw = minKw();
            return !Float.isNaN(kw) && kw > AC_MAX_KW + 0.5f;
        }

        /** A full test: from nearly empty to full in one go. Rounding is then worth about 1%. */
        boolean fullTest() {
            return measured() && socFrom <= TEST_FROM_PCT && socTo >= 100;
        }

        /** What a full battery holds by this charge. The dash percent is rounded down, so the
         *  start was on average half a percent above what it showed; 100 is 100. */
        float fullKwh() {
            float span = socTo >= 100 ? 100f - (socFrom + 0.5f) : socTo - socFrom;
            return measured() && span > 0 ? (batTo - batFrom) / span * 100f : Float.NaN;
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
            c.seenUntil = b.rise;
            c.socFrom = a.socEnd;
            c.socTo = b.socStart;
            c.batFrom = a.batEnd;
            c.batTo = b.batStart;
            c.gridFrom = a.gridEnd;
            c.gridTo = b.gridStart;
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

    // ---- Battery health: what a full battery holds now, against what it held new. One reading
    // per big swing of the battery (a charge, or a long drive), because the car only gives whole
    // percent: over 30% the rounding is worth about 3%, over 5% it would be 20%.

    /** BYD Australia battery warranty: 8 years / 160,000 km, at least this much capacity. */
    static final float WARRANTY_PCT = 70f;
    static final int HEALTH_MIN_SPAN = 30, HEALTH_EARLY = 3, HEALTH_SETTLED = 5, HEALTH_WINDOW = 10;

    static final class HealthReading {
        long wall;
        float odo = Float.NaN;
        /** A full battery by this reading, kWh. */
        float kwh;
        int span;
        boolean charge;

        float pct() {
            return kwh / PACK_KWH * 100f;
        }
    }

    private static void addHealth(List<HealthReading> out, long wall, float odo, float dKwh, int span, boolean charge) {
        if (span < HEALTH_MIN_SPAN || Float.isNaN(dKwh) || dKwh <= 0) return;
        float full = dKwh / span * 100f;
        if (full < PACK_KWH * 0.6f || full > PACK_KWH * 1.3f) return;   // not a believable battery
        HealthReading h = new HealthReading();
        h.wall = wall;
        h.odo = odo;
        h.kwh = full;
        h.span = span;
        h.charge = charge;
        out.add(h);
    }

    /** Oldest first. */
    static List<HealthReading> health(List<Trip> done, Trip current) {
        List<HealthReading> out = new ArrayList<>();
        Trip prev = null;
        for (int i = 0; i <= done.size(); i++) {
            Trip t = i < done.size() ? done.get(i) : current;
            if (t == null) continue;
            if (prev != null) {
                Charge c = Charge.between(prev, t);
                if (c != null && c.measured()) {
                    addHealth(out, c.to, t.odoStart, c.batTo - c.batFrom, c.socTo - c.socFrom, true);
                }
            }
            if (t != current) {
                addHealth(out, t.end, t.odoEnd, t.batStart - t.batEnd, t.socStart - t.socEnd, false);
            }
            prev = t;
        }
        return out;
    }

    // ---- Everyday health readings. The dash percent is the battery kWh over what the car's
    // battery computer takes a full battery to be, rounded down. So at the moment the percent
    // changes we know both exactly: full battery = kWh / percent. One such reading per percent
    // driven or charged, the middle one of each day is that day's dot.

    static final int TICK_MIN_SOC = 30, DAY_MIN_TICKS = 3, DAYS_SETTLED = 5;

    static final class Tick {
        long wall;
        float odo = Float.NaN, bat, kwh;
        int soc;

        String line() {
            return wall + "\t" + odo + "\t" + soc + "\t" + bat + "\t" + kwh;
        }

        static Tick parse(String s) {
            String[] f = s.split("\t");
            if (f.length < 5) return null;
            try {
                Tick t = new Tick();
                t.wall = Long.parseLong(f[0]);
                t.odo = Float.parseFloat(f[1]);
                t.soc = Integer.parseInt(f[2]);
                t.bat = Float.parseFloat(f[3]);
                t.kwh = Float.parseFloat(f[4]);
                return t;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The percent moved by one between two readings taken close together: a reading, or null.
     *  The change happened somewhere between the two, so the kWh is taken as their middle. */
    static Tick tick(Reading prev, Reading now) {
        if (prev == null || now == null || prev.soc < 0 || now.soc < 0) return null;
        if (Float.isNaN(prev.bat) || Float.isNaN(now.bat)) return null;
        if (Math.abs(now.soc - prev.soc) != 1 || now.wall - prev.wall > 180_000L || now.wall < prev.wall) return null;
        int edge = Math.max(now.soc, prev.soc);   // rounded down: the line crossed is the higher one
        if (edge < TICK_MIN_SOC) return null;     // low down, the 0.2 kWh steps are too coarse
        float mid = (prev.bat + now.bat) / 2f, full = mid / edge * 100f;
        if (full < PACK_KWH * 0.6f || full > PACK_KWH * 1.3f) return null;
        Tick t = new Tick();
        t.wall = now.wall;
        t.odo = now.odo;
        t.soc = edge;
        t.bat = mid;
        t.kwh = full;
        return t;
    }

    static List<Tick> loadTicks(File f) {
        List<Tick> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                Tick t = Tick.parse(l);
                if (t != null) out.add(t);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    static void appendTick(File f, Tick t) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(t.line() + "\n");
        }
    }

    private static float median(float[] v, int n) {
        java.util.Arrays.sort(v, 0, n);
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2f;
    }

    /** One reading per day that has DAY_MIN_TICKS or more: the middle one. span = how many. */
    static List<HealthReading> healthDays(List<Tick> ticks, Calendar cal) {
        List<HealthReading> out = new ArrayList<>();
        float[] buf = new float[ticks.size()];
        int i = 0;
        while (i < ticks.size()) {
            long day = dayStart(ticks.get(i).wall, cal);
            int n = 0, j = i;
            while (j < ticks.size() && dayStart(ticks.get(j).wall, cal) == day) buf[n++] = ticks.get(j++).kwh;
            if (n >= DAY_MIN_TICKS) {
                HealthReading h = new HealthReading();
                h.wall = ticks.get(j - 1).wall;
                h.odo = ticks.get(j - 1).odo;
                h.kwh = median(buf, n);
                h.span = n;
                out.add(h);
            }
            i = j;
        }
        return out;
    }

    /** The middle of the last HEALTH_WINDOW days; NaN with no days yet. */
    static float healthNow(List<HealthReading> days) {
        if (days.isEmpty()) return Float.NaN;
        int n = Math.min(days.size(), HEALTH_WINDOW);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = days.get(days.size() - n + i).kwh;
        return median(v, n);
    }

    /** The newest full test (nearly empty to 100% in one charge), or null. */
    static Charge lastTest(List<Charge> charges) {
        for (int i = charges.size() - 1; i >= 0; i--) if (charges.get(i).fullTest()) return charges.get(i);
        return null;
    }

    // ---- The battery test. The owner starts it, the app follows it step by step and says so,
    // and the result is the figure a buyer is shown: one charge from 10% or less to 100% on AC,
    // no driving in between. Nothing is counted unless it was started, so nobody waits half a
    // day on a test that was never running.

    static final class TestRun {
        static final int IDLE = 0, GO_LOW = 1, CHARGE = 2, DONE = 3, FAILED = 4;
        int state = IDLE;
        long startWall, lowWall, endWall;
        int lowSoc = -1, lastSoc = -1;
        float lowBat = Float.NaN, lowOdo = Float.NaN, endBat = Float.NaN, fullKwh = Float.NaN;
        String reason = "";

        boolean running() {
            return state == GO_LOW || state == CHARGE;
        }

        /** Can it start right now: the battery has to be at TEST_FROM_PCT or less already. */
        static boolean canStart(Reading now) {
            return now != null && now.soc >= 0 && now.soc <= TEST_FROM_PCT && !Float.isNaN(now.bat);
        }

        /** Starts only from a low battery (no "drive it down first" waiting state). */
        synchronized boolean start(long wall, Reading now) {
            if (!canStart(now)) return false;
            state = CHARGE;
            startWall = wall;
            endWall = 0;
            lastSoc = now.soc;
            endBat = fullKwh = Float.NaN;
            reason = "";
            setLow(now);
            return true;
        }

        synchronized void stop() {
            state = IDLE;
            reason = "";
        }

        /** kWh that went into the battery between the low point and full. */
        float kwhIn() {
            return endBat - lowBat;
        }

        /** Feed every reading while a test is on. True when something changed (save it). */
        synchronized boolean update(Reading r, boolean dcPlug) {
            if (!running() || r == null || r.soc < 0) return false;
            int before = state, beforeSoc = lastSoc, beforeLow = lowSoc;
            lastSoc = r.soc;
            if (state == GO_LOW) {
                if (r.soc <= TEST_FROM_PCT && !Float.isNaN(r.bat)) {
                    state = CHARGE;
                    setLow(r);
                }
            } else {
                boolean droveOn = !Float.isNaN(r.odo) && !Float.isNaN(lowOdo) && r.odo - lowOdo > 0.5f;
                if (r.soc <= lowSoc && !Float.isNaN(r.bat) && r.bat <= lowBat + 0.01f) {
                    setLow(r);                    // still running it down: the low point follows
                } else if (dcPlug) {
                    fail("A fast (DC) charger was plugged in. The test needs an AC charger.");
                } else if (droveOn && r.soc > lowSoc + 1) {
                    fail("The car was driven at " + r.soc + "% before the battery was full.");
                } else if (r.soc >= 100 && !Float.isNaN(r.bat)) {
                    endBat = r.bat;
                    endWall = r.wall;
                    // the dash rounds down, so the low point was on average half a percent higher
                    fullKwh = (endBat - lowBat) / (100f - (lowSoc + 0.5f)) * 100f;
                    if (fullKwh < PACK_KWH * 0.6f || fullKwh > PACK_KWH * 1.3f) {
                        fail("The car's numbers for this charge don't add up (" + one(fullKwh) + " kWh). Try again.");
                    } else {
                        state = DONE;
                    }
                }
            }
            return state != before || lastSoc != beforeSoc || lowSoc != beforeLow;
        }

        private void setLow(Reading r) {
            lowSoc = r.soc;
            lowBat = r.bat;
            lowOdo = r.odo;
            lowWall = r.wall;
        }

        private void fail(String why) {
            state = FAILED;
            reason = why;
        }

        String line() {
            return state + "\t" + startWall + "\t" + lowWall + "\t" + endWall + "\t" + lowSoc + "\t" + lastSoc + "\t"
                    + lowBat + "\t" + lowOdo + "\t" + endBat + "\t" + fullKwh + "\t" + reason.replace('\t', ' ');
        }

        static TestRun parse(String s) {
            TestRun t = new TestRun();
            if (s == null) return t;
            String[] f = s.split("\t", -1);
            if (f.length < 11) return t;
            try {
                t.state = Integer.parseInt(f[0]);
                t.startWall = Long.parseLong(f[1]);
                t.lowWall = Long.parseLong(f[2]);
                t.endWall = Long.parseLong(f[3]);
                t.lowSoc = Integer.parseInt(f[4]);
                t.lastSoc = Integer.parseInt(f[5]);
                t.lowBat = Float.parseFloat(f[6]);
                t.lowOdo = Float.parseFloat(f[7]);
                t.endBat = Float.parseFloat(f[8]);
                t.fullKwh = Float.parseFloat(f[9]);
                t.reason = f[10];
            } catch (NumberFormatException e) {
                return new TestRun();
            }
            if (t.state == GO_LOW) t.state = IDLE;   // an early build had a "drive it down" step
            return t;
        }

        static TestRun load(File f) {
            if (!f.exists()) return new TestRun();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                return parse(r.readLine());
            } catch (IOException e) {
                return new TestRun();
            }
        }

        synchronized void save(File f) throws IOException {
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(line() + "\n");
            }
            if (!tmp.renameTo(f)) throw new IOException("rename " + tmp);
        }
    }

    // ---- Money. Prices are what the owner typed; nothing is assumed.

    /** Dollars for a charge at the price for its kind, NaN when that price isn't set. */
    static float chargeCost(Charge c, boolean dc, int acCents, int dcCents) {
        int cents = dc ? dcCents : acCents;
        return cents > 0 ? c.kwh() * cents / 100f : Float.NaN;
    }

    /** What the same km would have cost in the petrol car the owner picked (litres per 100 km
     *  times ten, cents per litre). NaN until both are set. */
    static float petrolCost(float km, int l100x10, int centsPerLitre) {
        if (l100x10 <= 0 || centsPerLitre <= 0) return Float.NaN;
        return km * (l100x10 / 10f) / 100f * centsPerLitre / 100f;
    }

    static String dollars(float v) {
        if (Float.isNaN(v)) return "";
        return v >= 100 ? String.format(Locale.US, "$%,.0f", v) : String.format(Locale.US, "$%.2f", v);
    }

    /** The figure to show: the middle one of the last HEALTH_WINDOW readings (one odd charge
     *  can't move it). NaN until there are HEALTH_EARLY readings. */
    static float healthKwh(List<HealthReading> all) {
        if (all.size() < HEALTH_EARLY) return Float.NaN;
        int n = Math.min(all.size(), HEALTH_WINDOW);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = all.get(all.size() - n + i).kwh;
        java.util.Arrays.sort(v);
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2f;
    }

    /** kWh charged per calendar month, oldest first, this month last. */
    static float[] months(List<Charge> charges, long now, Calendar cal, int n) {
        float[] out = new float[n];
        cal.setTimeInMillis(now);
        int nowM = cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH);
        for (Charge c : charges) {
            cal.setTimeInMillis(c.to);
            int ago = nowM - (cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH));
            if (ago >= 0 && ago < n) out[n - 1 - ago] += c.kwh();
        }
        return out;
    }

    static String[] monthLabels(long now, Calendar cal, int n) {
        String[] out = new String[n];
        cal.setTimeInMillis(now);
        int m = cal.get(Calendar.MONTH);
        for (int i = 0; i < n; i++) out[n - 1 - i] = MONTHS[((m - i) % 12 + 12) % 12];
        return out;
    }

    /** The battery report: one page the owner can print or hand to a buyer. Plain HTML, no
     *  scripts, nothing loaded from the internet. It says what it is: the owner's log of what
     *  the car's battery computer reported, not a certificate. */
    static String report(List<Trip> done, Trip current, String vin, float odo, int carFigure, String appVersion,
                         long now, Calendar cal) {
        return report(done, current, vin, odo, Float.NaN, carFigure, appVersion, now, cal);
    }

    /** lifeKwh = kWh the car has used since new (its own counter), NaN if unknown. */
    static String report(List<Trip> done, Trip current, String vin, float odo, float lifeKwh, int carFigure,
                         String appVersion, long now, Calendar cal) {
        List<HealthReading> hr = health(done, current);
        return report(done, current, hr, healthKwh(hr), Float.NaN, vin, odo, lifeKwh, carFigure, appVersion, now, cal);
    }

    /** hr = the readings to list, full = the figure they give (NaN = none yet), testCharger =
     *  kWh the charger showed for the newest full test if the owner typed it. */
    static String report(List<Trip> done, Trip current, List<HealthReading> hr, float full, float testCharger,
                         String vin, float odo, float lifeKwh, int carFigure, String appVersion, long now,
                         Calendar cal) {
        return report(done, current, hr, full, null, testCharger, vin, odo, lifeKwh, carFigure, appVersion, now, cal);
    }

    /** test = a finished battery test (TestRun.DONE) or null. When there is one it leads the
     *  page: it is the figure a buyer should be shown. */
    static String report(List<Trip> done, Trip current, List<HealthReading> hr, float full, TestRun test,
                         float testCharger, String vin, float odo, float lifeKwh, int carFigure,
                         String appVersion, long now, Calendar cal) {
        List<Charge> ch = charges(done, current);
        if (test != null && test.state != TestRun.DONE) test = null;
        StringBuilder h = new StringBuilder(4096);
        h.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Battery report</title><style>")
                .append("body{font:15px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;color:#111;max-width:760px;margin:32px auto;padding:0 20px}")
                .append("h1{font-size:26px;margin:0}h2{font-size:17px;margin:28px 0 8px}.sub{color:#555}")
                .append(".big{font-size:40px;font-weight:600;line-height:1.1}.row{display:flex;align-items:center;gap:24px}table{border-collapse:collapse;width:100%}")
                .append("td,th{text-align:left;padding:5px 10px 5px 0;border-bottom:1px solid #ddd;font-size:14px}")
                .append(".box{border:1px solid #999;padding:12px 14px;margin-top:28px;font-size:14px}</style></head><body>");
        h.append("<h1>Battery report</h1><div class=\"sub\">BYD Sealion 7");
        if (vin != null) h.append(" &middot; VIN ").append(vin);
        h.append("<br>").append(dateLong(now, cal));
        if (!Float.isNaN(odo)) h.append(" &middot; ").append(String.format(Locale.US, "%,.0f", odo)).append(" km");
        h.append("</div>");

        if (test != null) {
            float tk = test.fullKwh;
            h.append("<h2>Battery test</h2><div class=\"row\">").append(ringSvg(tk / PACK_KWH * 100f))
                    .append("<div><div class=\"big\">").append(Math.round(Math.min(100f, tk / PACK_KWH * 100f)))
                    .append("% of new</div><div class=\"sub\">tested ").append(dateLong(test.endWall, cal))
                    .append(Float.isNaN(test.lowOdo) ? "" : String.format(Locale.US, " at %,.0f km", test.lowOdo))
                    .append("</div></div></div><p>One charge from ").append(test.lowSoc)
                    .append("% to 100% on an AC charger, with no driving in between. The battery took <b>")
                    .append(one(test.kwhIn())).append(" kWh</b>, which makes a full battery <b>").append(one(tk))
                    .append(" kWh</b> against ").append(one(PACK_KWH)).append(" kWh new.");
            if (!Float.isNaN(testCharger) && testCharger > 0) {
                float share = test.kwhIn() / testCharger;
                h.append(" The charger's own display showed ").append(one(testCharger)).append(" kWh delivered, so ")
                        .append(Math.round(share * 100f)).append("% of it reached the battery")
                        .append(share > 0.8f && share < 1.0f ? " (normal: the rest is lost as heat)."
                                : " (outside the usual 80 to 100%: check the figures).");
            }
            h.append("</p>");
        }
        h.append(test != null ? "<h2>Day-to-day readings</h2>" : "<h2>Battery health</h2>");
        if (Float.isNaN(full)) {
            h.append("<p>No figure yet: the app hasn't seen enough driving or charging.</p>");
        } else {
            float lo = Float.MAX_VALUE, hi = 0;
            int n = Math.min(hr.size(), HEALTH_WINDOW);
            for (int i = hr.size() - n; i < hr.size(); i++) {
                lo = Math.min(lo, hr.get(i).kwh);
                hi = Math.max(hi, hr.get(i).kwh);
            }
            h.append("<div class=\"row\">").append(ringSvg(full / PACK_KWH * 100f)).append("<div><div class=\"big\">about ")
                    .append(Math.round(Math.min(100f, full / PACK_KWH * 100f))).append("% of new</div><div class=\"sub\">orange mark = the ")
                    .append((int) WARRANTY_PCT).append("% warranty line</div></div></div>")
                    .append("<p>A full battery now holds about <b>").append(one(full)).append(" kWh</b> (estimate). New: ")
                    .append(one(PACK_KWH)).append(" kWh. Middle of the last ").append(n).append(" readings, which ranged from ")
                    .append(one(lo)).append(" to ").append(one(hi)).append(" kWh.")
                    .append(hr.size() < HEALTH_SETTLED ? " Fewer than " + HEALTH_SETTLED + " readings: treat as early." : "")
                    .append("</p>");
        }
        h.append("<p><b>Battery size used for the percentages: ").append(one(PACK_KWH)).append(" kWh.</b> ")
                .append(PACK_NOTE).append(" The Sealion 7 was sold with an 82.5 kWh battery (all Australian and NZ cars; "
                + "Comfort and Design in the UK and Europe) and a 91.3 kWh battery (Excellence AWD in the UK and Europe). "
                + "If the wrong size is used the percentages are wrong: measured against the smaller one, a worn larger "
                + "battery looks healthier than it is. Check the size against the car's trim, and go by the kWh figures, "
                + "which don't depend on it.</p>");
        h.append("<p class=\"sub\">BYD Australia battery warranty: 8 years or 160,000 km, at least ")
                .append((int) WARRANTY_PCT).append("% capacity.");
        if (carFigure >= 0) {
            h.append(" The car's own health figure reads ").append(carFigure)
                    .append("% (the car's figure, not this app's; it may not be a live value).");
        }
        h.append("</p>");

        if (!hr.isEmpty()) {
            h.append("<h2>Readings</h2><table><tr><th>Date</th><th>Odometer</th><th>Readings</th><th>Full battery</th><th>Of new</th></tr>");
            for (HealthReading x : hr) {
                h.append("<tr><td>").append(dateLong(x.wall, cal)).append("</td><td>")
                        .append(Float.isNaN(x.odo) ? "" : String.format(Locale.US, "%,.0f km", x.odo)).append("</td><td>")
                        .append(x.span).append("</td><td>")
                        .append(one(x.kwh)).append(" kWh</td><td>").append(Math.round(x.pct())).append("%</td></tr>");
            }
            h.append("</table>");
        }

        if (!Float.isNaN(lifeKwh) && !Float.isNaN(odo) && odo >= 1) {
            h.append("<h2>Use since new</h2><p>").append(String.format(Locale.US, "%,.0f km, %,.0f kWh", odo, lifeKwh))
                    .append(" (the car's own counters). That is about ").append(Math.round(lifeKwh / PACK_KWH))
                    .append(" full charge cycles, at an average of ").append(one(lifeKwh / odo * 100f))
                    .append(" kWh/100km.</p>");
        }
        h.append("<h2>Charging</h2>");
        if (ch.isEmpty()) {
            h.append("<p>No charges logged yet.</p>");
        } else {
            float kwh = 0;
            int toFull = 0, over80 = 0, below20 = 0;
            for (Charge c : ch) {
                kwh += c.kwh();
                if (c.socTo >= 99) toFull++;
                if (c.socTo > 80) over80++;
                if (c.socFrom < 20) below20++;
            }
            h.append("<p>").append(ch.size()).append(" charges logged since ").append(dateLong(ch.get(0).to, cal))
                    .append(", about ").append(km(kwh)).append(" kWh into the battery. ").append(toFull)
                    .append(" went to 100%, ").append(over80).append(" went above 80%, ").append(below20)
                    .append(" started below 20%.</p>");
        }
        float km = 0, kwhT = 0, kmE = 0;
        for (Trip t : done) {
            km += t.km;
            if (!Float.isNaN(t.kwh)) {
                kwhT += t.kwh;
                kmE += t.km;
            }
        }
        if (!done.isEmpty()) {
            h.append("<h2>Driving</h2><p>").append(done.size()).append(" trips logged since ")
                    .append(dateLong(done.get(0).start, cal)).append(", ").append(String.format(Locale.US, "%,.0f", km)).append(" km");
            if (kmE >= 1) h.append(", average ").append(one(kwhT / kmE * 100f)).append(" kWh/100km");
            h.append(".</p>");
        }

        h.append("<div class=\"box\"><b>What this is.</b> An owner's log made by the Nag Killer app (").append(appVersion)
                .append(") on the car's own screen. The app reads the battery percent and the kWh the car's battery computer ")
                .append("says are in the battery, each time the percent changes, and divides one by the other. ")
                .append("It is the battery computer's own estimate, good to about 2%, not an independent test and not a certificate. ")
                .append("kWh is what went into the battery, not what a charger delivered. For a sale, back it with a dealer or ")
                .append("independent battery test.</div></body></html>");
        return h.toString();
    }

    /** "$11.30" for kWh at a price in cents per kWh; "" when no price is set. */
    static String cost(float kwh, int cents) {
        if (cents <= 0 || Float.isNaN(kwh)) return "";
        return String.format(Locale.US, "$%.2f", kwh * cents / 100f);
    }

    /** The health ring as a picture for the report (three quarters of a circle, notch at 70%). */
    private static String ringSvg(float pct) {
        double r = 52, len = 2 * Math.PI * r * 0.75;
        double shown = Math.max(0, Math.min(100, pct)) / 100.0 * len;
        double ang = Math.toRadians(135 + 270 * WARRANTY_PCT / 100.0);
        return "<svg width=\"150\" height=\"140\" viewBox=\"0 0 130 124\"><g fill=\"none\" stroke-linecap=\"round\" stroke-width=\"11\" transform=\"rotate(135 65 65)\">"
                + "<circle cx=\"65\" cy=\"65\" r=\"52\" stroke=\"#e3e3e3\" stroke-dasharray=\"" + (float) len + " 999\"/>"
                + "<circle cx=\"65\" cy=\"65\" r=\"52\" stroke=\"#111\" stroke-dasharray=\"" + (float) shown + " 999\"/></g>"
                + "<line x1=\"" + (float) (65 + Math.cos(ang) * 44) + "\" y1=\"" + (float) (65 + Math.sin(ang) * 44)
                + "\" x2=\"" + (float) (65 + Math.cos(ang) * 60) + "\" y2=\"" + (float) (65 + Math.sin(ang) * 60)
                + "\" stroke=\"#e2620e\" stroke-width=\"3\"/>"
                + "<text x=\"65\" y=\"74\" text-anchor=\"middle\" font-size=\"30\" font-weight=\"600\">" + Math.round(Math.min(100f, pct))
                + "%</text><text x=\"65\" y=\"94\" text-anchor=\"middle\" font-size=\"11\" fill=\"#555\">of new</text></svg>";
    }

    static String dateLong(long wall, Calendar cal) {
        cal.setTimeInMillis(wall);
        return cal.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR);
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

    // ---- Week / month / year view of the trips.

    static final int WEEK = 0, MONTH = 1, YEAR = 2;

    static final class Period {
        /** km per bar (a day, or a month in the year view) and which bar is "now". */
        float[] bars;
        String[] labels;
        int now;
        float km, kwh, eff = Float.NaN;
        int trips, min;
    }

    /** WEEK = the 7 days ending today, MONTH = this calendar month by day, YEAR = the 12 months
     *  ending this month. Trips count by their start time. */
    static Period period(List<Trip> trips, long now, Calendar cal, int mode) {
        Period p = new Period();
        cal.setTimeInMillis(now);
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH), dom = cal.get(Calendar.DAY_OF_MONTH);
        long today = dayStart(now, cal);
        if (mode == MONTH) {
            cal.setTimeInMillis(now);
            int n = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
            p.bars = new float[n];
            p.labels = new String[n];
            for (int i = 0; i < n; i++) p.labels[i] = i % 7 == 0 ? String.valueOf(i + 1) : "";
            p.now = dom - 1;
        } else if (mode == YEAR) {
            p.bars = new float[12];
            p.labels = new String[12];
            for (int i = 0; i < 12; i++) p.labels[11 - i] = MONTHS[((m - i) % 12 + 12) % 12].substring(0, 1);
            p.now = 11;
        } else {
            p.bars = new float[7];
            p.labels = weekLabels(now, cal);
            p.now = 6;
        }
        float kmE = 0, kwhE = 0;
        for (Trip t : trips) {
            cal.setTimeInMillis(t.start);
            int ty = cal.get(Calendar.YEAR), tm = cal.get(Calendar.MONTH), td = cal.get(Calendar.DAY_OF_MONTH);
            int i;
            if (mode == MONTH) {
                i = ty == y && tm == m ? td - 1 : -1;
            } else if (mode == YEAR) {
                int ago = (y * 12 + m) - (ty * 12 + tm);
                i = ago >= 0 && ago < 12 ? 11 - ago : -1;
            } else {
                int ago = (int) Math.round((today - dayStart(t.start, cal)) / 86_400_000.0);
                i = ago >= 0 && ago < 7 ? 6 - ago : -1;
            }
            if (i < 0 || i >= p.bars.length) continue;
            p.bars[i] += t.km;
            p.km += t.km;
            p.trips++;
            p.min += Math.max(0, t.min);
            if (!Float.isNaN(t.kwh)) {
                p.kwh += t.kwh;
                kmE += t.km;
                kwhE += t.kwh;
            }
        }
        if (kmE >= 1) p.eff = kwhE / kmE * 100f;
        return p;
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
        return String.format(Locale.US, "%d h %02d min", min / 60, min % 60);
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
