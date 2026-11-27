// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * The home page as one run of a workflow. Each section (.yk-flow .yk-sec) is a node: queued until
 * the reader scrolls to it, then running, then succeeded, one at a time and in page order. Scrolling
 * ahead does not skip a node: the ones before it run first. A small run log in the corner shows
 * each step. Without this script the sections simply show no status.
 */
(function () {
  if (typeof window === "undefined") {
    return;
  }

  var STEP_MS = 650;

  function setup(flow) {
    flow.__ykRun = true;
    var nodes = [].slice.call(flow.querySelectorAll(".yk-sec"));
    var heads = nodes.map(function (n) { return n.querySelector(".ykn"); });
    var fast = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var ms = fast ? 60 : STEP_MS;
    var target = -1;
    var done = -1;
    var busy = false;
    var noted = false;

    nodes.forEach(function (n) { n.dataset.state = "queued"; });

    var log = document.createElement("div");
    // Folded to its head line so it never covers the page; hover or focus opens the step list.
    log.className = "yk-runlog idle";
    log.setAttribute("role", "status");
    log.tabIndex = 0;
    log.innerHTML =
      '<div class="yk-runlog-head"><span>run · this page</span><span class="yk-runlog-status">QUEUED</span></div><ol></ol>';
    document.body.appendChild(log);
    var list = log.querySelector("ol");
    var status = log.querySelector(".yk-runlog-status");

    function text(el, sel) {
      var x = el && el.querySelector(sel);
      return x ? x.textContent : "";
    }

    function line(cls, a, b, c) {
      var li = document.createElement("li");
      li.className = cls;
      [a, b, c].forEach(function (t) {
        var s = document.createElement("span");
        s.textContent = t;
        li.appendChild(s);
      });
      list.appendChild(li);
      while (list.children.length > 5) {
        list.removeChild(list.firstChild);
      }
      return li;
    }

    function id(i) {
      return (i < 9 ? "0" : "") + (i + 1);
    }

    function step() {
      if (busy || done >= target) {
        return;
      }
      busy = true;
      var i = done + 1;
      var n = nodes[i];
      n.dataset.state = "running";
      status.textContent = "RUNNING " + (i + 1) + "/" + nodes.length + " · " + (text(n, ".ykn-name") || "").toUpperCase();
      var li = line("run", id(i), text(n, ".ykn-type") || text(n, ".ykn-name"), "running");
      setTimeout(function () {
        n.dataset.state = "succeeded";
        li.className = "ok";
        li.lastChild.textContent = "✓ " + (ms / 1000).toFixed(1) + "s";
        done = i;
        busy = false;
        if (done === nodes.length - 1) {
          status.textContent = "SUCCEEDED";
          log.classList.add("done");
          line("ok", "··", "every step ran, in order", "");
        }
        step();
      }, ms);
    }

    var reach = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (e) {
          if (!e.isIntersecting) {
            return;
          }
          var i = heads.indexOf(e.target);
          if (i > target) {
            if (i > done + 2 && !noted) {
              noted = true;
              line("note", "··", "jumped ahead: earlier steps run first", "");
            }
            target = i;
            step();
          }
        });
      },
      { rootMargin: "0px 0px -35% 0px" }
    );
    heads.forEach(function (h) {
      if (h) {
        reach.observe(h);
      }
    });

    var show = new IntersectionObserver(function (entries) {
      log.classList.toggle("on", entries[0].isIntersecting);
    });
    show.observe(flow);

    // Mintlify swaps pages without a reload: drop the log once this page has gone.
    var watch = setInterval(function () {
      if (!flow.isConnected) {
        clearInterval(watch);
        reach.disconnect();
        show.disconnect();
        log.remove();
      }
    }, 1000);
  }

  setInterval(function () {
    var flow = document.querySelector(".yk-flow");
    if (flow && !flow.__ykRun) {
      setup(flow);
    }
  }, 500);
})();
