---
icon: material/language-java
---

# Java Docs

Full Javadoc for all modules, generated via `mvn javadoc:aggregate`.

<div class="fo-javadoc-wrap">
  <div class="fo-javadoc-toolbar">
    <span>Javadoc — all modules</span>
    <a href="../javadoc/index.html" target="_blank" title="Open in new tab">&#x2197;</a>
  </div>
  <iframe
    src="../javadoc/index.html"
    class="fo-javadoc-frame"
    title="Failover Javadoc"
    loading="lazy">
  </iframe>
</div>

!!! note "Generating Javadoc"
    The embedded Javadoc is available after building the site with Javadoc generated:

    ```bash
    # 1. Generate aggregate Javadoc
    mvn javadoc:aggregate -q

    # 2. Copy into docs tree
    mkdir -p docs/api/javadoc
    cp -r target/reports/apidocs/. docs/api/javadoc/

    # 3. Build and serve
    mkdocs serve
    ```

    On GitHub Pages, the CI workflow does this automatically on every push to `main`.
