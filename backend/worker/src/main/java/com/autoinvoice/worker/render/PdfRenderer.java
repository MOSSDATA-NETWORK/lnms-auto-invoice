package com.autoinvoice.worker.render;

import com.autoinvoice.platform.DomainException;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Media;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PdfRenderer {
    static final int MAX_PDF_BYTES = 16 * 1024 * 1024;
    static final int MAX_PAGES = 100;
    static final double MAX_LAYOUT_HEIGHT_CSS_PIXELS = 120_000;
    static final int MAX_RENDERED_HTML_CHARS = 4_000_000;
    private static final Duration DEFAULT_RENDER_TIMEOUT = Duration.ofSeconds(30);
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern PDF_PAGE_OBJECT = Pattern.compile(
            "/Type\\s*/Page(?!s)(?=[\\s/>])");

    private final Duration renderTimeout;
    private final RenderEngine renderEngine;
    private final ProcessTerminator processTerminator;

    public PdfRenderer() {
        this(DEFAULT_RENDER_TIMEOUT, null, PdfRenderer::terminateNewDescendants);
    }

    PdfRenderer(Duration renderTimeout, RenderEngine renderEngine) {
        this(renderTimeout, renderEngine, PdfRenderer::terminateNewDescendants);
    }

    PdfRenderer(Duration renderTimeout, RenderEngine renderEngine, ProcessTerminator processTerminator) {
        if (renderTimeout == null || renderTimeout.isZero() || renderTimeout.isNegative()) {
            throw new IllegalArgumentException("PDF render timeout must be positive");
        }
        this.renderTimeout = renderTimeout;
        this.renderEngine = renderEngine;
        this.processTerminator = processTerminator;
    }

    public synchronized RenderedPdf render(String html) {
        if (html == null || html.isBlank()) {
            throw new DomainException("PDF_RENDER_INPUT_INVALID", "Rendered HTML is required", 422, Map.of());
        }
        if (html.length() > MAX_RENDERED_HTML_CHARS) {
            throw new DomainException("PDF_RENDER_INPUT_TOO_LARGE",
                    "Rendered HTML exceeds the PDF safety limit", 422,
                    Map.of("maximum_characters", MAX_RENDERED_HTML_CHARS));
        }

        Set<Long> baselineDescendants = descendantProcessIds();
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("pdf-render-operation-", 0).factory());
        Future<RenderedPdf> future = executor.submit(() -> {
            RenderedPdf rendered = renderEngine == null
                    ? renderWithPlaywright(html)
                    : renderEngine.render(html);
            validatePdf(rendered.bytes());
            return rendered;
        });
        try {
            return future.get(renderTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            terminateSafely(baselineDescendants);
            throw new DomainException("PDF_RENDER_TIMEOUT", "PDF rendering exceeded the hard timeout", 504,
                    Map.of("timeout_millis", renderTimeout.toMillis()));
        } catch (InterruptedException exception) {
            future.cancel(true);
            terminateSafely(baselineDescendants);
            Thread.currentThread().interrupt();
            throw new DomainException("PDF_RENDER_INTERRUPTED", "PDF rendering was interrupted", 503, Map.of());
        } catch (ExecutionException exception) {
            terminateSafely(baselineDescendants);
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new DomainException("PDF_RENDER_FAILED", "PDF rendering failed", 502,
                    Map.of("cause", cause.getClass().getSimpleName()));
        } finally {
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    private RenderedPdf renderWithPlaywright(String html) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true)
                     .setChromiumSandbox(true)
                     .setArgs(launchArguments()));
             BrowserContext context = browser.newContext(contextOptions());
             Page page = context.newPage()) {
            page.setDefaultTimeout(15_000);
            page.setDefaultNavigationTimeout(15_000);
            page.route("**/*", route -> route.abort());
            page.setContent(html, new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.LOAD)
                    .setTimeout(15_000));
            page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
            assertLayoutWithinLimit(context, page);
            byte[] bytes = page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true)
                    .setPreferCSSPageSize(true));
            return new RenderedPdf(bytes, browser.version());
        }
    }

    private void assertLayoutWithinLimit(BrowserContext context, Page page) {
        CDPSession session = context.newCDPSession(page);
        try {
            JsonObject metrics = session.send("Page.getLayoutMetrics");
            JsonObject contentSize = metrics.has("cssContentSize")
                    ? metrics.getAsJsonObject("cssContentSize")
                    : metrics.getAsJsonObject("contentSize");
            double height = contentSize.get("height").getAsDouble();
            if (!Double.isFinite(height) || height > MAX_LAYOUT_HEIGHT_CSS_PIXELS) {
                throw new DomainException("PDF_LAYOUT_LIMIT_EXCEEDED",
                        "Rendered document exceeds the maximum layout height", 422,
                        Map.of("height_css_pixels", height,
                                "maximum_css_pixels", MAX_LAYOUT_HEIGHT_CSS_PIXELS));
            }
        } finally {
            session.detach();
        }
    }

    private void validatePdf(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length || !startsWith(bytes, PDF_MAGIC)) {
            throw new DomainException("PDF_RENDER_INVALID", "Chromium returned an invalid PDF", 502, Map.of());
        }
        if (bytes.length > MAX_PDF_BYTES) {
            throw new DomainException("PDF_SIZE_LIMIT_EXCEEDED",
                    "Rendered PDF exceeds the maximum byte size", 422,
                    Map.of("pdf_bytes", bytes.length, "maximum_bytes", MAX_PDF_BYTES));
        }
        int pages = countPages(bytes);
        if (pages < 1) {
            throw new DomainException("PDF_RENDER_INVALID",
                    "Rendered PDF does not contain a readable page tree", 502, Map.of());
        }
        if (pages > MAX_PAGES) {
            throw new DomainException("PDF_PAGE_LIMIT_EXCEEDED",
                    "Rendered PDF exceeds the maximum page count", 422,
                    Map.of("page_count", pages, "maximum_pages", MAX_PAGES));
        }
    }

    static Browser.NewContextOptions contextOptions() {
        return new Browser.NewContextOptions()
                .setAcceptDownloads(false)
                .setJavaScriptEnabled(false)
                .setOffline(true)
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK);
    }

    static List<String> launchArguments() {
        return List.of("--disable-dev-shm-usage");
    }

    static int countPages(byte[] bytes) {
        Matcher matcher = PDF_PAGE_OBJECT.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
        int pages = 0;
        while (matcher.find()) {
            pages++;
        }
        return pages;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
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

    private void terminateSafely(Set<Long> baselineDescendants) {
        try {
            processTerminator.terminate(baselineDescendants);
        } catch (RuntimeException ignored) {
            // Preserve the timeout/interruption failure while the daemon render thread is cancelled.
        }
    }

    private static Set<Long> descendantProcessIds() {
        Set<Long> result = new HashSet<>();
        ProcessHandle.current().descendants().forEach(process -> result.add(process.pid()));
        return result;
    }

    private static void terminateNewDescendants(Set<Long> baselineDescendants) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            List<ProcessHandle> spawned = ProcessHandle.current().descendants()
                    .filter(process -> !baselineDescendants.contains(process.pid()))
                    .toList();
            if (spawned.isEmpty()) {
                return;
            }
            spawned.forEach(process -> {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            });
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        } while (System.nanoTime() < deadline);
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface RenderEngine {
        RenderedPdf render(String html) throws Exception;
    }

    @FunctionalInterface
    interface ProcessTerminator {
        void terminate(Set<Long> baselineDescendants);
    }

    public record RenderedPdf(byte[] bytes, String chromiumVersion) {
    }
}
