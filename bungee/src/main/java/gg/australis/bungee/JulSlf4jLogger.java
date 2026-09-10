package gg.australis.bungee;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.logging.Logger;

/**
 * Bridges the shared {@link gg.australis.core.EdgeClient}'s SLF4J logging onto
 * Bungee's {@link java.util.logging.Logger}.
 *
 * <p>Velocity injects an SLF4J logger and Paper exposes {@code getSLF4JLogger()},
 * but BungeeCord only gives a plugin a {@code java.util.logging.Logger}. Rather
 * than fork the shared {@code EdgeClient} (which takes an {@code org.slf4j.Logger}),
 * we wrap the JUL logger. Extending {@link AbstractLogger} means we only have to
 * implement the level guards and one normalized sink — SLF4J handles all the
 * varargs/marker overloads. The whole {@code org.slf4j} package is relocated at
 * shade time (see build.gradle.kts) so this can never clash with another copy on
 * the proxy classpath.
 */
final class JulSlf4jLogger extends AbstractLogger {

    private final transient Logger jul;

    JulSlf4jLogger(Logger jul) {
        this.jul = jul;
        this.name = jul.getName();
    }

    private static java.util.logging.Level map(Level level) {
        return switch (level) {
            case ERROR -> java.util.logging.Level.SEVERE;
            case WARN -> java.util.logging.Level.WARNING;
            case INFO -> java.util.logging.Level.INFO;
            case DEBUG -> java.util.logging.Level.FINE;
            case TRACE -> java.util.logging.Level.FINER;
        };
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                                               Object[] arguments, Throwable throwable) {
        java.util.logging.Level julLevel = map(level);
        if (!jul.isLoggable(julLevel)) {
            return;
        }
        String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        if (throwable != null) {
            jul.log(julLevel, message, throwable);
        } else {
            jul.log(julLevel, message);
        }
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override public boolean isTraceEnabled() { return jul.isLoggable(java.util.logging.Level.FINER); }
    @Override public boolean isTraceEnabled(Marker marker) { return isTraceEnabled(); }
    @Override public boolean isDebugEnabled() { return jul.isLoggable(java.util.logging.Level.FINE); }
    @Override public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    @Override public boolean isInfoEnabled() { return jul.isLoggable(java.util.logging.Level.INFO); }
    @Override public boolean isInfoEnabled(Marker marker) { return isInfoEnabled(); }
    @Override public boolean isWarnEnabled() { return jul.isLoggable(java.util.logging.Level.WARNING); }
    @Override public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }
    @Override public boolean isErrorEnabled() { return jul.isLoggable(java.util.logging.Level.SEVERE); }
    @Override public boolean isErrorEnabled(Marker marker) { return isErrorEnabled(); }
}
