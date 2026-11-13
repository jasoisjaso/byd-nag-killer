package au.jaso.naglab;

/**
 * Version names the way this app and its GitHub tags write them: "0.8.5", "0.9.0-exp2", with or
 * without a leading "v". Pure Java, tested on the Mac.
 */
final class Version {
    private Version() {
    }

    /**
     * Negative when a is older than b, 0 when they are the same, positive when a is newer.
     * The numbers decide first, part by part (0.10 is newer than 0.9, 0.8.1 newer than 0.8).
     * With the same numbers, a plain release is newer than one with a suffix (0.9.0 comes after
     * 0.9.0-exp2), and two suffixes of the same kind go by their number (exp2 after exp1).
     */
    static int compare(String a, String b) {
        String[] pa = split(a), pb = split(b);
        String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            int x = i < na.length ? num(na[i]) : 0, y = i < nb.length ? num(nb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        String sa = pa[1], sb = pb[1];
        if (sa.isEmpty() || sb.isEmpty()) {
            return sa.isEmpty() == sb.isEmpty() ? 0 : sa.isEmpty() ? 1 : -1;
        }
        String wa = sa.replaceAll("\\d+$", ""), wb = sb.replaceAll("\\d+$", "");
        if (!wa.equals(wb)) return wa.compareTo(wb) < 0 ? -1 : 1;
        return Integer.compare(num(sa.substring(wa.length())), num(sb.substring(wb.length())));
    }

    /**
     * Which release to offer, from GitHub's release feed (releases.atom: one link per release,
     * ending in /releases/tag/TAG). The newest one that is really newer than what is installed,
     * whatever order they are listed in. Pre-releases carry a suffix (v0.9.0-exp2) and are only
     * offered to someone already on a build with a suffix. Returns the tag, or null when there
     * is nothing newer.
     */
    static String newest(String feed, String installed) {
        boolean experimental = installed.contains("-");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/releases/tag/([^\"'<>\\s]+)").matcher(feed);
        String best = null;
        while (m.find()) {
            String tag = m.group(1);
            if (tag.contains("-") && !experimental) continue;
            if (compare(tag, installed) <= 0) continue;
            if (best == null || compare(tag, best) > 0) best = tag;
        }
        return best;
    }

    /** {numbers, suffix} of "v0.9.0-exp2" = {"0.9.0", "exp2"}. */
    private static String[] split(String v) {
        v = v == null ? "" : v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int d = v.indexOf('-');
        return d < 0 ? new String[]{v, ""} : new String[]{v.substring(0, d), v.substring(d + 1)};
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
