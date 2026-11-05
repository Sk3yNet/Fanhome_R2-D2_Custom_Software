package dev.r2hack.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The pure part of boot hardening: the firewall scripts, parsing of `ls -l` and /proc/net, and which
 * device nodes get tightened. No Android calls here, so apps/test.sh can test it off the robot.
 *
 * Firewall design, chosen so it can't lock anyone out by itself:
 * - Our rules live in their own chains (r2_in, r2_in6). INPUT gets one jump to them, inserted only
 *   after the chain is complete, and removing that jump undoes everything.
 * - The chains never ACCEPT. Allowed traffic RETURNs to INPUT, where Android's own rules carry on as
 *   before; anything else is dropped. So the firewall can only narrow what reaches the robot.
 * - The policy of INPUT is never changed, and OUTPUT and FORWARD (the hotspot's tethering) are untouched.
 * - Allowed: loopback, replies to the robot's own connections, ICMP, DHCP, and from private sources
 *   (plus the extra subnets of POST /clients/allow) the ports below. The agent and R2 Main still check
 *   the robot's own subnet themselves; the firewall stops everything else that listens.
 * - The rules don't depend on the interface or the robot's address, so they stay right when R2
 *   switches between its hotspot and the home network.
 */
final class HardeningRules {
    static final String CHAIN4 = "r2_in";
    static final String CHAIN6 = "r2_in6";

    /** TCP ports that may be reached from the local network. */
    static final int[] TCP_PORTS = {
            7700,   // agent API and web page
            7710,   // R2 Main: DLNA media renderer (Home Assistant media player)
            10700,  // R2 Main: Wyoming voice satellite (Home Assistant Assist)
            8887,   // R2 Main: official phone app, control
            12121,  // R2 Main: official phone app, video
            5555,   // adb over Wi-Fi, only listening while switched on (POST /adb)
            53,     // DNS for clients on R2's hotspot
    };
    /** UDP ports that may be reached from the local network. */
    static final int[] UDP_PORTS = {
            1900,   // SSDP: Home Assistant finds the media renderer
            5353,   // mDNS / zeroconf: Home Assistant finds the voice satellite
            53,     // DNS for clients on R2's hotspot
    };
    static final String[] PRIVATE4 = {"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"};
    static final String[] PRIVATE6 = {"fe80::/10", "fc00::/7"};

    private HardeningRules() {}

    /**
     * Shell script (run as root) that removes our chain and its jump. Safe to run any number of times,
     * and also when nothing was ever set up. Every line ignores errors.
     */
    static String removeScript(String iptables, String chain) {
        return "n=0; while " + iptables + " -D INPUT -j " + chain + " 2>/dev/null; do n=$((n+1)); [ $n -gt 20 ] && break; done\n"
                + iptables + " -F " + chain + " 2>/dev/null\n"
                + iptables + " -X " + chain + " 2>/dev/null\n"
                + "true\n";
    }

    /**
     * Shell script that builds the chain from scratch and only then hooks it into INPUT. Any failing
     * rule stops the script with "FAIL: <rule>" and exit code 1 before the jump exists, so a half-built
     * firewall never takes effect. The caller then runs removeScript.
     *
     * @param ipv6        build the IPv6 chain (ip6tables) instead of the IPv4 one
     * @param state       "state" (-m state --state) or "conntrack" (-m conntrack --ctstate), whichever
     *                    the kernel has
     * @param extraSubnets the extra subnets of POST /clients/allow, as "a.b.c.d/n" or IPv6 equivalents;
     *                    the ones of the other address family are skipped
     */
    static String applyScript(boolean ipv6, String state, List<String> extraSubnets) {
        String ipt = ipv6 ? "ip6tables" : "iptables";
        String chain = ipv6 ? CHAIN6 : CHAIN4;
        List<String> rules = rules(ipv6, state, extraSubnets);
        StringBuilder sb = new StringBuilder(removeScript(ipt, chain));
        sb.append("r() { ").append(ipt).append(" \"$@\" || { echo \"FAIL: ").append(ipt).append(" $*\"; exit 1; }; }\n");
        sb.append("r -N ").append(chain).append('\n');
        for (String rule : rules) sb.append("r -A ").append(chain).append(' ').append(rule).append('\n');
        // Last: from here on the chain is in effect.
        sb.append("r -I INPUT 1 -j ").append(chain).append('\n');
        sb.append("echo OK\n");
        return sb.toString();
    }

    /** The rules of the chain, without "-A chain". The last one drops everything not allowed above it. */
    static List<String> rules(boolean ipv6, String state, List<String> extraSubnets) {
        List<String> r = new ArrayList<>();
        r.add("-i lo -j RETURN");
        r.add(("conntrack".equals(state) ? "-m conntrack --ctstate" : "-m state --state") + " ESTABLISHED,RELATED -j RETURN");
        if (ipv6) {
            r.add("-p icmpv6 -j RETURN");                         // neighbour discovery, router adverts, ping
            r.add("-p udp --sport 547 --dport 546 -j RETURN");   // DHCPv6 client
        } else {
            r.add("-p icmp -j RETURN");
            r.add("-p udp --sport 67 --dport 68 -j RETURN");     // DHCP client (offers can come from anywhere on the link)
            r.add("-p udp --dport 67 -j RETURN");                // DHCP server on R2's hotspot (clients start from 0.0.0.0)
        }
        for (String src : sources(ipv6, extraSubnets)) {
            for (int p : TCP_PORTS) r.add("-s " + src + " -p tcp --dport " + p + " -j RETURN");
            for (int p : UDP_PORTS) r.add("-s " + src + " -p udp --dport " + p + " -j RETURN");
        }
        r.add("-j DROP");
        return r;
    }

    /** The private ranges of one address family plus the matching extra subnets, without duplicates. */
    static List<String> sources(boolean ipv6, List<String> extraSubnets) {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, ipv6 ? PRIVATE6 : PRIVATE4);
        if (extraSubnets != null) {
            for (String s : extraSubnets) {
                String t = s.trim();
                if (t.isEmpty() || !t.matches("[0-9a-fA-F:.]+(/\\d{1,3})?")) continue; // nothing that could break the shell line
                if (t.contains(":") != ipv6) continue;
                if (!out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    // ---- device nodes ---------------------------------------------------------------------------

    /** One entry of `ls -l` (Android's toolbox: "crw-rw-rw- root root 4, 66 2026-01-01 00:00 ttyS2"). */
    static final class Node {
        final String path, owner, group;
        final char type;      // 'c', 'b', '-' ...
        final int mode;       // permission bits, e.g. 0666

        Node(String path, char type, int mode, String owner, String group) {
            this.path = path;
            this.type = type;
            this.mode = mode;
            this.owner = owner;
            this.group = group;
        }

        boolean worldAccessible() {
            return (mode & 0006) != 0;
        }

        boolean worldWritable() {
            return (mode & 0002) != 0;
        }

        @Override
        public String toString() {
            return path + " " + octal(mode) + " " + owner + ":" + group;
        }
    }

    /** Permission bits of an ls mode string like "crw-rw-rw-"; -1 if it isn't one. */
    static int parseMode(String s) {
        if (s == null || s.length() < 10) return -1;
        int m = 0;
        String perms = s.substring(1, 10);
        for (int i = 0; i < 9; i++) {
            char c = perms.charAt(i);
            char want = "rwxrwxrwx".charAt(i);
            boolean set = c == want || (want == 'x' && (c == 's' || c == 't'));
            if (!set && c != '-' && c != 'S' && c != 'T') return -1;
            if (set) m |= 1 << (8 - i);
        }
        return m;
    }

    static String octal(int mode) {
        return String.format(Locale.US, "%03o", mode);
    }

    /**
     * Parses lines written by `for f in ...; do echo "$f $(ls -l $f)"; done`: the full path, then the
     * ls line. Lines that don't parse (missing files, errors) are skipped.
     */
    static List<Node> parseNodes(String out) {
        List<Node> nodes = new ArrayList<>();
        if (out == null) return nodes;
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4 || !f[0].startsWith("/")) continue;
            int mode = parseMode(f[1]);
            if (mode < 0) continue;
            nodes.add(new Node(f[0], f[1].charAt(0), mode, f[2], f[3]));
        }
        return nodes;
    }

    /**
     * Parses a directory listing (`ls -l <dir>`, names only) into world-writable character and block
     * devices, for the report. Nothing is changed for these: many of them (/dev/null, /dev/binder, the
     * GPU, the log devices) have to stay that way.
     */
    static List<String> worldWritableDevices(String dir, String lsOut) {
        List<String> found = new ArrayList<>();
        if (lsOut == null) return found;
        for (String line : lsOut.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4 || f[0].isEmpty()) continue;
            char type = f[0].charAt(0);
            if (type != 'c' && type != 'b') continue;
            int mode = parseMode(f[0]);
            if (mode < 0 || (mode & 0002) == 0) continue;
            found.add(dir + "/" + f[f.length - 1] + " " + octal(mode));
        }
        return found;
    }

    /**
     * Shell glob list of the nodes that get their "other" permission bits removed: the UARTs (the
     * controller's /dev/ttyS2 among them), raw memory, the eMMC and other block devices, I2C and SPI
     * buses, and Allwinner's sunxi_debug root backdoor of the 3.4 kernel (writing a magic string to it
     * makes the writer root).
     */
    static final String TARGETS = "/dev/ttyS* /dev/mem /dev/kmem /dev/port /dev/block/mmcblk* /dev/block/nand* "
            + "/dev/i2c-* /dev/spidev* /proc/sunxi_debug/sunxi_debug";

    static String listTargetsScript() {
        return "for f in " + TARGETS + "; do [ -e \"$f\" ] && echo \"$f $(ls -l $f)\"; done; true";
    }

    /** The UART number of the kernel console in /proc/cmdline (console=ttyS0,115200), or -1. */
    static int consoleUart(String cmdline) {
        if (cmdline == null) return -1;
        for (String arg : cmdline.trim().split("\\s+")) {
            if (!arg.startsWith("console=ttyS")) continue;
            String n = arg.substring("console=ttyS".length());
            int end = 0;
            while (end < n.length() && Character.isDigit(n.charAt(end))) end++;
            if (end > 0) return Integer.parseInt(n.substring(0, end));
        }
        return -1;
    }

    /**
     * Whether a node is left alone: anything not world-accessible already, ttyS0 and the kernel
     * console's UART (the serial console is the recovery path of last resort), and anything that isn't
     * a device or the sunxi_debug file.
     */
    static boolean skip(Node n, int consoleUart) {
        if (!n.worldAccessible()) return true;
        if ("/dev/ttyS0".equals(n.path)) return true;
        if (consoleUart >= 0 && ("/dev/ttyS" + consoleUart).equals(n.path)) return true;
        boolean proc = n.path.startsWith("/proc/");
        return !proc && n.type != 'c' && n.type != 'b';
    }

    /** The new mode: the "other" bits removed, owner and group bits kept. */
    static int tightened(int mode) {
        return mode & 0770;
    }

    /**
     * Android's user name for an app uid (u0_a45 for 10045), which toolbox chown understands; null for
     * uids below 10000.
     */
    static String appUserName(int uid) {
        int user = uid / 100000, appId = uid % 100000;
        if (appId < 10000 || appId > 19999) return null;
        return "u" + user + "_a" + (appId - 10000);
    }

    // ---- listening sockets ----------------------------------------------------------------------

    /** A socket from /proc/net/{tcp,tcp6,udp,udp6} that accepts traffic from the network. */
    static final class Listener {
        final String proto, address;
        final int port, uid;

        Listener(String proto, String address, int port, int uid) {
            this.proto = proto;
            this.address = address;
            this.port = port;
            this.uid = uid;
        }

        boolean loopbackOnly() {
            return address.startsWith("127.") || "::1".equals(address);
        }
    }

    /**
     * Parses one /proc/net file. For TCP only LISTEN sockets (state 0A) count; for UDP every bound
     * socket does (state 07). proto is "tcp", "tcp6", "udp" or "udp6".
     */
    static List<Listener> parseProcNet(String proto, String text) {
        List<Listener> out = new ArrayList<>();
        if (text == null) return out;
        boolean tcp = proto.startsWith("tcp");
        for (String line : text.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 8 || !f[0].endsWith(":")) continue; // header line
            String[] local = f[1].split(":");
            if (local.length != 2) continue;
            String st = f[3];
            if (tcp ? !"0A".equals(st) : !"07".equals(st)) continue;
            try {
                int port = Integer.parseInt(local[1], 16);
                if (port == 0) continue;
                out.add(new Listener(proto, hexAddress(local[0]), port, Integer.parseInt(f[7])));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** /proc/net addresses are 32-bit words in host (little-endian) order. */
    static String hexAddress(String hex) {
        int words = hex.length() / 8;
        byte[] b = new byte[words * 4];
        for (int w = 0; w < words; w++) {
            long v = Long.parseLong(hex.substring(w * 8, w * 8 + 8), 16);
            for (int i = 0; i < 4; i++) b[w * 4 + i] = (byte) (v >> (8 * i));
        }
        if (b.length == 4) return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
        mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        if (mapped) return (b[12] & 0xff) + "." + (b[13] & 0xff) + "." + (b[14] & 0xff) + "." + (b[15] & 0xff);
        boolean any = true, loop = true;
        for (int i = 0; i < 16; i++) {
            any &= b[i] == 0;
            loop &= b[i] == (i == 15 ? 1 : 0);
        }
        if (any) return "::";
        if (loop) return "::1";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff)));
        }
        return sb.toString();
    }

    /** Whether the firewall lets the local network reach this listener. */
    static boolean allowed(Listener l) {
        int[] ports = l.proto.startsWith("tcp") ? TCP_PORTS : UDP_PORTS;
        for (int p : ports) if (p == l.port) return true;
        return !l.proto.startsWith("tcp") && (l.port == 68 || l.port == 67 || l.port == 546);
    }
}
