package dev.r2hack.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * API clients, each with its own key and permission level (docs/security.md).
 *
 * <ul>
 * <li><b>owner</b>: built in, keyed with this robot's token (files/api-token). Always admin and can't be
 * revoked, so there is always a way back in: the pairing button (GET /setup/token) or root adb.</li>
 * <li><b>dev</b>: built in when the APK carries a development token (your own builds). Admin too.</li>
 * <li>Paired or added clients: admin, control or read. Revocable.</li>
 * </ul>
 *
 * Admin requests must be signed (RequestAuth). A client made with plain=true may also send its key as a
 * plain token (X-R2-Token, basic auth, ?token=) for things that can't sign, such as Home Assistant's
 * rest_command and camera; those clients are read or control only. The owner and dev keys are signing
 * keys and are refused as plain tokens, since a plain token can be read off the network.
 * Pure Java plus org.json, so it runs in the off-robot tests.
 */
final class Clients {
    static final String READ = "read", CONTROL = "control", ADMIN = "admin";
    static final String OWNER = "owner", DEV = "dev";
    static final int MAX_CLIENTS = 32;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long TOUCH_SAVE_MS = 10 * 60_000;

    static int level(String role) {
        if (READ.equals(role)) return 1;
        if (CONTROL.equals(role)) return 2;
        if (ADMIN.equals(role)) return 3;
        return 0;
    }

    static boolean validRole(String role) {
        return level(role) > 0;
    }

    static final class Client {
        final String id;
        String name;
        final String key;
        String role;
        final boolean plain;
        final boolean builtIn;
        final long created;
        long lastSeen;
        String lastRequest = "";

        Client(String id, String name, String key, String role, boolean plain, boolean builtIn, long created) {
            this.id = id;
            this.name = name;
            this.key = key;
            this.role = role;
            this.plain = plain;
            this.builtIn = builtIn;
            this.created = created;
        }

