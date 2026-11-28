package au.jaso.naglab;

/** Seat fans, seat heat and the seat memory: the values come from the owner's car, 8 and 26 Nov 2026. */
public class SeatsTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        // What the car reads: 1 off, 2 low, 3 high (fans 354/462, heat 346/459).
        check(Seats.level(1) == 0 && Seats.level(2) == 1 && Seats.level(3) == 2, "the car reads 1 off, 2 low, 3 high");
        check(Seats.level(0) == -1 && Seats.level(4) == -1 && Seats.level(Integer.MIN_VALUE) == -1,
                "anything else is no reading");
        // What the car wants written: the reverse, 1 high, 2 low, 3 off (fans 358/359, heat 350/351).
        check(Seats.write(2) == 1 && Seats.write(1) == 2 && Seats.write(0) == 3, "the car is written 1 high, 2 low, 3 off");

        // One choice per seat: the car runs the fan or the heater, never both (26 Nov: heat low
        // switched the fan off, fan low switched the heat off).
        check(!Seats.heat(Seats.OFF) && !Seats.heat(Seats.FAN_LOW) && !Seats.heat(Seats.FAN_HIGH)
                && Seats.heat(Seats.HEAT_LOW) && Seats.heat(Seats.HEAT_HIGH), "which choices are heat");
        check(Seats.pickLevel(Seats.OFF) == 0 && Seats.pickLevel(Seats.FAN_LOW) == 1 && Seats.pickLevel(Seats.FAN_HIGH) == 2
                && Seats.pickLevel(Seats.HEAT_LOW) == 1 && Seats.pickLevel(Seats.HEAT_HIGH) == 2, "the level inside a choice");
        // Choices saved by 0.8.4 to 0.9.1 (0 off, 1 low, 2 high, fans only) mean the same thing now.
        check(Seats.OFF == 0 && Seats.FAN_LOW == 1 && Seats.FAN_HIGH == 2, "an older version's saved fan choice still means the same");
        check(Seats.valid(0) && Seats.valid(4) && Seats.valid(Seats.AUTO) && !Seats.valid(6) && !Seats.valid(-1),
                "only the six choices are valid");
        check(Seats.isAuto(Seats.AUTO) && !Seats.isAuto(Seats.OFF) && !Seats.isAuto(Seats.HEAT_LOW), "isAuto");

        // Auto mode: below heatBelow -> heat, above fanAbove -> fan, between -> off, NaN -> -1.
        check(Seats.resolve(Seats.FAN_HIGH, 10f, 16, 24, 1) == Seats.FAN_HIGH, "non-auto passes through");
        check(Seats.resolve(Seats.OFF, 10f, 16, 24, 1) == Seats.OFF, "off passes through");
        check(Seats.resolve(99, 10f, 16, 24, 1) == Seats.OFF, "invalid choice resolves to off");
        check(Seats.resolve(Seats.AUTO, Float.NaN, 16, 24, 1) == -1, "auto with no temp reading waits");
        check(Seats.resolve(Seats.AUTO, 15.5f, 16, 24, 1) == Seats.HEAT_LOW, "below heatBelow -> heat low");
        check(Seats.resolve(Seats.AUTO, 12.0f, 16, 24, 2) == Seats.HEAT_HIGH, "below heatBelow (high) -> heat high");
        check(Seats.resolve(Seats.AUTO, 24.5f, 16, 24, 1) == Seats.FAN_LOW, "above fanAbove -> fan low");
        check(Seats.resolve(Seats.AUTO, 30.0f, 16, 24, 2) == Seats.FAN_HIGH, "above fanAbove (high) -> fan high");
        check(Seats.resolve(Seats.AUTO, 16.0f, 16, 24, 1) == Seats.OFF, "at heatBelow -> off");
        check(Seats.resolve(Seats.AUTO, 20.0f, 16, 24, 2) == Seats.OFF, "between thresholds -> off");
        check(Seats.resolve(Seats.AUTO, 24.0f, 16, 24, 1) == Seats.OFF, "at fanAbove -> off");
        check(Seats.clampTemp(0) == Seats.MIN_TEMP && Seats.clampTemp(50) == Seats.MAX_TEMP
                && Seats.clampTemp(18) == 18, "clampTemp bounds");
        check(Seats.clampAutoLevel(1) == 1 && Seats.clampAutoLevel(2) == 2 && Seats.clampAutoLevel(9) == 1,
                "clampAutoLevel");

        // What a seat is doing now, from its fan reading and its heat reading.
        check(Seats.now(0, 0) == Seats.OFF, "fan off and heat off is Off");
        check(Seats.now(1, 0) == Seats.FAN_LOW && Seats.now(2, 0) == Seats.FAN_HIGH, "fan on");
        check(Seats.now(0, 1) == Seats.HEAT_LOW && Seats.now(0, 2) == Seats.HEAT_HIGH, "heat on");
        check(Seats.now(-1, -1) == -1, "no reading from either is unknown");
        check(Seats.now(2, -1) == Seats.FAN_HIGH && Seats.now(0, -1) == Seats.OFF,
                "a car with no heat reading still shows its fan");
        check(Seats.now(-1, 2) == Seats.HEAT_HIGH, "a car with no fan reading still shows its heat");

        // The row under "Seats" on the main screen.
        check("Not switched on at start.".equals(Seats.summary(Seats.OFF, Seats.OFF)), "nothing picked");
        check("Driver fan low · Passenger heat high, at every start."
                .equals(Seats.summary(Seats.FAN_LOW, Seats.HEAT_HIGH)), "both seats picked");
        check("Driver heat low, at every start.".equals(Seats.summary(Seats.HEAT_LOW, Seats.OFF)), "driver only");
        check("Passenger fan high, at every start.".equals(Seats.summary(Seats.OFF, Seats.FAN_HIGH)), "passenger only");
        // The "now" line in the Seats box (owner, 28 Nov: 24°C outside with Fan above 24 looked broken).
        check("It's 24°C outside, in between, so Auto seats stay off.".equals(Seats.autoNow(24f, 16, 24, 1)),
                "at the fan temperature exactly: says it stays off and why");
        check("It's 24°C outside, so Auto seats get the fan on high.".equals(Seats.autoNow(24f, 16, 22, 2)),
                "above Fan above: fan, with the level");
        check("It's 9°C outside, so Auto seats get the heat on low.".equals(Seats.autoNow(9f, 16, 24, 1)),
                "below Heat below: heat");
        check(Seats.autoNow(Float.NaN, 16, 24, 1).startsWith("The car hasn't given"), "no reading yet");
        check("Driver auto (heat below 16°C, fan above 24°C), at every start."
                .equals(Seats.summary(Seats.AUTO, Seats.OFF, 16, 24)), "driver auto summary");
        check("Driver auto · Passenger auto (heat below 15°C, fan above 25°C), at every start."
                .equals(Seats.summary(Seats.AUTO, Seats.AUTO, 15, 25)), "both auto summary");
        check("Not switched on at start.".equals(Seats.summary(9, -3)), "a rubbish saved value counts as Off");

        // Seat memory: the car's Position 1 and Position 2. Only with the car in Park and standing still.
        check(Seats.slot(1) && Seats.slot(2) && !Seats.slot(0) && !Seats.slot(3), "positions 1 and 2 only");
        check(Seats.mayMove(1, 0f), "in Park and stopped: the seat may move");
        check(!Seats.mayMove(4, 0f) && !Seats.mayMove(2, 0f) && !Seats.mayMove(3, 0f), "not in Drive, Reverse or Neutral");
        check(!Seats.mayMove(1, 3f), "not while the car is rolling");
        check(!Seats.mayMove(-1, 0f), "not when the gear can't be read");
        check(Seats.mayMove(1, Float.NaN), "in Park with no speed reading is still Park");

        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("SeatsTest: all passed");
    }
}
