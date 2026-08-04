package com.autoinvoice.worker.render;

import com.autoinvoice.platform.DomainException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfRendererTest {
    @Test
    void chromiumLaunchKeepsTheSandboxEnabled() {
        assertThat(PdfRenderer.launchArguments())
                .contains("--disable-dev-shm-usage")
                .doesNotContain("--no-sandbox", "--disable-setuid-sandbox");
    }

    @Test
    void browserContextDisablesActiveAndExternalCapabilities() {
        Browser.NewContextOptions options = PdfRenderer.contextOptions();

        assertThat(options.javaScriptEnabled).isFalse();
        assertThat(options.offline).isTrue();
        assertThat(options.acceptDownloads).isFalse();
        assertThat(options.serviceWorkers).isEqualTo(ServiceWorkerPolicy.BLOCK);
    }

    @Test
    void enforcesAHardRenderTimeout() {
        PdfRenderer renderer = new PdfRenderer(Duration.ofMillis(100), html -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return syntheticPdf(1);
        });
        long started = System.nanoTime();

        assertThatThrownBy(() -> renderer.render("<p>invoice</p>"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("PDF_RENDER_TIMEOUT"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void timeoutForceKillsProcessesSpawnedByTheRender() {
        AtomicReference<Process> spawnedProcess = new AtomicReference<>();
        PdfRenderer renderer = new PdfRenderer(Duration.ofMillis(500), html -> {
            Process process = startSleepingProcess();
            spawnedProcess.set(process);
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return syntheticPdf(1);
        });

        assertThatThrownBy(() -> renderer.render("<p>invoice</p>"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("PDF_RENDER_TIMEOUT"));

        Process process = spawnedProcess.get();
        assertThat(process).as("render engine must spawn the child before the timeout").isNotNull();
        assertThat(waitUntilDead(process, Duration.ofSeconds(3)))
                .as("timed-out render child process must be terminated")
                .isTrue();
    }

    @Test
    void failedRenderForceKillsProcessesSpawnedByTheRender() {
        AtomicReference<Process> spawnedProcess = new AtomicReference<>();
        PdfRenderer renderer = new PdfRenderer(Duration.ofSeconds(2), html -> {
            Process process = startSleepingProcess();
            spawnedProcess.set(process);
            throw new IllegalStateException("render failed");
        });

        assertThatThrownBy(() -> renderer.render("<p>invoice</p>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("render failed");

        Process process = spawnedProcess.get();
        assertThat(process).as("render engine must spawn the child before failing").isNotNull();
        assertThat(waitUntilDead(process, Duration.ofSeconds(3)))
                .as("failed render child process must be terminated")
                .isTrue();
    }

    @Test
    void rejectsRuntimeLayoutsThatWouldCreateExcessivePages() {
        PdfRenderer renderer = new PdfRenderer(Duration.ofSeconds(60), null);

        assertThatThrownBy(() -> renderer.render("""
                <!doctype html>
                <html><head><style>body { height: 120001px; margin: 0; }</style></head>
                <body>invoice</body></html>
                """))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("PDF_LAYOUT_LIMIT_EXCEEDED"));
    }

    @Test
    void rendersANormalA4DocumentWithRealChromium() {
        PdfRenderer.RenderedPdf rendered = new PdfRenderer(Duration.ofSeconds(60), null).render("""
                <!doctype html>
                <html lang="zh-CN">
                  <head>
                    <meta charset="UTF-8">
                    <style>
                      @page { size: A4; margin: 16mm; }
                      body { font-family: sans-serif; }
                      table { width: 100%; border-collapse: collapse; }
                      th, td { border: 1px solid #999; padding: 6px; }
                      .invoice-page { break-after: page; }
                      .invoice-page:last-child { break-after: auto; }
                    </style>
                  </head>
                  <body>
                    <section class="invoice-page">
                      <h1>Invoice INV-2026-0001</h1>
                      <table><tr><th>Item</th><th>Amount</th></tr><tr><td>Service</td><td>CNY 123.45</td></tr></table>
                    </section>
                    <section class="invoice-page"><h2>Usage details</h2><p>Aggregate 95th percentile</p></section>
                    <section class="invoice-page"><h2>Payment information</h2><p>Due in 30 days</p></section>
                  </body>
                </html>
                """);

        assertThat(rendered.bytes()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(rendered.chromiumVersion()).isNotBlank();
        assertThat(PdfRenderer.countPages(rendered.bytes())).isEqualTo(3);
    }

    @Test
    void rejectsPdfPageAndByteLimitOverruns() {
        PdfRenderer tooManyPages = new PdfRenderer(Duration.ofSeconds(1), html -> syntheticPdf(101));

        assertThatThrownBy(() -> tooManyPages.render("<p>invoice</p>"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("PDF_PAGE_LIMIT_EXCEEDED"));

        byte[] oversized = new byte[PdfRenderer.MAX_PDF_BYTES + 1];
        byte[] validPrefix = syntheticPdf(1).bytes();
        System.arraycopy(validPrefix, 0, oversized, 0, validPrefix.length);
        PdfRenderer tooManyBytes = new PdfRenderer(Duration.ofSeconds(1),
                html -> new PdfRenderer.RenderedPdf(oversized, "test"));

        assertThatThrownBy(() -> tooManyBytes.render("<p>invoice</p>"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("PDF_SIZE_LIMIT_EXCEEDED"));
    }

    private static PdfRenderer.RenderedPdf syntheticPdf(int pages) {
        StringBuilder value = new StringBuilder("%PDF-1.7\n");
        for (int page = 0; page < pages; page++) {
            value.append(page + 1).append(" 0 obj << /Type /Page >> endobj\n");
        }
        value.append("%%EOF");
        return new PdfRenderer.RenderedPdf(value.toString().getBytes(StandardCharsets.ISO_8859_1), "test");
    }

    private static Process startSleepingProcess() throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 30")
                    .redirectErrorStream(true)
                    .start();
        }
        return new ProcessBuilder("sh", "-c", "sleep 30")
                .redirectErrorStream(true)
                .start();
    }

    private static boolean waitUntilDead(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
