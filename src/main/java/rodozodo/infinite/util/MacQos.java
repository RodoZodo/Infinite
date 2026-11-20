package rodozodo.infinite.util;

import rodozodo.infinite.Log;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;


public final class MacQos {
    public enum Level {
        USER_INTERACTIVE(0x21), USER_INITIATED(0x19), DEFAULT(0x15), UTILITY(0x11), BACKGROUND(0x09);

        final int code;

        Level(int code) {
            this.code = code;
        }

        static Level of(int code) {
            for (var level : values()) if (level.code == code) return level;
            return null;
        }

        public static Level parse(String name) {
            if (name == null) return null;
            for (var level : values()) if (level.name().equalsIgnoreCase(name.trim())) return level;
            return null;
        }
    }

    private static final MethodHandle SET, GET;

    static {
        MethodHandle set = null, get = null;
        if (Platform.isMac()) {
            try {
                var linker = Linker.nativeLinker();
                var lookup = linker.defaultLookup();
                set = linker.downcallHandle(lookup.findOrThrow("pthread_set_qos_class_self_np"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                get = linker.downcallHandle(lookup.findOrThrow("qos_class_self"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            } catch (Throwable e) {
                Log.warn("macOS quality of service is not available: " + e);
                set = null;
                get = null;
            }
        }
        SET = set;
        GET = get;
    }

    private MacQos() {}

    public static boolean set(Level level) {
        if (SET == null || level == null) return false;
        try {
            return (int) SET.invokeExact(level.code, 0) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    public static Level current() {
        if (GET == null) return null;
        try {
            return Level.of((int) GET.invokeExact());
        } catch (Throwable e) {
            return null;
        }
    }
}
