package dev.r2hack.agent;

import java.net.URL;
import java.util.Locale;
import org.json.JSONObject;

/**
 * A copy of the GitHub releases on the local network (docs/update-mirror.md): the Home Assistant add-on
 * or the PC tool downloads each release and serves it over plain HTTP, because Android 4.4 can't reach
 * GitHub. The mirror is only a delivery route: the agent still installs nothing that isn't signed by a
 * trusted certificate, for the right package, with a newer versionCode (Updater.verify). The SHA-256 in
 * the manifest only catches damaged downloads.
 *
 * Layout, for a mirror at http://host:port/base:
 * <pre>
 *   base/owner/repo/latest.json
 *   base/owner/repo/v0.3.0/R2Main.apk, R2Agent.apk   (any path relative to latest.json's folder)
 *
 *   latest.json: {"repo": "owner/repo", "tag": "v0.3.0", "version": "0.3.0", "published": "...", "url": "...",
 *                 "assets": {"R2Main.apk": {"path": "v0.3.0/R2Main.apk", "sha256": "...", "size": 123,
 *                                           "version_code": 12345},
 *                            "R2Agent.apk": {...}}}
 * </pre>
 * "version" (else the tag without a leading v) and "version_code" are optional.
 */
final class UpdateMirror {
    static final String MAIN = "R2Main.apk";
    static final String AGENT = "R2Agent.apk";

    private UpdateMirror() {
    }

    /** The mirror URL as stored (no trailing slash), or null if it isn't a plain http(s)://host[:port][/path]. */
    static String normalize(String url) {
        if (url == null) return null;
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.length() > 200 || !url.matches("(?i)https?://[A-Za-z0-9.\\-\\[\\]:]+(/[A-Za-z0-9._~\\-/]*)?")) return null;
        try {
            URL u = new URL(url);
            if (u.getHost().isEmpty() || u.getUserInfo() != null || u.getQuery() != null || u.getRef() != null) return null;
            if (u.getPath().contains("..") || u.getPath().contains("//")) return null;
            return u.getProtocol().toLowerCase(Locale.US) + url.substring(url.indexOf("://"));
        } catch (Exception e) {
            return null;
        }
    }

    static String manifestUrl(String mirror, String repo) {
        return mirror + "/" + repo + "/latest.json";
    }

    /**
     * Reads latest.json into the shape Updater.check() keeps for GitHub (tag, version, main_url, agent_url,
     * plus main_sha256, main_size, main_version_code and the agent's). Throws with a readable reason if the
     * manifest is for another repository or names a file outside the mirror.
     */
    static JSONObject parse(String json, String manifestUrl, String repo, long maxApk) throws Exception {
        JSONObject m;
        try {
            m = new JSONObject(json);
        } catch (Exception e) {
            throw new Exception("the mirror's latest.json isn't valid JSON");
        }
        if (!repo.equalsIgnoreCase(m.optString("repo"))) {
            throw new Exception("the mirror's latest.json is for " + m.optString("repo", "no repository") + ", not " + repo);
        }
        String tag = m.optString("tag");
        if (!tag.matches("[A-Za-z0-9._+\\-]{1,80}")) throw new Exception("the mirror's latest.json has no usable tag");
        String version = m.optString("version", "");
        if (version.isEmpty()) version = tag.replaceFirst("^v", "");
        JSONObject out = new JSONObject().put("tag", tag).put("version", version)
                .put("published", m.optString("published")).put("url", m.optString("url")).put("mirror", manifestUrl);
        JSONObject assets = m.optJSONObject("assets");
        URL base = new URL(manifestUrl);
        asset(out, "main", assets == null ? null : assets.optJSONObject(MAIN), base, maxApk);
        asset(out, "agent", assets == null ? null : assets.optJSONObject(AGENT), base, maxApk);
        return out;
    }

    private static void asset(JSONObject out, String key, JSONObject a, URL base, long maxApk) throws Exception {
        if (a == null) {
            out.put(key + "_url", "");
            return;
        }
        String path = a.optString("path");
        if (!path.matches("[A-Za-z0-9._+\\-]+(/[A-Za-z0-9._+\\-]+)*") || path.contains("..")) {
            throw new Exception("the mirror names an unsafe file path: " + path);
        }
        String sha = a.optString("sha256").toLowerCase(Locale.US);
        if (!sha.matches("[0-9a-f]{64}")) throw new Exception("the mirror gives no SHA-256 for " + path);
        long size = a.optLong("size", -1);
        if (size <= 0 || size > maxApk) throw new Exception("the mirror gives a bad size for " + path);
        out.put(key + "_url", new URL(base, path).toString()).put(key + "_sha256", sha).put(key + "_size", size);
        if (a.has("version_code")) out.put(key + "_version_code", a.getInt("version_code"));
    }

    /**
     * Whether an asset from parse() is newer than what is installed: by versionCode when the mirror gives
     * one, else by version name as for GitHub releases (the robot checks the versionCode after download).
     */
    static boolean newer(JSONObject l, String key, String installedName, int installedCode) {
        if (l.optString(key + "_url").isEmpty()) return false;
        if (l.has(key + "_version_code")) return l.optInt(key + "_version_code") > installedCode;
        return !l.optString("version").equals(installedName);
    }
}
