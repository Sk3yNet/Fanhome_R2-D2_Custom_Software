package dev.r2hack.agent;

import java.io.File;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Signed updates (docs/security.md): an APK is installed only if it is signed by a trusted certificate.
 * Trusted are the agent's own signing certificate and any SHA-256 added with POST /trust?add=. The check
 * reads the APK's JAR (v1) signature, which every build of ours has (apksigner adds it for minSdk 19),
 * and needs every file in it signed by the same one certificate. Android's own installer checks the
 * signature again and refuses an update from a different key; this check also covers a first install
 * and makes sure a valid but foreign APK is never installed.
 * Pure Java, so it runs in the off-robot tests.
 */
final class ApkTrust {
    private final Set<String> own;
    private volatile List<String> extra;

    ApkTrust(Set<String> ownCerts, List<String> extraCerts) {
        own = Collections.unmodifiableSet(new LinkedHashSet<>(ownCerts));
        extra = Collections.unmodifiableList(new ArrayList<>(extraCerts));
    }

    Set<String> own() {
        return own;
    }

    List<String> extra() {
        return extra;
    }

    void setExtra(List<String> certs) {
        extra = Collections.unmodifiableList(new ArrayList<>(certs));
    }

    boolean trusts(String certSha256) {
        return own.contains(certSha256) || extra.contains(certSha256);
    }

    /** Lowercase hex without colons; accepts apksigner's and keytool's (AA:BB:...) forms. */
    static String normalize(String sha256) {
        String s = sha256 == null ? "" : sha256.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.US);
        if (!s.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("a certificate SHA-256 is 64 hex digits");
        return s;
    }

    /**
     * The one certificate that signs every file in the APK, as SHA-256 hex. Throws if the APK is unsigned,
     * damaged or modified after signing, or has files signed by different keys.
     */
    static String signer(File apk) throws Exception {
        JarFile jar = new JarFile(apk, true);
        try {
            String signer = null;
            int files = 0;
            byte[] buf = new byte[65536];
            Enumeration<JarEntry> e = jar.entries();
            while (e.hasMoreElements()) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")) continue;
                InputStream in = jar.getInputStream(entry);
                try {
                    while (in.read(buf) >= 0) {
                        // reading to the end checks the entry's digest; a mismatch throws SecurityException
                    }
                } finally {
                    in.close();
                }
                Certificate[] certs = entry.getCertificates();
                if (certs == null || certs.length == 0) throw new SecurityException("unsigned file in the APK: " + name);
                String s = RequestAuth.sha256Hex(certs[0].getEncoded());
                if (signer == null) signer = s;
                else if (!signer.equals(s)) throw new SecurityException("files in the APK are signed by different keys");
                files++;
            }
            if (signer == null || files == 0) throw new SecurityException("the APK is not signed");
            return signer;
        } finally {
            jar.close();
        }
    }

    /** null if the APK may be installed, otherwise why not. */
    String refusal(File apk) {
        String s;
        try {
            s = signer(apk);
        } catch (Exception e) {
            return "signature check failed: " + e.getMessage();
        }
        if (!trusts(s)) {
            return "signed by an untrusted certificate (SHA-256 " + s + "). If you trust it, add it with "
                    + "POST /trust?add=" + s;
        }
        return null;
    }
}
