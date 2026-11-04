package edu.cmu.pocketsphinx;

/** The configuration entry points of the same library (see PocketSphinxJNI). */
public final class SphinxBaseJNI {
    public static final native void Config_setString(long config, Config proxy, String key, String value);

    public static final native void Config_setFloat(long config, Config proxy, String key, double value);

    public static final native void Config_setBoolean(long config, Config proxy, String key, boolean value);

    public static final native double Config_getFloat(long config, Config proxy, String key);

    public static final native void delete_Config(long config);

    private SphinxBaseJNI() {}
}