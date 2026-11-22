/*
 * TV Remote - phone page logic.
 * Plain JS, no build step. Wire format and flow: docs/protocol.md.
 * Sections: constants, icons, storage, status, connection, pairing,
 * buttons, caps, settings, sound, init.
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
    close: '<path d="M6 6l12 12"/><path d="M18 6L6 18"/>',
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
      case 'pong':         state.lastPong = Date.now(); return;
      case 'err':          return toast(msg.msg || `${msg.c || 'Command'} failed`);
      default:             return;
    }
  }

  function onAuth(msg) {
    if (msg.ok) {
      state.authed = true;
      hidePairing();
      setStatus('connected');
      applyCaps(msg.caps);
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
      const inField = e.target.closest && e.target.closest('input');
      if (now - lastTouchEnd < 300 && !inField) e.preventDefault();
      lastTouchEnd = now;
    }, { passive: false });
    document.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  // ---- caps -----------------------------------------------------------------
  // Runs on every auth, paired and caps message.
  function applyCaps(caps) {
    if (!caps || typeof caps !== 'object') return;
    const noAccessibility = caps.accessibility === false;
    $('module').classList.toggle('a11y-off', noAccessibility);
    $('a11y-banner').hidden = !noAccessibility;

    // MENU sends the same key either way; only the label and icon change.
    const useMenu = caps.menu === 'menu';
    $('menu-label').textContent = useMenu ? 'Menu' : 'Search';
    $('menu-icon').innerHTML = iconSvg(useMenu ? 'menu' : 'search');
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
    bindRemoteButtons();
    bindPairing();
    bindSettings();
    bindGestureGuards();
    connect();
  }

  init();
})();