        JSONObject toPublic() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("role", role).put("plain_token", plain)
                    .put("built_in", builtIn).put("created", created).put("last_seen", lastSeen)
                    .put("last_request", lastRequest);
        }
    }

    private final File file;
    private final Map<String, Client> clients = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile String ownerKey;
    private final String devKey;
    private final Client owner, dev;
    private long lastSave;

    Clients(File file, String ownerKey, String devKey) {
        this.file = file;
        this.ownerKey = ownerKey;
        this.devKey = devKey == null ? "" : devKey;
        owner = new Client(OWNER, "robot owner (files/api-token)", "", ADMIN, false, true, 0);
        dev = new Client(DEV, "development token built into this APK", this.devKey, ADMIN, false, true, 0);
        load();
    }

    /** The owner's key changes when it is rotated (POST /clients/owner/rotate). */
    void setOwnerKey(String key) {
        ownerKey = key;
    }

    static String newKey(SecureRandom random, int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return RequestAuth.hex(b);
    }

    synchronized Client byId(String id) {
        if (id == null) return null;
        if (OWNER.equals(id)) return ownerKey.isEmpty() ? null : withKey(owner, ownerKey);
        if (DEV.equals(id)) return devKey.isEmpty() ? null : dev;
        return clients.get(id);
    }

    /** The owner's record carries its key only on lookup, since the key can be rotated. */
    private static Client withKey(Client c, String key) {
        Client k = new Client(c.id, c.name, key, c.role, c.plain, c.builtIn, c.created);
        k.lastSeen = c.lastSeen;
        k.lastRequest = c.lastRequest;
        return k;
    }

    /** The client whose plain token this is, or null. Checks every client, so timing says nothing. */
    synchronized Client byPlainToken(String token) {
        if (token == null || token.isEmpty()) return null;
        Client found = null;
        for (Client c : clients.values()) {
            if (RequestAuth.sameText(token, c.key) && c.plain) found = c;
        }
        return found;
    }

    /** True if this is the owner or dev key (refused as a plain token, with a hint). */
    boolean isSigningOnlyKey(String token) {
        if (token == null || token.isEmpty()) return false;
        boolean o = RequestAuth.sameText(token, ownerKey), d = !devKey.isEmpty() && RequestAuth.sameText(token, devKey);
        return o | d;
    }

    synchronized Client add(String name, String role, boolean plain) throws Exception {
        if (!validRole(role)) throw new IllegalArgumentException("role must be read, control or admin");
        if (plain && ADMIN.equals(role)) {
            throw new IllegalArgumentException("admin clients must sign their requests; a plain token can be read or control only");
        }
        String n = cleanName(name);
        if (clients.size() >= MAX_CLIENTS) throw new IllegalArgumentException("at most " + MAX_CLIENTS + " clients; revoke one first");
        String id;
        do {
            id = "c" + newKey(random, 4);
        } while (clients.containsKey(id));
        Client c = new Client(id, n, newKey(random, 24), role, plain, false, System.currentTimeMillis() / 1000);
        clients.put(id, c);
        save();
        return c;
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "unnamed";
        if (n.length() > 64) n = n.substring(0, 64);
        return n.replaceAll("[\\p{Cntrl}]", " ");
    }

    synchronized boolean revoke(String id) throws Exception {
        if (clients.remove(id) == null) return false;
        save();
        return true;
    }

    synchronized void setRole(String id, String role) throws Exception {
        Client c = clients.get(id);
        if (c == null) throw new IllegalArgumentException(builtIn(id) ? id + " is built in and always admin" : "no client " + id);
        if (!validRole(role)) throw new IllegalArgumentException("role must be read, control or admin");
        if (c.plain && ADMIN.equals(role)) throw new IllegalArgumentException("a plain-token client can be read or control only");
        c.role = role;
        save();
    }

    static boolean builtIn(String id) {
        return OWNER.equals(id) || DEV.equals(id);
    }

    /** Remembers when a client was last seen; saved now and then, not on every request. */
    synchronized void touch(String id, String request) {
        Client c = OWNER.equals(id) ? owner : DEV.equals(id) ? dev : clients.get(id);
        if (c == null) return;
        c.lastSeen = System.currentTimeMillis() / 1000;
        c.lastRequest = request;
        if (!c.builtIn && System.currentTimeMillis() - lastSave > TOUCH_SAVE_MS) {
            try {
                save();
            } catch (Exception ignored) {
            }
        }
    }

    synchronized JSONArray list() throws Exception {
        JSONArray a = new JSONArray();
        if (!ownerKey.isEmpty()) a.put(owner.toPublic());
        if (!devKey.isEmpty()) a.put(dev.toPublic());
        for (Client c : clients.values()) a.put(c.toPublic());
        return a;
    }

    synchronized List<Client> all() {
        return new ArrayList<>(clients.values());
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try {
            InputStream in = new FileInputStream(file);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            } finally {
                in.close();
            }
            JSONArray a = new JSONObject(new String(b.toByteArray(), UTF8)).optJSONArray("clients");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                String id = j.optString("id"), key = j.optString("key"), role = j.optString("role");
                boolean plain = j.optBoolean("plain");
                if (id.isEmpty() || builtIn(id) || key.length() < 32 || !validRole(role)) continue;
                if (plain && ADMIN.equals(role)) role = CONTROL; // never stored this way; refuse to load it as admin
                Client c = new Client(id, cleanName(j.optString("name")), key, role, plain, false, j.optLong("created"));
                c.lastSeen = j.optLong("last_seen");
                c.lastRequest = j.optString("last_request");
                clients.put(id, c);
            }
        } catch (Exception e) {
            // A damaged file loses the paired clients, never the owner key: re-pair with the button.
            System.err.println("clients: could not read " + file + ": " + e);
        }
    }

    private void save() throws Exception {
        lastSave = System.currentTimeMillis();
        if (file == null) return;
        JSONArray a = new JSONArray();
        for (Client c : clients.values()) {
            a.put(new JSONObject().put("id", c.id).put("name", c.name).put("key", c.key).put("role", c.role)
                    .put("plain", c.plain).put("created", c.created).put("last_seen", c.lastSeen)
                    .put("last_request", c.lastRequest));
        }
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(new JSONObject().put("clients", a).toString().getBytes(UTF8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        if (!tmp.renameTo(file)) throw new Exception("could not save " + file);
    }

    static String roleOf(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.US);
    }
}
