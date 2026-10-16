// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
/*
 * Scroll-reveal for the docs. Adds .yk-js to <html> (which arms the hidden initial state in
 * style.css), then fades headings, cards, callouts, tables and code blocks in as they scroll
 * into view. Does nothing for users who prefer reduced motion. Never throws.
 */
(function () {
  "use strict";
  try {
    if (window.__ykMotion) {
      return;
    }
    window.__ykMotion = true;

    var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)");
    if (!reduce || reduce.matches || typeof IntersectionObserver === "undefined") {
      return;
    }

    var root = document.documentElement;
    var SELECTOR = [
      "h2",
      "h3",
      ".card",
      '[data-component-part="card"]',
      '[data-callout-type]',
      ".callout",
      "table",
      "pre",
      "img.yk-diagram",
      '[data-component-part="steps"]',
      ".accordion-group",
    ].join(",");
    var CONTAINER_SELECTOR = "#content-area, #content-container, main, article";
    var SKIP = "#navbar, #sidebar, #table-of-contents, footer, nav, [data-yk-no-reveal]";

    var io = null;
    var failSafe = null;
    var mo = null;
    var moTimer = null;
    var observedContainer = null;

    function container() {
      return document.querySelector(CONTAINER_SELECTOR) || document.body;
    }

    function show(el) {
      el.classList.add("yk-in");
    }

    function onIntersect(entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          show(entry.target);
          io.unobserve(entry.target);
        }
      });
    }

    function scan() {
      try {
        if (!io) {
          io = new IntersectionObserver(onIntersect, {
            threshold: 0.08,
            rootMargin: "0px 0px -5% 0px",
          });
        }
        var scope = container();
        var nodes = scope.querySelectorAll(SELECTOR);
        for (var i = 0; i < nodes.length; i++) {
          var el = nodes[i];
          if (el.__ykSeen || el.closest(SKIP)) {
            continue;
          }
          // A reveal wrapper inside another revealed element would double the effect.
          if (el.tagName === "PRE" && el.closest(".card, table, [data-callout-type]")) {
            continue;
          }
          el.__ykSeen = true;
          el.classList.add("yk-reveal");
          io.observe(el);
        }
        armFailSafe();
        watchContainer(scope);
      } catch (e) {
        revealAll();
      }
    }

    function revealAll() {
      try {
        var hidden = document.querySelectorAll(".yk-reveal:not(.yk-in)");
        for (var i = 0; i < hidden.length; i++) {
          show(hidden[i]);
        }
      } catch (e) {
        // nothing left to do
      }
    }

    // Anything still hidden shortly after a scan (observer never fired, tab in background...)
    // is shown rather than left invisible.
    function armFailSafe() {
      if (failSafe) {
        clearTimeout(failSafe);
      }
      failSafe = setTimeout(revealAll, 2500);
    }

    function scheduleScan() {
      if (moTimer) {
        clearTimeout(moTimer);
      }
      moTimer = setTimeout(scan, 100);
    }

    function watchContainer(scope) {
      if (!window.MutationObserver || observedContainer === scope) {
        return;
      }
      if (mo) {
        mo.disconnect();
      }
      observedContainer = scope;
      mo = new MutationObserver(scheduleScan);
      mo.observe(scope, { childList: true, subtree: true });
    }

    root.classList.add("yk-js");
    window.addEventListener("mintlify:navigate", function () {
      // The old container may have been replaced; re-resolve it.
      observedContainer = null;
      scheduleScan();
    });
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", scan);
    } else {
      scan();
    }
  } catch (e) {
    try {
      document.documentElement.classList.remove("yk-js");
    } catch (ignored) {
      // ignore
    }
  }
})();
