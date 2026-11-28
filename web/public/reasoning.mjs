import { efforts } from './capabilities.mjs';
import { button, el, icon } from './ui.mjs';

const animationColors = { blue: '#0285ff', purple: '#9859ef' };
const white = [255, 255, 255];

function rgb(hex) {
  return hex.slice(1).match(/../g).map(channel => parseInt(channel, 16));
}

function mix(a, b, amount) {
  return a.map((channel, i) => Math.round(channel + (b[i] - channel) * amount));
}

function color([r, g, b], alpha = 1) {
  return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
}

function clamp(value) {
  return Math.max(0, Math.min(1, value));
}

// The Android slider uses PathInterpolator(.23, 1, .32, 1) for both transitions.
function ease(progress) {
  let low = 0, high = 1;
  for (let i = 0; i < 12; i++) {
    const t = (low + high) / 2, remaining = 1 - t;
    const x = 3 * remaining ** 2 * t * .23 + 3 * remaining * t ** 2 * .32 + t ** 3;
    if (x < progress) low = t;
    else high = t;
  }
  return 1 - (1 - (low + high) / 2) ** 3;
}

function tween(initial, duration) {
  let from = initial, to = initial, start = 0;
  function value(now) {
    if (from === to || now - start >= duration) return to;
    return from + (to - from) * ease(clamp((now - start) / duration));
  }
  return {
    value,
    active(now) { return from !== to && now - start < duration; },
    move(target, now) {
      if (to === target) return;
      from = value(now);
      to = target;
      start = now;
    },
  };
}

function noise(index, salt) {
  const value = Math.imul(index + 1, 1103515245) + salt * 12345;
  return ((value ^ (value >>> 16)) & 0xffff) / 65535;
}

const particles = Array.from({ length: 14 }, (_, i) => ({
  offset: (i + noise(i, 3) * .6) / 14,
  lane: .18 + noise(i, 7) * .64,
  scale: .7 + noise(i, 11) * .7,
  speed: .8 + noise(i, 17) * .4,
  opacity: .45 + noise(i, 23) * .55,
}));

function drawParticles(ctx, left, top, width, end, position, travel) {
  const loop = width + 40;
  if (position > .5) {
    const x = left - 80 + (travel * .25) % (loop + 120);
    const shimmer = ctx.createLinearGradient(x, 0, x + 80, 0);
    shimmer.addColorStop(0, 'transparent');
    shimmer.addColorStop(.5, color(white, (position - .5) * 2 * 48 / 255));
    shimmer.addColorStop(1, 'transparent');
    ctx.fillStyle = shimmer;
    ctx.fillRect(left, top, end - left, 24);
  }
  for (const [index, particle] of particles.entries()) {
    const x = left + (particle.offset * loop + travel * particle.speed) % loop - 20;
    const edge = clamp(Math.min((x - left) / 12, (end - x) / 12));
    if (!edge) continue;
    const y = top + 24 * particle.lane + Math.sin(travel * .018 + index) * .45;
    const radius = particle.scale, alpha = 210 * particle.opacity * edge / 255, tail = 3 + position * 8;
    ctx.fillStyle = color(white, alpha / 5);
    ctx.beginPath();
    ctx.roundRect(x - tail, y - radius * .45, tail, radius * .9, radius);
    ctx.fill();
    circle(ctx, x, y, radius * 2.6, color(white, alpha / 8));
    circle(ctx, x, y, radius, color(white, alpha));
  }
}

function circle(ctx, x, y, radius, fill, strokeWidth = 0) {
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  if (strokeWidth) {
    ctx.strokeStyle = fill;
    ctx.lineWidth = strokeWidth;
    ctx.stroke();
  } else {
    ctx.fillStyle = fill;
    ctx.fill();
  }
}

