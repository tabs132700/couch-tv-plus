// On-TV mode only. The TV app adds this script to the phone remote's page before anything else.
// It pairs the phone once (6 digit code shown on the TV), then adds the token to every /api call,
// so the remote itself is unchanged and the same build works with the computer bridge.
(function () {
  'use strict';
  var KEY = 'couchtv.token';
  var token = null;
  try { token = localStorage.getItem(KEY); } catch (e) { /* private mode: the cookie still works */ }
  if (!token) { var m = document.cookie.match(/(?:^|; )couchtv_token=([^;]+)/); if (m) token = decodeURIComponent(m[1]); }

  function isApi(url) {
    try { var u = new URL(url, location.href); return u.origin === location.origin && u.pathname.indexOf('/api/') === 0; } catch (e) { return false; }
  }
  function isPair(url) { return /\/api\/pair/.test(String(url)); }

  // these screens speak Dutch on a phone set to Dutch, else English.
  var NL = false;
  try { NL = /^nl/i.test(String(navigator.language || '')); } catch (e) { }
  function w(en, nl) { return NL ? nl : en; }

  var realFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || String(input);
    if (isApi(url) && token) {
      init = Object.assign({}, init || {});
      var h = new Headers(init.headers || (typeof input !== 'string' && input.headers) || {});
      h.set('Authorization', 'Bearer ' + token);
      init.headers = h;
    }
    return realFetch(input, init).then(function (r) {
      if (r.status === 401 && isApi(url) && !isPair(url)) showPairing(w('This phone needs to pair with the TV again.', 'Deze telefoon moet opnieuw met de tv koppelen.'));
      return r;
    });
  };

  var RealES = window.EventSource;
  if (RealES) {
    var ES = function (url, cfg) {
      if (token && isApi(url)) url += (String(url).indexOf('?') < 0 ? '?' : '&') + 'token=' + encodeURIComponent(token);
      return new RealES(url, cfg);
    };
    ES.prototype = RealES.prototype; ES.CONNECTING = 0; ES.OPEN = 1; ES.CLOSED = 2;
    window.EventSource = ES;
  }

  function save(t) {
    token = t;
    try { localStorage.setItem(KEY, t); } catch (e) { }
    document.cookie = 'couchtv_token=' + encodeURIComponent(t) + '; path=/; max-age=31536000; SameSite=Strict';
  }

  var shown = false;
  function showPairing(why) {
    if (shown) return; shown = true;
    var css = document.createElement('style');
    css.textContent = '#ctv-pair{position:fixed;top:0;right:0;bottom:0;left:0;z-index:99999;background:#0b0b0d;color:#f2ead8;display:flex;align-items:center;justify-content:center;padding:24px 16px;font:16px/1.4 -apple-system,system-ui,sans-serif}' +
      '#ctv-pair .box{width:100%;max-width:360px}#ctv-pair h1{font-size:26px;margin:0 0 8px}#ctv-pair p{color:#bdb6a7;margin:0 0 18px}' +
      '#ctv-pair input{width:100%;box-sizing:border-box;font-size:34px;letter-spacing:.3em;text-align:center;padding:14px;border-radius:14px;border:1px solid #3a3a40;background:#16161a;color:#f2ead8}' +
      '#ctv-pair button{width:100%;margin-top:12px;padding:15px;border-radius:14px;border:0;font-size:17px;font-weight:600}' +
      '#ctv-pair .go{background:#ffb52e;color:#111}#ctv-pair .ask{background:#222228;color:#f2ead8}#ctv-pair .msg{min-height:22px;margin-top:12px;color:#ffb52e}';
    document.head.appendChild(css);
    var d = document.createElement('div');
    d.id = 'ctv-pair';
    d.innerHTML = '<div class="box"><h1>' + w('Pair this phone', 'Koppel deze telefoon') + '</h1><p>' + (why || w('Scan the QR code on the TV (Phone remote, in the menu under the apps), or type the 6 digit code shown on the TV.', 'Scan de QR-code op de tv (Telefoonbediening, in het menu onder de apps), of typ de 6-cijferige code die op de tv staat.')) + '</p>' +
      '<input id="ctv-pin" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000000" aria-label="' + w('Code from the TV', 'Code van de tv') + '">' +
      '<button class="go" id="ctv-go">' + w('Pair', 'Koppelen') + '</button><button class="ask" id="ctv-ask">' + w('Show a code on the TV', 'Toon een code op de tv') + '</button><div class="msg" id="ctv-msg" role="status"></div></div>';
    (document.body ? Promise.resolve() : new Promise(function (r) { document.addEventListener('DOMContentLoaded', r); })).then(function () {
      document.body.appendChild(d);
      var pin = document.getElementById('ctv-pin'), msg = document.getElementById('ctv-msg');
      document.getElementById('ctv-ask').onclick = function () {
        msg.textContent = w('Asking the TV…', 'De tv vragen…');
        realFetch('/api/pair/start', { method: 'POST' }).then(function (r) { return r.json(); })
          .then(function (j) { msg.textContent = j.ok ? w('The code is on the TV now.', 'De code staat nu op de tv.') : (j.error || w('The TV did not answer.', 'De tv gaf geen antwoord.')); })
          .catch(function () { msg.textContent = w('Can’t reach the TV.', 'Kan de tv niet bereiken.'); });
      };
      document.getElementById('ctv-go').onclick = function () {
        var code = (pin.value || '').replace(/\D/g, '');
        if (code.length !== 6) { msg.textContent = w('The code has 6 digits.', 'De code heeft 6 cijfers.'); return; }
        msg.textContent = w('Pairing…', 'Koppelen…');
        realFetch('/api/pair', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pin: code, name: (navigator.platform || 'Phone') }) })
          .then(function (r) { return r.json(); })
          .then(function (j) { if (j.ok && j.token) { save(j.token); msg.textContent = w('Paired.', 'Gekoppeld.'); location.reload(); } else msg.textContent = j.error || w('That did not work.', 'Dat werkte niet.'); })
          .catch(function () { msg.textContent = w('Can’t reach the TV.', 'Kan de tv niet bereiken.'); });
      };
      pin.addEventListener('keydown', function (e) { if (e.key === 'Enter') document.getElementById('ctv-go').click(); });
    });
  }

  window.couchTv = { token: function () { return token; }, pair: showPairing };

  // the QR on the TV opens this page with ?pair=<link code>, which pairs in one go: no code to type.
  // The code is taken out of the address straight away, so it never ends up in a bookmark or the home screen icon.
  var linkCode = null;
  try {
    var q = new URLSearchParams(location.search);
    linkCode = q.get('pair');
    if (linkCode) { q.delete('pair'); var rest = q.toString(); history.replaceState(null, '', location.pathname + (rest ? '?' + rest : '') + location.hash); }
  } catch (e) { }
  function linkPair() {
    var cover = document.createElement('div');
    cover.id = 'ctv-linking';
    cover.style.cssText = 'position:fixed;top:0;right:0;bottom:0;left:0;z-index:99999;display:flex;align-items:center;justify-content:center;background:#0b0b0d;color:#f2ead8;font:18px/1.4 -apple-system,system-ui,sans-serif';
    cover.textContent = w('Connecting to the TV…', 'Verbinden met de tv…');
    (document.body ? Promise.resolve() : new Promise(function (r) { document.addEventListener('DOMContentLoaded', r); })).then(function () { document.body.appendChild(cover); });
    realFetch('/api/pair', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pin: linkCode, name: (navigator.platform || 'Phone') }) })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.ok && j.token) { save(j.token); location.reload(); return; }
        cover.remove(); showPairing(w('That QR code has run out. Open Phone remote on the TV again and scan the new code, or type the 6 digit code.', 'Die QR-code is verlopen. Open Telefoonbediening opnieuw op de tv en scan de nieuwe code, of typ de 6-cijferige code.'));
      })
      .catch(function () { cover.remove(); showPairing(w('Can’t reach the TV. Is this phone on the same Wi-Fi?', 'Kan de tv niet bereiken. Zit deze telefoon op dezelfde wifi?')); });
  }
  if (linkCode && !token) linkPair();
  else if (!token) showPairing();
})();
