package dev.r2hack.main;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The agent's rule for API clients, applied to servers R2 Main connects to on its own (the MQTT broker):
 * a private address on the robot's own subnet (its Wi-Fi network or hotspot), loopback, IPv6
 * link-local, or a subnet allowed with POST /clients/allow. Same logic as the agent's HttpApi.
 */
final class LocalNet {
    static final class Subnet {
        final byte[] net;
        final int prefix;

        Subnet(byte[] net, int prefix) {
            this.net = net;
            this.prefix = prefix;
        }

        boolean contains(byte[] a) {
            return a.length == net.length && samePrefix(a, net, prefix);
        }

        /** "a.b.c.d/n" or a bare address (one host). IP literals only, no DNS lookups. */
        static Subnet parse(String text) throws Exception {
            String t = text.trim();
            int slash = t.indexOf('/');
            String addr = slash >= 0 ? t.substring(0, slash) : t;
            if (!addr.matches("[0-9a-fA-F:.]+")) throw new Exception("not an IP address: " + t);
            byte[] b = InetAddress.getByName(addr).getAddress();
            int max = b.length * 8;
            int p = slash >= 0 ? Integer.parseInt(t.substring(slash + 1).trim()) : max;
            if (p < 8 || p > max) throw new Exception("prefix length must be 8 to " + max + ": " + t);
            return new Subnet(b, p);
        }
    }

    /** Interface addresses with their prefix, as {address, prefix}; replaceable in tests. */
    interface Interfaces {
        List<Subnet> list() throws Exception;
    }

    static final Interfaces SYSTEM = new Interfaces() {
        @Override
        public List<Subnet> list() throws Exception {
            List<Subnet> out = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress own = ia.getAddress();
                    if (own == null || own.isLoopbackAddress()) continue;
                    byte[] ours = own.getAddress();
                    int prefix = ia.getNetworkPrefixLength();
                    // Some Android 4.x builds report nonsense for IPv4 (e.g. 64 or 0); assume a /24 then.
                    if (ours.length == 4 && (prefix <= 0 || prefix > 32)) prefix = 24;
                    if (ours.length == 16 && (prefix <= 0 || prefix > 128)) prefix = 64;
                    out.add(new Subnet(ours, prefix));
                }
            }
            return out;
        }
    };

    private LocalNet() {}

    /** True if a may be used: private and on one of our subnets, or in an allowed extra subnet. */
    static boolean allowed(InetAddress a, List<Subnet> extra, Interfaces ifaces) {
        a = unmapped(a);
        if (a == null || !isPrivate(a)) return false;
        if (a.isLoopbackAddress() || (a instanceof Inet6Address && a.isLinkLocalAddress())) return true;
        byte[] b = a.getAddress();
        for (Subnet s : extra) if (s.contains(b)) return true;
        try {
            for (Subnet s : ifaces.list()) if (s.contains(b)) return true;
        } catch (Exception e) {
            return true; // a private address, and the interface list failed: don't lock out on a platform quirk
        }
        return false;
    }

    static boolean isPrivate(InetAddress a) {
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || (b[0] & 0xfe) == 0xfc; // unique local fc00::/7
        }
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    static boolean samePrefix(byte[] a, byte[] b, int prefix) {
        int full = prefix / 8, rest = prefix % 8;
        for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (a[full] & mask) == (b[full] & mask);
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) as IPv4; null if it can't be converted. */
    private static InetAddress unmapped(InetAddress a) {
        if (!(a instanceof Inet6Address)) return a;
        byte[] b = a.getAddress();
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
        mapped &= b[10] == (byte) 0xff && b[11] == (byte) 0xff;
        if (!mapped) return a;
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (Exception e) {
            return null;
        }
    }
}
