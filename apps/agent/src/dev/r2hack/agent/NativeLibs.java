package dev.r2hack.agent;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the robot's own libr2d2.so (it opens the controller UART and sets the baud rate).
 * The library is part of the factory firmware and is not redistributed with this project:
 * it is loaded from /system/lib, or, if that copy is missing, extracted from the factory
 * app's APK on the device into the private files dir.
 */
final class NativeLibs {
    private static final String SYSTEM_COPY = "/system/lib/libr2d2.so";
    private static final String APK_ENTRY = "lib/armeabi-v7a/libr2d2.so";
    private static boolean loaded;

    static synchronized void loadR2d2(Context ctx) throws Exception {
        if (loaded) return;
        if (new File(SYSTEM_COPY).isFile()) {
            System.load(SYSTEM_COPY);
        } else {
            System.load(extractFromFactoryApk(ctx).getAbsolutePath());
        }
        loaded = true;
    }

    private static File extractFromFactoryApk(Context ctx) throws Exception {
        String apk = ctx.getPackageManager().getApplicationInfo(Config.STOCK_PACKAGE, 0).sourceDir;
        File dir = new File(ctx.getFilesDir(), "lib");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
        File so = new File(dir, "libr2d2.so");
        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry e = zip.getEntry(APK_ENTRY);
            if (e == null) throw new Exception(APK_ENTRY + " not found in " + apk);
            InputStream in = zip.getInputStream(e);
            OutputStream out = new FileOutputStream(so);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
        } finally {
            zip.close();
        }
        return so;
    }

    private NativeLibs() {}
}
