package ac.boar.anticheat.util;

import ac.boar.anticheat.Boar;
import ac.boar.geyser.GeyserBoar;
import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class BoarLog {
    private BoarLog() {
    }

    public static void info(String area, String message) {
        log(Level.INFO, area, message, null, true);
    }

    public static void debug(String area, String message) {
        log(Level.INFO, area, message, null, isDebugEnabled());
    }

    public static void debug(String area, String message, Throwable throwable) {
        log(Level.INFO, area, message, throwable, isDebugEnabled());
    }

    public static void warning(String area, String message) {
        log(Level.WARNING, area, message, null, true);
    }

    public static void warning(String area, String message, Throwable throwable) {
        log(Level.WARNING, area, message, throwable, true);
    }

    public static void severe(String area, String message) {
        log(Level.SEVERE, area, message, null, true);
    }

    public static void severe(String area, String message, Throwable throwable) {
        log(Level.SEVERE, area, message, throwable, true);
    }

    private static boolean isDebugEnabled() {
        return Boar.getConfig() == null || Boar.getConfig().debugMode();
    }

    private static void log(Level level, String area, String message, Throwable throwable, boolean enabled) {
        if (!enabled) {
            return;
        }

        String line = "[Boar/" + area + "] " + message;
        if (throwable != null) {
            line += System.lineSeparator() + stackTrace(throwable);
        }

        ExtensionLogger logger = GeyserBoar.getLogger();
        if (logger == null) {
            System.out.println(line);
            return;
        }

        switch (level) {
            case INFO -> logger.info(line);
            case WARNING -> logger.warning(line);
            case SEVERE -> logger.severe(line);
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private enum Level {
        INFO,
        WARNING,
        SEVERE
    }
}
