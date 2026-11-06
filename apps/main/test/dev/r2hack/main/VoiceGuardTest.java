package dev.r2hack.main;

/** Off-robot tests for VoiceGuard: drive commands said twice in a noisy room (docs/noisy-room.md). Run with apps/test.sh. */
public final class VoiceGuardTest {
    private static int passed, failed;

    public static void main(String[] args) {
        VoiceGuard g = new VoiceGuard();
        VoiceGuard.Verdict RUN = VoiceGuard.Verdict.RUN, ASK = VoiceGuard.Verdict.ASK;
        check("a quiet room: patrol runs at once", g.check("patrol", true, false, 0), RUN);
        check("a noisy room: who-are-you runs at once (doesn't move)", g.check("who-are-you", false, true, 1000), RUN);
        check("a noisy room: patrol once only asks", g.check("patrol", true, true, 2000), ASK);
        check("waiting for patrol again", "patrol".equals(g.pending(3000)), true);
        check("patrol again within 8 s runs", g.check("patrol", true, true, 9000), RUN);
        check("nothing waiting after that", g.pending(9000) == null, true);
        check("the next patrol asks again", g.check("patrol", true, true, 10_000), ASK);
        check("too late: asks again", g.check("patrol", true, true, 10_000 + VoiceGuard.CONFIRM_MS + 1), ASK);
        check("a different drive command asks too", g.check("forward", true, true, 20_000), ASK);
        check("and doesn't confirm the other one", g.check("patrol", true, true, 21_000), ASK);
        check("stop runs and forgets what was waiting", g.check("stop", false, true, 22_000), RUN);
        check("so patrol after stop asks", g.check("patrol", true, true, 23_000), ASK);
        check("TV mode off again: runs at once", g.check("circle", true, false, 24_000), RUN);

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, Object got, Object want) {
        if (got.equals(want)) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + ": got " + got + ", expected " + want);
        }
    }
}