function railPainter(canvas, range, mode, panel, values, lifetime) {
  const ctx = canvas.getContext('2d');
  const css = getComputedStyle(panel);
  const dark = document.documentElement.dataset.theme === 'dark';
  const page = rgb(css.getPropertyValue('--page').trim());
  const ink = rgb(css.getPropertyValue('--ink').trim());
  const muted = rgb(css.getPropertyValue('--muted').trim());
  const active = rgb(mode || css.getPropertyValue('--accent-button').trim());
  const inactive = mix(page, ink, dark ? .12 : .06);
  const remainingTick = mix(inactive, muted, .52);
  const selectedTick = mix(active, white, .38);
  const outline = mix(inactive, ink, dark ? .10 : .05);
  const firstStopDisabled = values[0] === 'none';
  const max = values.length - 1;
  const fraction = index => firstStopDisabled ? (max ? index / max : 0) : (index + 1) / (max + 1);
  const position = tween(fraction(Number(range.value)), 300);
  const expansion = tween(0, 220);
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  let state = { index: Number(range.value), automatic: true, pressed: false };
  let frame = null, previousTime, travel = 0, width = 0, height = 0, density = 1;

  if (!firstStopDisabled) range.style.setProperty('--input-offset', 'calc((100% - 36px) / ' + values.length + ')');

  function schedule() {
    if (frame === null && !document.hidden && !lifetime.aborted) frame = requestAnimationFrame(draw);
  }

  function draw(now) {
    frame = null;
    if (!width || !height || !ctx) return;
    const animate = !motion.matches;
    const progress = animate ? position.value(now) : fraction(state.index);
    const radius = 14 + 2 * (animate ? expansion.value(now) : Number(state.pressed));
    const moving = animate && !state.automatic && (!firstStopDisabled || state.index > 0);
    if (moving && previousTime !== undefined) travel += (36 + 220 * progress) * Math.min(now - previousTime, 64) / 1000;
    previousTime = moving ? now : undefined;

    ctx.setTransform(density, 0, 0, density, 0, 0);
    ctx.clearRect(0, 0, width, height);
    const left = 18, railWidth = Math.max(0, width - 36), center = height / 2, top = center - 12;
    const end = left + railWidth * progress;
    const trackLeft = 4, trackWidth = Math.max(0, width - trackLeft * 2);
    ctx.beginPath();
    ctx.roundRect(trackLeft, top, trackWidth, 24, 12);
    ctx.fillStyle = color(inactive);
    ctx.fill();
    ctx.save();
    ctx.clip();
    if (progress > 0) {
      ctx.fillStyle = color(active);
      ctx.fillRect(trackLeft, top, end - trackLeft, 24);
    }
    if (moving && end > left) {
      ctx.save();
      ctx.beginPath();
      ctx.rect(left, top, end - left, 24);
      ctx.clip();
      drawParticles(ctx, left, top, railWidth, end, progress, travel);
      ctx.restore();
    }
    for (let index = 0; index <= max; index++) {
      const location = fraction(index);
      const passed = clamp((progress - location) * (firstStopDisabled ? max : max + 1) + .5);
      const alpha = !state.automatic && animate ? 1 - passed * progress * .55 : 1;
      circle(ctx, left + railWidth * location, center, 2, color(mix(remainingTick, selectedTick, passed), alpha));
    }
    ctx.restore();
    ctx.beginPath();
    ctx.roundRect(trackLeft, top, trackWidth, 24, 12);
    ctx.strokeStyle = color(outline);
    ctx.lineWidth = .5;
    ctx.stroke();
    if (range.matches(':focus-visible')) circle(ctx, end, center, radius + 2, color(active), 2);
    circle(ctx, end, center + 1, radius, '#00000018');
    circle(ctx, end, center, radius, '#ffffff');
    circle(ctx, end, center, radius - .375, '#00000033', .75);

    if (moving || animate && (position.active(now) || expansion.active(now))) schedule();
  }

  const observer = new ResizeObserver(([entry]) => {
    width = entry.contentRect.width;
    height = entry.contentRect.height;
    density = devicePixelRatio || 1;
    canvas.width = Math.round(width * density);
    canvas.height = Math.round(height * density);
    schedule();
  });
  observer.observe(canvas);
  const listeners = { signal: lifetime };
  motion.addEventListener('change', schedule, listeners);
  document.addEventListener('visibilitychange', () => { previousTime = undefined; schedule(); }, listeners);
  range.addEventListener('focus', schedule, listeners);
  range.addEventListener('blur', schedule, listeners);
  lifetime.addEventListener('abort', () => {
    observer.disconnect();
    if (frame !== null) cancelAnimationFrame(frame);
  }, { once: true });
  return next => {
    state = next;
    const now = performance.now();
    position.move(fraction(next.index), now);
    expansion.move(Number(next.pressed), now);
    schedule();
  };
}

export function reasoningLabel(value) {
  if (value === 'default') return 'Auto';
  return value === 'xhigh' ? '极高' : efforts[value];
}

