package com.computeruse.automation.surface.web;

import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.Addresses;
import com.computeruse.automation.surface.Conditions;
import com.computeruse.automation.surface.Evidence;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.SurfaceException;
import com.computeruse.automation.surface.TargetNotFoundException;
import com.computeruse.automation.surface.WaitTimedOutException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.SelectOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The web implementation of Surface: one Chromium browser, one tab, one run.
 *
 * Not thread-safe: Playwright objects must be used from the thread that created them.
 */
public final class PlaywrightSurface implements Surface {

    /** Fixed so a Coordinates locator recorded at this size lands on the same pixel in replay. */
    static final int VIEWPORT_WIDTH  = 1280;
    static final int VIEWPORT_HEIGHT = 720;

    /** How long any single Playwright operation may take before it fails. */
    private static final double DEFAULT_TIMEOUT_MS = 10_000;

    /** Painted over sensitive controls in screenshots. */
    static final String MASK_COLOR = "#FF00FF";

    /** How long to keep looking for a control before reporting it as not found. */
    private static final long FIND_TIMEOUT_MS = 5_000;

    /** Pause between two looks while waiting. */
    private static final long POLL_MS = 100;

    /**
     * Runs in every page of the browser. While a person has the window it writes down their
     * clicks and changes. It never writes what was typed: only how many characters went where.
     * The notes live in sessionStorage so they survive moving from one page to the next.
     */
    private static final String HANDOFF_RECORDER = """
            (() => {
              const LOG = '__handoffLog', ON = '__handoffOn';
              const clean = s => (s || '').replace(/\\s+/g, ' ').trim().slice(0, 40);
              const who = el => {
                const tag = el.tagName.toLowerCase();
                const name = el.getAttribute('name') || el.id || '';
                const isButton = tag === 'input' && ['submit', 'button', 'reset'].includes(el.type);
                const text = isButton ? clean(el.value)
                    : ['input', 'select', 'textarea'].includes(tag) ? '' : clean(el.innerText);
                return tag + (name ? '#' + name : '') + (text ? ' "' + text + '"' : '');
              };
              const note = message => {
                try {
                  if (sessionStorage.getItem(ON) !== '1') return;
                  const list = JSON.parse(sessionStorage.getItem(LOG) || '[]');
                  list.push(message);
                  sessionStorage.setItem(LOG, JSON.stringify(list));
                } catch (e) { /* no storage on this page */ }
              };
              addEventListener('click', e => {
                if (e.target instanceof Element) note('clicked ' + who(e.target));
              }, true);
              addEventListener('change', e => {
                const el = e.target;
                if (!(el instanceof Element)) return;
                if (el.tagName === 'SELECT') {
                  const picked = el.selectedOptions[0];
                  note('chose "' + clean(picked && picked.text) + '" in ' + who(el));
                } else if (el.type === 'checkbox' || el.type === 'radio') {
                  note('set ' + who(el) + ' to ' + el.checked);
                } else {
                  note('typed ' + (el.value || '').length + ' characters into ' + who(el));
                }
              }, true);
            })();
            """;

    /**
     * evidenceDir — folder that screenshots and snapshots are written into
     * headless    — false to watch the browser while it works
     * scrub       — removes secrets from text before it is written to disk
     */
    public record Options(Path evidenceDir, boolean headless, UnaryOperator<String> scrub) {}

    private final Options options;

    /** True while a person has the window. Reported by browser events, so used only on the owning thread. */
    private boolean handoffOn;

    /** Pages reached, in any tab, while a person had the window. */
    private final List<String> visited = new ArrayList<>();

    // Each of these lives inside the one above it.
    private Playwright playwright;   // the driver process that talks to browsers
    private Browser browser;         // one running Chromium
    private BrowserContext context;  // a fresh, isolated profile: its own cookies
    private Page page;               // a single tab

