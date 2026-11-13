package au.jaso.naglab;

/** Which release is newer: what the in-car updater offers hangs on this. */
public class VersionTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static void newer(String a, String b) {
        check(Version.compare(a, b) > 0 && Version.compare(b, a) < 0, a + " is newer than " + b);
    }

    public static void main(String[] args) {
        // every tag the repo has, oldest first: each must be newer than the one before
        String[] tags = {"v0.5", "v0.7", "v0.8", "v0.8.1", "v0.8.2-test1", "v0.8.2", "v0.8.3", "v0.8.4", "v0.8.5",
                "v0.9.0-exp1", "v0.9.0-exp2", "v0.9.0"};
        for (int i = 1; i < tags.length; i++) newer(tags[i], tags[i - 1]);
        newer("0.10.0", "0.9.9");
        newer("0.9.0-exp10", "0.9.0-exp9");
        newer("0.9.0", "0.9.0-exp99");
        newer("0.9.0-exp1", "0.8.6");          // a later hotfix on the old line is not an update for an experimental build
        newer("1.0", "0.99.99");
        check(Version.compare("v0.8.5", "0.8.5") == 0, "the v in front doesn't count");
        check(Version.compare("0.8", "0.8.0") == 0, "0.8 is 0.8.0");
        check(Version.compare("0.9.0-exp1", "v0.9.0-exp1") == 0, "same experimental build");
        check(Version.compare("", "0.8.5") < 0 && Version.compare("rubbish", "0.8.5") < 0, "an unreadable name is never newer");
        // GitHub's release feed as it was on 13 Nov 2026 (one link per release or tag, newest first).
        String feed = "<feed><title>Release notes from byd-nag-killer</title>"
                + "<entry><link rel=\"alternate\" type=\"text/html\" href=\"https://github.com/jasoisjaso/byd-nag-killer/releases/tag/v0.9.0-exp1\"/></entry>"
                + "<entry><link rel=\"alternate\" type=\"text/html\" href=\"https://github.com/jasoisjaso/byd-nag-killer/releases/tag/v0.8.5\"/></entry>"
                + "<entry><link rel=\"alternate\" type=\"text/html\" href=\"https://github.com/jasoisjaso/byd-nag-killer/releases/tag/v0.8.4\"/></entry>"
                + "<entry><link rel=\"alternate\" type=\"text/html\" href=\"https://github.com/jasoisjaso/byd-nag-killer/releases/tag/v0.8.2-test1\"/></entry></feed>";
        check("v0.8.5".equals(Version.newest(feed, "0.8.4")), "0.8.4 is offered 0.8.5, not the experimental build");
        check(Version.newest(feed, "0.8.5") == null, "0.8.5 is up to date");
        check("v0.9.0-exp1".equals(Version.newest(feed, "0.8.6-test1")), "a test build is offered the experimental one");
        check(Version.newest(feed, "0.9.0-exp1") == null, "0.9.0-exp1 is up to date");
        check(Version.newest(feed, "0.8.6") == null, "a build newer than every full release is offered nothing (no downgrade)");
        String later = feed.replace("<feed>", "<feed><entry><link href=\"https://github.com/jasoisjaso/byd-nag-killer/releases/tag/v0.8.6\"/></entry>"
                + "<entry><link href=\"https://github.com/jasoisjaso/byd-nag-killer/releases/tag/v0.9.0-exp2\"/></entry>");
        check("v0.9.0-exp2".equals(Version.newest(later, "0.9.0-exp1")), "0.9.0-exp1 is offered exp2, not the later 0.8.6 hotfix listed above it");
        check("v0.8.6".equals(Version.newest(later, "0.8.5")), "0.8.5 is offered the 0.8.6 hotfix");
        check(Version.newest("", "0.8.5") == null && Version.newest("<html>busy</html>", "0.8.5") == null, "an empty or odd page offers nothing");

        if (fails > 0) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        System.out.println("VersionTest OK");
    }
}
