/* Failover Docs — font scale · mermaid dark-mode fix · inline pan-zoom */
(function () {

  /* ═══════════════════════════════════════════════════
     1. FONT SCALE
     ═══════════════════════════════════════════════════ */

  var FS_KEY = 'fo-font', FS_DEFAULT = 'md', FS_SIZES = ['sm', 'md', 'lg'];

  function applyFontScale(size) {
    if (FS_SIZES.indexOf(size) < 0) size = FS_DEFAULT;
    document.documentElement.setAttribute('data-fo-font', size);
    try { localStorage.setItem(FS_KEY, size); } catch (_) {}
    document.querySelectorAll('.fo-fontsize-btn').forEach(function (b) {
      b.classList.toggle('fo-fontsize-active', b.dataset.size === size);
    });
  }

  /* Apply before first paint — prevents flash */
  var _saved; try { _saved = localStorage.getItem(FS_KEY); } catch (_) {}
  applyFontScale(_saved || FS_DEFAULT);

  function ensureFontWidget() {
    if (document.querySelector('.fo-fontsize-ctrl')) return;
    var ctrl = document.createElement('div');
    ctrl.className = 'fo-fontsize-ctrl';
    ctrl.setAttribute('role', 'group');
    ctrl.setAttribute('aria-label', 'Font size');
    ctrl.innerHTML =
      '<button class="fo-fontsize-btn" data-size="sm" title="Smaller">A−</button>' +
      '<button class="fo-fontsize-btn" data-size="md" title="Normal">A</button>'   +
      '<button class="fo-fontsize-btn" data-size="lg" title="Larger">A+</button>';
    ctrl.addEventListener('click', function (e) {
      var b = e.target.closest('.fo-fontsize-btn');
      if (b) applyFontScale(b.dataset.size);
    });
    document.body.appendChild(ctrl);
    var cur; try { cur = localStorage.getItem(FS_KEY); } catch (_) {}
    applyFontScale(cur || FS_DEFAULT);
  }

  /* ═══════════════════════════════════════════════════
     2. MERMAID DARK-MODE TEXT FIX
     ═══════════════════════════════════════════════════ */

  function fixMermaidDark() {
    if (document.documentElement.getAttribute('data-md-color-scheme') !== 'slate') return;
    document.querySelectorAll('.mermaid svg').forEach(function (svg) {
      svg.querySelectorAll('.labelBox').forEach(function (el) {
        el.style.fill = 'var(--fo-sur2)'; el.style.stroke = 'var(--fo-brd2)';
      });
      svg.querySelectorAll('.labelText,.labelText>tspan,.loopText,.loopText>tspan').forEach(function (el) {
        el.style.fill = 'var(--fo-br2)';
      });
      svg.querySelectorAll('.loopLine').forEach(function (el) { el.style.stroke = 'var(--fo-brd2)'; });
      svg.querySelectorAll('text[fill="#000"],text[fill="#000000"],text[fill="black"]').forEach(function (el) {
        el.style.fill = 'var(--fo-tx)';
      });
    });
  }

  /* ═══════════════════════════════════════════════════
     3. INLINE PAN-ZOOM for every .mermaid SVG
     ═══════════════════════════════════════════════════ */

  function initPanZoom(mermaidEl) {
    var svg = mermaidEl.querySelector('svg');
    if (!svg || svg.dataset.pzDone) return;
    svg.dataset.pzDone = '1';

    /* Allow SVG to scale beyond its natural size */
    svg.style.maxWidth      = 'none';
    svg.style.transformOrigin = '0 0';
    svg.style.display       = 'block';

    /* Remove existing wrapper to avoid double-wrap on re-render */
    var oldWrap = mermaidEl.parentNode;
    if (oldWrap && oldWrap.classList.contains('fo-mz-wrap')) {
      oldWrap.parentNode.insertBefore(mermaidEl, oldWrap);
      oldWrap.remove();
    }

    /* Create viewport wrapper */
    var wrap = document.createElement('div');
    wrap.className = 'fo-mz-wrap';
    mermaidEl.parentNode.insertBefore(wrap, mermaidEl);
    wrap.appendChild(mermaidEl);

    /* Toolbar — visible on hover */
    var bar = document.createElement('div');
    bar.className = 'fo-mz-bar';
    bar.innerHTML =
      '<button class="fo-mz-btn" data-a="out"   title="Zoom out (scroll ↓)">−</button>'  +
      '<button class="fo-mz-btn fo-mz-r" data-a="reset" title="Reset view">Reset</button>' +
      '<button class="fo-mz-btn" data-a="in"    title="Zoom in (scroll ↑)">+</button>'   +
      '<span class="fo-mz-pct">100%</span>';
    wrap.appendChild(bar);

    var tx = 0, ty = 0, scale = 1;
    var dragging = false, sx, sy, stx, sty;
    var pctEl = bar.querySelector('.fo-mz-pct');

    function applyTransform() {
      svg.style.transform = (scale === 1 && tx === 0 && ty === 0)
        ? ''
        : 'translate(' + tx.toFixed(1) + 'px,' + ty.toFixed(1) + 'px) scale(' + scale + ')';
      pctEl.textContent = Math.round(scale * 100) + '%';
    }

    function zoomAt(cx, cy, factor) {
      var ns = Math.max(0.15, Math.min(scale * factor, 8));
      tx += (cx - tx) * (1 - ns / scale);
      ty += (cy - ty) * (1 - ns / scale);
      scale = ns;
      applyTransform();
    }

    /* Buttons */
    bar.addEventListener('click', function (e) {
      e.stopPropagation();
      var btn = e.target.closest('[data-a]');
      if (!btn) return;
      var rc = wrap.getBoundingClientRect();
      var cx = rc.width / 2, cy = rc.height / 2;
      var a  = btn.dataset.a;
      if (a === 'in')    zoomAt(cx, cy, 1.35);
      if (a === 'out')   zoomAt(cx, cy, 1 / 1.35);
      if (a === 'reset') { scale = 1; tx = 0; ty = 0; applyTransform(); }
    });

    /* Scroll-wheel zoom toward cursor */
    wrap.addEventListener('wheel', function (e) {
      e.preventDefault();
      var rc = wrap.getBoundingClientRect();
      zoomAt(e.clientX - rc.left, e.clientY - rc.top, e.deltaY < 0 ? 1.12 : 1 / 1.12);
    }, { passive: false });

    /* Mouse drag pan */
    wrap.addEventListener('mousedown', function (e) {
      if (e.button !== 0 || e.target.closest('.fo-mz-bar')) return;
      dragging = true; sx = e.clientX; sy = e.clientY; stx = tx; sty = ty;
      wrap.style.cursor = 'grabbing';
      e.preventDefault();
    });
    document.addEventListener('mousemove', function (e) {
      if (!dragging) return;
      tx = stx + e.clientX - sx;
      ty = sty + e.clientY - sy;
      applyTransform();
    });
    document.addEventListener('mouseup', function () {
      if (dragging) { dragging = false; wrap.style.cursor = 'grab'; }
    });

    /* Touch: single-finger pan, two-finger pinch-zoom */
    var tOrigin = null, tDist = null;
    wrap.addEventListener('touchstart', function (e) {
      if (e.touches.length === 1) {
        tOrigin = { x: e.touches[0].clientX, y: e.touches[0].clientY, tx: tx, ty: ty };
        tDist = null;
      } else if (e.touches.length === 2) {
        tDist = Math.hypot(
          e.touches[0].clientX - e.touches[1].clientX,
          e.touches[0].clientY - e.touches[1].clientY
        );
        tOrigin = null;
      }
      e.preventDefault();
    }, { passive: false });

    wrap.addEventListener('touchmove', function (e) {
      if (tOrigin && e.touches.length === 1) {
        tx = tOrigin.tx + e.touches[0].clientX - tOrigin.x;
        ty = tOrigin.ty + e.touches[0].clientY - tOrigin.y;
        applyTransform();
      } else if (tDist && e.touches.length === 2) {
        var nd  = Math.hypot(
          e.touches[0].clientX - e.touches[1].clientX,
          e.touches[0].clientY - e.touches[1].clientY
        );
        var rc  = wrap.getBoundingClientRect();
        var pcx = (e.touches[0].clientX + e.touches[1].clientX) / 2 - rc.left;
        var pcy = (e.touches[0].clientY + e.touches[1].clientY) / 2 - rc.top;
        zoomAt(pcx, pcy, nd / tDist);
        tDist = nd;
      }
      e.preventDefault();
    }, { passive: false });

    wrap.addEventListener('touchend', function () { tOrigin = null; tDist = null; });
  }

  /* Watch for Mermaid injecting SVGs (async render) */
  var pzObserver = new MutationObserver(function (mutations) {
    mutations.forEach(function (m) {
      m.addedNodes.forEach(function (node) {
        if (node.nodeName === 'svg') {
          var mel = node.closest ? node.closest('.mermaid') : null;
          if (mel) { setTimeout(function () { initPanZoom(mel); fixMermaidDark(); }, 50); }
        }
      });
    });
  });

  /* ═══════════════════════════════════════════════════
     4. BOOT
     ═══════════════════════════════════════════════════ */

  function boot() {
    ensureFontWidget();
    pzObserver.observe(document.body, { childList: true, subtree: true });
    /* Also run on existing diagrams (already rendered before this script) */
    setTimeout(function () {
      document.querySelectorAll('.mermaid').forEach(initPanZoom);
      fixMermaidDark();
    }, 600);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  /* Material instant navigation */
  if (typeof document$ !== 'undefined') {
    document$.subscribe(function () {
      ensureFontWidget();
      setTimeout(function () {
        document.querySelectorAll('.mermaid').forEach(initPanZoom);
        fixMermaidDark();
      }, 600);
    });
  }

  /* Color-scheme toggle → re-fix mermaid text */
  new MutationObserver(function (muts) {
    muts.forEach(function (m) {
      if (m.attributeName === 'data-md-color-scheme') setTimeout(fixMermaidDark, 300);
    });
  }).observe(document.documentElement, { attributes: true });

})();
