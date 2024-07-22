package net.neoforged.serverstarterjar;

import java.lang.reflect.InvocationTargetException;
import java.security.Permission;

/**
 * A wrapper around {@link SecurityManager} used to run code with privileged access.
 * <p>
 * Security managers are deprecated in Java 17, and since Java 18 they need command line arguments
 * so it's not guaranteed that the security manager will always be set
 */
public class SecurityAccess {
    public static <T extends Throwable> void wrapNoForceExit(ThrowingRunnable<T> runnable) throws T {
        wrap(new SecurityManager() {
            private volatile boolean attemptedForceExit = false;

            @Override
            public void checkExit(int status) {
                if (status == 0) {
                    attemptedForceExit = true;
                    throw new SecurityException();
                } else if (status == 1 && attemptedForceExit) {
                    throw new SecurityException();
                }
            }

            @Override
            public void checkPermission(Permission perm) {
            }
        }, () -> {
            try {
                runnable.run();
            } catch (SecurityException ignored) {
            }
        });
    }

    public static <T extends Throwable> void wrap(SecurityManager manager, ThrowingRunnable<T> runnable) throws T {
        var old = System.getSecurityManager();
        try {
            System.setSecurityManager(manager);
        } catch (UnsupportedOperationException ignored) {
        }

        try {
            runnable.run();
        } finally {
            try {
                System.setSecurityManager(old);
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable<T extends Throwable> {
        void run() throws T;
    }
}
