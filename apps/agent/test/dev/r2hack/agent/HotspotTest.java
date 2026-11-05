package dev.r2hack.agent;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Off-robot tests for the hotspot password helpers. No test framework: run with apps/test.sh. */
public final class HotspotTest {
    private static int passed, failed;

    public static void main(String[] args) {
        // Generated passwords: the documented shape, valid for WPA2, and different each time.
        SecureRandom rnd = new SecureRandom();
        Set<String> seen = new HashSet<>();
        boolean shapeOk = true, validOk = true;
        for (int i = 0; i < 2000; i++) {
            String pw = Hotspot.generate(rnd);
            shapeOk &= pw.matches(Hotspot.OWN_PATTERN);
            validOk &= Hotspot.problem(pw) == null;
            seen.add(pw);
        }
        check("generated passwords look like xxxx-xxxx-xxxx", shapeOk);
        check("generated passwords are valid WPA2 passwords", validOk);
        check("generated passwords don't repeat", seen.size() == 2000);

        // Every character of the alphabet turns up, and no look-alikes.
        Set<Character> chars = new HashSet<>();
        Random fixed = new Random(42);
        for (int i = 0; i < 500; i++) for (char c : Hotspot.generate(fixed).toCharArray()) chars.add(c);
        chars.remove('-');
        check("all 32 characters are used", chars.size() == 32);
        check("no l, o, 0 or 1", !chars.contains('l') && !chars.contains('o') && !chars.contains('0') && !chars.contains('1'));
        check("alphabet is 32 characters", Hotspot.ALPHABET.length() == 32);

        // Validation of passwords people choose.
        check("7 characters refused", Hotspot.problem("1234567") != null);
        check("8 characters accepted", Hotspot.problem("12345678") == null);
        check("63 characters accepted", Hotspot.problem(repeat('a', 63)) == null);
        check("64 characters refused", Hotspot.problem(repeat('a', 64)) != null);
        check("spaces and symbols accepted", Hotspot.problem("my R2 pass!~") == null);
        check("non-ASCII refused", Hotspot.problem("pässwort1") != null);
        check("control characters refused", Hotspot.problem("abc\ndefgh") != null);
        check("null refused", Hotspot.problem(null) != null);

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
