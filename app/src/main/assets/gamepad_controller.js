/**
 * gamepad_controller.js
 * ---------------------------------------------------------------------------
 * Controlador de teclado virtual para juegos web dentro de un WebView.
 *
 * Recibe órdenes desde Kotlin (los botones táctiles) y las convierte en
 * eventos de teclado (keydown / keypress / keyup) como si vinieran de un
 * teclado físico de PC.
 *
 * API pública (window.__vgp):
 *   __vgp.down('ArrowUp')   -> pulsa y MANTIENE la tecla
 *   __vgp.up('ArrowUp')     -> suelta la tecla
 *   __vgp.tap('Space', 60)  -> pulsación breve (ms opcional)
 *   __vgp.reset()           -> suelta todas las teclas (evita teclas "pegadas")
 *   __vgp.config            -> parámetros de auto-repetición
 *
 * Los nombres de tecla siguen KeyboardEvent.code:
 *   ArrowUp/Down/Left/Right, Space, Enter, Escape, Tab, Backspace,
 *   ShiftLeft, ControlLeft, AltLeft, KeyA..KeyZ, Digit0..Digit9
 *
 * Se inyecta en TODOS los frames (juegos dentro de <iframe>): el frame
 * principal reenvía cada orden a sus hijos con postMessage, incluso entre
 * orígenes distintos (cross-origin).
 *
 * El script es idempotente: si se inyecta dos veces no hace nada la segunda.
 * ---------------------------------------------------------------------------
 */
