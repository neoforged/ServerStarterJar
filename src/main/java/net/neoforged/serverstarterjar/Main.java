package net.neoforged.serverstarterjar;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    public static final char ESCAPE = (char) 92; // \\
    public static final char SPACE = ' ';
    public static final char QUOTES = '"';
    public static final char SINGLE_QUOTES = '\'';
    public static final OperatingSystem OS = detectOs();
    public static final MethodHandle LOAD_MODULE;
    public static final MethodHandle BOOT_LAYER;

    static {
        var lookup = MethodHandles.lookup();
        try {
            LOAD_MODULE = lookup.unreflect(lookup.findClass("jdk.internal.loader.BuiltinClassLoader").getDeclaredMethod("loadModule", ModuleReference.class));
            BOOT_LAYER = MethodHandles.privateLookupIn(System.class, lookup).unreflectSetter(System.class.getDeclaredField("bootLayer"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] $) throws Throwable {
        final var argsFile = getArgsFile();
        final var args = Files.readAllLines(argsFile);
        final var copy = new ArrayList<>(args);

        final var pathArg = find(args, copy, arg -> arg.startsWith("-p "))
                .findFirst().orElseThrow().substring("-p ".length());
        final var bootPath = installModulePath(getModulePath(pathArg));

        copy.indexOf("-p");
        find(args, copy, arg -> arg.startsWith("--add-opens "))
                .map(arg -> arg.substring("--add-opens ".length()).split("="))
                .forEach(toOpen -> Agent.instrumentation.redefineModule(
                        bootPath.layer().findModule(toOpen[0].split("/")[0]).orElseThrow(),
                        Set.of(),
                        Map.of(),
                        Map.of(toOpen[0].split("/")[1], Set.of(bootPath.layer().findModule(toOpen[1]).orElseThrow())),
                        Set.of(),
                        Map.of()
                ));
        find(args, copy, arg -> arg.startsWith("--add-exports "))
                .map(arg -> arg.substring("--add-exports ".length()).split("="))
                .forEach(toExport -> Agent.instrumentation.redefineModule(
                        bootPath.layer().findModule(toExport[0].split("/")[0]).orElseThrow(),
                        Set.of(),
                        Map.of(toExport[0].split("/")[1], Set.of(bootPath.layer().findModule(toExport[1]).orElseThrow())),
                        Map.of(),
                        Set.of(),
                        Map.of()
                ));

        BOOT_LAYER.invokeExact(bootPath.layer());

        find(args, copy, arg -> arg.startsWith("-D"))
                .map(arg -> arg.substring("-D".length()).split("=", 2))
                .forEach(arg -> System.setProperty(arg[0], arg[1]));

        // The args file specifies "--add-modules ALL-MODULE-PATH" which is completely useless now
        copy.remove("--add-modules ALL-MODULE-PATH");

        final var mainName = copy.remove(0);
        final var mainSplit = mainName.split("\\.");
        final var mainSplitByDot = new ArrayList<>(Arrays.asList(mainSplit));
        mainSplitByDot.remove(mainSplitByDot.size() - 1);
        final var mainPkg = String.join(".", mainSplitByDot);

        final Method main;
        try {
            final var mainModule = bootPath.layer().modules()
                    .stream().filter(m -> m.getPackages().contains(mainPkg))
                    .findFirst()
                    .orElseThrow();
            final var mainClass = mainModule.getClassLoader().loadClass(mainName);
            main = mainClass.getDeclaredMethod("main", String[].class);
        } catch (Exception e) {
            throw new Exception("Failed to find main class \"" + mainName + "\"", e);
        }

        main.invoke(null, new Object[] { copy.stream().flatMap(arg -> toArgs(arg).stream()).toArray(String[]::new) });
    }

    private static Stream<String> find(List<String> args, List<String> copy, Predicate<String> test) {
        return args.stream()
                .filter(test)
                .peek(copy::remove);
    }

    private static ModuleLayer.Controller installModulePath(Path[] path) throws Throwable {
        final var finder = ModuleFinder.of(path);
        final var allModules = finder.findAll();
        for (ModuleReference module : allModules) {
            LOAD_MODULE.invoke(ClassLoader.getSystemClassLoader(), module);
        }
        return ModuleLayer.defineModules(
                ModuleLayer.boot().configuration().resolve(
                        finder, ModuleFinder.of(), allModules
                                .stream().map(mr -> mr.descriptor().name())
                                .collect(Collectors.toSet())
                ),
                List.of(ModuleLayer.boot()),
                s -> ClassLoader.getSystemClassLoader()
        );
    }

    private static Path[] getModulePath(String path) {
        return Arrays.stream(path.split(File.pathSeparator))
                .map(Path::of)
                .toArray(Path[]::new);
    }

    private static Path getArgsFile() {
        var command = toArgs(getCommand());
        for (String part : command) {
            if (part.startsWith("@") && part.endsWith("/" + OS.argsFile)) {
                return Path.of(part.substring(1));
            }
        }
        System.err.println("Failed to find argument file in command " + command);
        System.exit(0);
        throw null;
    }

    private static String getCommand() {
        final var path = Path.of(OS.runFile);
        try {
            final var contents = Files.readAllLines(path);
            for (String content : contents) {
                if (content.isBlank() || OS.irrelevantCommand.test(content)) continue;
                return content;
            }
            System.err.println("Failed to find start command in file " + path);
            System.exit(0);
        } catch (IOException e) {
            System.err.println("Failed to read contents of " + OS.runFile + " file at " + path);
            System.exit(0);
        }

        throw null;
    }

    public static List<String> toArgs(String str) {
        final List<String> args = new ArrayList<>();
        StringBuilder current = null;
        char enclosing = 0;

        final char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final boolean isEscaped = i > 0 && chars[i - 1] == ESCAPE;
            final char ch = chars[i];
            if (ch == SPACE && enclosing == 0 && current != null) {
                args.add(current.toString());
                current = null;
                continue;
            }

            if (!isEscaped) {
                if (ch == enclosing) {
                    args.add(current.toString());
                    enclosing = 0;
                    current = null;
                    continue;
                } else if ((ch == QUOTES || ch == SINGLE_QUOTES) && (current == null || current.toString().isBlank())) {
                    current = new StringBuilder();
                    enclosing = ch;
                    continue;
                }
            }

            if (ch != ESCAPE) {
                if (current == null) current = new StringBuilder();
                current.append(ch);
            }
        }

        if (current != null && enclosing == 0) {
            args.add(current.toString());
        }

        return args;
    }

    public static OperatingSystem detectOs() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return OperatingSystem.WINDOWS;
        }
        return OperatingSystem.NIX;
    }

    public enum OperatingSystem {
        WINDOWS("run.bat", s -> s.startsWith("@") || s.startsWith("REM "), "win_args.txt"),
        NIX("run.sh", s -> s.startsWith("#"), "unix_args.txt");

        public final String runFile;
        public final Predicate<String> irrelevantCommand;
        public final String argsFile;

        OperatingSystem(String runFile, Predicate<String> irrelevantCommand, String argsFile) {
            this.runFile = runFile;
            this.irrelevantCommand = irrelevantCommand;
            this.argsFile = argsFile;
        }
    }
}