function positionPopover(panel, anchor, signal) {
  const viewport = window.visualViewport;
  const options = { signal };
  function place() {
    if (!panel.matches(':popover-open')) return;
    const rect = anchor.getBoundingClientRect();
    const width = panel.offsetWidth;
    const height = panel.offsetHeight;
    const left = (viewport?.offsetLeft ?? 0) + 12;
    const top = (viewport?.offsetTop ?? 0) + 8;
    const viewportWidth = viewport?.width ?? window.innerWidth;
    const right = left + viewportWidth - 24;
    const bottom = top + (viewport?.height ?? window.innerHeight) - 16;
    const idealX = rect.left + (rect.width - width) / 2;
    const above = rect.top - height - 8;
    const below = rect.bottom + 8;
    const roomBelowOnNarrowScreen = viewportWidth < 560 && below + height <= bottom;
    const idealY = roomBelowOnNarrowScreen || above < top ? below : above;
    panel.style.left = Math.round(Math.max(left, Math.min(right - width, idealX))) + 'px';
    panel.style.top = Math.round(Math.max(top, Math.min(bottom - height, idealY))) + 'px';
  }
  const resize = new ResizeObserver(place);
  resize.observe(panel);
  window.addEventListener('resize', place, options);
  window.addEventListener('scroll', place, { ...options, capture: true });
  viewport?.addEventListener('resize', place, options);
  viewport?.addEventListener('scroll', place, options);
  const frame = requestAnimationFrame(place);
  signal.addEventListener('abort', () => {
    resize.disconnect();
    cancelAnimationFrame(frame);
  }, { once: true });
}

export function renderReasoningPopover({ panel, anchor, model, caps, initial, options, apply, chooseModel, signal }) {
  const listeners = { signal };
  const manual = caps?.levels.filter(level => level !== 'default') ?? [];
  const customColor = options.reasoningCustomColor;
  const choice = options.reasoningColorMode;
  const activeColor = choice === 'custom' && /^#[a-f0-9]{6}$/i.test(customColor) ? customColor : animationColors[choice];
  const header = el('div', undefined, 'reasoning-popover-head');
  const symbol = el('span', undefined, 'reasoning-symbol');
  symbol.innerHTML = icon('reasoning');
  const labels = el('div', undefined, 'reasoning-labels');
  const current = el('strong', reasoningLabel(initial), 'reasoning-value');
  const modelButton = button('', '', undefined, 'reasoning-model');
  modelButton.append(el('span', model || '选择模型'));
  modelButton.insertAdjacentHTML('beforeend', icon('chevron'));
  modelButton.title = model ? '切换模型 · ' + model : '选择模型';
  modelButton.addEventListener('click', () => {
    panel.hidePopover();
    chooseModel();
  }, listeners);
  labels.append(current, modelButton);

  const reset = button('', '', undefined, 'reasoning-reset', 'cycle');
  reset.ariaLabel = reset.title = '恢复自动思考强度';
  header.append(symbol, labels, reset);
  panel.replaceChildren(header);
  positionPopover(panel, anchor, signal);

  if (!manual.length) {
    current.textContent = 'Auto';
    reset.disabled = true;
    if (model) panel.append(el('p', '当前模型使用默认思考强度', 'reasoning-fixed'));
    return;
  }

  const rail = el('div', undefined, 'reasoning-rail');
  const canvas = el('canvas');
  canvas.setAttribute('aria-hidden', 'true');
  const range = el('input');
  range.type = 'range';
  range.min = '0';
  range.max = String(manual.length - 1);
  range.step = '1';
  range.value = String(Math.max(0, manual.indexOf(initial)));
  range.setAttribute('aria-label', '思考强度滑动条');
  range.autofocus = anchor.matches(':focus-visible');
  rail.append(canvas, range);
  panel.append(rail);

  const paint = railPainter(canvas, range, activeColor, panel, manual, signal);
  let pressed = false, automatic = !manual.includes(initial);
  function render() {
    const index = Number(range.value);
    const level = reasoningLabel(manual[index]);
    current.textContent = automatic ? 'Auto' : level;
    range.setAttribute('aria-valuetext', automatic ? '模型默认' : level + '，第 ' + (index + 1) + ' 档，共 ' + manual.length + ' 档');
    reset.disabled = automatic;
    rail.classList.toggle('is-auto', automatic);
    paint({ index, automatic, pressed });
  }

  range.addEventListener('keydown', event => { if (event.key === 'Enter') event.preventDefault(); }, listeners);
  range.addEventListener('input', () => {
    automatic = false;
    apply(manual[Number(range.value)]);
    render();
  }, listeners);
  range.addEventListener('pointerdown', () => {
    pressed = true;
    render();
  }, listeners);
  const stopPress = () => { if (pressed) { pressed = false; render(); } };
  window.addEventListener('pointerup', stopPress, listeners);
  window.addEventListener('pointercancel', stopPress, listeners);
  // Activating the position already under the thumb may not dispatch an input event.
  range.addEventListener('click', () => {
    if (!automatic) return;
    automatic = false;
    apply(manual[Number(range.value)]);
    render();
  }, listeners);
  reset.addEventListener('click', () => {
    automatic = true;
    apply('default');
    range.focus();
    render();
  }, listeners);
  render();
}
