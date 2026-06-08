(function () {
  var sizes  = [13, 15, 17, 19, 21];   /* px — root font-size, all rem scales with it */
  var labels = ["A––", "A–", "A", "A+", "A++"];
  var DEFAULT = 2;  /* index 2 = 17px */

  function applySize(idx) {
    document.documentElement.style.fontSize = sizes[idx] + "px";
    document.querySelectorAll(".font-size-btn").forEach(function (btn, i) {
      btn.classList.toggle("active", i === idx);
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    /* Always start at default on every page load — no localStorage restore */
    var toolbar = document.createElement("div");
    toolbar.className = "font-size-toolbar";
    toolbar.setAttribute("aria-label", "Font size controls");

    labels.forEach(function (label, i) {
      var btn = document.createElement("button");
      btn.className = "font-size-btn";
      btn.textContent = label;
      btn.title = "Font size " + label;
      btn.setAttribute("aria-label", "Set font size to " + label);
      btn.addEventListener("click", function () { applySize(i); });
      toolbar.appendChild(btn);
    });

    /* Insert LEFT of the theme/palette toggle (.md-header__option) */
    var headerInner = document.querySelector(".md-header__inner") ||
                      document.querySelector(".md-header") ||
                      document.body;
    var paletteOption = headerInner.querySelector(".md-header__option");
    if (paletteOption) {
      headerInner.insertBefore(toolbar, paletteOption);
    } else {
      headerInner.appendChild(toolbar);
    }

    /* Reset to default when Failover logo/icon is clicked */
    document.addEventListener("click", function (e) {
      var t = e.target;
      /* Match: Material header logo link, hero logo image, header brand title link */
      if (
        t.closest("a.md-logo") ||
        t.closest("a.md-header__button") ||
        t.closest(".fo-hero-icon") ||
        t.closest(".fo-hero-brand") ||
        (t.tagName === "A" && (t.getAttribute("href") === "." || t.getAttribute("href") === "./"))
      ) {
        applySize(DEFAULT);
      }
    });

    applySize(DEFAULT);
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