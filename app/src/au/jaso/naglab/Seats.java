package au.jaso.naglab;

/**
 * Seats: what each front seat is switched to at a car start (fan or heat), and the driver's
 * seat memory. Plain Java, no Android, so the Mac tests cover it.
 *
 * One choice per seat. The car runs a seat's fan or its heater, never both: seen on the owner's
 * car 26 Nov 2026, switching the heat on switched the fan off and the other way round.
 */
final class Seats {
    private Seats() {
    }

    /** A seat's choice, as saved. 0 to 2 are what 0.8.4 to 0.9.1 saved for the fans. */
    static final int OFF = 0, FAN_LOW = 1, FAN_HIGH = 2, HEAT_LOW = 3, HEAT_HIGH = 4, AUTO = 5;
    static final String[] WORDS = {"Off", "Fan low", "Fan high", "Heat low", "Heat high", "Auto"};

    /** Auto mode defaults and bounds (°C). Below heatBelow -> heat, above fanAbove -> fan,
     *  between them -> off. autoLevel is 1 (low) or 2 (high). */
    static final int DEFAULT_HEAT_BELOW = 16, DEFAULT_FAN_ABOVE = 24;
    static final int MIN_TEMP = 5, MAX_TEMP = 35;
    static final int DEFAULT_AUTO_LEVEL = 1;

    static boolean valid(int pick) {
        return pick >= OFF && pick <= AUTO;
    }

    static boolean isAuto(int pick) {
        return pick == AUTO;
    }

    static boolean heat(int pick) {
        return pick == HEAT_LOW || pick == HEAT_HIGH;
    }

    static int clampTemp(int temp) {
        return Math.max(MIN_TEMP, Math.min(MAX_TEMP, temp));
    }

    static int clampAutoLevel(int level) {
        return level == 2 ? 2 : 1;
    }

    /** Turn a saved choice into what the car should do now (OFF / FAN_* / HEAT_*).
     *  For AUTO: heat below heatBelow, fan above fanAbove, OFF between them, or -1 if
     *  outsideTemp isn't known (NaN). */
    static int resolve(int pick, float outsideTemp, int heatBelow, int fanAbove, int autoLevel) {
        if (!valid(pick)) return OFF;
        if (pick != AUTO) return pick;
        if (Float.isNaN(outsideTemp)) return -1;
        int lvl = clampAutoLevel(autoLevel);
        if (outsideTemp < heatBelow) return lvl == 2 ? HEAT_HIGH : HEAT_LOW;
        if (outsideTemp > fanAbove) return lvl == 2 ? FAN_HIGH : FAN_LOW;
        return OFF;
    }

    /** The level inside a choice: 0 off, 1 low, 2 high. */
    static int pickLevel(int pick) {
        if (pick == FAN_LOW || pick == HEAT_LOW) return 1;
        if (pick == FAN_HIGH || pick == HEAT_HIGH) return 2;
        return 0;
    }

    /** A fan or heat reading from the car (1 off, 2 low, 3 high) as a level 0 to 2, -1 = no reading. */
    static int level(int raw) {
        return raw >= 1 && raw <= 3 ? raw - 1 : -1;
    }

    /** What the car wants written for a level: 1 high, 2 low, 3 off (the reverse of its reading). */
    static int write(int level) {
        return level == 2 ? 1 : level == 1 ? 2 : 3;
    }

    /** What a seat is doing now, as a choice, from its fan level and heat level (-1 = no
     *  reading). -1 only when neither could be read. */
    static int now(int fan, int heat) {
        if (heat > 0) return heat == 2 ? HEAT_HIGH : HEAT_LOW;
        if (fan > 0) return fan == 2 ? FAN_HIGH : FAN_LOW;
        return fan < 0 && heat < 0 ? -1 : OFF;
    }

    /** The line under "Seats" on the main screen. */
    static String summary(int driver, int passenger) {
        return summary(driver, passenger, DEFAULT_HEAT_BELOW, DEFAULT_FAN_ABOVE);
    }

    /** What Auto does right now, for the Seats box: the outside reading and the outcome in words. */
    static String autoNow(float outside, int heatBelow, int fanAbove, int autoLevel) {
        if (Float.isNaN(outside)) return "The car hasn't given an outside temperature yet.";
        int t = Math.round(outside);
        int want = resolve(AUTO, outside, heatBelow, fanAbove, autoLevel);
        String lvl = clampAutoLevel(autoLevel) == 2 ? "high" : "low";
        if (heat(want)) return "It's " + t + "°C outside, so Auto seats get the heat on " + lvl + ".";
        if (want != OFF) return "It's " + t + "°C outside, so Auto seats get the fan on " + lvl + ".";
        return "It's " + t + "°C outside, in between, so Auto seats stay off.";
    }

    /** The line under "Seats" on the main screen, with the auto temperature thresholds when Auto is picked. */
    static String summary(int driver, int passenger, int heatBelow, int fanAbove) {
        boolean d = valid(driver) && driver != OFF, p = valid(passenger) && passenger != OFF;
        if (!d && !p) return "Not switched on at start.";
        StringBuilder s = new StringBuilder();
        if (d) s.append("Driver ").append(WORDS[driver].toLowerCase(java.util.Locale.ROOT));
        if (d && p) s.append(" · ");
        if (p) s.append("Passenger ").append(WORDS[passenger].toLowerCase(java.util.Locale.ROOT));
        if (driver == AUTO || passenger == AUTO) {
            s.append(" (heat below ").append(heatBelow).append("°C, fan above ").append(fanAbove).append("°C)");
        }
        return s.append(", at every start.").toString();
    }

    // ---- Seat memory: the car's own saved positions for the driver's seat.

    /** The car's Position 1 and Position 2. */
    static boolean slot(int slot) {
        return slot == 1 || slot == 2;
    }

    static final int GEAR_PARK = 1;

    /** The seat is only moved with the car in Park and standing still. */
    static boolean mayMove(int gear, float speed) {
        return gear == GEAR_PARK && !(speed > 0.5f);
    }
}
