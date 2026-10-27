package au.jaso.naglab;

import java.util.List;

/**
 * Words for what a switch-off did, shared by the app screen and the quick button's card.
 * Pure Java so test/run.sh checks it on the Mac.
 */
final class Report {
    static final String SWITCHED_OFF = "switched off", ALREADY_OFF = "already off",
            STILL_ON = "still on", SWITCHED_ON = "switched on", ALREADY_ON = "already on",
            STILL_OFF = "still off", NO_ANSWER = "no answer";

    /** Card verdicts. The worst one in the set wins: still on, then no answer. */
    static final int NOTHING = 0, ALL_OFF = 1, UNKNOWN = 2, FAILED = 3;

    private Report() {
    }

    /** One nag's result. States: 1 on, 0 off, -1 unknown. kill = we asked for off. */
    static String outcome(int before, int after, boolean kill) {
        int want = kill ? 0 : 1;
        if (after == want) {
            if (before == want) return kill ? ALREADY_OFF : ALREADY_ON;
            return kill ? SWITCHED_OFF : SWITCHED_ON;
        }
        if (after < 0) return NO_ANSWER;
        return kill ? STILL_ON : STILL_OFF;
    }

    static boolean isOff(String outcome) {
        return SWITCHED_OFF.equals(outcome) || ALREADY_OFF.equals(outcome);
    }

    static int verdict(List<String> outcomes) {
        if (outcomes.isEmpty()) return NOTHING;
        int v = ALL_OFF;
        for (String o : outcomes) {
            if (STILL_ON.equals(o)) return FAILED;
            if (NO_ANSWER.equals(o)) v = UNKNOWN;
        }
        return v;
    }

    /** Card header: one or two words, read at a glance. */
    static String header(int verdict) {
        switch (verdict) {
            case ALL_OFF: return "All off";
            case UNKNOWN: return "No answer";
            case FAILED: return "Still on";
            default: return "Nothing set";
        }
    }

    static final String NOTHING_HINT = "Pick what the buttons switch off, under On screen.";

    /** The big button. kept = how many nags are in the button set. */
    static String buttonLabel(int kept, boolean working) {
        if (working) return "Switching off...";
        return kept <= 0 ? "Switch off now" : "Switch off now (" + kept + ")";
    }
}
