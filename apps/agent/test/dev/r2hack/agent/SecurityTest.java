package dev.r2hack.agent;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Off-robot tests for signed requests, clients and permissions, and the APK signature check.
 * Usage (apps/test.sh): SecurityTest <signed.apk> <tampered.apk> <unsigned.apk> <signer sha256>
 */
public final class SecurityTest {
    private static int failures, checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        check(want == null ? got == null : want.equals(got), what + ": want <" + want + "> got <" + got + ">");
    }

    public static void main(String[] args) throws Exception {
        signing();
        clients();
        permissions();
        if (args.length >= 4) apks(new File(args[0]), new File(args[1]), new File(args[2]), args[3]);
        else System.out.println("(skipping the APK signature tests: no test APKs given)");
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static String sign(String key, String method, String path, Map<String, String> q, String time, String nonce, String body) {
        return RequestAuth.hmacHex(key, RequestAuth.canonical(method, path, q, time, nonce, body));
    }

    private static void signing() {
        // A fixed vector, shared with the web page's and the PC tools' tests.
        Map<String, String> q = new HashMap<>();
        q.put("name", "dance party");
        q.put("b", "2");
        q.put("r2sig", "ignored");
        String canon = RequestAuth.canonical("POST", "/action", q, "1700000000", "abcdefghijklmnop0123", RequestAuth.EMPTY_SHA256);
        eq("R2-HMAC-SHA256\nPOST\n/action\nb=2&name=dance party\n1700000000\nabcdefghijklmnop0123\n"
                + RequestAuth.EMPTY_SHA256, canon, "canonical string");
        eq("4fd29b77c7c1d4dc9d7b1d82a9b3ba84e8e29f59de4bdfb9afb0876e6ec1aa27".length(), RequestAuth.hmacHex("k", canon).length(),
                "hmac length");
        System.out.println("vector: key=test-key sig=" + RequestAuth.hmacHex("test-key", canon));
        eq(RequestAuth.EMPTY_SHA256, RequestAuth.sha256Hex(new byte[0]), "empty sha256");

        RequestAuth a = new RequestAuth();
        long now = 1_700_000_000L, started = now - 100;
        Map<String, String> none = Collections.emptyMap();
        String t = String.valueOf(now), n = "nonce0000000000001", b = RequestAuth.EMPTY_SHA256;
        String good = sign("key1", "GET", "/status", none, t, n, b);
        eq(null, a.check("key1", "c1", "GET", "/status", none, t, n, b, good, now, started), "good signature");
        check(a.check("key1", "c1", "GET", "/status", none, t, n, b, good, now, started).contains("replayed"), "replay refused");
        String n2 = "nonce0000000000002";
        String other = sign("key1", "GET", "/status", none, t, n2, b);
        check(a.check("key1", "c1", "POST", "/status", none, t, n2, b, other, now, started).equals("bad signature"),
                "changed method refused");
        check(a.check("key2", "c1", "GET", "/status", none, t, n2, b, other, now, started).equals("bad signature"),
                "wrong key refused");
        Map<String, String> added = new HashMap<>();
        added.put("x", "1");
        check(a.check("key1", "c1", "GET", "/status", added, t, n2, b, other, now, started).equals("bad signature"),
                "added query parameter refused");
        String oldT = String.valueOf(now - 301);
        check(a.check("key1", "c1", "GET", "/status", none, oldT, n2, b, sign("key1", "GET", "/status", none, oldT, n2, b),
                now, now - 1000).startsWith("clock"), "old time refused");
        String preStart = String.valueOf(started - 5);
        check(a.check("key1", "c1", "GET", "/status", none, preStart, n2, b,
                sign("key1", "GET", "/status", none, preStart, n2, b), now, started).contains("before the agent started"),
                "signed before start refused");
        check(a.check("key1", "c1", "GET", "/status", none, t, "short", b, good, now, started).contains("nonce"), "short nonce");
        check(a.check("key1", "c1", "GET", "/status", none, t, n2, "XYZ", good, now, started).contains("X-R2-Body"), "bad body hash");
        check(a.check("key1", "c1", "GET", "/status", none, null, n2, b, good, now, started) != null, "missing time");
        // Same nonce from another client is a different request.
        String n3 = "nonce0000000000001";
        eq(null, a.check("key3", "c3", "GET", "/status", none, t, n3, b, sign("key3", "GET", "/status", none, t, n3, b),
                now, started), "nonces are per client");
        // Upper-case signature hex is accepted.
        String n4 = "nonce0000000000004";
        eq(null, a.check("key1", "c1", "GET", "/status", none, t, n4, b,
                sign("key1", "GET", "/status", none, t, n4, b).toUpperCase(), now, started), "upper-case hex");
    }

    private static void clients() throws Exception {
        File dir = Files.createTempDirectory("r2clients").toFile();
        File f = new File(dir, "clients.json");
        Clients c = new Clients(f, "ownerkey000000000000000000000000000000000000000", "");
        check(c.byId("owner") != null && Clients.ADMIN.equals(c.byId("owner").role), "owner is admin");
        check(c.byId("dev") == null, "no dev client without a dev token");
        check(c.byPlainToken("ownerkey000000000000000000000000000000000000000") == null, "owner key is not a plain token");
        check(c.isSigningOnlyKey("ownerkey000000000000000000000000000000000000000"), "owner key recognized");
        try {
            c.add("ha", "admin", true);
            check(false, "plain admin refused");
        } catch (IllegalArgumentException expected) {
        }
        try {
            c.add("x", "root", false);
            check(false, "unknown role refused");
        } catch (IllegalArgumentException expected) {
        }
        Clients.Client ha = c.add("home-assistant", "control", true);
        Clients.Client phone = c.add("phone", "admin", false);
        eq(48, ha.key.length(), "key length");
        check(c.byPlainToken(ha.key) == ha, "plain token found");
        check(c.byPlainToken(phone.key) == null, "signing-only client is not a plain token");
        check(c.byPlainToken("nope") == null, "wrong plain token");
        try {
            c.setRole(ha.id, "admin");
            check(false, "plain client can't become admin");
        } catch (IllegalArgumentException expected) {
        }
        c.setRole(phone.id, "read");
        check(!c.revoke("owner"), "owner can't be revoked");

        Clients again = new Clients(f, "ownerkey000000000000000000000000000000000000000", "devtoken");
        eq("read", again.byId(phone.id).role, "role kept across restarts");
        eq(ha.key, again.byId(ha.id).key, "key kept across restarts");
        check(again.byId("dev") != null, "dev client with a dev token");
        check(again.revoke(phone.id), "revoke");
        check(new Clients(f, "o", "").byId(phone.id) == null, "revoked stays revoked");
        c.setOwnerKey("rotated");
        eq("rotated", c.byId("owner").key, "owner key rotation");
        for (File x : dir.listFiles()) x.delete();
        dir.delete();
    }

    private static void permissions() {
        eq(Permissions.OPEN, Permissions.required("GET", "/ping"), "ping open");
        eq(Permissions.OPEN, Permissions.required("GET", "/"), "page open");
        eq("read", Permissions.required("GET", "/status"), "status read");
        eq("read", Permissions.required("POST", "/stop"), "stop is read");
        eq("control", Permissions.required("POST", "/action"), "action control");
        eq("read", Permissions.required("GET", "/sounds"), "soundboard list read");
        eq("control", Permissions.required("POST", "/sound"), "soundboard play control");
        eq("control", Permissions.required("POST", "/sound/stop"), "soundboard stop control");
        // Control may ask for an install; AgentService refuses it unless an admin allowed it (POST /update/control).
        eq("control", Permissions.required("POST", "/update/install"), "update install control (gated)");
        eq("control", Permissions.required("POST", "/update/check"), "update check control");
        for (String r : new String[] {"POST /main/install", "POST /agent/install", "POST /reboot", "POST /adb", "POST /serial",
                "POST /clients/add", "POST /update/control", "POST /update/mirror", "POST /update/source", "POST /trust", "POST /wifi", "GET /logcat", "POST /brand-new"}) {
            String[] p = r.split(" ");
            eq("admin", Permissions.required(p[0], p[1]), r + " admin");
        }
        check(Clients.level("read") < Clients.level("control") && Clients.level("control") < Clients.level("admin"), "levels nest");
    }

    private static void apks(File signed, File tampered, File unsigned, String sha) throws Exception {
        eq(sha, ApkTrust.signer(signed), "signer of the signed APK");
        ApkTrust trust = new ApkTrust(new HashSet<>(Collections.singletonList(sha)), Collections.<String>emptyList());
        eq(null, trust.refusal(signed), "trusted APK accepted");
        check(trust.refusal(tampered) != null, "tampered APK refused");
        check(trust.refusal(unsigned) != null, "unsigned APK refused");
        ApkTrust other = new ApkTrust(new HashSet<>(Collections.singletonList(RequestAuth.EMPTY_SHA256)),
                Collections.<String>emptyList());
        check(other.refusal(signed) != null && other.refusal(signed).contains("untrusted"), "untrusted signer refused");
        other.setExtra(Collections.singletonList(sha));
        eq(null, other.refusal(signed), "added certificate trusted");
        eq(sha, ApkTrust.normalize(sha.toUpperCase().replaceAll("(..)(?!$)", "$1:")), "keytool form normalized");
    }
}
