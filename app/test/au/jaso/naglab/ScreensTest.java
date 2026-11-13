package au.jaso.naglab;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;

/**
 * Draws the app's real screens on a computer and checks the layout, so a screen that only goes
 * wrong with certain numbers (a 46.5 km trip, a drive of over an hour) is caught without the car.
 * Run with test/screens.sh; the pictures land in build/screens/.
 *
 * The screen is the car's: 1920 x 1080 at 240 dpi, less the car's own bars (84 px on top, 90 px
 * at the bottom, measured off a screenshot from the car), which leaves 1280 x 604 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1280dp-h604dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {
    private final List<String> problems = new ArrayList<>();

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void clean() {
        NagService.live = null;
        NagService.tripNow = null;
        NagService.running = true;
        NagService.connected = true;
        NagService.tripsVersion++;
        // prices set, so the petrol line shows at its full length
        app().getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(BatteryScreen.KEY_PRICE, 31).putInt(BatteryScreen.KEY_PRICE_DC, 65)
                .putInt(BatteryScreen.KEY_PETROL, 250).putInt(BatteryScreen.KEY_L100, 120)
                // the helper counts as running: the screen must not go looking for the car's adb
                .putLong(HelperPing.KEY_PING, Math.max(1, SystemClock.elapsedRealtime()))
                .putLong(HelperPing.KEY_BOOT, HelperPing.bootId())
                .commit();
    }

    // ---- A few days of trips of the usual sort (school run, shops, one longer drive), ending today.

    private static long at(int daysAgo, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() - daysAgo * 86_400_000L;
    }

    private static Trips.Trip trip(int daysAgo, int h, int m, int min, float km, float kwh,
                                   int soc0, int soc1, float odo0) {
        Trips.Trip t = new Trips.Trip();
        t.start = at(daysAgo, h, m);
        t.end = t.start + min * 60_000L;
        t.min = min;
        t.km = km;
        t.kwh = kwh;
        t.socStart = soc0;
        t.socEnd = soc1;
        t.odoStart = odo0;
        t.odoEnd = odo0 + km;
        return t;
    }

    private static List<Trips.Trip> history() {
        List<Trips.Trip> l = new ArrayList<>();
        l.add(trip(2, 14, 10, 26, 9.7f, 2.3f, 33, 32, 12009.8f));
        l.add(trip(2, 14, 36, 23, 7.6f, 1.8f, 32, 30, 12019.5f));
        l.add(trip(2, 17, 50, 37, 12.7f, 2.3f, 30, 27, 12027.1f));
        l.add(trip(1, 9, 5, 12, 6.4f, 0.7f, 27, 26, 12039.8f));
        l.add(trip(1, 9, 26, 8, 2.9f, 0.6f, 26, 25, 12046.2f));
        l.add(trip(1, 9, 39, 8, 5.1f, 1.2f, 25, 24, 12049.1f));
        l.add(trip(1, 12, 20, 12, 6.8f, 1.0f, 24, 54, 12054.2f));
        l.add(trip(1, 16, 15, 8, 3.1f, 0.6f, 54, 54, 12061.0f));
        l.add(trip(1, 17, 5, 5, 4.3f, 1.0f, 54, 52, 12064.2f));
        l.add(trip(0, 8, 20, 14, 6.9f, 0.9f, 57, 55, 12068.6f));
        l.add(trip(0, 8, 38, 23, 12.2f, 2.3f, 55, 52, 12075.4f));
        l.add(trip(0, 9, 2, 22, 9.7f, 1.5f, 52, 50, 12087.7f));
        l.add(trip(0, 9, 43, 8, 4.8f, 1.1f, 50, 49, 12097.3f));
        return l;
    }

    private static void store(List<Trips.Trip> trips) throws Exception {
        try (PrintWriter w = new PrintWriter(new File(app().getFilesDir(), NagService.TRIPS_FILE), "UTF-8")) {
            for (Trips.Trip t : trips) w.println(t.line());
        }
    }

    private static Trips.Reading car(boolean driving, float km, int min, float kwh, int soc, int range,
                                     float odo, float life) {
        Trips.Reading r = new Trips.Reading();
        r.wall = System.currentTimeMillis();
        r.km = km;
        r.min = min;
        r.kwh = kwh;
        r.soc = soc;
        r.range = range;
        r.odo = odo;
        r.life = life;
        r.gear = driving ? 4 : Trips.GEAR_P;
        r.speed = driving ? 72 : 0;
        return r;
    }

    /** On the road: the trip in progress, as the car's counters give it. */
    private static void driving(float km, int min, float kwh, int soc0, int soc, int range) {
        Trips.Trip t = new Trips.Trip();
        t.start = System.currentTimeMillis() - min * 60_000L;
        t.end = System.currentTimeMillis();
        t.km = km;
        t.min = min;
        t.kwh = kwh;
        t.socStart = soc0;
        t.socEnd = soc;
        NagService.tripNow = t;
        NagService.live = car(true, km, min, kwh, soc, range, 12114.8f + km, 2241.2f + kwh);
    }

    // ---- Scenes

    /** Parked after a short trip (4.8 km). This always looked fine and has to stay that way. */
    @Test
    public void tripsParkedShortTrip() throws Exception {
        store(history());
        NagService.live = car(false, 0, 0, 0, 49, 262, 12102.1f, 2239f);
        check("trips-parked-4.8km", MainActivity.TAB_TRIPS);
    }

    /** Parked after 46.5 km at 17.8 kWh/100km: on the car this read "46.517.8". */
    @Test
    public void tripsParkedAfter46km() throws Exception {
        tripsParkedAfter46kmScene();
        check("trips-parked-46.5km", MainActivity.TAB_TRIPS);
    }

    private static void tripsParkedAfter46kmScene() throws Exception {
        List<Trips.Trip> l = history();
        l.add(trip(0, 15, 30, 29, 12.7f, 2.1f, 49, 45, 12102.1f));
        l.add(trip(0, 17, 12, 59, 46.5f, 8.28f, 45, 34, 12114.8f));
        store(l);
        NagService.live = car(false, 0, 0, 0, 34, 170, 12163f, 2249f);
    }

    /** Driving, 10.8 km in: on the car this read "10.822.2". */
    @Test
    public void tripsDriving10km() throws Exception {
        store(history());
        driving(10.8f, 9, 2.4f, 45, 42, 230);
        check("trips-driving-10.8km", MainActivity.TAB_TRIPS);
    }

    /** Driving, 32.2 km in: on the car this read "32.218.6". */
    @Test
    public void tripsDriving32km() throws Exception {
        store(history());
        driving(32.2f, 38, 6.0f, 45, 37, 199);
        check("trips-driving-32.2km", MainActivity.TAB_TRIPS);
    }

    /** A drive of over an hour: the time turns into 1:35. */
    @Test
    public void tripsDrivingOverAnHour() throws Exception {
        store(history());
        driving(88.4f, 95, 15.9f, 96, 76, 318);
        check("trips-driving-1h35", MainActivity.TAB_TRIPS);
    }

    /** A long run: three-figure km, a thirsty stretch, hours on the clock. */
    @Test
    public void tripsDrivingLong() throws Exception {
        store(history());
        driving(412f, 618, 101.2f, 100, 4, 12);
        check("trips-driving-412km", MainActivity.TAB_TRIPS);
    }

    /** A 61 minute drive that an earlier build stored as two trips (cut at the hour, the second
     *  with the whole drive's km): it shows as one trip, 47.6 km in 1:01, not as 94.1 km. */
    @Test
    public void tripsParkedAfterADriveCutAtTheHour() throws Exception {
        List<Trips.Trip> l = history();
        l.add(trip(0, 17, 12, 59, 46.5f, 8.3f, 45, 34, 12114.9f));
        Trips.Trip second = trip(0, 18, 12, 1, 47.6f, 8.4f, 34, 34, 12114.9f);
        second.end = second.start + 2 * 60_000L;
        l.add(second);
        store(l);
        NagService.live = car(false, 0, 0, 0, 34, 168, 12162.5f, 2249f);
        List<TextView> shown = check("trips-parked-1h01", MainActivity.TAB_TRIPS);
        if (!shows(shown, "47.6") || !shows(shown, "1:01") || !shows(shown, "1 h 01 min") || shows(shown, "94.1")) {
            fail("the drive cut at the hour is not shown as one trip of 47.6 km in 1:01");
        }
    }

    /** Nothing recorded and the car not answering yet. */
    @Test
    public void tripsEmpty() throws Exception {
        check("trips-empty", MainActivity.TAB_TRIPS);
    }

    /** The screen turned upright, parked after the 46.5 km trip. */
    @Test
    @Config(qualifiers = "w720dp-h1164dp-port-hdpi")
    public void tripsUprightAfter46km() throws Exception {
        tripsParkedAfter46kmScene();
        check("trips-upright-46.5km", MainActivity.TAB_TRIPS);
    }

    /** Upright and driving for over an hour. */
    @Test
    @Config(qualifiers = "w720dp-h1164dp-port-hdpi")
    public void tripsUprightOverAnHour() throws Exception {
        store(history());
        driving(88.4f, 95, 15.9f, 96, 76, 318);
        check("trips-upright-1h35", MainActivity.TAB_TRIPS);
    }

    // ---- Drawing and checking

    private List<TextView> check(String name, int tab) throws Exception {
        app().getSharedPreferences(NagService.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(MainActivity.KEY_TAB, tab).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);   // tab fade, bars, one refresh
        View decor = a.getWindow().getDecorView();

        File out = new File(System.getProperty("screens.out", "build/screens"));
        out.mkdirs();
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(b));
        try (FileOutputStream o = new FileOutputStream(new File(out, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }

        List<TextView> shown = new ArrayList<>();
        collect(decor, shown);
        try (PrintWriter w = new PrintWriter(new File(out, name + ".txt"), "UTF-8")) {
            w.println(decor.getWidth() + " x " + decor.getHeight());
            for (TextView t : shown) {
                int[] at = new int[2];
                t.getLocationInWindow(at);
                w.println(at[0] + "," + at[1] + " " + t.getWidth() + "x" + t.getHeight() + " "
                        + Math.round(t.getTextSize()) + "px  " + t.getText());
            }
        }
        textFits(name, shown);
        figuresApart(name, shown);
        if (!problems.isEmpty()) fail(String.join("\n", problems));
        return shown;
    }

    private static boolean shows(List<TextView> shown, String text) {
        for (TextView t : shown) if (t.getText().toString().contains(text)) return true;
        return false;
    }

    private static void collect(View v, List<TextView> out) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getText() != null && t.getText().toString().trim().length() > 0
                    && t.getLayout() != null && t.getWidth() > 0
                    && t.getGlobalVisibleRect(new Rect())) {
                out.add(t);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    /** Every piece of text is all there: not cut at the side, not ending in "...", no lines lost. */
    private void textFits(String scene, List<TextView> shown) {
        for (TextView t : shown) {
            Layout l = t.getLayout();
            String what = scene + ": \"" + t.getText() + "\" ";
            int room = t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight();
            for (int i = 0; i < l.getLineCount(); i++) {
                if (l.getLineMax(i) > room + 1f) {
                    problems.add(what + "is cut off: it needs " + Math.round(l.getLineMax(i))
                            + " px and has " + room);
                }
                if (l.getEllipsisCount(i) > 0) problems.add(what + "ends in ...");
            }
            if (t.getMaxLines() > 0 && l.getLineCount() > t.getMaxLines()) {
                problems.add(what + "runs to " + l.getLineCount() + " lines, " + t.getMaxLines() + " shown");
            }
            Rect seen = new Rect();
            t.getGlobalVisibleRect(seen);
            if (seen.width() < t.getWidth()) {
                problems.add(what + "is cut at the side by what's around it (" + seen.width()
                        + " of " + t.getWidth() + " px showing)");
            }
        }
    }

    /** Big figures side by side read as separate figures: at least 0.3 of their height apart. */
    private void figuresApart(String scene, List<TextView> shown) {
        float big = 40 * app().getResources().getDisplayMetrics().scaledDensity;
        List<int[]> spans = new ArrayList<>();   // left, right, top, bottom, index
        for (int i = 0; i < shown.size(); i++) {
            TextView t = shown.get(i);
            if (t.getTextSize() < big) continue;
            int[] at = new int[2];
            t.getLocationInWindow(at);
            int left = at[0] + t.getTotalPaddingLeft() + Math.round(t.getLayout().getLineLeft(0));
            int right = left + Math.round(t.getLayout().getLineMax(0));
            spans.add(new int[]{left, right, at[1], at[1] + t.getHeight(), i});
        }
        Collections.sort(spans, (x, y) -> x[0] - y[0]);
        for (int i = 0; i + 1 < spans.size(); i++) {
            int[] p = spans.get(i), n = spans.get(i + 1);
            if (p[3] <= n[2] || n[3] <= p[2]) continue;   // not on the same line
            TextView pt = shown.get(p[4]), nt = shown.get(n[4]);
            float need = 0.3f * Math.min(pt.getTextSize(), nt.getTextSize());
            int gap = n[0] - p[1];
            if (gap < need) {
                problems.add(scene + ": \"" + pt.getText() + "\" and \"" + nt.getText() + "\" run together ("
                        + gap + " px apart, " + Math.round(need) + " needed)");
            }
        }
    }
}
