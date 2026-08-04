package com.autoinvoice.worker.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot entry point for exercising the production {@link PdfRenderer} inside the hardened
 * worker container. This class intentionally lives under src/test and is not packaged in the
 * production application jar.
 */
public final class PdfRendererContainerSmoke {
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final List<String> FORBIDDEN_SANDBOX_ARGUMENTS = List.of(
            "--no-sandbox",
            "--disable-setuid-sandbox"
    );

    private PdfRendererContainerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        List<String> observedChromiumCommands = new CopyOnWriteArrayList<>();
        AtomicBoolean polling = new AtomicBoolean(true);
        Thread processObserver = Thread.ofPlatform()
                .name("chromium-command-observer")
                .daemon(true)
                .start(() -> observeChromiumCommands(polling, observedChromiumCommands));

        PdfRenderer.RenderedPdf renderedPdf;
        try {
            renderedPdf = new PdfRenderer().render("""
                    <!doctype html>
                    <html lang="zh-CN">
                      <head>
                        <meta charset="utf-8">
                        <style>
                          @page { size: A4; margin: 18mm; }
                          body { font-family: sans-serif; color: #172033; }
                          h1 { font-size: 24px; }
                          table { width: 100%; border-collapse: collapse; }
                          th, td { border: 1px solid #cbd5e1; padding: 8px; }
                          td:last-child { text-align: right; }
                        </style>
                      </head>
                      <body>
                        <h1>Auto Invoice 安全渲染冒烟</h1>
                        <table>
                          <thead><tr><th>项目</th><th>金额</th></tr></thead>
                          <tbody><tr><td>Render Worker</td><td>CNY 123.45</td></tr></tbody>
                        </table>
                      </body>
                    </html>
                    """);
        } finally {
            polling.set(false);
            processObserver.join(2_000);
        }

        require(renderedPdf.bytes().length > PDF_MAGIC.length,
                "rendered PDF is empty");
        require(startsWith(renderedPdf.bytes(), PDF_MAGIC),
                "rendered output does not start with %PDF-");
        require(renderedPdf.chromiumVersion() != null && !renderedPdf.chromiumVersion().isBlank(),
                "Chromium version is missing");
        require(!observedChromiumCommands.isEmpty(),
                "no Chromium process command line was observed during rendering");

        List<String> forbiddenArguments = observedChromiumCommands.stream()
                .flatMap(command -> Arrays.stream(command.split("\\s+")))
                .filter(FORBIDDEN_SANDBOX_ARGUMENTS::contains)
                .distinct()
                .toList();
        require(forbiddenArguments.isEmpty(),
                "Chromium sandbox was disabled with arguments: " + forbiddenArguments);

        System.out.printf(
                "PDF_RENDERER_CONTAINER_SMOKE_OK bytes=%d magic=%%PDF- chromium=%s observed_chromium_processes=%d forbidden_sandbox_args=none%n",
                renderedPdf.bytes().length,
                renderedPdf.chromiumVersion(),
                observedChromiumCommands.size());
    }

    private static void observeChromiumCommands(
            AtomicBoolean polling,
            List<String> observedChromiumCommands) {
        while (polling.get()) {
            try (var processes = Files.list(Path.of("/proc"))) {
                processes.filter(path -> path.getFileName().toString().chars().allMatch(Character::isDigit))
                        .map(path -> path.resolve("cmdline"))
                        .filter(Files::isReadable)
                        .map(PdfRendererContainerSmoke::readCommandLine)
                        .filter(command -> command.contains("/ms-playwright/")
                                && (command.contains("chrome") || command.contains("headless_shell")))
                        .filter(command -> !command.isBlank())
                        .forEach(command -> {
                            if (!observedChromiumCommands.contains(command)) {
                                observedChromiumCommands.add(command);
                            }
                        });
            } catch (IOException ignored) {
                // Processes may exit between listing /proc and reading cmdline; retry until render ends.
            }

            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String readCommandLine(Path commandLine) {
        try {
            byte[] bytes = Files.readAllBytes(commandLine);
            List<String> arguments = new ArrayList<>();
            int start = 0;
            for (int index = 0; index <= bytes.length; index++) {
                if (index == bytes.length || bytes[index] == 0) {
                    if (index > start) {
                        arguments.add(new String(bytes, start, index - start, StandardCharsets.UTF_8));
                    }
                    start = index + 1;
                }
            }
            return String.join(" ", arguments);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
