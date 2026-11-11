package au.jaso.naglab;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Trips from the car's counters. Readings below are from the owner's drive on 2026-11-11. */
public class TripsTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static final Calendar CAL = Calendar.getInstance(TimeZone.getTimeZone("Australia/Brisbane"));

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Australia/Brisbane"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static Trips.Reading r(long wall, float km, int min, float kwh, float odo, int soc, int gear) {
        Trips.Reading x = new Trips.Reading();
        x.wall = wall;
        x.km = Trips.Reading.km(km);
        x.min = Trips.Reading.min(min);
        x.kwh = Trips.Reading.kwh(kwh);
        x.odo = Trips.Reading.odo(odo);
        x.soc = Trips.Reading.soc(soc);
        x.gear = gear;
        return x;
    }

    public static void main(String[] a) throws Exception {
        // Sentinels
        check(Float.isNaN(Trips.Reading.km(-1f)) && Float.isNaN(Trips.Reading.km(6553.5f)), "negative / huge trip km = no data");
        check(Float.isNaN(Trips.Reading.odo(1048575f)), "odo 1048575 = no data");
        check(Float.isNaN(Trips.Reading.kwh(2047f)), "kwh 2047 = no data");
        check(Trips.Reading.soc(255) == -1, "soc 255 = no data");

        // The real drive: parked 23 min, then 3.3 km.
        Trips.Tracker t = new Trips.Tracker();
        check(t.update(r(at(2026, 11, 11, 6, 28), 0.0f, 23, 0.3f, 5290.0f, 40, 1)) == null, "first reading starts a trip");
        check(t.current.start == at(2026, 11, 11, 6, 5), "start = now - trip time (6:05)");
        t.update(r(at(2026, 11, 11, 6, 31), 0.2f, 26, 0.4f, 5290.2f, 40, 4));
        t.update(r(at(2026, 11, 11, 6, 35), 3.3f, 30, 1.0f, 5293.3f, 39, 4));
        check(t.current.km == 3.3f && t.current.min == 30, "trip grows");
        check(t.current.socStart == 40 && t.current.socEnd == 39, "battery 40 -> 39");
        check(Math.abs(t.current.odoStart - 5290.0f) < 0.01, "odo at start");
        check(Math.abs(t.current.efficiency() - 30.3f) < 0.1, "1.0 kWh / 3.3 km = 30.3 kWh/100km");

        // Next start: counters reset -> the drive is finished.
        Trips.Trip done = t.update(r(at(2026, 11, 11, 17, 10), 0.0f, 0, 0.0f, 5293.3f, 39, 1));
        check(done != null && done.km == 3.3f, "counter reset ends the trip");
        check(t.current.km == 0f, "new trip starts at 0");

        // Driveway on/off: too short, dropped.
        t.update(r(at(2026, 11, 11, 17, 12), 0.05f, 2, 0.0f, 5293.35f, 39, 1));
        check(t.update(r(at(2026, 11, 11, 18, 0), 0.0f, 0, 0.0f, 5293.35f, 39, 1)) == null, "tiny trip dropped");

        // App missed the reset: odometer moved further than the trip counter says.
        Trips.Tracker t2 = new Trips.Tracker();
        t2.update(r(at(2026, 11, 12, 7, 0), 12.0f, 20, 2.0f, 5300f, 38, 4));
        Trips.Trip missed = t2.update(r(at(2026, 11, 12, 18, 0), 15.0f, 25, 2.5f, 5330f, 34, 4));
        check(missed != null && missed.km == 12.0f, "odo jump closes the old trip");
        check(t2.current.km == 15.0f, "and starts the one we see now");

        // Real car 11 Nov 15:30: minutes reset ~10 s before km/kWh. Must not make a 2nd trip.
        Trips.Tracker t4 = new Trips.Tracker();
        t4.update(r(at(2026, 11, 11, 15, 29), 9.7f, 26, 2.3f, 5400f, 32, 1));
        Trips.Trip first = t4.update(r(at(2026, 11, 11, 15, 30), 9.7f, 0, 2.3f, 5400f, 32, 1));
        check(first != null && first.km == 9.7f && first.min == 26, "minutes reset ends the trip once");
        check(t4.update(r(at(2026, 11, 11, 15, 30) + 5_000, 9.7f, 0, 2.3f, 5400f, 32, 1)) == null,
                "half-reset counters ignored");
        check(t4.update(r(at(2026, 11, 11, 15, 30) + 10_000, 0f, 0, 0f, 5400f, 32, 1)) == null
                && t4.current != null && t4.current.km == 0f, "no phantom trip when km resets 10 s later");
        // Car never finishes the reset: give up waiting after a minute.
        Trips.Tracker t5 = new Trips.Tracker();
        t5.update(r(at(2026, 11, 11, 15, 29), 9.7f, 26, 2.3f, 5400f, 32, 1));
        t5.update(r(at(2026, 11, 11, 15, 30), 9.7f, 0, 2.3f, 5400f, 32, 1));
        t5.update(r(at(2026, 11, 11, 15, 32), 9.9f, 2, 2.4f, 5400.2f, 32, 4));
        check(t5.current != null && t5.current.km == 9.9f, "hold ends after a minute");

        // No data from the car = no trip.
        Trips.Tracker t3 = new Trips.Tracker();
        check(t3.update(r(at(2026, 11, 12, 7, 0), Float.NaN, -1, Float.NaN, Float.NaN, -1, -1)) == null
                && t3.current == null, "unreadable reading ignored");

        // Moving
        check(!r(0, 0, 0, 0, 0, 0, 1).moving(), "P = parked");
        check(r(0, 0, 0, 0, 0, 0, 4).moving(), "D = moving");
        Trips.Reading sp = r(0, 0, 0, 0, 0, 0, -1);
        sp.speed = 40;
        check(sp.moving(), "unknown gear but 40 km/h = moving");

        // Storage round trip
        File dir = Files.createTempDirectory("trips").toFile();
        File f = new File(dir, "trips.tsv");
        Trips.append(f, done);
        Trips.append(f, missed);
        List<Trips.Trip> back = Trips.load(f);
        check(back.size() == 2 && back.get(0).km == 3.3f && back.get(1).socEnd == 38, "append + load");
        File cur = new File(dir, "trip-current.tsv");
        Trips.saveCurrent(cur, t2.current);
        check(Trips.loadCurrent(cur).km == 15.0f, "current trip saved and loaded");
        Files.write(f.toPath(), Arrays.asList("garbage", done.line()));
        check(Trips.load(f).size() == 1, "bad lines skipped");
        Trips.Trip ph = Trips.Trip.parse(first.line());
        ph.start = first.end + 10_000;
        ph.end = ph.start;
        ph.min = 0;
        Files.write(f.toPath(), Arrays.asList(first.line(), ph.line()));
        check(Trips.load(f).size() == 1, "stored phantom copy skipped on load");

        // Summaries
        long now = at(2026, 11, 12, 20, 0);
        List<Trips.Trip> all = new ArrayList<>(Arrays.asList(done, missed));
        float[] wk = Trips.week(all, now, CAL);
        check(wk[6] == 12.0f && wk[5] == 3.3f, "week bars: yesterday 3.3, today 12");
        float[] tot = Trips.weekTotals(all, now, CAL);
        check(Math.abs(tot[0] - 15.3f) < 0.01 && Math.abs(tot[1] - 3.0f / 15.3f * 100f) < 0.1, "week totals");
        check(Trips.newestFirst(all, t2.current).get(0) == t2.current, "current trip first");
        check(Arrays.equals(Trips.weekLabels(now, CAL), new String[]{"F", "S", "S", "M", "T", "W", "T"}),
                "day letters end on Thursday 12 Nov");

        // Short stop merged, long stop not
        Trips.Trip m1 = new Trips.Trip();
        m1.start = at(2026, 11, 13, 7, 0); m1.end = at(2026, 11, 13, 7, 30); m1.km = 30; m1.kwh = 5; m1.socStart = 80; m1.socEnd = 74;
        Trips.Trip m2 = new Trips.Trip();
        m2.start = at(2026, 11, 13, 7, 35); m2.end = at(2026, 11, 13, 8, 0); m2.km = 20; m2.kwh = 3; m2.socStart = 74; m2.socEnd = 70;
        Trips.Trip m3 = new Trips.Trip();
        m3.start = at(2026, 11, 13, 17, 0); m3.end = at(2026, 11, 13, 17, 40); m3.km = 50; m3.kwh = 9;
        List<Trips.Trip> mg = Trips.merge(Arrays.asList(m1, m2, m3));
        check(mg.size() == 2 && mg.get(0).km == 50f && mg.get(0).kwh == 8f && mg.get(0).min == 60,
                "5 min stop merged: 50 km, 8 kWh, 60 min");
        check(mg.get(0).socStart == 80 && mg.get(0).socEnd == 70, "merged battery 80 -> 70");
        check(mg.get(1) == m3, "9 hour gap not merged");
        check(Trips.newestFirst(Arrays.asList(m1, m2), null).size() == 1, "list shows the merged trip");

        // Formatting
        check(Trips.km(12.44f).equals("12.4") && Trips.km(148.2f).equals("148"), "km format");
        check(Trips.duration(23).equals("23 min") && Trips.duration(65).equals("1 h 05"), "durations");
        check(Trips.clock(at(2026, 11, 11, 7, 42), CAL).equals("7:42 am"), "7:42 am");
        check(Trips.clock(at(2026, 11, 11, 12, 5), CAL).equals("12:05 pm"), "12:05 pm");
        check(Trips.day(at(2026, 11, 12, 7, 0), now, CAL).equals("Today"), "Today");
        check(Trips.day(at(2026, 11, 11, 7, 0), now, CAL).equals("Yesterday"), "Yesterday");
        check(Trips.day(at(2026, 11, 9, 7, 0), now, CAL).equals("Mon 9 Nov"), "Mon 9 Nov");
        check(Trips.one(Float.NaN).equals("–"), "missing value shows a dash");

        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("TripsTest OK");
    }
}
