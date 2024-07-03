package net.neoforged.serverstarterjar;

import java.lang.reflect.InvocationTargetException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main8 {
    static final int JAVA_VERSION = getJavaVersion();

    public static void main(String[] args) throws Throwable {
        if (JAVA_VERSION < 16) {
            System.err.println("The server bootstrap jar requires Java 16, but the current Java version is " + JAVA_VERSION);
            System.err.println("Note that, depending on the Minecraft version you're launching, you may need a higher Java version:\n\t1.17 requires Java 16\n\t1.18 through 1.20.4 require Java 17\n\t1.20.5 and upwards require Java 21");
            System.exit(1);
        }

        try {
            try {
                Class.forName("net.neoforged.serverstarterjar.Main").getDeclaredMethod("main", String[].class).invoke(null, (Object)args);
            } catch (InvocationTargetException exception) {
                // The reflection will cause all exceptions to be wrapped in an InvocationTargetException
                throw exception.getCause();
            }
        } catch (UnsupportedClassVersionError error) {
            if (error.getMessage() != null) {
                Matcher matcher = Pattern.compile("has been compiled by a more recent version of the Java Runtime \\(class file version (?<major>\\d+)\\.\\d\\)").matcher(error.getMessage());
                if (matcher.find()) {
                    int expected = Integer.parseInt(matcher.group(1));

                    // Java 8 is 52
                    ClassFormatError ex = new UnsupportedClassVersionError("A minimum Java version of " + (8 + (expected - 52)) + " is required, but the current Java version is " + JAVA_VERSION);
                    ex.setStackTrace(new StackTraceElement[0]);
                    ex.addSuppressed(error);
                    throw ex;
                }
            }
            throw error;
        }
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {  // Pre Java 9, Java versions started with "1."
            version = version.substring(2);
        }

        final int dotIndex = version.indexOf(".");
        // Only the major version is important
        if (dotIndex != -1) {
            version = version.substring(0, dotIndex);
        }

        return Integer.parseInt(version);
    }
}
