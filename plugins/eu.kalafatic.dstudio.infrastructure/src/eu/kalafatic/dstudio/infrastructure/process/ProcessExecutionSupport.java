package eu.kalafatic.dstudio.infrastructure.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ProcessExecutionSupport {

    public record ProcessExecutionResult(boolean success, int exitCode, boolean timedOut,
            boolean cancelled, List<String> stdoutLines, List<String> stderrLines) {

        public String stderrTail(int lines) {
            if (stderrLines.isEmpty()) {
                return "";
            }
            List<String> tail = stderrLines.subList(Math.max(0, stderrLines.size() - lines),
                    stderrLines.size());
            return String.join("\n", tail);
        }
    }

    private ProcessExecutionSupport() {
    }

    public static ProcessExecutionResult run(List<String> command, Path workingDirectory,
            Map<String, String> environment, Map<String, String> environmentRemovals,
            Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer,
            BooleanSupplier cancellationSupplier, long timeoutMillis) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectInput(ProcessBuilder.Redirect.from(
                new java.io.File("/dev/null")));
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        if (environmentRemovals != null) {
            for (String key : environmentRemovals.keySet()) {
                builder.environment().remove(key);
            }
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new ProcessExecutionResult(false, -1, false, false, List.of(),
                    List.of("Failed to start process: " + e.getMessage()));
        }
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        Thread stdoutThread = new Thread(() -> drain(process, false, stdoutLines,
                stdoutConsumer), "dstudio-stdout");
        Thread stderrThread = new Thread(() -> drain(process, true, stderrLines,
                stderrConsumer), "dstudio-stderr");
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        boolean timedOut = false;
        boolean cancelled = false;
        int exitCode = -1;
        try {
            while (true) {
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    cancelled = true;
                    destroy(process);
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true;
                    destroy(process);
                    break;
                }
            }
            stdoutThread.join(2000);
            stderrThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy(process);
            cancelled = true;
        }
        return new ProcessExecutionResult(!timedOut && !cancelled && exitCode == 0, exitCode,
                timedOut, cancelled, List.copyOf(stdoutLines), List.copyOf(stderrLines));
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void drain(Process process, boolean error, List<String> lines,
            Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                error ? process.getErrorStream() : process.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (consumer != null) {
                    consumer.accept(line);
                }
            }
        } catch (IOException e) {
            System.err.println("EVO-Studio process stream drain failed: " + e.getMessage());
        }
    }
}
