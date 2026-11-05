package dev.r2hack.agent;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed requests (docs/security.md). A client signs
 *
 * <pre>
 * R2-HMAC-SHA256
 * METHOD
 * /path
 * k1=v1&amp;k2=v2      query parameters decoded, sorted by name then value, without the r2* auth parameters
 * time              unix seconds on the robot's clock (GET /ping returns it)
 * nonce             16 to 64 letters and digits, never reused
 * body-sha256       hex SHA-256 of the body (of "" when there is none)
 * </pre>
 *
 * with HMAC-SHA256, keyed with its key's UTF-8 bytes, and sends the hex result in X-R2-Signature,
 * with X-R2-Client, X-R2-Time, X-R2-Nonce and X-R2-Body. Links that can't carry headers (an img tag)
 * put r2client, r2time, r2nonce and r2sig in the query instead.
 *
 * A captured request can't be replayed: its nonce is remembered until its time is out of the window,
 * and anything signed before the agent started is refused, so a restart doesn't reopen the window.
 * Pure Java, so it runs in the off-robot tests (apps/test.sh).
 */
final class RequestAuth {
    static final String SCHEME = "R2-HMAC-SHA256";
    static final long SKEW_S = 300;
    static final int MAX_NONCES = 20_000;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** remembered nonce (client + ":" + nonce) -> when it may be forgotten (unix s) */
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    static boolean isAuthParam(String name) {
        return "r2client".equals(name) || "r2time".equals(name) || "r2nonce".equals(name) || "r2sig".equals(name);
    }

    static String canonical(String method, String path, Map<String, String> query, String time, String nonce,
            String bodySha256) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (isAuthParam(e.getKey())) continue;
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        Collections.sort(pairs);
        StringBuilder q = new StringBuilder();
        for (String p : pairs) q.append(q.length() == 0 ? "" : "&").append(p);
        return SCHEME + "\n" + method + "\n" + path + "\n" + q + "\n" + time + "\n" + nonce + "\n" + bodySha256;
    }

    static String hmacHex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(UTF8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(UTF8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    static boolean sameText(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(UTF8), b.getBytes(UTF8));
    }

    /**
     * Checks a signature over the claimed body hash (the body itself is compared after it is read).
     * Returns null when the request is good, otherwise why not. nowS and earliestS are robot unix seconds;
     * earliestS is when the agent started.
     */
    String check(String key, String clientId, String method, String path, Map<String, String> query, String time,
            String nonce, String bodySha256, String signature, long nowS, long earliestS) {
        if (time == null || nonce == null || signature == null || bodySha256 == null) {
            return "signed requests need X-R2-Time, X-R2-Nonce, X-R2-Body and X-R2-Signature";
        }
        if (!nonce.matches("[A-Za-z0-9]{16,64}")) return "the nonce must be 16 to 64 letters and digits";
        if (!bodySha256.matches("[0-9a-f]{64}")) return "X-R2-Body must be the body's SHA-256 in lowercase hex";
        long t;
        try {
            t = Long.parseLong(time.trim());
        } catch (NumberFormatException e) {
            return "X-R2-Time must be unix seconds";
        }
        if (Math.abs(nowS - t) > SKEW_S) return "clock: the request time is more than " + SKEW_S + " s from the robot's";
        if (t < earliestS) return "clock: signed before the agent started";
        String want = hmacHex(key, canonical(method, path, query, time.trim(), nonce, bodySha256));
        if (!sameText(want, signature.trim().toLowerCase(java.util.Locale.US))) return "bad signature";
        synchronized (seen) {
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) { // insertion order is roughly expiry order; stop at the first live one
                if (it.next().getValue() < nowS) it.remove();
                else break;
            }
            String k = clientId + ":" + nonce;
            if (seen.containsKey(k)) return "replayed request (nonce already used)";
            if (seen.size() >= MAX_NONCES) return "too many requests; try again in a minute";
            seen.put(k, Math.max(t, nowS) + SKEW_S);
        }
        return null;
    }
}
