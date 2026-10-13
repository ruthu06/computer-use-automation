package com.computeruse.automation.surface.web;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.UiElement;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lists the controls a person could see and use on the current page.
 *
 * The scan runs inside the browser as one script, because asking Playwright
 * about each control separately would cost one round trip per control.
 */
final class PageElements {

    private static final String SCAN_SCRIPT = """
            () => {
              const SELECTOR = 'a[href], button, input, select, textarea, td, h1, h2, h3, h4, h5, h6, [role]';
              const BUTTON_TYPES = ['submit', 'button', 'reset', 'image'];

              const clean = s => (s || '').replace(/\\s+/g, ' ').trim();

              const visible = el => {
                const box = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return box.width > 0 && box.height > 0
                    && style.visibility !== 'hidden' && style.display !== 'none';
              };

              const roleOf = el => {
                const explicit = el.getAttribute('role');
                if (explicit) return explicit;
                const tag = el.tagName.toLowerCase();
                const type = (el.getAttribute('type') || 'text').toLowerCase();
                if (tag === 'a') return 'link';
                if (tag === 'button') return 'button';
                if (tag === 'select') return 'combobox';
                if (tag === 'textarea') return 'textbox';
                if (tag === 'td') return 'cell';
                if (/^h[1-6]$/.test(tag)) return 'heading';
                if (tag === 'input') {
                  if (type === 'hidden') return null;
                  if (BUTTON_TYPES.includes(type)) return 'button';
                  if (type === 'checkbox' || type === 'radio') return type;
                  return 'textbox';
                }
                return null;
              };

              const nameOf = (el, role) => {
                const aria = el.getAttribute('aria-label');
                if (aria) return clean(aria);
                if (el.labels && el.labels.length) return clean(el.labels[0].innerText);
                if (el.tagName === 'INPUT' && role === 'button') return clean(el.value || el.alt);
                if (role === 'link' || role === 'button' || role === 'heading' || role === 'cell') return clean(el.innerText);
                return clean(el.getAttribute('title') || el.getAttribute('placeholder'));
              };

              const valueOf = (el, role) => {
                if (el.type === 'password') return null;
                if (role === 'textbox') return el.value;
                if (role === 'combobox') return el.selectedOptions.length ? clean(el.selectedOptions[0].text) : '';
                if (role === 'checkbox' || role === 'radio') return String(el.checked);
                return null;
              };

              // The words written just before a control, e.g. "Password" before its box.
              const captionOf = el => {
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                let caption = '';
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                  if (!(el.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_PRECEDING)) break;
                  const parent = node.parentElement;
                  if (clean(node.textContent) && !['SCRIPT', 'STYLE'].includes(parent.tagName) && visible(parent)) {
                    caption = clean(parent.innerText);
                  }
                }
                return caption.length <= 60 ? caption : '';
              };

              const only = selector => document.querySelectorAll(selector).length === 1;
              const usable = value => value && !/['\\\\]/.test(value);

              // A CSS path that points at this control and nothing else.
              const cssPath = el => {
                const tag = el.tagName.toLowerCase();
                if (usable(el.id) && only("[id='" + el.id + "']")) return "[id='" + el.id + "']";
                const name = el.getAttribute('name');
                if (usable(name) && only(tag + "[name='" + name + "']")) return tag + "[name='" + name + "']";

                const steps = [];
                for (let node = el; node !== document.body; node = node.parentElement) {
                  if (node !== el && usable(node.id) && only("[id='" + node.id + "']")) {
                    steps.unshift("[id='" + node.id + "']");
                    return steps.join(' > ');
                  }
                  const sameTag = Array.from(node.parentElement.children).filter(c => c.tagName === node.tagName);
                  steps.unshift(node.tagName.toLowerCase() + ':nth-of-type(' + (sameTag.indexOf(node) + 1) + ')');
                }
                steps.unshift('body');
                return steps.join(' > ');
              };

              const FORM_ROLES = ['textbox', 'combobox', 'checkbox', 'radio'];

              const found = [];
              for (const el of document.querySelectorAll(SELECTOR)) {
                const role = roleOf(el);
                if (!role || !visible(el)) continue;
                // A cell is only worth listing if it shows something a person could read.
                if (role === 'cell' && !clean(el.innerText)) continue;
                found.push({
                  role: role,
                  name: nameOf(el, role),
                  value: valueOf(el, role),
                  enabled: !el.disabled,
                  caption: FORM_ROLES.includes(role) ? captionOf(el) : '',
                  css: cssPath(el)
                });
              }
              return found;
            }
            """;

    /** Controls in the order they appear on the page. */
    @SuppressWarnings("unchecked")
    static List<UiElement> scan(Page page) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) page.evaluate(SCAN_SCRIPT);

        List<UiElement> elements = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> el = raw.get(i);
            String role = (String) el.get("role");
            String name = (String) el.get("name");
            elements.add(new UiElement(
                    "e" + (i + 1),
                    role,
                    name,
                    (String) el.get("value"),
                    (Boolean) el.get("enabled"),
                    targetFor(page, role, name, (String) el.get("caption"), (String) el.get("css"))));
        }
        return elements;
    }

    /** The ways to find this control again, most durable first. */
    private static Target targetFor(Page page, String role, String name, String caption, String css) {
        List<Locator> locators = new ArrayList<>();
        if (!name.isEmpty()) {
            locators.add(new Locator.Role(role, name));
        }
        if (!caption.isEmpty()) {
            Locator.Label byCaption = new Locator.Label(caption, role);
            if (leadsTo(page, byCaption, css)) {
                locators.add(byCaption);
            }
        }
        locators.add(new Locator.Structure(css));

        String label = !name.isEmpty() ? name : caption;
        return new Target((role + " " + label).strip(), locators);
    }

    /** Tries the locator on the live page: does it really end up at the control at this path? */
    private static boolean leadsTo(Page page, Locator locator, String css) {
        return TargetResolver.toPlaywright(page, locator)
                .map(found -> (Boolean) found.evaluateAll(
                        "(matches, css) => matches.length === 1 && matches[0] === document.querySelector(css)", css))
                .orElse(false);
    }

    private PageElements() {}
}
