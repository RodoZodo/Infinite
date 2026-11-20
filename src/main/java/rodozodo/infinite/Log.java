package rodozodo.infinite;

import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;


public final class Log {
    @FunctionalInterface
    public interface Sink {
        void show(String line);
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("Infinite");
    private static final StackWalker CALLERS = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ClassValue<String> TAGS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            var outer = type;
            while (outer.getEnclosingClass() != null) outer = outer.getEnclosingClass();
            return "[" + outer.getSimpleName() + "] ";
        }
    };

    private static final long CHAT_INTERVAL_MILLIS = 5_000;
    private static long lastChatMillis;
    private static int unshownErrors;
    private static volatile @Nullable Sink sink;
    private static volatile @Nullable LinkedBlockingQueue<String> copy;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    public static void setSink(@Nullable Sink sink) {
        Log.sink = sink;
    }

    public static void info(String message) {
        String line = tag() + message;
        if (LOG.isInfoEnabled()) LOG.info(line);
        copy("INFO", line, null);
    }

    public static void warn(String message) {
        String line = tag() + message;
        LOG.warn(line);
        copy("WARN", line, null);
    }

    public static void warn(String message, Throwable cause) {
        String line = tag() + message;
        LOG.warn(line, cause);
        copy("WARN", line, cause);
    }

    public static void error(String message) {
        String line = tag() + message;
        LOG.error(line);
        copy("ERROR", line, null);
        toChat(message, null);
    }

    public static void error(String message, Throwable cause) {
        String line = tag() + message;
        LOG.error(line, cause);
        copy("ERROR", line, cause);
        toChat(message, cause);
    }

    public static void error(Throwable cause) {
        error(cause.getClass().getSimpleName(), cause);
    }

    public static void copyTo(Path file) {
        if (copy != null) return;
        var queue = new LinkedBlockingQueue<String>();
        BufferedWriter out;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".previous"), StandardCopyOption.REPLACE_EXISTING);
            }
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open " + file + " for Infinite's own copy of its log", e);
            return;
        }
        var writer = new Thread(() -> {
            var batch = new ArrayList<String>();
            try (out) {
                while (true) {
                    batch.add(queue.take());
                    queue.drainTo(batch);
                    for (var line : batch) out.write(line);
                    batch.clear();
                    out.flush();
                }
            } catch (InterruptedException | IOException e) {
            }
        }, "Infinite log writer");
        writer.setDaemon(true);
        writer.start();
        copy = queue;
    }

    private static void copy(String level, String line, @Nullable Throwable cause) {
        var queue = copy;
        if (queue == null) return;
        var text = new StringBuilder().append('[').append(LocalTime.now().format(TIME)).append("] [")
                .append(Thread.currentThread().getName()).append('/').append(level).append("] ").append(line)
                .append(System.lineSeparator());
        if (cause != null) {
            var trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            text.append(trace);
        }
        queue.add(text.toString());
    }

    private static String tag() {
        return CALLERS.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != Log.class)
                .findFirst()
                .map(TAGS::get)
                .orElse(""));
    }

    private static void toChat(String message, @Nullable Throwable cause) {
        String line;
        synchronized (Log.class) {
            long now = System.currentTimeMillis();
            if (now - lastChatMillis < CHAT_INTERVAL_MILLIS) {
                unshownErrors++;
                return;
            }
            lastChatMillis = now;
            line = "Infinite: " + message + (cause == null || cause.getMessage() == null ? "" : " (" + cause.getMessage() + ")");
            if (unshownErrors != 0) line += " [+" + unshownErrors + " more in the log]";
            unshownErrors = 0;
        }
        var current = sink;
        if (current == null) return;
        try {
            current.show(line);
        } catch (RuntimeException | LinkageError noDisplay) {
        }
    }
}