    /**
     * Launches the browser and opens a blank tab.
     *
     * @throws SurfaceException if the browser could not be started
     */
    public PlaywrightSurface(Options options) {
        this.options = options;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(options.headless()));
            context = browser.newContext(
                    new Browser.NewContextOptions().setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT));
            context.addInitScript(HANDOFF_RECORDER);
            page = context.newPage();
            page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
            watchForNavigation(page);
            // A second tab or window is how a person gets to somewhere the run is not looking.
            context.onPage(opened -> {
                if (handoffOn) {
                    visited.add(Addresses.ANOTHER_TAB);
                }
                watchForNavigation(opened);
            });
        } catch (PlaywrightException e) {
            // The caller never receives this object if we throw, so it could not
            // call close() itself. Clean up here or a Chromium process is left running.
            close();
            throw new SurfaceException("could not start the browser: " + e.getMessage(), e);
        }
    }

    /**
     * Fault injection, for proving that replay copes with a slow application without
     * having to change the application. Every request whose address matches the pattern
     * is held back for delayMs before it is let through.
     *
     * @param urlPattern a Playwright glob, e.g. "**&#47;overview.htm*"
     */
    public void injectDelay(String urlPattern, long delayMs) {
        context.route(urlPattern, route -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            route.resume();
        });
    }

    @Override
    public Observation observe() {
        try {
            String location = page.url();
            String title    = page.title();
            String text     = page.locator("body").innerText();
            return new Observation(location, title, text, PageElements.scan(page), null);
        } catch (PlaywrightException e) {
            throw new SurfaceException("could not read the page: " + e.getMessage(), e);
        }
    }

    /** Notes each page this tab moves to while a person has the window. Sub-frames and requests are ignored. */
    private void watchForNavigation(Page watched) {
        watched.onFrameNavigated(frame -> {
            if (handoffOn && frame.parentFrame() == null) {
                visited.add(Addresses.page(frame.url()));
            }
        });
    }

    /**
     * For tests: opens a second tab, as a person could. It is not for the run's own use.
     */
    public void openSecondTab(String address) {
        Page second = context.newPage();
        second.navigate(address);
    }

    @Override
    public List<String> visitedDuringHandoff() {
        List<String> reached = new ArrayList<>(visited);
        // A tab that was left sitting on another page shows up here even if its navigation was missed.
        for (Page open : context.pages()) {
            reached.add(Addresses.page(open.url()));
        }
        return reached.stream().distinct().toList();
    }

    @Override
    public void beginHandoff() {
        visited.clear();
        handoffOn = true;
        try {
            // Leaving a field fires its pending change. Let that happen before noting starts,
            // so the automation's last typing is not mistaken for the person's.
            page.evaluate("() => { if (document.activeElement) document.activeElement.blur(); "
                    + "sessionStorage.setItem('__handoffOn', '1'); "
                    + "sessionStorage.setItem('__handoffLog', '[]'); }");
        } catch (PlaywrightException e) {
            throw new SurfaceException("could not start noting what the person does: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> endHandoff() {
        try {
            // This call also delivers any browser events that arrived while nobody was looking.
            Object notes = page.evaluate("() => { sessionStorage.setItem('__handoffOn', '0'); "
                    + "return JSON.parse(sessionStorage.getItem('__handoffLog') || '[]'); }");
            handoffOn = false;
            return ((List<Object>) notes).stream().map(String::valueOf).toList();
        } catch (PlaywrightException e) {
            handoffOn = false;
            throw new SurfaceException("could not read what the person did: " + e.getMessage(), e);
        }
    }

    @Override
    public String location() {
        try {
            return page.url();
        } catch (PlaywrightException e) {
            throw new SurfaceException("could not read the page address: " + e.getMessage(), e);
        }
    }

    @Override
    public ActionResult act(Action action) {
        try {
            return switch (action) {
                case Action.Navigate a -> navigate(a);
                case Action.Click a    -> click(a);
                case Action.Type a     -> type(a);
                case Action.Select a   -> select(a);
                case Action.Read a     -> read(a);
                case Action.Wait a     -> waitUntil(a);
            };
        } catch (PlaywrightException e) {
            throw new SurfaceException(action.type() + " failed: " + e.getMessage(), e);
        }
    }

    /** Returns once the page has finished loading. */
    private ActionResult navigate(Action.Navigate action) {
        page.navigate(action.url());
        return new ActionResult(null, null);
    }

    private ActionResult click(Action.Click action) {
        TargetResolver.Match match = locate(action.target());
        match.control().click();
        // A click often opens a new page. Wait for it, so the next look sees the new page.
        page.waitForLoadState();
        return new ActionResult(null, match.matchedBy());
    }

    /** Replaces whatever is in the box with the new text. */
    private ActionResult type(Action.Type action) {
        TargetResolver.Match match = locate(action.target());
        match.control().fill(action.text());
        return new ActionResult(null, match.matchedBy());
    }

    /** Picks the dropdown option by the words a person sees, e.g. "SAVINGS". */
    private ActionResult select(Action.Select action) {
        TargetResolver.Match match = locate(action.target());
        match.control().selectOption(new SelectOption().setLabel(action.option()));
        return new ActionResult(null, match.matchedBy());
    }

    private ActionResult read(Action.Read action) {
        TargetResolver.Match match = locate(action.target());
        return new ActionResult(match.control().innerText().strip(), match.matchedBy());
    }

    private ActionResult waitUntil(Action.Wait action) {
        long deadline = System.nanoTime() + action.timeoutMs() * 1_000_000;
        while (!holdsNow(action.until())) {
            if (System.nanoTime() >= deadline) {
                throw new WaitTimedOutException(action.until());
            }
            page.waitForTimeout(POLL_MS);
        }
        return new ActionResult(null, null);
    }

    private boolean holdsNow(Condition condition) {
        try {
            return Conditions.holds(condition, observe());
        } catch (SurfaceException e) {
            // The page was in the middle of loading. Not true yet; look again.
            return false;
        }
    }

    /**
     * Keeps looking for a short while, because legacy pages often draw their
     * content a moment after the page itself has loaded.
     */
    private TargetResolver.Match locate(Target target) {
        long deadline = System.nanoTime() + FIND_TIMEOUT_MS * 1_000_000;
        while (true) {
            Optional<TargetResolver.Match> match = TargetResolver.find(page, target);
            if (match.isPresent()) {
                return match.get();
            }
            if (System.nanoTime() >= deadline) {
                throw new TargetNotFoundException(target);
            }
            page.waitForTimeout(POLL_MS);
        }
    }

    @Override
    public Evidence captureEvidence(String label, List<Target> sensitiveTargets) {
        String fileName = label.replaceAll("[^A-Za-z0-9._-]", "_");
        Path screenshot = options.evidenceDir().resolve(fileName + ".png");
        Path snapshot   = options.evidenceDir().resolve(fileName + ".txt");
        try {
            Files.createDirectories(options.evidenceDir());

            // Every match of every locator is covered. Covering too much costs
            // a little debugging detail; covering too little leaks a secret.
            List<Locator> covers = sensitiveTargets.stream()
                    .flatMap(target -> target.locators().stream())
                    .map(locator -> TargetResolver.toPlaywright(page, locator))
                    .flatMap(Optional::stream)
                    .toList();

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshot)
                    .setFullPage(true)
                    .setMask(covers)
                    .setMaskColor(MASK_COLOR));

            Files.writeString(snapshot, options.scrub().apply(observe().describe()));

            return new Evidence(screenshot.toString(), snapshot.toString());
        } catch (PlaywrightException | IOException e) {
            throw new SurfaceException("could not save evidence: " + e.getMessage(), e);
        }
    }

    /**
     * Shuts everything down, innermost first.
     * Safe to call twice, and safe to call on a surface that only half started.
     */
    @Override
    public void close() {
        closeQuietly(context);
        closeQuietly(browser);
        closeQuietly(playwright);
        page = null;
        context = null;
        browser = null;
        playwright = null;
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Already gone, e.g. the browser crashed. Nothing left to release.
        }
    }
}
