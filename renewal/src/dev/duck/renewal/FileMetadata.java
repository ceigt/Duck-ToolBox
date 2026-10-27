package dev.duck.renewal;

import java.nio.file.*;

/** An Android daemon must retain access after a root-owned temporary file is renamed. */
final class FileMetadata {
    static void preserve(Path original, Path temporary) throws Exception {
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) return;
        Class<?> os;
        try { os = Class.forName("android.system.Os"); }
        catch (ClassNotFoundException host) { return; }
        Object stat = os.getMethod("stat", String.class).invoke(null, original.toString());
        Class<?> type = stat.getClass();
        os.getMethod("chown", String.class, int.class, int.class).invoke(null, temporary.toString(),
                type.getField("st_uid").getInt(stat), type.getField("st_gid").getInt(stat));
        byte[] context = (byte[]) os.getMethod("getxattr", String.class, String.class)
                .invoke(null, original.toString(), "security.selinux");
        os.getMethod("setxattr", String.class, String.class, byte[].class, int.class)
                .invoke(null, temporary.toString(), "security.selinux", context, 0);
    }
}