(function () {
  'use strict';

  if (window.__vgp) return;

  // ------------------------------------------------------------------ Config
  const CONFIG = {
    autoRepeat: true,     // simula la repetición de un teclado físico al mantener pulsado
    repeatDelayMs: 400,   // espera antes de empezar a repetir
    repeatRateMs: 40,     // intervalo entre repeticiones (~25 Hz)
    tapDurationMs: 60     // duración por defecto de tap()
  };

  // ------------------------------------------------------- Tabla de teclas
  // keyCode es el valor "legacy" que todavía usan muchos juegos antiguos.
  const SPECIAL_KEYS = {
    ArrowLeft:   { key: 'ArrowLeft',  keyCode: 37 },
    ArrowUp:     { key: 'ArrowUp',    keyCode: 38 },
    ArrowRight:  { key: 'ArrowRight', keyCode: 39 },
    ArrowDown:   { key: 'ArrowDown',  keyCode: 40 },
    Space:       { key: ' ',          keyCode: 32, printable: true },
    Enter:       { key: 'Enter',      keyCode: 13, printable: true },
    Escape:      { key: 'Escape',     keyCode: 27 },
    Tab:         { key: 'Tab',        keyCode: 9 },
    Backspace:   { key: 'Backspace',  keyCode: 8 },
    ShiftLeft:   { key: 'Shift',      keyCode: 16, location: 1, modifier: true },
    ControlLeft: { key: 'Control',    keyCode: 17, location: 1, modifier: true },
    AltLeft:     { key: 'Alt',        keyCode: 18, location: 1, modifier: true }
  };

  /** Devuelve el descriptor completo de una tecla a partir de su nombre. */
  function describeKey(name) {
    const special = SPECIAL_KEYS[name];
    if (special) return Object.assign({ code: name, location: 0 }, special);

    let m = /^Key([A-Z])$/.exec(name);
    if (m) {
      return { code: name, key: m[1].toLowerCase(), keyCode: m[1].charCodeAt(0),
               location: 0, printable: true };
    }
    m = /^Digit([0-9])$/.exec(name);
    if (m) {
      return { code: name, key: m[1], keyCode: 48 + Number(m[1]),
               location: 0, printable: true };
    }
    return null; // tecla desconocida: se ignora
  }

  // ------------------------------------------------------ Estado y eventos
  const pressed = Object.create(null); // nombre -> { delayTimer, rateTimer }

  function modifierState() {
    return {
      shiftKey: !!pressed.ShiftLeft,
      ctrlKey: !!pressed.ControlLeft,
      altKey: !!pressed.AltLeft
    };
  }

  function defineGetter(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, { get: function () { return value; } });
    } catch (e) { /* ignorar */ }
  }

  /** Crea un KeyboardEvent con todas las propiedades que los juegos consultan. */
  function makeEvent(type, d, repeat) {
    const mods = modifierState();
    let key = d.key;
    if (mods.shiftKey && /^[a-z]$/.test(key)) key = key.toUpperCase();

    const ev = new KeyboardEvent(type, {
      key: key,
      code: d.code,
      location: d.location,
      repeat: repeat,
      bubbles: true,
      cancelable: true,
      composed: true,
      shiftKey: mods.shiftKey,
      ctrlKey: mods.ctrlKey,
      altKey: mods.altKey,
      view: window
    });

    // Chromium ignora keyCode/which del constructor: se fuerzan a mano.
    const legacy = (type === 'keypress')
      ? (key.length === 1 ? key.charCodeAt(0) : 13)
      : d.keyCode;
    defineGetter(ev, 'keyCode', legacy);
    defineGetter(ev, 'which', legacy);
    if (type === 'keypress') defineGetter(ev, 'charCode', legacy);
    return ev;
  }

  /**
   * Elige dónde lanzar el evento. Al burbujear llega a body, document y
   * window, así que cubre los tres sitios donde los juegos suelen escuchar:
   *   1) elemento con foco (p. ej. un <canvas tabindex="0">)
   *   2) el <canvas> visible más grande
   *   3) <body> / document
   */
  function pickTarget() {
    const a = document.activeElement;
    if (a && a !== document.body && a !== document.documentElement &&
        a.tagName !== 'IFRAME' && a.tagName !== 'FRAME') {
      return a;
    }
    let best = null;
    let bestArea = 0;
    const canvases = document.getElementsByTagName('canvas');
    for (let i = 0; i < canvases.length; i++) {
      const r = canvases[i].getBoundingClientRect();
      const area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = canvases[i]; }
    }
    return best || document.body || document;
  }

  function fire(type, d, repeat) {
    try {
      pickTarget().dispatchEvent(makeEvent(type, d, repeat));
    } catch (e) {
      try { document.dispatchEvent(makeEvent(type, d, repeat)); } catch (e2) { /* ignorar */ }
    }
  }

  // ------------------------------------------------------- Pulsar / soltar
  function press(name) {
    const d = describeKey(name);
    if (!d || pressed[name]) return; // ya estaba pulsada

    const entry = { delayTimer: 0, rateTimer: 0 };
    pressed[name] = entry;

    fire('keydown', d, false);
    if (d.printable) fire('keypress', d, false);

    // Auto-repetición como un teclado físico (los modificadores no repiten)
    if (CONFIG.autoRepeat && !d.modifier) {
      entry.delayTimer = setTimeout(function () {
        entry.rateTimer = setInterval(function () {
          fire('keydown', d, true);
          if (d.printable) fire('keypress', d, true);
        }, CONFIG.repeatRateMs);
      }, CONFIG.repeatDelayMs);
    }
  }

  function release(name) {
    const entry = pressed[name];
    const d = describeKey(name);
    if (!entry || !d) return;

    clearTimeout(entry.delayTimer);
    clearInterval(entry.rateTimer);
    delete pressed[name];
    fire('keyup', d, false);
  }

  function releaseAll() {
    Object.keys(pressed).forEach(release);
  }

  // ------------------------------------------------ Reenvío a los <iframe>
  function forward(type, name) {
    const frames = document.querySelectorAll('iframe, frame');
    for (let i = 0; i < frames.length; i++) {
      try {
        frames[i].contentWindow.postMessage({ __vgp: 1, type: type, name: name }, '*');
      } catch (e) { /* frame inaccesible */ }
    }
  }

  function apply(type, name) {
    if (type === 'down') press(name);
    else if (type === 'up') release(name);
    else if (type === 'reset') releaseAll();
    forward(type, name);
  }

  // Los frames hijos solo aceptan mensajes de SU padre (evita suplantación entre hermanos).
  window.addEventListener('message', function (e) {
    const m = e.data;
    if (!m || m.__vgp !== 1) return;
    if (window.parent === window || e.source !== window.parent) return;
    apply(m.type, m.name);
  });

  // Seguridad: nunca dejar teclas "pegadas" si la página se oculta o se cierra.
  window.addEventListener('pagehide', releaseAll);
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) releaseAll();
  });

  // ------------------------------------------------------------ API pública
  window.__vgp = {
    down:  function (name) { apply('down', name); },
    up:    function (name) { apply('up', name); },
    reset: function () { apply('reset', ''); },
    tap:   function (name, ms) {
      apply('down', name);
      setTimeout(function () { apply('up', name); }, ms || CONFIG.tapDurationMs);
    },
    config: CONFIG
  };
})();
