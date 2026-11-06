package dev.r2hack.agent;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/**
 * Off-robot tests for reading a local release mirror (UpdateMirror). The example latest.json is the one the
 * Home Assistant add-on's test checks its own output against, so both sides agree on the format.
 * Run with apps/test.sh.
 */
public final class UpdateMirrorTest {
    private static int passed, failed;
    private static final String REPO = "Sk3yNet/Fanhome_R2-D2_Custom_Software";
    private static final long MAX = 64L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        String example = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");

        // Mirror URLs.
        check("plain LAN URL", "http://192.168.2.10:8765".equals(UpdateMirror.normalize("http://192.168.2.10:8765/")));
        check("with a path", "http://ha.lan:8765/r2".equals(UpdateMirror.normalize(" http://ha.lan:8765/r2 ")));
        check("https allowed", "https://mirror.example".equals(UpdateMirror.normalize("HTTPS://mirror.example")));
        for (String bad : new String[] {"ftp://x", "http://", "http://user:pw@host", "http://h/x?y=1", "http://h/#x",
                "http://h/a/../b", "file:///data", "javascript:alert(1)", "192.168.2.10:8765", "http://h//x", null}) {
            check("refused: " + bad, UpdateMirror.normalize(bad) == null);
        }
        check("manifest URL", ("http://h:8765/" + REPO + "/latest.json").equals(UpdateMirror.manifestUrl("http://h:8765", REPO)));

        // The example manifest.
        String url = UpdateMirror.manifestUrl("http://192.168.2.10:8765", REPO);
        JSONObject l = UpdateMirror.parse(example, url, REPO, MAX);
        check("tag", "v0.3.0".equals(l.getString("tag")) && "0.3.0".equals(l.getString("version")));
        check("main URL relative to latest.json",
                ("http://192.168.2.10:8765/" + REPO + "/v0.3.0/R2Main.apk").equals(l.getString("main_url")));
        check("agent URL", l.getString("agent_url").endsWith("/v0.3.0/R2Agent.apk"));
        check("sha and size", l.getString("main_sha256").startsWith("aa11") && l.getLong("main_size") == 2483112);
        check("repository name ignores case", UpdateMirror.parse(example, url, REPO.toLowerCase(), MAX).has("main_url"));

        // Newer or not.
        check("newer by versionCode", UpdateMirror.newer(l, "main", "0.3.0", 1015839));
        check("same versionCode is not newer", !UpdateMirror.newer(l, "main", "anything", 1015840));
        check("older versionCode is not newer", !UpdateMirror.newer(l, "agent", "x", 2000000));
        JSONObject noCode = new JSONObject(example);
        noCode.getJSONObject("assets").getJSONObject("R2Main.apk").remove("version_code");
        noCode.put("tag", "local-0.4.0").remove("version");
        JSONObject n = UpdateMirror.parse(noCode.toString(), url, REPO, MAX);
        check("version from a tag without a v", "local-0.4.0".equals(n.getString("version")));
        check("by name without versionCode", UpdateMirror.newer(n, "main", "0.3.0", 99) && !UpdateMirror.newer(n, "main", "local-0.4.0", 1));

        // A release with only one app.
        JSONObject one = new JSONObject(example);
        one.getJSONObject("assets").remove("R2Agent.apk");
        JSONObject o = UpdateMirror.parse(one.toString(), url, REPO, MAX);
        check("missing app: no URL, never newer", o.getString("agent_url").isEmpty() && !UpdateMirror.newer(o, "agent", "", -1));

        // Refusals.
        refused("another repository", example.replace("\"repo\": \"" + REPO, "\"repo\": \"someone/else"), url);
        refused("not JSON", "<html>", url);
        refused("path out of the mirror", example.replace("v0.3.0/R2Main.apk", "../../x/R2Main.apk"), url);
        refused("absolute URL", example.replace("v0.3.0/R2Main.apk", "http://evil/R2Main.apk"), url);
        refused("leading slash", example.replace("v0.3.0/R2Main.apk", "/v0.3.0/R2Main.apk"), url);
        refused("bad sha", example.replace("aa11bb22", "zz11bb22"), url);
        refused("too large", example.replace("2483112", String.valueOf(MAX + 1)), url);
        refused("bad tag", example.replace("\"tag\": \"v0.3.0\"", "\"tag\": \"../x\""), url);

        System.out.println("UpdateMirrorTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void refused(String what, String json, String url) {
        try {
            UpdateMirror.parse(json, url, REPO, MAX);
            check("refused: " + what, false);
        } catch (Exception e) {
            check("refused: " + what + " (" + e.getMessage() + ")", true);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }
}
