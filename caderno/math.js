'use strict';

/* =========================================================
   Cálculo de contas escritas à mão — 100% offline.
   1. Separa a escrita selecionada (laço) em símbolos, usando a
      posição e a ordem em que os traços foram feitos.
   2. Sinais (+ − × ÷ = , / parênteses) são reconhecidos por regras
      de forma (retas, cruzamentos, pontos, curvas).
   3. Números passam por uma rede neural pequena (CNN treinada com
      70 mil dígitos manuscritos, incluindo 7 cortado e 1 com
      "chapéu"), rodando no próprio aparelho (model/digits.bin).
   4. "Ensinar minha letra" guarda exemplos do usuário, que ajustam
      a decisão da rede (vizinho mais próximo nas características).
   Usa estado e funções de app.js (ssel, toast, commitChange…).
   ========================================================= */

const MathInk = (() => {
  const KEY = 'caderno.ink';
  const DIGITS = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
  const PER = 3; // exemplos por dígito na tela de treino

  let samples = {};
  try { samples = JSON.parse(localStorage.getItem(KEY) || '{}').samples || {}; } catch (_) {}
  // descarta modelos antigos de sinais (agora reconhecidos por regras)
  for (const k of Object.keys(samples)) if (!DIGITS.includes(k)) delete samples[k];
  const saveSamples = () => { try { localStorage.setItem(KEY, JSON.stringify({ samples })); } catch (_) {} };
  const trained = () => Object.keys(samples).length > 0;

  /* =========================================================
     Rede neural (CNN) — inferência em JavaScript puro
     ========================================================= */
  const SHAPES = [
    [3, 3, 1, 16], [16], [3, 3, 16, 16], [16], [3, 3, 16, 32], [32], [3, 3, 32, 32], [32],
    [512, 64], [64], [64, 10], [10],
  ];
  let W = null, loading = null;

  function loadModel() {
    if (W) return Promise.resolve(W);
    if (!loading) {
      loading = fetch('model/digits.bin').then((r) => {
        if (!r.ok) throw new Error('modelo ' + r.status);
        return r.arrayBuffer();
      }).then((buf) => {
        const all = new Float32Array(buf);
        let o = 0;
        W = SHAPES.map((s) => { const n = s.reduce((a, b) => a * b, 1); const a = all.subarray(o, o + n); o += n; return a; });
        return W;
      }).catch((e) => { loading = null; throw e; });
    }
    return loading;
  }

  // conv 3x3 "valid" + ReLU. x: H×W×C (HWC), k: 3×3×C×O
  function conv(x, H, Wd, C, k, b, O) {
    const oh = H - 2, ow = Wd - 2, out = new Float32Array(oh * ow * O);
    for (let i = 0; i < oh; i++) for (let j = 0; j < ow; j++) {
      const ob = (i * ow + j) * O;
      for (let o = 0; o < O; o++) out[ob + o] = b[o];
      for (let ki = 0; ki < 3; ki++) for (let kj = 0; kj < 3; kj++) {
        const xb = ((i + ki) * Wd + (j + kj)) * C, kb = (ki * 3 + kj) * C * O;
        for (let c = 0; c < C; c++) {
          const v = x[xb + c];
          if (v === 0) continue;
          const kc = kb + c * O;
          for (let o = 0; o < O; o++) out[ob + o] += v * k[kc + o];
        }
      }
      for (let o = 0; o < O; o++) if (out[ob + o] < 0) out[ob + o] = 0;
    }
    return out;
  }

  function pool(x, H, Wd, C) {
    const oh = H >> 1, ow = Wd >> 1, out = new Float32Array(oh * ow * C);
    for (let i = 0; i < oh; i++) for (let j = 0; j < ow; j++) for (let c = 0; c < C; c++) {
      const a = x[((2 * i) * Wd + 2 * j) * C + c], b = x[((2 * i) * Wd + 2 * j + 1) * C + c];
      const d = x[((2 * i + 1) * Wd + 2 * j) * C + c], e = x[((2 * i + 1) * Wd + 2 * j + 1) * C + c];
      out[(i * ow + j) * C + c] = Math.max(a, b, d, e);
    }
    return out;
  }

  function dense(x, w, b, nOut, relu) {
    const out = new Float32Array(nOut);
    for (let j = 0; j < nOut; j++) out[j] = b[j];
    for (let i = 0; i < x.length; i++) {
      const v = x[i];
      if (v === 0) continue;
      const r = i * nOut;
      for (let j = 0; j < nOut; j++) out[j] += v * w[r + j];
    }
    if (relu) for (let j = 0; j < nOut; j++) if (out[j] < 0) out[j] = 0;
    return out;
  }

  function forward(img) {
    let h = conv(img, 28, 28, 1, W[0], W[1], 16);
    h = conv(h, 26, 26, 16, W[2], W[3], 16);
    h = pool(h, 24, 24, 16);
    h = conv(h, 12, 12, 16, W[4], W[5], 32);
    h = conv(h, 10, 10, 32, W[6], W[7], 32);
    h = pool(h, 8, 8, 32);
    const emb = dense(h, W[8], W[9], 64, true);
    const z = dense(emb, W[10], W[11], 10, false);
    const m = Math.max(...z);
    let s = 0;
    const p = Array.from(z, (v) => { const e = Math.exp(v - m); s += e; return e; }).map((e) => e / s);
    return { p, emb };
  }

  /* Rasteriza traços no padrão MNIST: maior lado = 20px, centro de massa no meio de 28×28 */
  const big = document.createElement('canvas'); big.width = big.height = 112;
  const small = document.createElement('canvas'); small.width = small.height = 28;
  function raster(strokes) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const s of strokes) for (const [x, y] of s) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    const sc = 80 / Math.max(x1 - x0, y1 - y0, 1e-3); // 20px × 4
    const bx = big.getContext('2d');
    bx.setTransform(1, 0, 0, 1, 0, 0);
    bx.fillStyle = '#000'; bx.fillRect(0, 0, 112, 112);
    bx.setTransform(sc, 0, 0, sc, 56 - (x0 + x1) / 2 * sc, 56 - (y0 + y1) / 2 * sc);
    bx.strokeStyle = bx.fillStyle = '#fff';
    bx.lineWidth = 10.5 / sc; bx.lineCap = bx.lineJoin = 'round';
    for (const s of strokes) {
      bx.beginPath();
      s.forEach(([x, y], i) => (i ? bx.lineTo(x, y) : bx.moveTo(x, y)));
      if (s.length === 1) bx.lineTo(s[0][0] + 0.01, s[0][1]);
      bx.stroke();
    }
    const sx = small.getContext('2d', { willReadFrequently: true });
    sx.imageSmoothingEnabled = true; sx.imageSmoothingQuality = 'high';
    const draw = (dx, dy) => {
      sx.setTransform(1, 0, 0, 1, 0, 0);
      sx.fillStyle = '#000'; sx.fillRect(0, 0, 28, 28);
      sx.drawImage(big, dx, dy, 28, 28);
      return sx.getImageData(0, 0, 28, 28).data;
    };
    let d = draw(0, 0), m = 0, mx = 0, my = 0;
    for (let i = 0; i < 784; i++) { const v = d[i * 4]; m += v; mx += v * (i % 28); my += v * Math.floor(i / 28); }
    if (m > 0) d = draw(13.5 - mx / m, 13.5 - my / m);
    const img = new Float32Array(784);
    for (let i = 0; i < 784; i++) img[i] = d[i * 4] / 255;
    return img;
  }

  /* Exemplos do usuário: características (embedding) calculadas uma vez */
  let userEmb = null;
  function buildUserEmb() {
    userEmb = [];
    for (const lab of Object.keys(samples)) for (const st of samples[lab]) {
      const { emb } = forward(raster(st));
      userEmb.push({ lab, v: normalize(emb) });
    }
  }
  function normalize(v) { let n = 0; for (const x of v) n += x * x; n = Math.sqrt(n) || 1; return Array.from(v, (x) => x / n); }

  function classifyDigit(strokes) {
    const { p, emb } = forward(raster(strokes));
    const score = p.map((x) => Math.log(x + 1e-6));
    if (trained()) {
      if (!userEmb) buildUserEmb();
      const e = normalize(emb), best = new Array(10).fill(0);
      for (const u of userEmb) {
        let s = 0;
        for (let i = 0; i < e.length; i++) s += e[i] * u.v[i];
        const d = +u.lab;
        if (s > best[d]) best[d] = s;
      }
      // bônus para o dígito cujo exemplo do usuário é parecido
      for (let d = 0; d < 10; d++) score[d] += 12 * Math.max(0, best[d] - 0.75);
    }
    let bi = 0;
    for (let d = 1; d < 10; d++) if (score[d] > score[bi]) bi = d;
    return String(bi);
  }

  /* =========================================================
     Geometria dos traços
     ========================================================= */
  const ptsOf = (s) => { const a = []; for (let i = 0; i < s.pts.length; i += 3) a.push([s.pts[i], s.pts[i + 1]]); return a; };

  function boxPts(list) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const p of list) for (const [x, y] of p) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    return { x0, y0, x1, y1, w: x1 - x0, h: y1 - y0, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
  }

  function info(p) {
    let len = 0;
    for (let i = 1; i < p.length; i++) len += Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
    const a = p[0], z = p[p.length - 1];
    const chord = Math.hypot(z[0] - a[0], z[1] - a[1]);
    const ang = Math.abs(Math.atan2(z[1] - a[1], z[0] - a[0]) * 180 / Math.PI); // 0..180
    const horiz = Math.min(ang, 180 - ang);            // 0 = horizontal, 90 = vertical
    const slope = (z[0] - a[0]) * (z[1] - a[1]);       // >0 "\", <0 "/"
    return { len, chord, straight: len > 0 ? chord / len : 1, horiz, slope, a, z, b: boxPts([p]) };
  }

  // mesmo que info(), mas sem os 15% iniciais e finais do traço (ganchos da caneta)
  function core(p) {
    const L = info(p).len;
    if (L === 0 || p.length < 2) return info(p);
    const at = (target) => { // ponto exato a uma distância ao longo do traço
      let acc = 0;
      for (let i = 1; i < p.length; i++) {
        const d = Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
        if (acc + d >= target && d > 0) { const t = (target - acc) / d; return [p[i - 1][0] + (p[i][0] - p[i - 1][0]) * t, p[i - 1][1] + (p[i][1] - p[i - 1][1]) * t]; }
        acc += d;
      }
      return p[p.length - 1];
    };
    const out = [];
    for (let k = 0; k <= 12; k++) out.push(at(L * (0.15 + 0.7 * k / 12)));
    return info(out);
  }

  // reamostra por comprimento e mede a maior curva (ângulo) entre trechos
  function maxTurn(p) {
    const n = 8, L = info(p).len;
    if (L === 0) return 0;
    const step = L / n, r = [p[0]];
    let acc = 0;
    for (let i = 1; i < p.length; i++) {
      let [ax, ay] = p[i - 1];
      const [bx, by] = p[i];
      let d = Math.hypot(bx - ax, by - ay);
      while (acc + d >= step && d > 0) {
        const t = (step - acc) / d;
        ax += (bx - ax) * t; ay += (by - ay) * t;
        r.push([ax, ay]);
        d = Math.hypot(bx - ax, by - ay); acc = 0;
      }
      acc += d;
    }
    let mt = 0;
    for (let i = 2; i < r.length; i++) {
      const a1 = Math.atan2(r[i - 1][1] - r[i - 2][1], r[i - 1][0] - r[i - 2][0]);
      const a2 = Math.atan2(r[i][1] - r[i - 1][1], r[i][0] - r[i - 1][0]);
      let d = Math.abs(a2 - a1) * 180 / Math.PI;
      if (d > 180) d = 360 - d;
      mt = Math.max(mt, d);
    }
    return mt;
  }

  // ponto na metade do comprimento e seu desvio em relação à corda
  function midBulge(p) {
    const { len, a, z } = info(p);
    let acc = 0, m = p[0];
    for (let i = 1; i < p.length; i++) {
      acc += Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
      if (acc >= len / 2) { m = p[i]; break; }
    }
    const dx = z[0] - a[0], dy = z[1] - a[1], L = Math.hypot(dx, dy) || 1;
    return ((m[0] - a[0]) * dy - (m[1] - a[1]) * dx) / L; // distância com sinal
  }

  function segCross(p, q, tol) {
    const [a, b] = [p.a, p.z], [c, d] = [q.a, q.z];
    const ext = (u, v) => { const dx = v[0] - u[0], dy = v[1] - u[1]; return [[u[0] - dx * tol, u[1] - dy * tol], [v[0] + dx * tol, v[1] + dy * tol]]; };
    const [A, B] = ext(a, b), [C, D] = ext(c, d);
    const cr = (o, u, v) => (u[0] - o[0]) * (v[1] - o[1]) - (u[1] - o[1]) * (v[0] - o[0]);
    return cr(A, B, C) * cr(A, B, D) < 0 && cr(C, D, A) * cr(C, D, B) < 0;
  }

  // os dois traços (retas) se cruzam perto do meio de ambos?
  function crossMid(p, q) {
    const [ax, ay] = p.a, [bx, by] = p.z, [cx, cy] = q.a, [dx, dy] = q.z;
    const den = (bx - ax) * (dy - cy) - (by - ay) * (dx - cx);
    if (Math.abs(den) < 1e-6) return false;
    const t = ((cx - ax) * (dy - cy) - (cy - ay) * (dx - cx)) / den;
    const u = ((cx - ax) * (by - ay) - (cy - ay) * (bx - ax)) / den;
    return t > 0.08 && t < 0.92 && u > 0.08 && u < 0.92;
  }

  const median = (a) => { const s = a.slice().sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; };

  /* =========================================================
     Separação em símbolos e linhas
     ========================================================= */
  function segment(strokes) {
    const items = strokes.map((s) => { const p = ptsOf(s); return { p, b: boxPts([p]) }; });
    const hs = items.map((i) => i.b.h);
    const maxH = Math.max(...hs, 1);
    const H0 = median(hs.filter((h) => h > 0.4 * maxH)) || maxH;
    const groups = [];
    for (const it of items) {
      // só junta com o símbolo que estava sendo escrito (ordem dos traços)
      const g = groups[groups.length - 1];
      if (g && sameSymbol(g.b, it.b, H0)) { g.p.push(it.p); g.b = boxPts(g.p); } else groups.push({ p: [it.p], b: it.b });
    }
    const gh = groups.map((g) => g.b.h);
    const gmax = Math.max(...gh, 1);
    const H = median(gh.filter((h) => h > 0.4 * gmax)) || gmax;
    const rows = [];
    for (const g of groups.slice().sort((a, b) => a.b.cy - b.b.cy)) {
      const r = rows.find((r) => Math.abs(r.cy - g.b.cy) < 0.7 * H);
      if (r) { r.groups.push(g); r.cy = median(r.groups.map((x) => x.b.cy)); } else rows.push({ groups: [g], cy: g.b.cy });
    }
    for (const r of rows) r.groups.sort((a, b) => a.b.x0 - b.b.x0);
    return { rows, H };
  }

  function sameSymbol(a, b, H) {
    const vert = b.y0 <= a.y1 + 0.35 * H && b.y1 >= a.y0 - 0.35 * H;
    if (!vert) return false;
    const [s, l] = a.w <= b.w ? [a, b] : [b, a];
    // o menor precisa cair na parte central do maior (não só encostar na borda)
    const m = Math.max(0.15 * l.w, 0.04 * H);
    return s.cx >= l.x0 + m && s.cx <= l.x1 - m;
  }

  /* =========================================================
     Classificação de um símbolo
     ========================================================= */
  function symbolOf(g, H, row) {
    const parts = g.p.map(info);
    const tiny = (q) => Math.max(q.b.w, q.b.h) < 0.22 * H;
    const flat = (q) => !tiny(q) && q.b.h < Math.max(0.3 * q.b.w, 0.15 * H);

    // pontos e traços horizontais: − = ÷ ,
    if (parts.every((q) => tiny(q) || flat(q))) {
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

    // dois traços (quase) retos que se cruzam ou se tocam: + ou ×
    // usa o "miolo" de cada traço, ignorando ganchinhos no começo e no fim
    if (parts.length === 2) {
      const cores = g.p.map(core);
      if (cores.every((q) => q.straight > 0.82 && q.len > 0.15 * H) && crossMid(cores[0], cores[1])) {
        const [p, q] = cores;
        const hv = (u, v) => u.horiz < 35 && v.horiz > 55;
        if (hv(p, q) || hv(q, p)) return '+';
        const diag = (u) => u.horiz >= 20 && u.horiz <= 70;
        if (diag(p) && diag(q) && p.slope * q.slope < 0) return '×';
      }
    }

    // vírgula/ponto decimal: pequeno e na parte de baixo da linha
    {
      const tall = row.groups.filter((x) => x.b.h > 0.5 * H);
      const base = tall.length ? median(tall.map((x) => x.b.y1)) : row.cy + H / 2;
      if (Math.max(g.b.w, g.b.h) < 0.38 * H && g.b.y0 > base - 0.45 * H) return ',';
    }

    if (parts.length === 1) {
      const q = parts[0], p = g.p[0];
      // parênteses: curva suave, alta e estreita, com "barriga" no meio
      if (q.b.h > 0.6 * H && q.b.w < 0.7 * q.b.h && q.straight > 0.42 && maxTurn(p) < 60) {
        const bulge = midBulge(p);
        if (Math.abs(bulge) > 0.12 * q.b.h) {
          // corda de cima para baixo: desvio para a esquerda = "("
          const down = q.z[1] > q.a[1];
          return (bulge < 0) === down ? '(' : ')';
        }
      }
      // traço reto: 1 (em pé) ou / (bem inclinado)
      if (q.straight > 0.93 && q.b.h > 0.45 * H) {
        if (q.b.w < 0.42 * q.b.h) return '1';
        if (q.slope < 0 && q.horiz > 35) return '/';
      }
    }
    return classifyDigit(g.p);
  }

  /* =========================================================
     Conta
     ========================================================= */
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
    function implicit(v) { return peek() === '(' ? v * factor() : v; } // 2(3+1)
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

  const fmt = (v) => (Math.round(v * 1000) / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 3, useGrouping: false });

  /* =========================================================
     Escrever o resultado (imita a letra do usuário se ensinada)
     ========================================================= */
  function arc(cx, cy, rx, ry, a0, a1, steps = 18) {
    const out = [];
    for (let i = 0; i <= steps; i++) {
      const a = (a0 + (a1 - a0) * i / steps) * Math.PI / 180;
      out.push([cx + rx * Math.cos(a), cy + ry * Math.sin(a)]);
    }
    return out;
  }
  const GLYPH = {
    '0': [arc(0.3, 0.5, 0.3, 0.5, -90, 270, 28)],
    '1': [[[0.12, 0.18], [0.3, 0], [0.3, 1]]],
    '2': [[...arc(0.3, 0.3, 0.3, 0.3, 190, 400), [0, 1], [0.65, 1]]],
    '3': [[...arc(0.3, 0.25, 0.28, 0.25, 200, 450), ...arc(0.3, 0.75, 0.3, 0.25, 270, 520)]],
    '4': [[[0.5, 0], [0, 0.65], [0.7, 0.65]], [[0.5, 0.3], [0.5, 1]]],
    '5': [[[0.55, 0], [0.1, 0], [0.06, 0.45], ...arc(0.3, 0.7, 0.3, 0.3, 235, 500)]],
    '6': [[[0.55, 0.02], [0.15, 0.4], ...arc(0.3, 0.7, 0.3, 0.3, 180, 540)]],
    '7': [[[0, 0], [0.6, 0], [0.2, 1]], [[0.12, 0.5], [0.52, 0.5]]],
    '8': [[...arc(0.3, 0.25, 0.24, 0.25, 90, 450), ...arc(0.3, 0.73, 0.3, 0.27, 270, 630)]],
    '9': [[...arc(0.3, 0.28, 0.3, 0.28, 0, 360), [0.6, 0.28], [0.55, 1]]],
    '=': [[[0, 0.38], [0.5, 0.38]], [[0, 0.62], [0.5, 0.62]]],
    '-': [[[0, 0.5], [0.45, 0.5]]],
    ',': [[[0.1, 0.85], [0.08, 0.95], [0, 1.12]]],
  };
  const glyph = (ch) => (samples[ch] && samples[ch][0]) || GLYPH[ch] || [];

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

  /* =========================================================
     Fluxo "Calcular"
     ========================================================= */
  let pending = null;

  async function calc() {
    if (!ssel) return;
    try { await loadModel(); } catch (e) { toast('Não consegui carregar o reconhecedor: ' + e.message, 4000); return; }
    if (!ssel) return;
    const { rows, H } = segment(ssel.strokes);
    if (!rows.length) return;
    const row = rows.slice().sort((a, b) => b.groups.length - a.groups.length)[0]; // linha principal
    const syms = row.groups.map((g) => symbolOf(g, H, row));
    let expr = syms.join('');
    if (expr.includes('=')) expr = expr.slice(0, expr.indexOf('='));
    const box = boxPts(row.groups.flatMap((g) => g.p));
    const tall = row.groups.filter((g) => g.b.h > 0.5 * H);
    const base = tall.length ? median(tall.map((g) => g.b.y1)) : box.y1;
    pending = {
      v: ssel.v, expr, H, base, x: box.x1 + 0.35 * H, endsWithEq: syms[syms.length - 1] === '=',
      color: ssel.strokes[0].color, size: median(ssel.strokes.map((s) => s.size)),
    };
    showResult();
  }

  const pretty = (e) => e.replace(/×/g, ' × ').replace(/÷/g, ' ÷ ').replace(/\//g, ' / ').replace(/\+/g, ' + ').replace(/(\d)-/g, '$1 - ');

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
    if (!trained()) toast('Dica: ⋯ → Ensinar minha letra deixa o cálculo ainda mais preciso', 4000);
  }

  function correct() {
    if (!pending) return;
    const t = prompt('Corrija a conta (use + - x / ( ) e vírgula):', pretty(pending.expr).replace(/\s+/g, ' ').trim());
    if (t === null) return;
    pending.expr = t.replace(/\s+/g, '').replace(/[x*X]/g, '×').replace(/:/g, '÷').replace(/=.*$/, '');
    showResult();
  }

  /* =========================================================
     Tela "Ensinar minha letra" (só números)
     ========================================================= */
  function train() {
    const wrap = document.createElement('div');
    wrap.className = 'train';
    wrap.innerHTML = `
      <div class="train-box">
        <h2>Ensinar minha letra</h2>
        <p>Escreva cada número nos quadrinhos, do jeito que você escreve normalmente (${PER} vezes cada). Os sinais + − × ÷ = já são reconhecidos sozinhos. Toque em ↺ para apagar um quadrinho.</p>
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
    for (const lab of DIGITS) {
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
          const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [];
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
      if (!confirm('Apagar a letra ensinada e voltar ao reconhecimento padrão?')) return;
      samples = {}; saveSamples(); userEmb = null; close(); toast('Reconhecimento padrão restaurado');
    };
    wrap.querySelector('[data-t=save]').onclick = () => {
      const fresh = {};
      for (const o of cells) {
        if (!o.strokes.length) continue;
        let x0 = Infinity, y0 = Infinity, y1 = -Infinity;
        for (const s of o.strokes) for (const [x, y] of s) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
        const h = Math.max(y1 - y0, 1);
        const norm = o.strokes.map((s) => s.map(([x, y]) => [Math.round((x - x0) / h * 1000) / 1000, Math.round((y - y0) / h * 1000) / 1000]));
        (fresh[o.lab] = fresh[o.lab] || []).push(norm);
      }
      const n = Object.keys(fresh).length;
      if (!n) { toast('Escreva pelo menos um número'); return; }
      Object.assign(samples, fresh);
      saveSamples();
      userEmb = null;
      close();
      toast(`Letra salva (${n} números) ✓`);
    };
  }

  /* ---------- Botões ---------- */
  let bound = false;
  function bind() {
    if (bound) return; bound = true;
    const b = $('#calcBar');
    b.querySelector('[data-c=ins]').onclick = insert;
    b.querySelector('[data-c=fix]').onclick = correct;
    b.querySelector('[data-c=x]').onclick = closeBar;
    $('#strokeBar [data-act=calc]').onclick = calc;
  }
  if (document.readyState !== 'loading') bind(); else document.addEventListener('DOMContentLoaded', bind);
  // baixa o modelo em segundo plano para o primeiro cálculo ser rápido
  setTimeout(() => loadModel().catch(() => {}), 3000);

  return { calc, train, closeBar, evaluate, _forward: (img) => loadModel().then(() => forward(img)), _segment: segment, _symbolOf: symbolOf, _raster: raster, _info: info, _maxTurn: maxTurn, _midBulge: midBulge, _core: core, _crossMid: crossMid };
})();
