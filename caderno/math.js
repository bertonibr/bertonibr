'use strict';

/* =========================================================
   Cálculo de contas escritas à mão — 100% offline.
   - Separa a escrita selecionada (laço) em símbolos.
   - Reconhece cada símbolo com o algoritmo $P (nuvem de pontos),
     comparando com modelos da própria letra do usuário
     (tela "Ensinar minha letra") ou modelos padrão.
   - Calcula e escreve o resultado ao lado, no estilo da letra.
   Usa estado e funções de app.js (ssel, S, toast, commitChange…).
   ========================================================= */

const MathInk = (() => {
  const KEY = 'caderno.ink';
  const TRAIN = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '×', '(', ')'];
  const N = 32;

  let samples = {};
  try { samples = JSON.parse(localStorage.getItem(KEY) || '{}').samples || {}; } catch (_) {}
  const saveSamples = () => { try { localStorage.setItem(KEY, JSON.stringify({ samples })); } catch (_) {} };
  const trained = () => Object.keys(samples).length > 0;

  /* ---------- Modelos padrão (unidade: altura 1, y para baixo) ---------- */
  function arc(cx, cy, rx, ry, a0, a1, steps = 18) {
    const out = [];
    for (let i = 0; i <= steps; i++) {
      const a = (a0 + (a1 - a0) * i / steps) * Math.PI / 180;
      out.push([cx + rx * Math.cos(a), cy + ry * Math.sin(a)]);
    }
    return out;
  }
  const G = {
    '0': [[arc(0.3, 0.5, 0.3, 0.5, -90, 270, 28)]],
    '1': [[[[0.12, 0.18], [0.3, 0], [0.3, 1]]], [[[0.3, 0], [0.3, 1]]]],
    '2': [[[...arc(0.3, 0.3, 0.3, 0.3, 190, 400), [0, 1], [0.65, 1]]]],
    '3': [[[...arc(0.3, 0.25, 0.28, 0.25, 200, 450), ...arc(0.3, 0.75, 0.3, 0.25, 270, 520)]]],
    '4': [[[[0.5, 0], [0, 0.65], [0.7, 0.65]], [[0.5, 0.3], [0.5, 1]]],
          [[[0.05, 0], [0.05, 0.6], [0.7, 0.6]], [[0.55, 0], [0.55, 1]]]],
    '5': [[[[0.55, 0], [0.1, 0], [0.06, 0.45], ...arc(0.3, 0.7, 0.3, 0.3, 235, 500)]],
          [[[0.1, 0], [0.06, 0.45], ...arc(0.3, 0.7, 0.3, 0.3, 235, 500)], [[0.1, 0], [0.6, 0]]]],
    '6': [[[[0.55, 0.02], [0.15, 0.4], ...arc(0.3, 0.7, 0.3, 0.3, 180, 540)]]],
    '7': [[[[0, 0], [0.6, 0], [0.2, 1]]], [[[0, 0], [0.6, 0], [0.2, 1]], [[0.12, 0.5], [0.52, 0.5]]]],
    '8': [[[...arc(0.3, 0.25, 0.24, 0.25, 90, 450), ...arc(0.3, 0.73, 0.3, 0.27, 270, 630)]]],
    '9': [[[...arc(0.3, 0.28, 0.3, 0.28, 0, 360), [0.6, 0.28], [0.55, 1]]]],
    '+': [[[[0, 0.5], [0.8, 0.5]], [[0.4, 0.1], [0.4, 0.9]]]],
    '×': [[[[0, 0.2], [0.6, 0.8]], [[0.6, 0.2], [0, 0.8]]]],
    '(': [[arc(0.42, 0.5, 0.38, 0.55, 300, 60)]],
    '/': [[[[0.5, 0], [0, 1]]]],
  };
  // ")" é o espelho de "("
  G[')'] = [[G['('][0][0].map(([x, y]) => [0.8 - x, y])]];

  /* ---------- $P point-cloud recognizer ---------- */
  function cloud(strokes) {
    // strokes: [[ [x,y], ... ], ...] -> nuvem de N pontos normalizada
    const flat = [];
    strokes.filter((s) => s.length).forEach((s, id) => s.forEach(([x, y]) => flat.push({ x, y, id })));
    let pts = resample(flat, N);
    // escala uniforme + centróide
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const p of pts) { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); }
    const sc = Math.max(x1 - x0, y1 - y0) || 1;
    pts = pts.map((p) => ({ x: (p.x - x0) / sc, y: (p.y - y0) / sc, id: p.id }));
    let cx = 0, cy = 0;
    for (const p of pts) { cx += p.x; cy += p.y; }
    cx /= pts.length; cy /= pts.length;
    return pts.map((p) => ({ x: p.x - cx, y: p.y - cy, id: p.id }));
  }

  function pathLen(pts) {
    let d = 0;
    for (let i = 1; i < pts.length; i++) if (pts[i].id === pts[i - 1].id) d += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    return d;
  }

  function resample(pts, n) {
    const L = pathLen(pts);
    if (L === 0) return Array.from({ length: n }, () => ({ ...pts[0] }));
    const I = L / (n - 1);
    let D = 0;
    const out = [{ ...pts[0] }];
    const p = pts.map((q) => ({ ...q }));
    for (let i = 1; i < p.length; i++) {
      if (p[i].id !== p[i - 1].id) continue;
      const d = Math.hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y);
      if (D + d >= I) {
        const t = (I - D) / d;
        const q = { x: p[i - 1].x + t * (p[i].x - p[i - 1].x), y: p[i - 1].y + t * (p[i].y - p[i - 1].y), id: p[i].id };
        out.push(q);
        p.splice(i, 0, q);
        D = 0;
      } else D += d;
    }
    while (out.length < n) out.push({ ...p[p.length - 1] });
    return out.slice(0, n);
  }

  function cloudDist(a, b, start) {
    const n = a.length, matched = new Array(n).fill(false);
    let sum = 0, i = start;
    do {
      let min = Infinity, idx = -1;
      for (let j = 0; j < n; j++) {
        if (matched[j]) continue;
        const d = (a[i].x - b[j].x) ** 2 + (a[i].y - b[j].y) ** 2;
        if (d < min) { min = d; idx = j; }
      }
      matched[idx] = true;
      const w = 1 - ((i - start + n) % n) / n;
      sum += w * Math.sqrt(min);
      i = (i + 1) % n;
    } while (i !== start);
    return sum;
  }

  function match(a, b) {
    const step = Math.floor(Math.sqrt(N));
    let min = Infinity;
    for (let i = 0; i < N; i += step) min = Math.min(min, cloudDist(a, b, i), cloudDist(b, a, i));
    return min;
  }

  let templates = null;
  function buildTemplates() {
    templates = [];
    const labels = new Set([...Object.keys(G), ...Object.keys(samples)]);
    for (const lab of labels) {
      const own = samples[lab];
      const list = own && own.length ? own : G[lab] || [];
      for (const strokes of list) templates.push({ lab, pts: cloud(strokes) });
    }
  }

  function classify(strokes) {
    if (!templates) buildTemplates();
    const c = cloud(strokes);
    let best = null, bd = Infinity;
    for (const t of templates) {
      const d = match(c, t.pts);
      if (d < bd) { bd = d; best = t.lab; }
    }
    return best;
  }

  /* ---------- Segmentação ---------- */
  function boxOf(list) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const s of list) {
      const p = s.pts;
      for (let i = 0; i < p.length; i += 3) {
        x0 = Math.min(x0, p[i]); x1 = Math.max(x1, p[i]);
        y0 = Math.min(y0, p[i + 1]); y1 = Math.max(y1, p[i + 1]);
      }
    }
    return { x0, y0, x1, y1, w: x1 - x0, h: y1 - y0, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
  }

  function median(a) { const s = a.slice().sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; }

  function groupStrokes(strokes) {
    const items = strokes.map((s) => ({ strokes: [s], b: boxOf([s]) })).sort((a, b) => a.b.x0 - b.b.x0);
    const groups = [];
    for (const it of items) {
      // junta com um grupo existente se os centros caírem dentro um do outro (mesmo símbolo)
      const g = groups.find((g) => {
        const vert = it.b.y0 <= g.b.y1 + 20 && it.b.y1 >= g.b.y0 - 20;
        return vert && ((it.b.cx >= g.b.x0 && it.b.cx <= g.b.x1) || (g.b.cx >= it.b.x0 && g.b.cx <= it.b.x1));
      });
      if (g) { g.strokes.push(...it.strokes); g.b = boxOf(g.strokes); } else groups.push({ strokes: [...it.strokes], b: it.b });
    }
    return groups;
  }

  function rowsOf(groups) {
    const hs = groups.map((g) => g.b.h);
    const maxH = Math.max(...hs, 1);
    const H = median(hs.filter((h) => h > 0.4 * maxH)) || maxH;
    const sorted = groups.slice().sort((a, b) => a.b.cy - b.b.cy);
    const rows = [];
    for (const g of sorted) {
      const r = rows.find((r) => Math.abs(r.cy - g.b.cy) < 0.7 * H);
      if (r) { r.groups.push(g); r.cy = median(r.groups.map((x) => x.b.cy)); } else rows.push({ groups: [g], cy: g.b.cy });
    }
    for (const r of rows) r.groups.sort((a, b) => a.b.x0 - b.b.x0);
    return { rows, H };
  }

  function symbolOf(g, H, row) {
    const parts = g.strokes.map((s) => boxOf([s]));
    const tiny = (b) => Math.max(b.w, b.h) < 0.22 * H;
    const flat = (b) => !tiny(b) && b.h < Math.max(0.3 * b.w, 0.15 * H);
    if (parts.every((b) => tiny(b) || flat(b))) {
      const f = parts.filter(flat).length, t = parts.filter(tiny).length;
      if (f === 1 && t >= 1) return '÷';
      if (f === 2 && t === 0) return '=';
      if (f === 1 && t === 0) return '-';
      if (f === 0) {
        const tall = row.groups.filter((x) => x.b.h > 0.5 * H);
        const base = tall.length ? median(tall.map((x) => x.b.y1)) : row.cy + H / 2;
        return g.b.cy > base - 0.3 * H ? ',' : '×'; // ponto embaixo = vírgula; no meio = vezes
      }
    }
    const raw = g.strokes.map((s) => { const a = []; for (let i = 0; i < s.pts.length; i += 3) a.push([s.pts[i], s.pts[i + 1]]); return a; });
    return classify(raw);
  }

  /* ---------- Conta ---------- */
  function evaluate(text) {
    const src = text.replace(/\s+/g, '').replace(/[x*X]/g, '×').replace(/[:]/g, '÷').replace(/\./g, ',');
    let i = 0;
    const peek = () => src[i];
    function num() {
      let s = '';
      while (i < src.length && /[0-9,]/.test(src[i])) s += src[i++];
      if (!s || (s.match(/,/g) || []).length > 1) throw new Error('número');
      return parseFloat(s.replace(',', '.'));
    }
    function factor() {
      if (peek() === '-') { i++; return -factor(); }
      if (peek() === '+') { i++; return factor(); }
      if (peek() === '(') {
        i++; const v = expr();
        if (peek() !== ')') throw new Error(')'); i++;
        return implicit(v);
      }
      return implicit(num());
    }
    function implicit(v) { return peek() === '(' ? v * factor() : v; } // 2(3+1)
    function term() {
      let v = factor();
      while ('×÷/'.includes(peek() || '_')) {
        const op = src[i++], r = factor();
        if (op === '×') v *= r; else { if (r === 0) throw new Error('divisão por zero'); v /= r; }
      }
      return v;
    }
    function expr() {
      let v = term();
      while (peek() === '+' || peek() === '-') { const op = src[i++]; const r = term(); v = op === '+' ? v + r : v - r; }
      return v;
    }
    const v = expr();
    if (i !== src.length) throw new Error('sobrou');
    if (!isFinite(v)) throw new Error('inválido');
    return v;
  }

  function fmt(v) {
    const r = Math.round(v * 1000) / 1000;
    return r.toLocaleString('pt-BR', { maximumFractionDigits: 3, useGrouping: false });
  }

  /* ---------- Escrever o resultado com a letra do usuário ---------- */
  function glyph(ch) {
    const own = samples[ch] && samples[ch][0];
    if (own) return own;
    if (ch === '=') return [[[0, 0.38], [0.5, 0.38]], [[0, 0.62], [0.5, 0.62]]];
    if (ch === '-') return [[[0, 0.5], [0.45, 0.5]]];
    if (ch === ',') return [[[0.1, 0.85], [0.08, 0.95], [0, 1.12]]];
    const g = G[ch];
    return g ? g[0] : [];
  }

  function writeText(text, x, baseY, H, color, size) {
    const out = [];
    let cx = x;
    for (const ch of text) {
      if (ch === ' ') { cx += 0.35 * H; continue; }
      const strokes = glyph(ch);
      let w = 0;
      for (const s of strokes) for (const [px] of s) w = Math.max(w, px);
      for (const s of strokes) {
        const pts = [];
        for (const [px, py] of s) pts.push(Math.round((cx + px * H) * 10) / 10, Math.round((baseY - H + py * H) * 10) / 10, 0.5);
        out.push({ id: Math.random().toString(36).slice(2, 10), tool: 'pen', color, size, pts });
      }
      cx += (w + 0.18) * H;
    }
    return out;
  }

  /* ---------- Fluxo "Calcular" ---------- */
  let pending = null;

  function calc() {
    if (!ssel) return;
    const groups = groupStrokes(ssel.strokes);
    if (!groups.length) return;
    const { rows, H } = rowsOf(groups);
    const row = rows.sort((a, b) => b.groups.length - a.groups.length)[0]; // linha principal
    const syms = row.groups.map((g) => symbolOf(g, H, row));
    let expr = syms.join('');
    const hasEq = expr.includes('=');
    if (hasEq) expr = expr.slice(0, expr.indexOf('='));
    const box = boxOf(row.groups.flatMap((g) => g.strokes));
    const tall = row.groups.filter((g) => g.b.h > 0.5 * H);
    const base = tall.length ? median(tall.map((g) => g.b.y1)) : box.y1;
    const endsWithEq = syms[syms.length - 1] === '=';
    const first = ssel.strokes[0];
    pending = {
      v: ssel.v, expr, H, base, x: box.x1 + 0.35 * H, endsWithEq,
      color: first.color, size: median(ssel.strokes.map((s) => s.size)),
    };
    showResult();
  }

  function pretty(e) { return e.replace(/×/g, ' × ').replace(/÷/g, ' ÷ ').replace(/\//g, ' / ').replace(/\+/g, ' + ').replace(/(\d)-/g, '$1 - '); }

  function showResult() {
    const bar = $('#calcBar');
    let msg, ok = false;
    try {
      pending.value = evaluate(pending.expr);
      msg = `${pretty(pending.expr)} = <b>${fmt(pending.value)}</b>`;
      ok = true;
    } catch (_) {
      msg = `Não entendi: <b>${pretty(pending.expr) || '—'}</b>`;
    }
    bar.querySelector('.calc-text').innerHTML = msg;
    bar.querySelector('[data-c=ins]').hidden = !ok;
    bar.hidden = false;
    $('#strokeBar').hidden = true;
  }

  function closeBar() {
    $('#calcBar').hidden = true;
    pending = null;
    if (ssel) $('#strokeBar').hidden = false;
  }

  function insert() {
    if (!pending || pending.value === undefined) return;
    const p = pending, page = p.v.page;
    const text = (p.endsWithEq ? '' : '= ') + fmt(p.value);
    const strokes = writeText(text, p.x, p.base, p.H, p.color, p.size);
    const before = page.strokes.slice();
    page.strokes.push(...strokes);
    commitChange(page, before);
    closeBar();
    selectStrokes(p.v, strokes);
    if (!trained()) toast('Dica: ⋯ → Ensinar minha letra deixa o cálculo mais preciso', 4000);
  }

  function correct() {
    if (!pending) return;
    const t = prompt('Corrija a conta (use + - x / ( ) e vírgula):', pretty(pending.expr).replace(/\s+/g, ' ').trim());
    if (t === null) return;
    pending.expr = t.replace(/\s+/g, '').replace(/[x*X]/g, '×').replace(/:/g, '÷').replace(/=.*$/, '');
    showResult();
  }

  /* ---------- Tela "Ensinar minha letra" ---------- */
  function train() {
    const PER = 2;
    const wrap = document.createElement('div');
    wrap.className = 'train';
    wrap.innerHTML = `
      <div class="train-box">
        <h2>Ensinar minha letra</h2>
        <p>Escreva cada símbolo nos quadrinhos, do jeito que você escreve normalmente. Toque em ↺ para apagar um quadrinho.</p>
        <div class="train-grid"></div>
        <div class="dlg-actions">
          <button class="btn" data-t="reset">Voltar ao padrão</button>
          <span style="flex:1"></span>
          <button class="btn" data-t="cancel">Cancelar</button>
          <button class="btn primary" data-t="save">Salvar</button>
        </div>
      </div>`;
    document.body.appendChild(wrap);
    const grid = wrap.querySelector('.train-grid');
    const cells = [];
    for (const lab of TRAIN) {
      for (let k = 0; k < PER; k++) {
        const cell = document.createElement('div');
        cell.className = 'train-cell';
        cell.innerHTML = `<span class="lab">${lab}</span><button class="clr" title="Apagar">↺</button><canvas></canvas>`;
        grid.appendChild(cell);
        const c = cell.querySelector('canvas');
        const obj = { lab, strokes: [], c, cur: null };
        cells.push(obj);
        const draw = () => {
          const ctx = c.getContext('2d');
          const dpr = window.devicePixelRatio || 1;
          if (c.width !== c.clientWidth * dpr) { c.width = c.clientWidth * dpr; c.height = c.clientHeight * dpr; }
          ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
          ctx.clearRect(0, 0, c.width, c.height);
          ctx.strokeStyle = '#1a1a1a'; ctx.lineWidth = 2.5; ctx.lineCap = ctx.lineJoin = 'round';
          for (const s of obj.strokes) {
            ctx.beginPath();
            s.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
            if (s.length === 1) ctx.lineTo(s[0][0] + 0.1, s[0][1]);
            ctx.stroke();
          }
        };
        obj.draw = draw;
        cell.querySelector('.clr').onclick = () => { obj.strokes = []; draw(); };
        c.addEventListener('pointerdown', (e) => {
          if (e.pointerType === 'touch' && penOnly()) return;
          e.preventDefault();
          c.setPointerCapture(e.pointerId);
          const r = c.getBoundingClientRect();
          obj.cur = { id: e.pointerId, s: [[e.clientX - r.left, e.clientY - r.top]] };
          obj.strokes.push(obj.cur.s);
          draw();
        });
        c.addEventListener('pointermove', (e) => {
          if (!obj.cur || obj.cur.id !== e.pointerId) return;
          const r = c.getBoundingClientRect();
          const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
          for (const ev of evs.length ? evs : [e]) obj.cur.s.push([ev.clientX - r.left, ev.clientY - r.top]);
          draw();
        });
        const end = (e) => { if (obj.cur && obj.cur.id === e.pointerId) obj.cur = null; };
        c.addEventListener('pointerup', end);
        c.addEventListener('pointercancel', end);
        requestAnimationFrame(draw);
      }
    }
    const close = () => wrap.remove();
    wrap.querySelector('[data-t=cancel]').onclick = close;
    wrap.querySelector('[data-t=reset]').onclick = () => {
      if (!confirm('Apagar a letra ensinada e voltar aos modelos padrão?')) return;
      samples = {}; saveSamples(); templates = null; close(); toast('Modelos padrão restaurados');
    };
    wrap.querySelector('[data-t=save]').onclick = () => {
      const fresh = {};
      for (const o of cells) {
        if (!o.strokes.length) continue;
        // normaliza para altura 1 com origem no canto do símbolo
        let x0 = Infinity, y0 = Infinity, y1 = -Infinity;
        for (const s of o.strokes) for (const [x, y] of s) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
        const h = Math.max(y1 - y0, 1);
        const norm = o.strokes.map((s) => s.map(([x, y]) => [Math.round((x - x0) / h * 1000) / 1000, Math.round((y - y0) / h * 1000) / 1000]));
        (fresh[o.lab] = fresh[o.lab] || []).push(norm);
      }
      const n = Object.keys(fresh).length;
      if (!n) { toast('Escreva pelo menos um símbolo'); return; }
      Object.assign(samples, fresh);
      saveSamples();
      templates = null;
      close();
      toast(`Letra salva (${n} símbolos) ✓`);
    };
  }

  /* ---------- Botões ---------- */
  document.addEventListener('DOMContentLoaded', bind);
  if (document.readyState !== 'loading') bind();
  let bound = false;
  function bind() {
    if (bound) return; bound = true;
    const b = $('#calcBar');
    b.querySelector('[data-c=ins]').onclick = insert;
    b.querySelector('[data-c=fix]').onclick = correct;
    b.querySelector('[data-c=x]').onclick = closeBar;
    $('#strokeBar [data-act=calc]').onclick = calc;
  }

  return { calc, train, closeBar, evaluate, _classify: classify, _group: groupStrokes };
})();
