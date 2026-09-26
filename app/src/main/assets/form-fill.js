(function (objectName, nonce, documentUrl) {
  'use strict';
  const origin = new URL(documentUrl).origin;
  if (window !== window.top || !window.isSecureContext || location.origin !== origin) return;
  const bridge = window[objectName];
  if (!bridge) return;
  let selected = null, stopped = false, observer, timer;
  const visible = e => {
    if (!e.isConnected || e.disabled || e.readOnly || e.closest('[inert]')) return false;
    const rects = Array.from(e.getClientRects());
    if (!rects.some(r => r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 &&
        r.top < window.innerHeight && r.left < window.innerWidth)) return false;
    for (let node = e; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.visibility !== 'visible' || style.display === 'none' || Number(style.opacity) === 0) return false;
    }
    return true;
  };
  const hint = e => (e.autocomplete || '').toLowerCase().trim().split(/\s+/)
    .find(token => ['username', 'email', 'current-password', 'new-password'].includes(token));
  const score = e => {
    if (!visible(e) || !['text', 'email'].includes(e.type)) return 0;
    if (hint(e) === 'username') return 3;
    if (hint(e) === 'email') return 2;
    return e.type === 'email' || /^(user(name)?|email|login)$/i.test(e.name || e.id) ? 1 : 0;
  };
  const candidates = () => {
    const result = [];
    for (const form of document.forms) {
      const fields = Array.from(form.querySelectorAll('input')).filter(e => e.form === form);
      const passwords = fields.filter(e => e.type === 'password' && visible(e));
      // Registration/change-password forms are deliberately excluded.
      if (passwords.length !== 1 || hint(passwords[0]) === 'new-password') continue;
      const users = fields.map(e => [e, score(e)]).filter(pair => pair[1] > 0);
      const best = Math.max(0, ...users.map(pair => pair[1]));
      const winners = users.filter(pair => pair[1] === best);
      if (winners.length === 1) result.push([form, winners[0][0], passwords[0]]);
    }
    return result;
  };
  const stop = () => {
    stopped = true;
    if (observer) observer.disconnect();
    clearTimeout(timer);
    document.removeEventListener('DOMContentLoaded', scan);
  };
  const cleanup = () => { stop(); selected = null; bridge.onmessage = null; };
  const scan = () => {
    if (stopped || location.origin !== origin) return;
    const found = candidates();
    if (found.length !== 1) return;
    selected = found[0];
    stop();
    timer = setTimeout(cleanup, 65000);
    bridge.postMessage(JSON.stringify({type: 'ready', nonce: nonce}));
  };
  bridge.onmessage = event => {
    try {
      const data = JSON.parse(event.data);
      const found = candidates();
      if (!selected || data.nonce !== nonce || location.origin !== origin ||
          found.length !== 1 || !found[0].every((e, i) => e === selected[i])) return;
      if (typeof data.username !== 'string' || typeof data.password !== 'string') return;
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(selected[1], data.username);
      setter.call(selected[2], data.password);
      for (const field of selected.slice(1)) {
        field.dispatchEvent(new Event('input', {bubbles: true}));
        field.dispatchEvent(new Event('change', {bubbles: true}));
      }
    } finally { cleanup(); }
  };
  observer = new MutationObserver(scan);
  observer.observe(document.documentElement, {childList: true, subtree: true, attributes: true});
  document.addEventListener('DOMContentLoaded', scan);
  timer = setTimeout(cleanup, 10000);
  window.addEventListener('pagehide', cleanup, {once: true});
  scan();
})
