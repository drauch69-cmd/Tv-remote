/*
 * TV Remote - phone page logic.
 * Plain JS, no build step. Wire format and flow: docs/protocol.md.
 * Sections: constants, icons, storage, status, connection, pairing,
 * buttons, now playing, caps, tabs, keyboard, apps, favourites,
 * settings, sound, init.
 */
(() => {
  'use strict';

  // ---- constants ----------------------------------------------------------
  const TOKEN_KEY = 'tvr.token';
  const HAND_KEY = 'tvr.hand';        // 'right' (default) or 'left'
  const SOUND_KEY = 'tvr.sound';      // '1' or '0' (default off)
  const PING_MS = 3000;
  const DEAD_MS = 7000;               // no pong this long = dead socket
  const BACKOFF_MS = [500, 1000, 2000, 5000]; // last value repeats
  const REPEAT_DELAY_MS = 400;
  const REPEAT_EVERY_MS = 110;
  const TOAST_MS = 2000;
  const LOCK_WAIT_MS = 60000;         // pairing off this long after "locked"
  const TAB_KEY = 'tvr.tab';          // 'remote' (default), 'keyboard' or 'apps'
  const FAVS_KEY = 'tvr.favs';        // JSON array of package names, display order
  const TABS = ['remote', 'keyboard', 'apps', 'more'];
  const TEXT_DEBOUNCE_MS = 150;       // quiet time before the phone sends TEXT
  const TAP_SLOP_PX = 8;              // a touch that moves further than this is a scroll
  const FLASH_MS = 180;               // how long a tapped tile stays pressed

  const $ = (id) => document.getElementById(id);

  // Mutable state: timers, socket, and the button currently held.
  const state = {
    ws: null,
    authed: false,        // true after auth ok or paired
    lastPong: 0,
    pingTimer: null,
    reconnectTimer: null,
    backoffIdx: 0,
    pressBtn: null,
    repeatTimer: null,
    repeatInterval: null,
    countdownTimer: null,
    countdownEnds: 0,
    lockTimer: null,
    toastTimer: null,
    audio: null,          // WebAudio context, created on first touch
    textTimer: null,      // debounce for the TEXT command
    caps: {},             // last caps seen, merged over messages
    apps: null,           // last apps list from the TV (null until loaded)
    favs: [],             // favourite package names, from localStorage
    tapDrag: null,        // where a touch started, to tell a tap from a scroll
  };

  // ---- icons (inner markup of 24px viewBox, stroke 2, round caps) -----------
  const ICONS = {
    up: '<path d="M12 19V5"/><path d="M5 12l7-7 7 7"/>',
    down: '<path d="M12 5v14"/><path d="M19 12l-7 7-7-7"/>',
    left: '<path d="M19 12H5"/><path d="M12 19l-7-7 7-7"/>',
    right: '<path d="M5 12h14"/><path d="M12 5l7 7-7 7"/>',
    home: '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/>',
    back: '<path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>',
    recents: '<rect x="3" y="7" width="14" height="14" rx="2"/>' +
      '<path d="M7 7V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-2"/>',
    menu: '<path d="M4 6h16"/><path d="M4 12h16"/><path d="M4 18h16"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4.5-4.5"/>',
    gear: '<circle cx="12" cy="12" r="8" stroke-dasharray="3 2.4"/><circle cx="12" cy="12" r="3"/>',
    moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
    power: '<path d="M12 3v9"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/>',
    close: '<path d="M6 6l12 12"/><path d="M18 6L6 18"/>',
    'skip-back': '<path d="M5 5v14"/><path d="M19 5v14l-10-7z"/>',
    rewind: '<path d="M11 6l-7 6 7 6z"/><path d="M21 6l-7 6 7 6z"/>',
    play: '<path d="M7 5l12 7-12 7z"/>',
    pause: '<path d="M8 5v14"/><path d="M16 5v14"/>',
    'fast-forward': '<path d="M13 6l7 6-7 6z"/><path d="M3 6l7 6-7 6z"/>',
    'skip-forward': '<path d="M19 5v14"/><path d="M5 5v14l10-7z"/>',
    'vol-down': '<path d="M4 9h3l5-4v14l-5-4H4z"/><path d="M16 12h5"/>',
    mute: '<path d="M4 9h3l5-4v14l-5-4H4z"/><path d="M16 9.5l5 5"/><path d="M21 9.5l-5 5"/>',
    'vol-up': '<path d="M4 9h3l5-4v14l-5-4H4z"/><path d="M16 12h5"/><path d="M18.5 9.5v5"/>',
    refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7"/><path d="M20 4v5h-5"/>',
    star: '<path d="M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z"/>',
  };

  function iconSvg(name) {
    return ICONS[name] ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      ICONS[name] + '</svg>' : '';
  }

  function applyIcons() {
    document.querySelectorAll('[data-icon]').forEach((el) => {
      el.innerHTML = iconSvg(el.dataset.icon);
    });
  }

  // ---- storage (localStorage can throw, e.g. in private mode) ---------------
  const store = {
    get(key) { try { return localStorage.getItem(key); } catch (e) { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* ignore */ } },
    del(key) { try { localStorage.removeItem(key); } catch (e) { /* ignore */ } },
  };

  // ---- status pill, module dimming, toast -----------------------------------
  const STATUS_TEXT = {
    connecting: 'Connecting…',
    connected: 'Connected',
    offline: 'Offline – retrying',
    notpaired: 'Not paired',
  };

  function setStatus(kind) {
    const pill = $('status');
    pill.dataset.state = kind;            // colours come from CSS
    pill.textContent = STATUS_TEXT[kind];
    // Dim the remote unless the TV is connected and paired.
    $('module').classList.toggle('offline', kind !== 'connected');
    $('media').classList.toggle('offline', kind !== 'connected');
  }

  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => el.classList.remove('show'), TOAST_MS);
  }

  // ---- connection -----------------------------------------------------------
  function socketOpen() {
    return !!state.ws && state.ws.readyState === WebSocket.OPEN;
  }

  // Sends one JSON message. Returns false when the socket is not open.
  function send(msg) {
    if (!socketOpen()) return false;
    try { state.ws.send(JSON.stringify(msg)); return true; } catch (e) { return false; }
  }

  function connect() {
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    if (state.ws) return;                 // already connecting or open

    let sock;
    try {
      sock = new WebSocket(`ws://${location.host}/ws`);
    } catch (e) {
      scheduleReconnect();
      return;
    }
    state.ws = sock;
    state.lastPong = Date.now();
    setStatus('connecting');
    startPing();

    sock.onopen = () => {
      if (state.ws !== sock) return;
      state.backoffIdx = 0;
      state.lastPong = Date.now();
      send({ t: 'hello', token: store.get(TOKEN_KEY) || '' });
    };
    sock.onmessage = (ev) => { if (state.ws === sock) onMessage(ev.data); };
    sock.onclose = () => { if (state.ws === sock) dropSocket(); };
  }

  // Detaches handlers and closes, without scheduling a retry.
  function closeSocket() {
    const sock = state.ws;
    state.ws = null;
    state.authed = false;
    stopPing();
    stopPress();
    if (sock) {
      sock.onopen = sock.onmessage = sock.onclose = null;
      try { sock.close(); } catch (e) { /* already closed */ }
    }
  }

  // Connection lost: update the UI and retry with backoff.
  function dropSocket() {
    closeSocket();
    setStatus('offline');
    scheduleReconnect();
  }

  function scheduleReconnect() {
    if (state.reconnectTimer) return;
    const delay = BACKOFF_MS[Math.min(state.backoffIdx, BACKOFF_MS.length - 1)];
    state.backoffIdx += 1;
    state.reconnectTimer = setTimeout(connect, delay);
  }

  // Ping every 3 s; a socket with no pong for 7 s counts as dead.
  function startPing() {
    stopPing();
    state.pingTimer = setInterval(() => {
      if (!state.ws) return;
      if (Date.now() - state.lastPong > DEAD_MS) { dropSocket(); return; }
      if (socketOpen()) send({ t: 'ping' });
    }, PING_MS);
  }

  function stopPing() {
    clearInterval(state.pingTimer);
    state.pingTimer = null;
  }

  function onMessage(raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch (e) { return; }   // ignore bad frames
    if (!msg || typeof msg !== 'object') return;

    switch (msg.t) {
      case 'auth':         return onAuth(msg);
      case 'pair_started': return onPairStarted(msg);
      case 'paired':       return onPaired(msg);
      case 'pair_failed':  return onPairFailed(msg);
      case 'caps':         return applyCaps(msg.caps);
      case 'now':          return applyNow(msg.now);
      case 'pong':         state.lastPong = Date.now(); return;
      case 'err':          return toast(msg.msg || `${msg.c || 'Command'} failed`);
      case 'update':       return applyUpdate(msg.update);
      case 'apps':         return onApps(msg);
      default:             return;
    }
  }

  function onAuth(msg) {
    if (msg.ok) {
      state.authed = true;
      hidePairing();
      setStatus('connected');
      applyCaps(msg.caps);
      ensureApps();
      return;
    }
    // Token missing, wrong or revoked: forget it and ask for a new pairing.
    state.authed = false;
    store.del(TOKEN_KEY);
    setStatus('notpaired');
    showPairing();
  }

  // iOS suspends sockets in the background: restart at once on return.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return;
    if (socketOpen() && Date.now() - state.lastPong <= DEAD_MS) return;
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    state.backoffIdx = 0;
    closeSocket();
    connect();
  });

  // ---- pairing UI -----------------------------------------------------------
  function showPairing() {
    $('pair').hidden = false;
    showPairStep('start');
    $('btn-show-code').disabled = false;
    clearPairError();
  }

  function hidePairing() {
    stopCountdown();
    $('pair').hidden = true;
    $('pair-code').value = '';
    updatePairButton();
    clearPairError();
  }

  function showPairStep(step) {
    $('pair-step-start').hidden = step !== 'start';
    $('pair-step-code').hidden = step !== 'code';
  }

  function setPairError(text) { $('pair-error').textContent = text || ''; }
  function clearPairError() { setPairError(''); }

  function updatePairButton() {
    $('btn-pair').disabled = !/^\d{4}$/.test($('pair-code').value);
  }

  function submitCode() {
    const code = $('pair-code').value;
    if (!/^\d{4}$/.test(code)) return;
    clearPairError();
    if (!send({ t: 'pair', code })) setPairError('Not connected – retrying');
  }

  function onPairStarted(msg) {
    $('btn-show-code').disabled = false;
    $('pair-code').value = '';
    updatePairButton();
    clearPairError();
    showPairStep('code');
    startCountdown(Number(msg.ttl) || 120);
    $('pair-code').focus();
  }

  function onPaired(msg) {
    if (typeof msg.token === 'string' && msg.token) store.set(TOKEN_KEY, msg.token);
    state.authed = true;
    hidePairing();
    setStatus('connected');
    applyCaps(msg.caps);
    ensureApps();
  }

  function onPairFailed(msg) {
    if (msg.reason === 'expired') return onCodeExpired();
    if (msg.reason === 'locked') return lockPairing();
    if (msg.reason !== 'wrong_code') return setPairError('Pairing failed – try again');
    const left = Number.isFinite(msg.left) ? msg.left : null;
    setPairError(left === null ? 'Wrong code' :
      `Wrong code – ${left} attempt${left === 1 ? '' : 's'} left`);
    $('pair-code').value = '';
    updatePairButton();
    $('pair-code').focus();
  }

  // Counts down from the TV's ttl (seconds the code stays valid).
  function startCountdown(ttl) {
    stopCountdown();
    state.countdownEnds = Date.now() + ttl * 1000;
    tickCountdown();
    state.countdownTimer = setInterval(tickCountdown, 1000);
  }

  function tickCountdown() {
    const left = Math.ceil((state.countdownEnds - Date.now()) / 1000);
    if (left <= 0) return onCodeExpired();
    const s = String(left % 60).padStart(2, '0');
    $('pair-countdown').textContent = `Code expires in ${Math.floor(left / 60)}:${s}`;
  }

  function stopCountdown() {
    clearInterval(state.countdownTimer);
    state.countdownTimer = null;
    $('pair-countdown').textContent = '';
  }

  function onCodeExpired() {
    stopCountdown();
    $('pair-code').value = '';
    updatePairButton();
    showPairStep('start');
    $('btn-show-code').disabled = false;
    setPairError('Code expired – request a new one');
  }

  // Too many wrong tries: keep "Show code" off for a minute.
  function lockPairing() {
    stopCountdown();
    $('pair-code').value = '';
    showPairStep('start');
    $('btn-show-code').disabled = true;
    setPairError('Too many tries – wait a minute');
    clearTimeout(state.lockTimer);
    state.lockTimer = setTimeout(() => {
      $('btn-show-code').disabled = false;
      clearPairError();
    }, LOCK_WAIT_MS);
  }

  function bindPairing() {
    const input = $('pair-code');
    $('btn-show-code').addEventListener('click', () => {
      clearPairError();
      if (send({ t: 'pair_request' })) $('btn-show-code').disabled = true; // re-enabled on pair_started
      else setPairError('Not connected – retrying');
    });
    input.addEventListener('input', () => {
      input.value = input.value.replace(/\D/g, '').slice(0, 4);
      updatePairButton();
    });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submitCode(); });
    $('btn-pair').addEventListener('click', submitCode);
  }

  // ---- buttons and auto-repeat ----------------------------------------------
  // Commands go out only while the TV is connected and authenticated.
  function sendCmd(name, repeat) {
    const msg = { t: 'cmd', c: name };
    if (repeat) msg.repeat = true;
    return send(msg);
  }

  // Pointerdown sends at once; D-pad arrows then auto-repeat.
  function onPressStart(ev) {
    if (ev.pointerType === 'mouse' && ev.button !== 0) return;
    ev.preventDefault();
    ensureAudio();
    const btn = ev.currentTarget;
    if (state.pressBtn) endPress(state.pressBtn);
    state.pressBtn = btn;
    btn.classList.add('pressed');

    if (!(state.authed && socketOpen())) { toast('Not connected'); return; }
    playClick();
    const name = btn.dataset.cmd;
    sendCmd(name, false);

    if (btn.dataset.repeat === '1') {
      state.repeatTimer = setTimeout(() => {
        state.repeatTimer = null;
        state.repeatInterval = setInterval(() => sendCmd(name, true), REPEAT_EVERY_MS);
      }, REPEAT_DELAY_MS);
    }
  }

  // Pointerup, pointercancel and pointerleave all end the press.
  function onPressEnd(ev) {
    if (ev.currentTarget === state.pressBtn) endPress(state.pressBtn);
  }

  function endPress(btn) {
    btn.classList.remove('pressed');
    stopRepeat();
    state.pressBtn = null;
  }

  function stopRepeat() {
    clearTimeout(state.repeatTimer);
    clearInterval(state.repeatInterval);
    state.repeatTimer = null;
    state.repeatInterval = null;
  }

  function stopPress() {
    if (state.pressBtn) endPress(state.pressBtn);
    stopRepeat();
  }

  function bindRemoteButtons() {
    document.querySelectorAll('.btn[data-cmd]').forEach((btn) => {
      btn.addEventListener('pointerdown', onPressStart);
      ['pointerup', 'pointercancel', 'pointerleave'].forEach((type) => {
        btn.addEventListener(type, onPressEnd);
      });
    });
  }

  // No pinch zoom, no double-tap zoom, no long-press menu.
  function bindGestureGuards() {
    ['gesturestart', 'gesturechange', 'gestureend'].forEach((type) => {
      document.addEventListener(type, (e) => e.preventDefault());
    });
    let lastTouchEnd = 0;
    document.addEventListener('touchend', (e) => {
      const now = Date.now();
      const inField = e.target.closest && e.target.closest('input, textarea');
      if (now - lastTouchEnd < 300 && !inField) e.preventDefault();
      lastTouchEnd = now;
    }, { passive: false });
    document.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  // ---- now playing ----------------------------------------------------------
  // The TV sends null when nothing plays. Play/pause icon follows `playing`.
  function applyNow(now) {
    const info = now && typeof now === 'object' ? now : null;
    $('now').hidden = !info;
    if (info) {
      $('now-app').textContent = info.app || '';
      $('now-app').hidden = !info.app;
      $('now-title').textContent = info.title || '';
      $('now-sub').textContent = info.subtitle || '';
      $('now-sub').hidden = !info.subtitle;
    }

    const playing = !!info && info.playing === true;
    $('play-icon').innerHTML = iconSvg(playing ? 'pause' : 'play');
    $('btn-play').setAttribute('aria-label', playing ? 'Pause' : 'Play');
  }

  // ---- caps -----------------------------------------------------------------
  // Runs on every auth, paired and caps message.
  function applyCaps(caps) {
    if (!caps || typeof caps !== 'object') return;
    state.caps = Object.assign({}, state.caps, caps);
    const noAccessibility = caps.accessibility === false;
    $('module').classList.toggle('a11y-off', noAccessibility);
    $('a11y-banner').hidden = !noAccessibility;

    // Volume row greys out when the TV cannot change volume.
    $('media').classList.toggle('vol-off', caps.volume === false);

    // MENU sends the same key either way; only the label and icon change.
    const useMenu = caps.menu === 'menu';
    $('menu-label').textContent = useMenu ? 'Menu' : 'Search';
    $('menu-icon').innerHTML = iconSvg(useMenu ? 'menu' : 'search');

    // Keyboard greys out without text input; launching greys out without apps.
    $('kb-body').classList.toggle('needs-text', state.caps.text === false);
    $('kb-hint').textContent = state.caps.text === false
      ? 'Text input needs the Accessibility service or Shizuku'
      : 'Select a search box on the TV first, then type here.';
    document.body.classList.toggle('no-launch', state.caps.apps === false);

    // Shizuku: More-tab keys and the status card.
    const shz = state.caps.shizuku === true;
    document.body.classList.toggle('no-shizuku', !shz);
    $('shz-state').textContent = shz ? 'Ready' : 'Off';
    $('shz-state').dataset.on = shz ? '1' : '0';
    $('shz-hint').textContent = shz
      ? 'Real volume keys, Menu, Settings, Sleep and typing in any app.'
      : 'Off. After a TV reboot, open Shizuku on the TV and start it again.';
  }

  // ---- any key code (More tab) ---------------------------------------------
  function bindKeyBox() {
    const sendKey = () => {
      const raw = $('key-code').value || $('key-select').value;
      const code = parseInt(raw, 10);
      if (!(code >= 0 && code <= 400)) { toast('Pick a key or type a code'); return; }
      if (!send({ t: 'cmd', c: 'KEY', code })) toast('Not connected');
      else playClick();
    };
    $('key-send').addEventListener('click', sendKey);
    $('key-select').addEventListener('change', () => { $('key-code').value = ''; });
    $('key-code').addEventListener('input', () => {
      $('key-code').value = $('key-code').value.replace(/\D/g, '').slice(0, 3);
    });
  }

  // ---- tabs -----------------------------------------------------------------
  // Remote is the default. Keyboard and Apps replace the remote area; the top bar stays.
  function setTab(name) {
    const tab = TABS.includes(name) ? name : 'remote';
    document.querySelectorAll('.tab').forEach((btn) => {
      btn.setAttribute('aria-selected', String(btn.dataset.tab === tab));
    });
    $('view-remote').hidden = tab !== 'remote';
    $('view-keyboard').hidden = tab !== 'keyboard';
    $('view-apps').hidden = tab !== 'apps';
    $('view-more').hidden = tab !== 'more';
    store.set(TAB_KEY, tab);

    // Focus after un-hiding, so the field can take it. Leaving the tab hides the phone keyboard.
    if (tab === 'keyboard') $('kb-text').focus();
    else $('kb-text').blur();
    if (tab === 'apps') requestApps();
  }

  // Instant pressed feedback, the same as the remote buttons.
  function bindPressFeedback(el) {
    el.addEventListener('pointerdown', () => el.classList.add('pressed'));
    ['pointerup', 'pointercancel', 'pointerleave'].forEach((type) => {
      el.addEventListener(type, () => el.classList.remove('pressed'));
    });
  }

  function bindTabs() {
    document.querySelectorAll('.tab').forEach((btn) => {
      bindPressFeedback(btn);
      btn.addEventListener('click', () => setTab(btn.dataset.tab));
    });
  }

  // ---- keyboard -------------------------------------------------------------
  // The whole box value goes to the TV's focused field, debounced while typing.
  function sendText() {
    clearTimeout(state.textTimer);
    state.textTimer = null;
    send({ t: 'cmd', c: 'TEXT', text: $('kb-text').value });
  }

  function queueText() {
    clearTimeout(state.textTimer);
    state.textTimer = setTimeout(sendText, TEXT_DEBOUNCE_MS);
  }

  // Enter sends any pending text first, so the TV has the full value before the key.
  function pressEnter() {
    if (state.textTimer) sendText();
    sendCmd('TEXT_ENTER');
  }

  function clearText() {
    clearTimeout(state.textTimer);
    state.textTimer = null;
    $('kb-text').value = '';
    sendCmd('TEXT_CLEAR');
  }

  function bindKeyboard() {
    const box = $('kb-text');
    box.addEventListener('input', queueText);
    box.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' || e.isComposing) return;
      e.preventDefault();                 // no newline in the box
      pressEnter();
    });
    bindPressFeedback($('kb-clear'));
    bindPressFeedback($('kb-enter'));
    $('kb-clear').addEventListener('click', clearText);
    $('kb-enter').addEventListener('click', pressEnter);
  }

  // ---- apps -----------------------------------------------------------------
  // The TV sends the list sorted by label. The last list is kept for the grid and the favourites row.
  function requestApps() {
    return send({ t: 'apps_get' });
  }

  // Asked once after auth, so the favourites row can show names and banners.
  function ensureApps() {
    if (state.apps === null) requestApps();
  }

  function onApps(msg) {
    if (!Array.isArray(msg.apps)) return;
    state.apps = msg.apps
      .filter((a) => a && typeof a.pkg === 'string' && a.pkg)
      .map((a) => ({ pkg: a.pkg, label: String(a.label || a.pkg), banner: a.banner === true }));
    renderApps();
    renderFavs();
  }

  // Same image URL as the TV's own endpoint: the token goes in the query string.
  function iconUrl(pkg) {
    const token = store.get(TOKEN_KEY) || '';
    return `/icon?pkg=${encodeURIComponent(pkg)}&token=${encodeURIComponent(token)}`;
  }

  // Art box for one app. A failed image leaves the label in its place.
  function makeArt(app) {
    const art = document.createElement('div');
    art.className = app.banner ? 'art' : 'art is-icon';

    const img = document.createElement('img');
    img.alt = '';
    img.loading = 'lazy';
    img.addEventListener('error', () => {
      img.remove();
      art.classList.add('no-img');
    });
    img.src = iconUrl(app.pkg);

    const label = document.createElement('span');
    label.className = 'art-label';
    label.textContent = app.label;

    art.append(img, label);
    return art;
  }

  // Grid tile: 16:9 art with the star in its corner, caption underneath.
  function makeTile(app) {
    const tile = document.createElement('div');
    tile.className = 'tile';
    tile.dataset.pkg = app.pkg;
    tile.setAttribute('aria-label', `Launch ${app.label}`);

    const art = makeArt(app);
    const star = document.createElement('button');
    star.type = 'button';
    star.className = 'star';
    star.dataset.pkg = app.pkg;
    star.setAttribute('aria-label', 'Favourite');
    star.innerHTML = iconSvg('star');
    star.addEventListener('click', (e) => {
      e.stopPropagation();                // the tile itself must not launch
      toggleFav(app.pkg);
    });
    paintStar(star);
    art.appendChild(star);

    const cap = document.createElement('span');
    cap.className = 'tile-cap';
    cap.textContent = app.label;

    tile.append(art, cap);
    return tile;
  }

  function renderApps() {
    const grid = $('apps-grid');
    const list = state.apps;
    const q = $('apps-filter').value.trim().toLowerCase();
    const shown = list ? list.filter((a) => a.label.toLowerCase().includes(q)) : [];

    grid.textContent = '';
    shown.forEach((app) => grid.appendChild(makeTile(app)));

    const status = $('apps-status');
    status.hidden = shown.length > 0;
    status.textContent = !list ? 'Loading…'
      : list.length === 0 ? 'No apps on the TV'
      : 'No apps match the filter';
  }

  // Taps on tiles inside a scrolling container. A touch that moved is a scroll, not a tap.
  function bindTileTaps(container, onTap) {
    let drag = null;

    container.addEventListener('pointerdown', (e) => {
      const tile = e.target.closest('.tile, .fav');
      drag = null;
      if (!tile || e.target.closest('.star')) return;   // the star handles its own taps
      drag = { x: e.clientX, y: e.clientY, moved: false, tile };
      tile.classList.add('pressed');
    });

    container.addEventListener('pointermove', (e) => {
      if (!drag || drag.moved) return;
      if (Math.abs(e.clientX - drag.x) > TAP_SLOP_PX || Math.abs(e.clientY - drag.y) > TAP_SLOP_PX) {
        drag.moved = true;
      }
    });

    ['pointerup', 'pointercancel'].forEach((type) => {
      container.addEventListener(type, () => {
        if (drag) drag.tile.classList.remove('pressed');
      });
    });

    container.addEventListener('click', (e) => {
      const moved = !!(drag && drag.moved);
      drag = null;
      const tile = e.target.closest('.tile, .fav');
      if (!tile || moved || !container.contains(tile)) return;
      flash(tile);
      onTap(tile);
    });
  }

  // Keeps the pressed look for a moment after a tap, so the tap reads as sent.
  function flash(el) {
    el.classList.add('pressed');
    setTimeout(() => el.classList.remove('pressed'), FLASH_MS);
  }

  // The TV does the launch; the phone only sends it.
  function launchApp(pkg) {
    if (!(state.authed && socketOpen())) { toast('Not connected'); return; }
    if (state.caps.apps === false) { toast('Launching is not available on the TV'); return; }
    ensureAudio();
    playClick();
    send({ t: 'cmd', c: 'LAUNCH', pkg });
  }

  function bindApps() {
    bindTileTaps($('apps-grid'), (tile) => launchApp(tile.dataset.pkg));
    bindTileTaps($('favs'), (tile) => launchApp(tile.dataset.pkg));
    $('apps-filter').addEventListener('input', renderApps);
    bindPressFeedback($('btn-apps-refresh'));
    $('btn-apps-refresh').addEventListener('click', () => {
      if (!requestApps()) toast('Not connected');
    });
    bindPressFeedback($('fav-hint'));
    $('fav-hint').addEventListener('click', () => setTab('apps'));
  }

  // ---- favourites -----------------------------------------------------------
  // Stored on the phone only. Unreadable storage counts as no favourites.
  function loadFavs() {
    try {
      const list = JSON.parse(store.get(FAVS_KEY) || '[]');
      return Array.isArray(list) ? list.filter((p) => typeof p === 'string') : [];
    } catch (e) {
      return [];
    }
  }

  function isFav(pkg) {
    return state.favs.includes(pkg);
  }

  function toggleFav(pkg) {
    state.favs = isFav(pkg) ? state.favs.filter((p) => p !== pkg) : state.favs.concat(pkg);
    store.set(FAVS_KEY, JSON.stringify(state.favs));
    document.querySelectorAll('#apps-grid .star').forEach((star) => paintStar(star));
    renderFavs();
  }

  function paintStar(star) {
    const on = isFav(star.dataset.pkg);
    star.classList.toggle('on', on);
    star.setAttribute('aria-pressed', String(on));
  }

  // Shows the pinned apps, or the hint when there are none. Names come from the cached list.
  function renderFavs() {
    const row = $('favs');
    const byPkg = new Map((state.apps || []).map((a) => [a.pkg, a]));
    const items = state.favs
      .filter((pkg) => !state.apps || byPkg.has(pkg))     // uninstalled apps drop out
      .map((pkg) => byPkg.get(pkg) || { pkg, label: '', banner: true });

    row.textContent = '';
    items.forEach((app) => row.appendChild(makeFav(app)));
    row.hidden = items.length === 0;
    $('fav-hint').hidden = items.length > 0;
  }

  function makeFav(app) {
    const fav = document.createElement('div');
    fav.className = 'fav';
    fav.dataset.pkg = app.pkg;
    fav.setAttribute('aria-label', `Launch ${app.label || 'app'}`);
    fav.appendChild(makeArt(app));
    return fav;
  }

  // ---- settings -------------------------------------------------------------
  function setHand(hand) {
    const side = hand === 'left' ? 'left' : 'right';
    $('module').dataset.hand = side;      // CSS puts the D-pad on this side
    store.set(HAND_KEY, side);
    $('opt-hand').setAttribute('aria-checked', String(side === 'left'));
  }

  function bindSettings() {
    const close = () => { $('settings').hidden = true; };
    $('btn-settings').addEventListener('click', () => { $('settings').hidden = false; });
    $('btn-settings-close').addEventListener('click', close);
    $('settings-backdrop').addEventListener('click', close);
    $('opt-hand').addEventListener('click', () => {
      setHand($('module').dataset.hand === 'left' ? 'right' : 'left');
    });
    $('opt-sound').addEventListener('click', () => setSound(!soundOn()));
    $('btn-forget').addEventListener('click', () => {
      if (!window.confirm('Forget this TV? You will need to pair again.')) return;
      store.del(TOKEN_KEY);
      location.reload();
    });
  }

  // ---- app updates ----------------------------------------------------------
  // The TV checks GitHub on its own; this just shows its state and offers buttons.
  const UPDATE_TEXT = {
    idle: 'Not checked yet',
    checking: 'Checking…',
    up_to_date: 'Up to date',
    downloading: 'Downloading update…',
    ready: 'Update ready – installs when the TV is idle',
    installing: 'Installing… the remote will reconnect',
    error: 'Update problem',
  };

  function applyUpdate(u) {
    if (!u || typeof u !== 'object') return;
    const newer = u.latest > u.current;
    $('update-version').textContent = newer ? `build ${u.current} → ${u.latest}` : `build ${u.current}`;
    $('update-status').textContent = u.msg || UPDATE_TEXT[u.phase] || '';
    $('btn-update-install').hidden = u.phase !== 'ready';
    $('btn-update-check').disabled = u.phase === 'checking' || u.phase === 'downloading' || u.phase === 'installing';
    $('btn-settings').classList.toggle('has-update', u.phase === 'ready');
  }

  function bindUpdates() {
    $('btn-update-check').addEventListener('click', () => {
      if (!send({ t: 'update_check' })) toast('Not connected');
    });
    $('btn-update-install').addEventListener('click', () => {
      if (send({ t: 'update_install' })) toast('Installing – the remote will reconnect');
      else toast('Not connected');
    });
  }

  // ---- sound ----------------------------------------------------------------
  function soundOn() { return store.get(SOUND_KEY) === '1'; }

  function setSound(on) {
    store.set(SOUND_KEY, on ? '1' : '0');
    $('opt-sound').setAttribute('aria-checked', String(on));
  }

  // Created lazily on the first touch (iOS only allows audio after a gesture).
  function ensureAudio() {
    if (state.audio) {
      if (state.audio.state === 'suspended') state.audio.resume();
      return;
    }
    const Ctx = window.AudioContext || window.webkitAudioContext;
    try { state.audio = Ctx ? new Ctx() : null; } catch (e) { state.audio = null; }
  }

  // Tiny ~15 ms blip, only when the sound setting is on.
  function playClick() {
    if (!soundOn() || !state.audio) return;
    try {
      const ctx = state.audio;
      const t = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = 1400;
      gain.gain.setValueAtTime(0.0001, t);
      gain.gain.exponentialRampToValueAtTime(0.08, t + 0.002);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.015);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t);
      osc.stop(t + 0.016);
    } catch (e) { /* sound is optional */ }
  }

  // ---- init -----------------------------------------------------------------
  function init() {
    applyIcons();
    setHand(store.get(HAND_KEY));
    setSound(soundOn());
    state.favs = loadFavs();
    bindRemoteButtons();
    bindPairing();
    bindTabs();
    bindKeyboard();
    bindApps();
    bindSettings();
    bindUpdates();
    bindKeyBox();
    bindGestureGuards();
    renderFavs();
    setTab(store.get(TAB_KEY));
    connect();
  }

  init();
})();
