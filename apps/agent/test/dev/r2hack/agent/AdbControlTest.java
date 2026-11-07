package dev.r2hack.agent;

/** Off-robot checks of how the agent reads adb's TCP properties and the root helper's exit code. */
public final class AdbControlTest {
    private static int failures;

    public static void main(String[] args) {
        // service.adb.tcp.port wins when set; the factory init.rc sets it to 5555 at every boot.
        check(AdbControl.listening("5555", "-1"), "service 5555 opens it although persist is off");
        check(!AdbControl.listening("-1", "5555"), "service -1 closes it although persist is 5555");
        check(AdbControl.listening("", "5555"), "unset service falls back to persist");
        check(!AdbControl.listening("", "-1"), "both off");
        check(!AdbControl.listening("", ""), "both unset: USB");
        check(!AdbControl.listening("0", "5555"), "service 0 means USB");
        check(!AdbControl.listening(null, null), "nulls");
        check(AdbControl.listening(" 5555\n", ""), "getprop output with whitespace");
        check(Root.parseCode("0\n") == 0, "exit code 0");
        check(Root.parseCode("255") == 255, "exit code 255");
        check(Root.parseCode("") == -1, "missing exit code");
        if (failures > 0) {
            System.out.println(failures + " AdbControl check(s) failed");
            System.exit(1);
        }
        System.out.println("AdbControl: all checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
