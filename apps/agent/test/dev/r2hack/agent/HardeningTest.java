package dev.r2hack.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Off-robot tests for the firewall scripts and parsers of boot hardening (HardeningRules).
 * Run by apps/test.sh. Optionally writes the scripts to a directory (first argument), so the test
 * script can also run them against a real iptables in a throwaway network namespace.
 */
public class HardeningTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        firewallScript();
        sources();
        modes();
        nodes();
        listeners();
        if (args.length > 0) writeScripts(args[0]);
        if (failures > 0) {
            System.out.println(failures + " hardening test(s) FAILED");
            System.exit(1);
        }
        System.out.println("hardening tests passed");
    }

    private static void firewallScript() {
        String s = HardeningRules.applyScript(false, "state", Arrays.asList("10.0.20.0/24", "fd00::/8"));
        String[] lines = s.trim().split("\n");
        check("script ends with OK", lines[lines.length - 1].equals("echo OK"));
        check("jump into INPUT comes last", lines[lines.length - 2].equals("r -I INPUT 1 -j r2_in"));
        check("jump appears once", s.split("-I INPUT", -1).length == 2);
        int drop = s.indexOf("-A r2_in -j DROP");
        check("DROP is the chain's last rule", drop > 0 && s.indexOf("-A r2_in", drop + 1) < 0);
        check("no ACCEPT anywhere", !s.contains("ACCEPT"));
        check("INPUT policy untouched", !s.contains("-P "));
        check("starts by removing old rules", s.startsWith("n=0; while iptables -D INPUT -j r2_in"));
        check("rules fail loudly", s.contains("echo \"FAIL: iptables $*\"; exit 1"));
        for (int p : new int[] {7700, 7710, 10700, 8887, 12121, 5555}) {
            check("tcp " + p + " allowed from 192.168/16", s.contains("-s 192.168.0.0/16 -p tcp --dport " + p + " -j RETURN"));
        }
        check("API allowed from extra subnet", s.contains("-s 10.0.20.0/24 -p tcp --dport 7700 -j RETURN"));
        check("v6 subnet not in v4 chain", !s.contains("fd00::/8"));
        check("ssdp", s.contains("-s 10.0.0.0/8 -p udp --dport 1900 -j RETURN"));
        check("mdns", s.contains("-s 172.16.0.0/12 -p udp --dport 5353 -j RETURN"));
        check("dhcp client", s.contains("-p udp --sport 67 --dport 68 -j RETURN"));
        check("established", s.contains("-m state --state ESTABLISHED,RELATED -j RETURN"));
        check("loopback first", s.indexOf("-i lo -j RETURN") < s.indexOf("-j DROP"));
        String c = HardeningRules.applyScript(false, "conntrack", Collections.<String>emptyList());
        check("conntrack variant", c.contains("-m conntrack --ctstate ESTABLISHED,RELATED -j RETURN") && !c.contains("--state "));
        String v6 = HardeningRules.applyScript(true, "state", Arrays.asList("10.0.20.0/24", "fd00::/8"));
        check("v6 uses ip6tables", v6.contains("r -N r2_in6") && v6.contains("ip6tables -D INPUT -j r2_in6"));
        check("v6 icmpv6 for neighbour discovery", v6.contains("-p icmpv6 -j RETURN"));
        check("v6 link-local API", v6.contains("-s fe80::/10 -p tcp --dport 7700 -j RETURN"));
        check("v6 extra subnet", v6.contains("-s fd00::/8 -p tcp --dport 7700"));
        check("v6 no v4 subnet", !v6.contains("10.0.20.0/24"));
        String rm = HardeningRules.removeScript("iptables", "r2_in");
        check("remove deletes jump, flushes, deletes chain", rm.contains("-D INPUT -j r2_in") && rm.contains("-F r2_in") && rm.contains("-X r2_in"));
        check("remove never fails", rm.trim().endsWith("true"));
    }

    private static void sources() {
        List<String> s = HardeningRules.sources(false, Arrays.asList("10.0.0.0/8", "1.2.3.4/32; reboot", "", "8.8.8.8"));
        check("dedup and drop unsafe text", s.equals(Arrays.asList("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "8.8.8.8")));
    }

    private static void modes() {
        check("0666", HardeningRules.parseMode("crw-rw-rw-") == 0666);
        check("0660", HardeningRules.parseMode("brw-rw----") == 0660);
        check("0755 dir", HardeningRules.parseMode("drwxr-xr-x") == 0755);
        check("setuid", HardeningRules.parseMode("-rwsr-x---") == 0750);
        check("junk", HardeningRules.parseMode("hello") == -1);
        check("tightened", HardeningRules.tightened(0666) == 0660 && HardeningRules.tightened(0604) == 0600);
        check("octal", HardeningRules.octal(0660).equals("660") && HardeningRules.octal(0).equals("000"));
        check("app user", "u0_a45".equals(HardeningRules.appUserName(10045)) && HardeningRules.appUserName(1000) == null
                && "u10_a3".equals(HardeningRules.appUserName(1010003)));
        check("console uart", HardeningRules.consoleUart("console=ttyS0,115200 root=/dev/mmcblk0p7") == 0
                && HardeningRules.consoleUart("init=/init console=ttyS3,115200n8") == 3
                && HardeningRules.consoleUart("console=tty0") == -1 && HardeningRules.consoleUart(null) == -1);
    }

    private static void nodes() {
        String out = "/dev/ttyS0 crw-rw-rw- root root 4, 64 2014-01-01 00:00 ttyS0\n"
                + "/dev/ttyS2 crw-rw-rw- root root 4, 66 2014-01-01 00:00 ttyS2\n"
                + "/dev/ttyS3 crw-rw---- root system 4, 67 2014-01-01 00:00 ttyS3\n"
                + "/dev/block/mmcblk0 brw-rw-rw- root root 179, 0 2014-01-01 00:00 mmcblk0\n"
                + "/proc/sunxi_debug/sunxi_debug -rw-rw-rw- root root 0 2014-01-01 00:00 sunxi_debug\n"
                + "/dev/i2c-1 lrwxrwxrwx root root 2014-01-01 00:00 i2c-1 -> x\n"
                + "/dev/ttyS* ls: /dev/ttyS*: No such file or directory\n";
        List<HardeningRules.Node> n = HardeningRules.parseNodes(out);
        check("parsed nodes", n.size() == 6);
        check("ttyS0 skipped", HardeningRules.skip(n.get(0), -1));
        check("ttyS2 fixed", !HardeningRules.skip(n.get(1), 0) && n.get(1).mode == 0666 && "root".equals(n.get(1).group));
        check("ttyS2 skipped when it is the console", HardeningRules.skip(n.get(1), 2));
        check("ttyS3 already tight", HardeningRules.skip(n.get(2), 0));
        check("eMMC fixed", !HardeningRules.skip(n.get(3), 0) && n.get(3).type == 'b');
        check("sunxi_debug fixed", !HardeningRules.skip(n.get(4), 0));
        check("symlink skipped", HardeningRules.skip(n.get(5), 0));
        String ls = "crw-rw-rw- root root 1, 3 2014-01-01 00:00 null\n"
                + "crw------- root root 1, 1 2014-01-01 00:00 mem\n"
                + "drwxrwxrwx root root 2014-01-01 00:00 shm\n"
                + "crw-rw-rw- system graphics 10, 60 2014-01-01 00:00 mali\n";
        List<String> ww = HardeningRules.worldWritableDevices("/dev", ls);
        check("world writable report", ww.equals(Arrays.asList("/dev/null 666", "/dev/mali 666")));
    }

    private static void listeners() {
        String tcp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                + "   0: 00000000:1E14 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10045        0 1234 1\n"
                + "   1: 0100007F:1E15 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10045        0 1235 1\n"
                + "   2: 0B01A8C0:1E14 0201A8C0:C350 01 00000000:00000000 00:00000000 00000000 10045        0 1236 1\n";
        List<HardeningRules.Listener> l = HardeningRules.parseProcNet("tcp", tcp);
        check("two listeners", l.size() == 2);
        check("any address 7700", l.get(0).port == 7700 && "0.0.0.0".equals(l.get(0).address) && l.get(0).uid == 10045);
        check("loopback 7701", l.get(1).port == 7701 && l.get(1).loopbackOnly());
        check("7700 allowed", HardeningRules.allowed(l.get(0)));
        check("ip order", "192.168.1.11".equals(HardeningRules.hexAddress("0B01A8C0")));
        check("v6 any", "::".equals(HardeningRules.hexAddress("00000000000000000000000000000000")));
        check("v6 loopback", "::1".equals(HardeningRules.hexAddress("00000000000000000000000001000000")));
        check("v4 mapped", "192.168.1.11".equals(HardeningRules.hexAddress("0000000000000000FFFF00000B01A8C0")));
        String udp = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n"
                + "  10: 00000000:076C 00000000:0000 07 00000000:00000000 00:00000000 00000000 10046        0 99 2 0 0\n"
                + "  11: 00000000:0BB8 00000000:0000 07 00000000:00000000 00:00000000 00000000  1002        0 98 2 0 0\n";
        List<HardeningRules.Listener> u = HardeningRules.parseProcNet("udp", udp);
        check("udp ssdp allowed", u.size() == 2 && u.get(0).port == 1900 && HardeningRules.allowed(u.get(0)));
        check("udp 3000 dropped", u.get(1).port == 3000 && !HardeningRules.allowed(u.get(1)));
    }

    private static void writeScripts(String dir) throws Exception {
        List<String> extra = Arrays.asList("10.0.20.0/24", "fd00::/8");
        write(dir + "/fw4.sh", HardeningRules.applyScript(false, "state", extra));
        write(dir + "/fw4-conntrack.sh", HardeningRules.applyScript(false, "conntrack", extra));
        write(dir + "/fw6.sh", HardeningRules.applyScript(true, "state", extra));
        write(dir + "/fw4-off.sh", HardeningRules.removeScript("iptables", HardeningRules.CHAIN4));
        write(dir + "/fw6-off.sh", HardeningRules.removeScript("ip6tables", HardeningRules.CHAIN6));
    }

    private static void write(String path, String text) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(path);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
