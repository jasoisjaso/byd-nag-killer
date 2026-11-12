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
            // Charged with the car switched on, then driven off without a restart: the trip
            // starts from the charged battery, so the charge shows up before it.
            if (r.km < MIN_KM && r.soc > socStart && socStart >= 0) {
                socStart = r.soc;
                if (!Float.isNaN(r.bat)) batStart = r.bat;
            }
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
        List<Charge> ch = charges(done, current);
        float full = healthKwh(hr);
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

        h.append("<h2>Battery health</h2>");
        if (Float.isNaN(full)) {
            h.append("<p>No figure yet. It needs ").append(HEALTH_EARLY).append(" readings (a charge or drive of ")
                    .append(HEALTH_MIN_SPAN).append("% or more in one go); there are ").append(hr.size()).append(" so far.</p>");
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
        h.append("<p class=\"sub\">BYD Australia battery warranty: 8 years or 160,000 km, at least ")
                .append((int) WARRANTY_PCT).append("% capacity.");
        if (carFigure >= 0) h.append(" The car's own health figure reads ").append(carFigure).append("%.");
        h.append("</p>");

        if (!hr.isEmpty()) {
            h.append("<h2>Readings</h2><table><tr><th>Date</th><th>Odometer</th><th>From</th><th>Battery moved</th><th>Full battery</th><th>Of new</th></tr>");
            for (HealthReading x : hr) {
                h.append("<tr><td>").append(dateLong(x.wall, cal)).append("</td><td>")
                        .append(Float.isNaN(x.odo) ? "" : String.format(Locale.US, "%,.0f km", x.odo)).append("</td><td>")
                        .append(x.charge ? "charge" : "drive").append("</td><td>").append(x.span).append("%</td><td>")
                        .append(one(x.kwh)).append(" kWh</td><td>").append(Math.round(x.pct())).append("%</td></tr>");
            }
            h.append("</table>");
        }

        if (!Float.isNaN(lifeKwh) && !Float.isNaN(odo) && odo >= 1) {
            h.append("<h2>Use since new</h2><p>").append(String.format(Locale.US, "%,.0f km, %,.0f kWh", odo, lifeKwh))
                    .append(" (the car's own counters). That is ").append(one(lifeKwh / PACK_KWH))
                    .append(" full batteries' worth, at an average of ").append(one(lifeKwh / odo * 100f))
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
                .append("says are in the battery, before and after each big charge or drive, and divides one by the other. ")
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
