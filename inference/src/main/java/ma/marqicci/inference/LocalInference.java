package ma.marqicci.inference;

public final class LocalInference {
    static { System.loadLibrary("pappap"); }
    private LocalInference() {}
    public static native byte[] generate(String modelPath, byte[] prompt, int limit);
}
