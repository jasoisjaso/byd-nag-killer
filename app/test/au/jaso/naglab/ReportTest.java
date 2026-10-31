package au.jaso.naglab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** Result words and card verdicts for the big button and the quick button. */
public class ReportTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // kill: before/after 1 on, 0 off, -1 unknown
        check(Report.outcome(1, 0, true).equals(Report.SWITCHED_OFF), "on -> off = switched off");
        check(Report.outcome(0, 0, true).equals(Report.ALREADY_OFF), "off -> off = already off");
        check(Report.outcome(-1, 0, true).equals(Report.SWITCHED_OFF), "unknown -> off = switched off");
        check(Report.outcome(1, 1, true).equals(Report.STILL_ON), "on -> on = still on");
        check(Report.outcome(1, -1, true).equals(Report.NO_ANSWER), "on -> unknown = no answer");
        check(Report.outcome(-1, -1, true).equals(Report.NO_ANSWER), "unknown -> unknown = no answer");
        // restore
        check(Report.outcome(0, 1, false).equals(Report.SWITCHED_ON), "restore off -> on");
        check(Report.outcome(1, 1, false).equals(Report.ALREADY_ON), "restore on -> on");
        check(Report.outcome(0, 0, false).equals(Report.STILL_OFF), "restore off -> off");

        check(Report.isOff(Report.SWITCHED_OFF) && Report.isOff(Report.ALREADY_OFF), "isOff for off results");
        check(!Report.isOff(Report.STILL_ON) && !Report.isOff(Report.NO_ANSWER), "isOff false otherwise");

        check(Report.verdict(Collections.<String>emptyList()) == Report.NOTHING, "empty set = nothing");
        check(Report.verdict(Arrays.asList(Report.SWITCHED_OFF, Report.ALREADY_OFF)) == Report.ALL_OFF,
                "all off");
        check(Report.verdict(Arrays.asList(Report.SWITCHED_OFF, Report.NO_ANSWER)) == Report.UNKNOWN,
                "no answer beats off");
        check(Report.verdict(Arrays.asList(Report.NO_ANSWER, Report.STILL_ON, Report.SWITCHED_OFF))
                == Report.FAILED, "still on beats no answer");
        check(!Report.header(Report.UNKNOWN).equals(Report.header(Report.FAILED)),
                "no answer and still on read differently");
        ArrayList<String> seen = new ArrayList<>();
        for (int v : new int[]{Report.NOTHING, Report.ALL_OFF, Report.UNKNOWN, Report.FAILED}) {
            String h = Report.header(v);
            check(!seen.contains(h) && h.length() <= 12, "header '" + h + "' unique and short");
            seen.add(h);
        }

        check(Report.buttonLabel(0, false).equals("Switch off now"), "button with an empty button set");
        check(Report.buttonLabel(2, false).equals("Switch off now (2)"), "button names the count");
        check(Report.buttonLabel(2, true).startsWith("Switching off"), "button while working");

        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("ReportTest OK");
    }
}
