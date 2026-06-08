(function () {
  var levels = ["0.7rem", "0.8rem", "0.9rem", "1rem", "1.15rem"];
  var labels = ["A--", "A-", "A", "A+", "A++"];
  var current = 2;

  function applySize(idx) {
    document.documentElement.style.setProperty("--font-size-override", levels[idx]);
    document.querySelectorAll(".font-size-btn").forEach(function (btn, i) {
      btn.classList.toggle("active", i === idx);
    });
    try { localStorage.setItem("mkdocs-font-size", idx); } catch (e) {}
  }

  document.addEventListener("DOMContentLoaded", function () {
    try {
      var saved = localStorage.getItem("mkdocs-font-size");
      if (saved !== null) current = parseInt(saved, 10);
    } catch (e) {}

    var toolbar = document.createElement("div");
    toolbar.className = "font-size-toolbar";
    toolbar.setAttribute("aria-label", "Font size controls");

    labels.forEach(function (label, i) {
      var btn = document.createElement("button");
      btn.className = "font-size-btn";
      btn.textContent = label;
      btn.title = "Font size " + label;
      btn.setAttribute("aria-label", "Set font size to " + label);
      btn.addEventListener("click", function () { current = i; applySize(i); });
      toolbar.appendChild(btn);
    });

    var header = document.querySelector(".md-header__inner") ||
                 document.querySelector(".md-header") ||
                 document.body;
    header.appendChild(toolbar);
    applySize(current);
  });
})();

/* ── Scroll-reveal: adds .fo-visible to .fo-reveal elements on viewport entry ── */
(function () {
  if (!window.IntersectionObserver || !window.MutationObserver) return;

  var iobs = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (e.isIntersecting) {
        e.target.classList.add('fo-visible');
        iobs.unobserve(e.target);
      }
    });
  }, { threshold: 0.12 });

  function attachReveal() {
    document.querySelectorAll('.fo-reveal:not(.fo-visible)').forEach(function (el) {
      iobs.observe(el);
    });
  }

  /* Watch for new .fo-reveal nodes injected by Material instant-navigation */
  new MutationObserver(function (mutations) {
    mutations.forEach(function (m) {
      m.addedNodes.forEach(function (node) {
        if (node.nodeType !== 1) return;
        if (node.classList && node.classList.contains('fo-reveal')) iobs.observe(node);
        node.querySelectorAll && node.querySelectorAll('.fo-reveal:not(.fo-visible)').forEach(function (el) {
          iobs.observe(el);
        });
      });
    });
  }).observe(document.body, { childList: true, subtree: true });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', attachReveal);
  } else {
    attachReveal();
  }
})();
