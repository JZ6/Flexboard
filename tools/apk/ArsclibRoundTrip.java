/**
 * Decode/encode shim for check_patch_resources.py — see that script for what the lane proves.
 *
 * Two modes so the expensive half (decode) runs once and caches, while the cheap half (encode)
 * runs on every pre-push check:
 *
 *   decode <gboard.apk> <outdir>   full XML decode of the APK (no dex), like Morphe's
 *                                  ArsclibResourceCoder.decodeResources()
 *   encode <workdir> <out.apk>     full-table rebuild from a decoded+modified tree, like
 *                                  ArsclibResourceCoder.encodeResources(). This is the exact
 *                                  arsclib call whose XmlEncodeException killed 1.4.0-dev.3
 *                                  ("Undefined entry name", type derived from filename) and whose
 *                                  SAX "END_TAG resources" burn killed dev.4 — reproduced here on
 *                                  the desk instead of on a phone.
 *
 * Compiled on demand by the python lane into its cache dir; only needs the pinned arsclib jar
 * on the classpath, no Android SDK.
 */
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.ApkModuleXmlDecoder;
import com.reandroid.apk.ApkModuleXmlEncoder;
import java.io.File;

public class ArsclibRoundTrip {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                "usage: ArsclibRoundTrip decode <apk> <outdir> | encode <workdir> <out.apk>");
            System.exit(2);
        }
        switch (args[0]) {
            case "decode":
                decode(args[1], args[2]);
                break;
            case "encode":
                encode(args[1], args[2]);
                break;
            default:
                System.err.println("unknown mode: " + args[0]);
                System.exit(2);
        }
    }

    static void decode(String apkPath, String outDir) throws Exception {
        ApkModule module = ApkModule.loadApkFile(new File(apkPath));
        try {
            ApkModuleXmlDecoder decoder = new ApkModuleXmlDecoder(module);
            decoder.setKeepResPath(false);
            decoder.setDexDecoder((dexFile, mainDir) -> {});
            decoder.setDexProfileDecoder(null);
            decoder.decode(new File(outDir));
        } finally {
            module.close();
        }
    }

    static void encode(String workDir, String outApk) throws Exception {
        ApkModuleXmlEncoder encoder = new ApkModuleXmlEncoder();
        ApkModule module = encoder.getApkModule();
        try {
            encoder.scanDirectory(new File(workDir));
            module.writeApk(new File(outApk));
        } finally {
            module.close();
        }
    }
}
