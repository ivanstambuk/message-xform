package io.messagexform.standalone.proxy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.LoggerFactory;

/**
 * Programmatic Logback configuration for structured JSON vs. text logging
 * (T-004-49, NFR-004-07).
 *
 * <p>
 * Called during startup after config is loaded. Reconfigures the root
 * logger's appender and level based on {@code logging.format} and
 * {@code logging.level} from
 * {@link io.messagexform.standalone.config.ProxyConfig}.
 *
 * <p>
 * JSON mode uses Logback 1.5's built-in {@link JsonEncoder}, which
 * produces structured JSON output with timestamp, level, thread, logger,
 * message, and MDC fields. Text mode uses a human-readable pattern.
 */
public final class LogbackConfigurator {

    /** Human-readable pattern for text mode (development). */
    static final String TEXT_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    private LogbackConfigurator() {
        // utility class
    }

    /**
     * Configures the Logback root logger based on proxy configuration.
     *
     * @param format "json" for structured JSON output, "text" for
     *               human-readable pattern
     * @param level  log level (TRACE, DEBUG, INFO, WARN, ERROR)
     */
    public static void configure(String format, String level) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // Set root level
        rootLogger.setLevel(Level.toLevel(level, Level.INFO));

        // Replace root appender
        rootLogger.detachAndStopAllAppenders();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("STDOUT");

        if ("json".equalsIgnoreCase(format)) {
            JsonEncoder encoder = new JsonEncoder();
            encoder.setContext(context);
            encoder.start();
            appender.setEncoder(encoder);
        } else {
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern(TEXT_PATTERN);
            encoder.start();
            appender.setEncoder(encoder);
        }

        appender.start();
        rootLogger.addAppender(appender);

        // Keep Jetty/Javalin noise suppressed
        context.getLogger("org.eclipse.jetty").setLevel(Level.WARN);
        context.getLogger("io.javalin").setLevel(Level.INFO);
    }
}
