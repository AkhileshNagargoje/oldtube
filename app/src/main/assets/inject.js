/**
 * Installs the classic skin on m.youtube.com and keeps it installed.
 *
 * MainActivity injects this at page start and page finish, but m.youtube.com is
 * a single-page app — tapping a video or a tab never fires either callback. So
 * the stylesheet defends itself instead of relying on re-injection: an observer
 * puts it back if YouTube's renderer drops it, and moves it back to the end of
 * <head> whenever YouTube appends a sheet of its own after ours.
 *
 * The placeholder on the CSS line below is replaced with a JSON string literal
 * of classic.css before this script reaches the WebView. It must appear exactly
 * once in this file — the substitution replaces every occurrence, so a second
 * one (in a comment, say) would be swapped for a stylesheet too.
 */
(function () {
  "use strict";

  var CSS = __CSS__;
  var THEME = __THEME__;
  var ID = "oldtube-classic-skin";

  // A second injection on the same document: refresh the CSS and stop. The
  // observers from the first run are still live.
  if (window.__oldtubeSkin) {
    window.__oldtubeSkin.css = CSS;
    window.__oldtubeSkin.theme = THEME;
    window.__oldtubeSkin.apply();
    return;
  }

  var skin = {
    css: CSS,
    theme: THEME,

    apply: function () {
      // The dark palette keys off this attribute. Re-stamped alongside the
      // stylesheet because YouTube rewrites <html>'s attributes on some
      // navigations and would otherwise drop it.
      var root = document.documentElement;
      if (root && root.getAttribute("data-oldtube-theme") !== skin.theme) {
        root.setAttribute("data-oldtube-theme", skin.theme);
      }

      var parent = document.head || document.documentElement;
      if (!parent) return;

      var style = document.getElementById(ID);
      if (!style) {
        style = document.createElement("style");
        style.id = ID;
      }
      if (style.textContent !== skin.css) style.textContent = skin.css;

      // Being the last child of <head> is what wins ties against YouTube's own
      // sheets, so re-append whenever we're not.
      if (style.parentNode !== parent || style.nextSibling) parent.appendChild(style);
    },
  };

  window.__oldtubeSkin = skin;
  skin.apply();

  // Watch <head> for stylesheet churn — that's the only thing that can unseat
  // us. Coalesced to once a frame so a burst of appends costs one check.
  var queued = false;
  var headObserver = new MutationObserver(function () {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      skin.apply();
    });
  });

  function watchHead() {
    if (!document.head) return false;
    headObserver.observe(document.head, { childList: true });
    return true;
  }

  // At document_start <head> may not exist yet.
  if (!watchHead()) {
    var wait = new MutationObserver(function () {
      if (watchHead()) {
        wait.disconnect();
        skin.apply();
      }
    });
    wait.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
