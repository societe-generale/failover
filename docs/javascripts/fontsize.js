/* Failover Docs — font size control + mermaid dark-mode fix */
(function () {
  var KEY     = 'fo-font';
  var DEFAULT = 'md';
  var SIZES   = ['sm', 'md', 'lg'];

  /* Apply a font scale to <html> and persist */
  function apply(size) {
    if (SIZES.indexOf(size) === -1) size = DEFAULT;
    document.documentElement.setAttribute('data-fo-font', size);
    try { localStorage.setItem(KEY, size); } catch (e) {}
    document.querySelectorAll('.fo-fontsize-btn').forEach(function (btn) {
      btn.classList.toggle('fo-fontsize-active', btn.dataset.size === size);
    });
  }

  /* Apply immediately (before paint) so there is no flash */
  var saved;
  try { saved = localStorage.getItem(KEY); } catch (e) {}
  apply(saved || DEFAULT);

  /* Re-fix mermaid dark-mode when Mermaid re-renders a diagram */
  function fixMermaidDark() {
    if (document.documentElement.getAttribute('data-md-color-scheme') !== 'slate') return;
    document.querySelectorAll('.mermaid svg').forEach(function (svg) {
      /* alt/else section labels */
      svg.querySelectorAll('.labelBox').forEach(function (el) {
        el.style.fill   = 'var(--fo-sur2)';
        el.style.stroke = 'var(--fo-brd2)';
      });
      svg.querySelectorAll('.labelText, .labelText > tspan').forEach(function (el) {
        el.style.fill = 'var(--fo-br2)';
      });
      /* loop boxes */
      svg.querySelectorAll('.loopLine').forEach(function (el) {
        el.style.stroke = 'var(--fo-brd2)';
      });
      svg.querySelectorAll('.loopText, .loopText > tspan').forEach(function (el) {
        el.style.fill = 'var(--fo-br2)';
      });
      /* generic SVG text with explicit black fill */
      svg.querySelectorAll('text[fill="#000"], text[fill="#000000"], text[fill="black"]').forEach(function (el) {
        el.style.fill = 'var(--fo-tx)';
      });
    });
  }

  /* Build and inject the widget once */
  function ensureWidget() {
    if (document.querySelector('.fo-fontsize-ctrl')) return;

    var ctrl = document.createElement('div');
    ctrl.className = 'fo-fontsize-ctrl';
    ctrl.setAttribute('role', 'group');
    ctrl.setAttribute('aria-label', 'Font size');
    ctrl.innerHTML =
      '<button class="fo-fontsize-btn" data-size="sm" title="Smaller text">A−</button>' +
      '<button class="fo-fontsize-btn" data-size="md" title="Normal text">A</button>'  +
      '<button class="fo-fontsize-btn" data-size="lg" title="Larger text">A+</button>';

    ctrl.addEventListener('click', function (e) {
      var btn = e.target.closest('.fo-fontsize-btn');
      if (btn) apply(btn.dataset.size);
    });

    document.body.appendChild(ctrl);

    /* Sync active state */
    var cur;
    try { cur = localStorage.getItem(KEY); } catch (e) {}
    apply(cur || DEFAULT);
  }

  /* Wire up after DOM is ready */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      ensureWidget();
      setTimeout(fixMermaidDark, 400);
    });
  } else {
    ensureWidget();
    setTimeout(fixMermaidDark, 400);
  }

  /* Handle Material instant navigation */
  if (typeof document$ !== 'undefined') {
    document$.subscribe(function () {
      ensureWidget();
      setTimeout(fixMermaidDark, 400);
    });
  }

  /* Watch for color-scheme changes to re-fix mermaid */
  var observer = new MutationObserver(function (mutations) {
    mutations.forEach(function (m) {
      if (m.attributeName === 'data-md-color-scheme') {
        setTimeout(fixMermaidDark, 300);
      }
    });
  });
  observer.observe(document.documentElement, { attributes: true });
})();
