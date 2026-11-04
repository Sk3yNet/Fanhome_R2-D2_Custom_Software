package edu.cmu.pocketsphinx;

/**
 * The few entry points of CMU PocketSphinx's JNI library (libpocketsphinx_jni.so, BSD licence) that
 * R2 Main uses. The library itself isn't bundled: it is loaded at runtime from the factory app already
 * installed on the robot (dev.r2hack.main.Voice). The names must match the library's, so this class
 * keeps the package and method names of the library's own Java bindings. The proxy-object arguments
 * are unused by the library and passed as null.
 */
public final class PocketSphinxJNI {
    public static final native long Decoder_defaultConfig();

    public static final native long new_Decoder__SWIG_1(long config, Config configProxy);

    public static final native void delete_Decoder(long decoder);

    public static final native void Decoder_setKws(long decoder, Decoder proxy, String name, String path);

    public static final native void Decoder_setSearch(long decoder, Decoder proxy, String name);

    public static final native void Decoder_startUtt(long decoder, Decoder proxy);

    public static final native void Decoder_endUtt(long decoder, Decoder proxy);

    public static final native int Decoder_processRaw(long decoder, Decoder proxy, short[] data, long count,
            boolean noSearch, boolean fullUtt);

    public static final native long Decoder_hyp(long decoder, Decoder proxy);

    public static final native String Hypothesis_hypstr_get(long hyp, Hypothesis proxy);

    public static final native void delete_Hypothesis(long hyp);

    private PocketSphinxJNI() {}
}