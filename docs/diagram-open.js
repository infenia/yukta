// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * On a phone a diagram (img.yk-diagram) is drawn at a third of its size, too small to read. Tapping it
 * opens the image on its own, where the browser's pinch zoom works. One listener on the document, so
 * it covers every page Mintlify swaps in without touching the markup it renders. The hint under the
 * diagram is the :has(> img.yk-diagram)::after rule in style.css.
 */
(function () {
  if (typeof window === "undefined" || window.__ykDiagramOpen) {
    return;
  }
  window.__ykDiagramOpen = true;
  var narrow = window.matchMedia("(max-width: 767px)");
  document.addEventListener("click", function (e) {
    var img = e.target && e.target.closest ? e.target.closest("img.yk-diagram") : null;
    if (!img || !narrow.matches) {
      return;
    }
    window.open(img.currentSrc || img.src, "_blank", "noopener");
  });
})();
