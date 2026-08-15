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

  /**
   * The period tab bar had four entries; YouTube now ships three. Trending is
   * gone for good — /feed/trending and /feed/explore both redirect to the home
   * feed — so the empty slot gets History instead, which still exists and in
   * 2017 lived inside Library.
   *
   * This is a real anchor to a real page, not a decorative stand-in. It is
   * rebuilt whenever YouTube re-renders the bar, which it does on navigation.
   */
  function addHistoryTab() {
    try {
      var bar = document.querySelector("ytm-pivot-bar-renderer");
      if (!bar || bar.querySelector(".oldtube-tab")) return;

      // Each tab sits in its own `ytm-pivot-bar-item-renderer`, and those
      // renderers — not the tabs — are the bar's children. Inserting relative
      // to a tab throws, because the reference node belongs to a different
      // parent. Position against the renderers instead.
      var slots = bar.querySelectorAll("ytm-pivot-bar-item-renderer");
      if (slots.length < 2) return; // bar not built yet

      var tab = document.createElement("a");
      tab.className = "pivot-bar-item-tab oldtube-tab";
      tab.setAttribute("role", "tab");
      tab.setAttribute("href", "/feed/history");
      tab.setAttribute(
        "aria-selected",
        location.pathname === "/feed/history" ? "true" : "false"
      );

      var title = document.createElement("div");
      title.className = "pivot-bar-item-title";
      title.textContent = "History";
      tab.appendChild(title);

      // Second slot, where Trending sat.
      bar.insertBefore(tab, slots[1]);
    } catch (e) {
      // Never let a DOM change here stop the stylesheet from being applied —
      // this runs at the top of apply().
    }
  }

  var skin = {
    css: CSS,
    theme: THEME,

    apply: function () {
      addHistoryTab();

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

  /**
   * The stylesheet only needs <head>, but the History tab lives in the body and
   * YouTube rebuilds the pivot bar on navigation, dropping it. This watches the
   * body for that.
   *
   * The body mutates constantly, so the work is coalesced to once a frame and
   * addHistoryTab bails on a single querySelector when the tab is already
   * there — which is the overwhelmingly common case.
   */
  var tabQueued = false;
  function watchBody() {
    if (!document.body) return false;
    new MutationObserver(function () {
      if (tabQueued) return;
      tabQueued = true;
      requestAnimationFrame(function () {
        tabQueued = false;
        addHistoryTab();
      });
    }).observe(document.body, { childList: true, subtree: true });
    return true;
  }

  if (!watchBody()) {
    var waitBody = new MutationObserver(function () {
      if (watchBody()) {
        waitBody.disconnect();
        addHistoryTab();
      }
    });
    waitBody.observe(document.documentElement, { childList: true, subtree: true });
  }
})();
