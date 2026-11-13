// Controller support for the player overlay, ported from the Stremio Community
// shell's gamepad.cpp. The pad is read natively (XInput, in Kotlin) and each
// press arrives here as window.nuvioGamepad.press(name, {lb, rb, repeat}); this
// file only decides what a press means in the player. Most bindings replay the
// overlay's own keyboard shortcuts, so a controller and a keyboard behave the
// same; the held-direction seek is the one thing done differently.
//
// Loaded after controls.js, whose top-level bindings (state, send, activeModal,
// isScrubbing, ...) it shares.
(() => {
  // --- accelerated seek ----------------------------------------------------
  // A tap is the overlay's own ten-second step, but a held direction is someone
  // travelling. So a sustained run turns into one long scrub: playback pauses,
  // the frame follows the target as it moves, and the seek is committed once
  // when the direction is let go - then playback resumes, unless it was already
  // paused before the scrub. The step grows with the length of the run.
  const SEEK_RAMP = [[3, 10], [8, 30], [15, 60]];   // [steps so far, seconds]
  const SEEK_TOP_STEP = 120;
  const SEEK_TAP_MS = 350;      // grace after a release before committing
  const SEEK_IDLE_MS = 900;     // net for a release that never arrives
  const SEEK_PRIME_TAPS = 2;    // first presses use the plain 10s seek
  const SEEK_GESTURE_MS = 650;  // longer than one repeat interval
  const SEEK_BUTTONS = { LEFT: -1, RIGHT: 1, LT: -1, RT: 1 };

  const held = new Set();
  let scrub = null;
  let seekPrimeCount = 0;
  let seekPrimeAt = 0;
  let seekPrimeDir = 0;
  let navMode = false;

  const now = () => (window.performance ? window.performance.now() : Date.now());

  const sendKey = (code, key, extra = {}) => {
    const target = document.activeElement || document.body || document;
    target.dispatchEvent(new KeyboardEvent("keydown", {
      code, key, bubbles: true, cancelable: true, ...extra,
    }));
    target.dispatchEvent(new KeyboardEvent("keyup", {
      code, key, bubbles: true, cancelable: true, ...extra,
    }));
  };

  // A mouse move is what brings the controls back natively; this is the same.
  const wakeControls = () => {
    send("cursorActivity", 0);
    noteChromeActivity(true);
  };

  const seekHeld = () => Object.keys(SEEK_BUTTONS).some(name => held.has(name));

  const formatDelta = ms => {
    const sign = ms < 0 ? "-" : "+";
    return sign + formatTime(Math.abs(ms));
  };

  const scrubStart = () => {
    const durationMs = Math.max(0, Number(state.durationMs) || 0);
    if (!durationMs) return false;
    const startMs = Math.min(durationMs, Math.max(0, Number(state.positionMs) || 0));
    scrub = {
      durationMs,
      startMs,
      target: startMs,
      dir: 0,
      run: 0,
      timer: 0,
      wasPaused: !state.isPlaying,
    };
    if (!scrub.wasPaused) send("gamepadSetPaused", 1);
    isScrubbing = true;
    scrubPositionMs = startMs;
    send("scrubChange", startMs);
    return true;
  };

  const scrubStep = dir => {
    if (scrub.dir !== dir) {
      scrub.dir = dir;
      scrub.run = 0;
    }
    let stepSeconds = SEEK_TOP_STEP;
    for (const [runs, seconds] of SEEK_RAMP) {
      if (scrub.run < runs) {
        stepSeconds = seconds;
        break;
      }
    }
    scrub.run += 1;
    scrub.target = Math.max(0, Math.min(scrub.durationMs, scrub.target + dir * stepSeconds * 1000));
    scrubPositionMs = scrub.target;
    setProgress(scrub.target, scrub.durationMs, state.bufferedPositionMs);
    send("scrubChange", scrub.target);
    // The paused frame follows the target, so you see where you are going.
    send("gamepadPreviewSeek", scrub.target);
    showPlayerToast(`${formatDelta(scrub.target - scrub.startMs)}  ·  ${formatTime(scrub.target)}`);
    noteChromeActivity(true);
    window.clearTimeout(scrub.timer);
    scrub.timer = window.setTimeout(scrubCommit, SEEK_IDLE_MS);
  };

  // A release does not commit outright: another press inside the grace joins
  // the same gesture, so a flurry of taps is still one seek.
  const scrubEnd = () => {
    if (!scrub) return;
    window.clearTimeout(scrub.timer);
    scrub.timer = window.setTimeout(scrubCommit, SEEK_TAP_MS);
  };

  const scrubCommit = () => {
    if (!scrub) return;
    const s = scrub;
    scrub = null;
    window.clearTimeout(s.timer);
    isScrubbing = false;
    state.positionMs = s.target;
    send("scrubFinish", s.target);
    if (!s.wasPaused) send("gamepadSetPaused", 0);
    render();
  };

  const playerSeek = dir => {
    const t = now();
    if (!scrub) {
      if (dir !== seekPrimeDir || t - seekPrimeAt > SEEK_GESTURE_MS) seekPrimeCount = 0;
      seekPrimeDir = dir;
      seekPrimeAt = t;
      seekPrimeCount += 1;
      if (seekPrimeCount <= SEEK_PRIME_TAPS) {
        sendKey(dir < 0 ? "ArrowLeft" : "ArrowRight", dir < 0 ? "ArrowLeft" : "ArrowRight");
        return;
      }
      if (!scrubStart()) {
        sendKey(dir < 0 ? "ArrowLeft" : "ArrowRight", dir < 0 ? "ArrowLeft" : "ArrowRight");
        return;
      }
    }
    scrubStep(dir);
  };

  // --- control-bar nav mode ------------------------------------------------
  // X puts a focus ring on the control bar and the d-pad walks it, instead of
  // seeking and changing volume. X again drops the ring.
  const setNavMode = on => {
    navMode = on;
    if (on) {
      wakeControls();
      ensureActionFocus({ focus: true });
    } else {
      document.querySelectorAll(".action-pill .action.focused")
        .forEach(button => button.classList.remove("focused"));
      focusShortcutRoot();
    }
  };

  // --- menus -----------------------------------------------------------------
  const modalElement = () => (isPartyConfirmOpen() ? partyConfirm : modalByName[activeModal]);

  const modalFocusables = modal => Array.from(modal.querySelectorAll(
    'button:not([disabled]):not([hidden]), input:not([disabled]):not([hidden]), [tabindex]:not([tabindex="-1"])',
  )).filter(el => el.offsetWidth > 0 || el.offsetHeight > 0);

  // Nearest focusable in the pressed direction, scored the way the shell's
  // spatial navigation did: distance along the axis, then overhang across it.
  const spatialMove = (modal, direction) => {
    const current = document.activeElement;
    const items = modalFocusables(modal);
    if (!items.length) return false;
    if (!current || !modal.contains(current)) {
      (items.find(el => el.classList.contains("selected")) || items[0]).focus();
      return true;
    }
    const from = current.getBoundingClientRect();
    const fromX = from.left + from.width / 2;
    const fromY = from.top + from.height / 2;
    let best = null;
    let bestScore = Infinity;
    for (const el of items) {
      if (el === current) continue;
      const r = el.getBoundingClientRect();
      const x = r.left + r.width / 2;
      const y = r.top + r.height / 2;
      let gap;
      let cross;
      if (direction === "up") { gap = from.top - r.bottom; cross = Math.abs(x - fromX); }
      else if (direction === "down") { gap = r.top - from.bottom; cross = Math.abs(x - fromX); }
      else if (direction === "left") { gap = from.left - r.right; cross = Math.abs(y - fromY); }
      else { gap = r.left - from.right; cross = Math.abs(y - fromY); }
      if (gap < -2) continue;
      const score = Math.max(0, gap) + cross * 3;
      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    }
    if (!best) return false;
    best.focus();
    best.scrollIntoView({ block: "nearest", inline: "nearest" });
    return true;
  };

  const ARROW_CODES = { UP: "ArrowUp", DOWN: "ArrowDown", LEFT: "ArrowLeft", RIGHT: "ArrowRight" };

  const menuDirection = name => {
    const modal = modalElement();
    if (!modal) return;
    // Audio and speed apply as the selection moves, and episodes/sources use
    // left/right for their filter chips: those already have arrow bindings.
    const ownArrows =
      ((activeModal === "audio" || activeModal === "speed") && (name === "UP" || name === "DOWN")) ||
      ((activeModal === "episodes" || activeModal === "sources") && (name === "LEFT" || name === "RIGHT") &&
        document.querySelector(".filter-chip:not([hidden])"));
    if (ownArrows) {
      sendKey(ARROW_CODES[name], ARROW_CODES[name]);
      return;
    }
    spatialMove(modal, name.toLowerCase());
  };

  const menuActivate = () => {
    const modal = modalElement();
    const current = document.activeElement;
    if (modal && current && modal.contains(current) && typeof current.click === "function") {
      current.click();
      return;
    }
    if (modal) spatialMove(modal, "down");
  };

  // --- bindings --------------------------------------------------------------
  const press = (name, { rb = false } = {}) => {
    held.add(name);
    document.documentElement.classList.add("gamepad-active");
    if (scrub && !(name in SEEK_BUTTONS)) scrubCommit();
    if (playbackErrorText()) {
      if (name === "B" || name === "A") sendKey("Escape", "Escape");
      return;
    }
    wakeControls();
    const menuOpen = Boolean(activeModal) || isPartyConfirmOpen();

    if (menuOpen) {
      switch (name) {
        case "UP": case "DOWN": case "LEFT": case "RIGHT": menuDirection(name); return;
        case "A": case "START": menuActivate(); return;
        case "B": case "BACK": sendKey("Escape", "Escape"); if (navMode) window.setTimeout(() => ensureActionFocus({ focus: true }), 120); return;
        case "Y": sendKey(rb ? "KeyA" : "KeyS", rb ? "a" : "s"); return;
        case "X": if (rb) sendKey("Backquote", "`"); return;
        default: return;
      }
    }

    switch (name) {
      case "UP":
        if (navMode) return;
        sendKeyboardVolume(1);
        return;
      case "DOWN":
        if (navMode) return;
        sendKeyboardVolume(-1);
        return;
      case "LEFT":
      case "RIGHT":
        if (navMode) {
          moveActionFocus(name === "LEFT" ? -1 : 1);
          return;
        }
        playerSeek(SEEK_BUTTONS[name]);
        return;
      case "LT":
      case "RT":
        playerSeek(SEEK_BUTTONS[name]);
        return;
      case "A":
        if (rb) {
          // RB+A: skip the intro/recap, or take the next-episode card.
          if (state.skipPromptVisible) send("skipInterval", 0);
          else if (state.nextEpisodeVisible && state.nextEpisodePlayable) send("playNextEpisode", 0);
          return;
        }
        if (navMode) {
          const focused = document.querySelector(".action-pill .action.focused");
          if (focused) focused.click();
          else ensureActionFocus({ focus: true });
          return;
        }
        requestPlaybackState("setPlaybackStateQuiet", true);
        return;
      case "START":
        requestPlaybackState("setPlaybackStateQuiet", true);
        return;
      case "B":
        if (navMode) {
          setNavMode(false);
          return;
        }
        sendKey("Escape", "Escape");
        return;
      case "X":
        if (rb) sendKey("Backquote", "`");
        else setNavMode(!navMode);
        return;
      case "Y":
        sendKey(rb ? "KeyA" : "KeyS", rb ? "a" : "s");
        return;
      case "BACK":
        // The episode list for a series, the source list otherwise.
        if (episodesButton && !episodesButton.hidden) sendKey("KeyE", "e");
        else sendKey("KeyQ", "q");
        return;
      case "L3":
        togglePlayerFullscreen();
        return;
      case "R3":
        sendKey("Backquote", "`");
        return;
      default:
        return;
    }
  };

  const release = name => {
    held.delete(name);
    if (name in SEEK_BUTTONS && scrub && !seekHeld()) scrubEnd();
  };

  const setActive = on => {
    document.documentElement.classList.toggle("gamepad-active", Boolean(on));
    if (!on) {
      held.clear();
      if (scrub) scrubCommit();
      if (navMode) setNavMode(false);
    }
  };

  window.nuvioGamepad = { press, release, setActive };

  // Ctrl+Q opens the power menu (sleep timer). Keys in the player reach this
  // page rather than the app window, so the shortcut is caught here too.
  document.addEventListener("keydown", event => {
    if (event.code !== "KeyQ" || !event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (!event.repeat) send("powerMenu", 0);
  }, true);
})();
