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

  let samples = {};
  try { samples = JSON.parse(localStorage.getItem(KEY) || '{}').samples || {}; } catch (_) {}
  // descarta modelos antigos de sinais (agora reconhecidos por regras)
  for (const k of Object.keys(samples)) if (!DIGITS.includes(k)) delete samples[k];

  /* Exemplos da letra do usuário (folha de treino): cada item é um símbolo
     com traços normalizados pela altura da linha H:
     x = (x - início do símbolo) / H, y = (y - topo da linha) / H, base da linha = 1 */
  const KEY2 = 'caderno.ink2';
  const MAX_PER = 15;
  let items = [];
  try { items = JSON.parse(localStorage.getItem(KEY2) || '{}').items || []; } catch (_) {}
  if (!items.length) for (const lab of Object.keys(samples)) for (const st of samples[lab]) items.push({ lab, st });
  const saveItems = () => { try { localStorage.setItem(KEY2, JSON.stringify({ items })); } catch (_) {} };
  const trained = () => items.length > 0;

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

  function normalize(v) { let n = 0; for (const x of v) n += x * x; n = Math.sqrt(n) || 1; return Array.from(v, (x) => x / n); }

  function classifyDigit(strokes) {
    const { p } = forward(raster(strokes));
    let bi = 0;
    for (let d = 1; d < 10; d++) if (p[d] > p[bi]) bi = d;
    return String(bi);
  }

  /* Características de um símbolo: "impressão digital" da rede + forma/posição na linha */
  function featOf(st) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const s of st) for (const [x, y] of s) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
    const w = x1 - x0, h = y1 - y0;
    return {
      e: normalize(forward(raster(st)).emb),
      asp: Math.max(-3, Math.min(3, Math.log((w + 0.03) / (h + 0.03)))) / 3,
      hr: Math.min(h, 2) / 2,
      cy: Math.max(-1, Math.min(1, (y0 + y1) / 2 - 0.5)),
      n: Math.min(st.length, 4),
    };
  }

  function similarity(a, b) {
    let c = 0;
    for (let i = 0; i < a.e.length; i++) c += a.e[i] * b.e[i];
    return c - 0.45 * (Math.abs(a.asp - b.asp) + Math.abs(a.hr - b.hr) + Math.abs(a.cy - b.cy)) - 0.06 * Math.abs(a.n - b.n);
  }

  function knn(f) {
    let best = null;
    for (const it of items) {
      if (!it.f) it.f = featOf(it.st);
      const sc = similarity(f, it.f);
      if (!best || sc > best.score) best = { lab: it.lab, score: sc };
    }
    return best;
  }

  // normaliza os traços de um símbolo pela linha onde ele está
  function normGroup(g, H, row) {
    const base = baseOf(row, H);
    return g.p.map((s) => s.map(([x, y]) => [Math.round((x - g.b.x0) / H * 1000) / 1000, Math.round((y - (base - H)) / H * 1000) / 1000]));
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
    // altura de referência: os traços mais altos (um "4" de dois traços curtos não puxa para baixo)
    const tallH = hs.filter((h) => h > 0.4 * maxH).sort((a, b) => a - b);
    const H0 = tallH.length ? tallH[Math.floor(tallH.length * 0.75)] : maxH;
    const groups = [];
    for (const it of items) {
      // só junta com o símbolo que estava sendo escrito (ordem dos traços)
      const g = groups[groups.length - 1];
      if (g && (sameSymbol(g.b, it.b, H0) || crossesGroup(g, it.p, H0))) { g.p.push(it.p); g.b = boxPts(g.p); } else groups.push({ p: [it.p], b: it.b });
    }
    if (items.length && W) learnedMerge(groups, H0);
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

  /* Usa a letra aprendida para separar: se dois pedaços seguidos, juntos,
     ficam muito parecidos com um símbolo que o usuário ensinou (o 4 dele de
     dois traços, o ÷ dele…), eles viram um símbolo só. */
  function learnedMerge(groups, H) {
    const tall = groups.filter((g) => g.b.h > 0.5 * H);
    const base = tall.length ? median(tall.map((g) => g.b.y1)) : median(groups.map((g) => g.b.y1));
    const score = (p) => {
      const b = boxPts(p);
      const st = p.map((q) => q.map(([x, y]) => [(x - b.x0) / H, (y - (base - H)) / H]));
      return knn(featOf(st));
    };
    for (let i = 0; i < groups.length - 1; i++) {
      const a = groups[i], b = groups[i + 1];
      const gap = Math.max(a.b.x0, b.b.x0) - Math.min(a.b.x1, b.b.x1);
      if (gap > 0.3 * H) continue;
      const m = score([...a.p, ...b.p]);
      if (!m || m.score < 0.82) continue;
      const sa = score(a.p), sb = score(b.p);
      if (m.score > ((sa ? sa.score : 0) + (sb ? sb.score : 0)) / 2 + 0.04) {
        a.p.push(...b.p); a.b = boxPts(a.p);
        groups.splice(i + 1, 1);
        i--;
      }
    }
  }

  /* Treino: encaixa os traços nos símbolos esperados (sabendo a resposta),
     sem exigir que o usuário mude o jeito de escrever. Divide os traços, na
     ordem em que foram feitos, em exatamente m símbolos, escolhendo a divisão
     mais "natural" (pedaços de um símbolo próximos; símbolos diferentes sem
     se sobrepor). */
  function alignTo(strokes, m) {
    const P = strokes.map(ptsOf);
    const n = P.length;
    if (n < m || m === 0) return null;
    const B = P.map((p) => boxPts([p]));
    const hs = B.map((b) => b.h), maxH = Math.max(...hs, 1);
    const tallH = hs.filter((h) => h > 0.4 * maxH).sort((a, b) => a - b);
    const H = tallH.length ? tallH[Math.floor(tallH.length * 0.75)] : maxH;
    const U = {}; // união [a, b)
    const union = (a, b) => {
      const k = a * 100 + b;
      if (!U[k]) {
        let x0 = Infinity, x1 = -Infinity, gaps = 0;
        const sorted = B.slice(a, b).sort((p, q) => p.x0 - q.x0);
        for (const bx of sorted) { if (x1 > -Infinity && bx.x0 > x1) gaps += bx.x0 - x1; x0 = Math.min(x0, bx.x0); x1 = Math.max(x1, bx.x1); }
        U[k] = { x0, x1, cx: (x0 + x1) / 2, cost: gaps / H + Math.max(0, (x1 - x0) - 1.6 * H) / H };
      }
      return U[k];
    };
    const INF = 1e18;
    // dp[k][b][a]: k símbolos cobrindo traços [0,b), último começa em a
    const dp = Array.from({ length: m + 1 }, () => Array.from({ length: n + 1 }, () => new Float64Array(n + 1).fill(INF)));
    const from = Array.from({ length: m + 1 }, () => Array.from({ length: n + 1 }, () => new Int16Array(n + 1).fill(-1)));
    for (let b = 1; b <= n; b++) dp[1][b][0] = union(0, b).cost;
    for (let k = 1; k < m; k++) for (let b = k; b <= n; b++) for (let a = 0; a < b; a++) {
      const cur = dp[k][b][a];
      if (cur >= INF) continue;
      const u1 = union(a, b);
      for (let c = b + 1; c <= n - (m - k - 1); c++) {
        const u2 = union(b, c);
        const ov = Math.max(0, Math.min(u1.x1, u2.x1) - Math.max(u1.x0, u2.x0)) / H;
        const back = u2.cx < u1.cx ? 1 : 0;
        const v = cur + u2.cost + 2 * ov + back;
        if (v < dp[k + 1][c][b]) { dp[k + 1][c][b] = v; from[k + 1][c][b] = a; }
      }
    }
    let best = INF, a = -1;
    for (let x = 0; x < n; x++) if (dp[m][n][x] < best) { best = dp[m][n][x]; a = x; }
    if (a < 0) return null;
    const segs = [];
    let b = n;
    for (let k = m; k >= 1; k--) { segs.unshift([a, b]); const pa = from[k][b][a]; b = a; a = pa; }
    const groups = segs.map(([x, y]) => { const p = P.slice(x, y); return { p, b: boxPts(p) }; });
    groups.sort((g1, g2) => g1.b.cx - g2.b.cx);
    const gh = groups.map((g) => g.b.h), gmax = Math.max(...gh, 1);
    const Hg = median(gh.filter((h) => h > 0.4 * gmax)) || gmax;
    return { groups, H: Hg, row: { groups, cy: median(groups.map((g) => g.b.cy)) } };
  }

  // traço reto que atravessa ou passa rente ao símbolo anterior
  // (a reta do 4, o corte do 7, a barra do 5) — mas não a barra de um ÷ ao lado de um 0
  function crossesGroup(g, p, H) {
    const c = core(p);
    if (c.straight < 0.85 || c.len < 0.2 * H) return false;
    const pb = boxPts([p]);
    const ov = Math.max(0, Math.min(pb.x1, g.b.x1) - Math.max(pb.x0, g.b.x0));
    const ovY = Math.max(0, Math.min(pb.y1, g.b.y1) - Math.max(pb.y0, g.b.y0));
    if (c.horiz > 55) { if (ovY < 0.5 * Math.min(g.b.h, pb.h)) return false; }        // em pé: precisa acompanhar a altura
    else if (c.horiz < 35) { if (ov < 0.6 * pb.w) return false; }                     // deitado: precisa ficar "dentro"
    else if (ov < 0.4 * pb.w) return false;                                           // inclinado
    const thin = (q) => { const out = [q[0]]; const step = Math.max(1, Math.floor(q.length / 40)); for (let i = step; i < q.length; i += step) out.push(q[i]); out.push(q[q.length - 1]); return out; };
    const P = thin(p);
    const total = info(p).len || 1;
    const near = 0.1 * H;
    let along = 0;
    for (let i = 1; i < P.length; i++) {
      along += Math.hypot(P[i][0] - P[i - 1][0], P[i][1] - P[i - 1][1]);
      const t = along / total;
      if (t < 0.12 || t > 0.88) continue; // pelo meio do traço, não só a ponta
      for (const q of g.p) {
        const Q = thin(q);
        for (let j = 1; j < Q.length; j++) {
          if (segX(P[i - 1], P[i], Q[j - 1], Q[j])) return true;
          if (distPS(P[i], Q[j - 1], Q[j]) < near) return true;
        }
      }
    }
    return false;
  }
  function distPS(p, a, b) {
    const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
    let t = l2 ? ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2 : 0;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
  }
  function segX(a, b, c, d) {
    const cr = (o, u, v) => (u[0] - o[0]) * (v[1] - o[1]) - (u[1] - o[1]) * (v[0] - o[0]);
    return cr(a, b, c) * cr(a, b, d) < 0 && cr(c, d, a) * cr(c, d, b) < 0;
  }

  function sameSymbol(a, b, H) {
    // pontinhos (como os do ÷) podem ficar mais longe da barra
    const dot = Math.max(b.w, b.h) < 0.25 * H || Math.max(a.w, a.h) < 0.25 * H;
    const tol = (dot ? 0.8 : 0.5) * H;
    const vert = b.y0 <= a.y1 + tol && b.y1 >= a.y0 - tol;
    if (!vert) return false;
    const [s, l] = a.w <= b.w ? [a, b] : [b, a];
    // o menor precisa cair na parte central do maior (não só encostar na borda)
    const m = Math.max(0.15 * l.w, 0.04 * H);
    return s.cx >= l.x0 + m && s.cx <= l.x1 - m;
  }

  /* =========================================================
     Classificação de um símbolo
     ========================================================= */
  function baseOf(row, H) {
    const tall = row.groups.filter((x) => x.b.h > 0.5 * H);
    return tall.length ? median(tall.map((x) => x.b.y1)) : row.cy + H / 2;
  }

  // exemplos da folha de treino têm prioridade quando forem bem parecidos
  function symbolOf(g, H, row) {
    const def = ruleSymbol(g, H, row);
    if (!items.length) return def;
    const k = knn(featOf(normGroup(g, H, row)));
    return k && k.score >= 0.72 ? k.lab : def;
  }

  function ruleSymbol(g, H, row) {
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
  const glyph = (ch) => {
    for (let i = items.length - 1; i >= 0; i--) if (items[i].lab === ch) return items[i].st;
    return GLYPH[ch] || [];
  };

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
    if (!trained()) toast('Dica: ⋯ → Folha de treino deixa o cálculo mais preciso na sua letra', 4500);
  }

  function correct() {
    if (!pending) return;
    const t = prompt('Corrija a conta (use + - x / ( ) e vírgula):', pretty(pending.expr).replace(/\s+/g, ' ').trim());
    if (t === null) return;
    pending.expr = t.replace(/\s+/g, '').replace(/[x*X]/g, '×').replace(/:/g, '÷').replace(/=.*$/, '');
    showResult();
  }

  /* =========================================================
     Folha de treino: o app mostra uma conta, o usuário escreve e
     cada símbolo vira exemplo da letra dele (já sabendo a resposta).
     ========================================================= */
  const EXERCISES = [
    '0 1 2 3 4', '5 6 7 8 9', '22 + 22', '70 ÷ 3', '34 + 36 = 70', '70 × 3,5',
    '245 ÷ 3', '(5 + 3) × 2', '98 − 19', '1260 ÷ 4', '17 + 28', '6 × 9 = 54',
    '40 − 15', '3,75 + 1,5', '(84 − 6) ÷ 2', '905 − 318', '1 + 7 = 8', '2 × 2 = 4',
  ];
  const expectOf = (t) => t.replace(/\s+/g, '').replace(/−/g, '-');
  const SYMBOLS = [...DIGITS, '+', '-', '×', '÷', '=', ',', '(', ')'];
  const GOAL = 6; // exemplos por símbolo para considerar "bem treinado"
  const show = (t) => t.replace(/-/g, '−');

  function counts() {
    const c = {};
    for (const k of SYMBOLS) c[k] = 0;
    for (const it of items) if (c[it.lab] !== undefined) c[it.lab]++;
    return c;
  }

  // Gera uma conta nova focando nos símbolos com menos exemplos
  function generate() {
    const c = counts();
    const rnd = (n) => Math.floor(Math.random() * n);
    const byNeed = (list) => list.slice().sort((x, y) => (c[x] - c[y]) || (Math.random() - 0.5));
    const weakD = byNeed(DIGITS).slice(0, 4);
    const digit = () => (Math.random() < 0.7 ? weakD[rnd(weakD.length)] : DIGITS[rnd(10)]);
    const number = (len) => {
      let n = '';
      for (let i = 0; i < len; i++) { let d = digit(); if (i === 0 && d === '0' && len > 1) d = String(1 + rnd(9)); n += d; }
      return n;
    };
    const ops = byNeed(['+', '-', '×', '÷']);
    const op = Math.random() < 0.7 ? ops[0] : ops[rnd(4)];
    let a = number(1 + rnd(3)), b = number(1 + rnd(2));
    if (c[','] < GOAL && Math.random() < 0.6) a = number(1 + rnd(2)) + ',' + String(1 + rnd(9));
    let expr = `${a} ${op} ${b}`;
    if ((c['('] < GOAL || c[')'] < GOAL) && Math.random() < 0.55) {
      const op2 = ['+', '-', '×'][rnd(3)];
      expr = `(${expr}) ${op2} ${number(1 + rnd(2))}`;
    }
    if (c['='] < GOAL && Math.random() < 0.6) {
      try {
        const v = evaluate(expectOf(expr));
        if (Number.isInteger(v) && v >= 0 && v < 100000) expr += ` = ${v}`;
      } catch (_) {}
    }
    return show(expr);
  }

  function sheet() {
    if (document.querySelector('.train')) return; // já aberta
    let fixed = 0, done = 0, learned = 0, current = '';
    try { fixed = +localStorage.getItem('caderno.sheetIdx') || 0; } catch (_) {}
    const wrap = document.createElement('div');
    wrap.className = 'train';
    wrap.innerHTML = `
      <div class="train-box sheet">
        <div class="sheet-head"><h2>Folha de treino</h2><span class="sheet-prog"></span></div>
        <div class="sheet-tabs">
          <button data-m="copy" class="active">1 · Copiar a conta</button>
          <button data-m="guess">2 · Adivinhe o que escrevi</button>
        </div>
        <p class="sheet-help"></p>
        <div class="sheet-ex"></div>
        <div class="sheet-pad"><canvas></canvas></div>
        <div class="sheet-msg"></div>
        <div class="sheet-need"></div>
        <div class="dlg-actions sheet-actions">
          <button class="btn" data-t="reset">Esquecer minha letra</button>
          <span style="flex:1"></span>
          <button class="btn" data-t="clear">Apagar</button>
          <button class="btn" data-t="skip">Outra conta</button>
          <button class="btn" data-t="mine">Minha conta…</button>
          <button class="btn" data-t="close">Sair do treino</button>
          <button class="btn bad" data-t="no" hidden>Errou — corrigir</button>
          <button class="btn primary good" data-t="yes" hidden>Acertou ✓</button>
          <button class="btn primary" data-t="ok">Aprender e próxima ✓</button>
        </div>
      </div>`;
    document.body.appendChild(wrap);
    const c = wrap.querySelector('canvas');
    const msg = wrap.querySelector('.sheet-msg');
    let strokes = [], cur = null, boxes = null;
    let mode = 'copy', guessed = null;
    const score = { ok: 0, tot: 0 };
    const $w = (q) => wrap.querySelector(q);
    const COLORS = ['#2563eb', '#e8590c', '#2b8a3e', '#c2255c', '#7048e8', '#0c8599'];

    const draw = () => {
      const dpr = window.devicePixelRatio || 1;
      const W = c.clientWidth, H = c.clientHeight;
      if (c.width !== W * dpr || c.height !== H * dpr) { c.width = W * dpr; c.height = H * dpr; }
      const ctx = c.getContext('2d');
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, W, H);
      ctx.strokeStyle = '#b9cbe6'; ctx.lineWidth = 1.2;
      ctx.beginPath(); ctx.moveTo(0, H * 0.78); ctx.lineTo(W, H * 0.78); ctx.stroke();
      // caixas mostrando como a escrita foi separada (quando a contagem não bate)
      if (boxes) boxes.forEach((b, i) => {
        ctx.strokeStyle = COLORS[i % COLORS.length]; ctx.lineWidth = 2; ctx.setLineDash([5, 4]);
        ctx.strokeRect(b.x0 - 5, b.y0 - 5, b.w + 10, b.h + 10);
        ctx.setLineDash([]); ctx.fillStyle = COLORS[i % COLORS.length]; ctx.font = 'bold 12px -apple-system, sans-serif';
        ctx.fillText(String(i + 1), b.x0 - 4, b.y0 - 9);
      });
      ctx.strokeStyle = '#1a1a1a'; ctx.lineWidth = 3; ctx.lineCap = ctx.lineJoin = 'round';
      for (const s of strokes) {
        ctx.beginPath();
        s.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
        if (s.length === 1) ctx.lineTo(s[0][0] + 0.1, s[0][1]);
        ctx.stroke();
      }
    };
    const status = () => {
      const k = counts();
      const need = SYMBOLS.filter((x) => k[x] < GOAL);
      wrap.querySelector('.sheet-prog').textContent = mode === 'guess'
        ? `Acertos: ${score.ok} de ${score.tot}${score.tot ? ` (${Math.round(100 * score.ok / score.tot)}%)` : ''} · ${items.length} exemplos aprendidos`
        : `${done} conta${done === 1 ? '' : 's'} nesta sessão · ${items.length} exemplos aprendidos`;
      wrap.querySelector('.sheet-need').innerHTML = need.length
        ? `Ainda precisam de treino: ${need.map((x) => `<span class="chip">${show(x)}</span>`).join('')}`
        : 'Todos os números e sinais já têm bastante exemplos ✓ — continuar treinando ainda ajuda.';
    };
    const setExercise = (t) => {
      current = t;
      wrap.querySelector('.sheet-ex').textContent = t;
      strokes = []; boxes = null; draw(); status();
    };
    const next = () => {
      if (fixed < EXERCISES.length) {
        setExercise(EXERCISES[fixed]);
        fixed++;
        try { localStorage.setItem('caderno.sheetIdx', fixed); } catch (_) {}
      } else setExercise(generate());
    };
    const say = (t, kind) => { msg.innerHTML = t; msg.className = 'sheet-msg ' + (kind || ''); };

    c.addEventListener('pointerdown', (e) => {
      if (e.pointerType === 'touch' && penOnly()) return;
      e.preventDefault();
      try { c.setPointerCapture(e.pointerId); } catch (_) {}
      const r = c.getBoundingClientRect();
      cur = { id: e.pointerId, s: [[e.clientX - r.left, e.clientY - r.top]] };
      strokes.push(cur.s);
      if (boxes) { boxes = null; say(''); }
      if (guessed !== null) { guessed = null; guessButtons(false); say(''); } // continuou escrevendo
      draw();
    });
    c.addEventListener('pointermove', (e) => {
      if (!cur || cur.id !== e.pointerId) return;
      const r = c.getBoundingClientRect();
      const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [];
      for (const ev of evs.length ? evs : [e]) cur.s.push([ev.clientX - r.left, ev.clientY - r.top]);
      draw();
    });
    const end = (e) => { if (cur && cur.id === e.pointerId) cur = null; };
    c.addEventListener('pointerup', end);
    c.addEventListener('pointercancel', end);

    wrap.querySelector('[data-t=clear]').onclick = () => { strokes = []; boxes = null; guessed = null; guessButtons(false); draw(); say(''); };
    wrap.querySelector('[data-t=skip]').onclick = () => { say(''); setExercise(generate()); };
    wrap.querySelector('[data-t=mine]').onclick = () => {
      const t = prompt('Digite a conta que você quer treinar (números, + - x ÷ / = , e parênteses):', '');
      if (t === null) return;
      const norm = t.trim().replace(/\*/g, '×').replace(/[xX]/g, '×').replace(/:/g, '÷').replace(/\./g, ',').replace(/-/g, '−');
      if (!norm || !/^[0-9+−×÷/=,()\s]+$/.test(norm)) { say('Use só números e os sinais + − × ÷ / = , ( )', 'bad'); return; }
      say(''); setExercise(norm);
    };
    wrap.querySelector('[data-t=close]').onclick = () => {
      wrap.remove();
      window.removeEventListener('resize', draw);
      if (learned) toast(`Treino salvo: ${learned} símbolos aprendidos nesta sessão ✓`);
    };
    wrap.querySelector('[data-t=reset]').onclick = () => {
      if (!confirm('Esquecer tudo que o app aprendeu da sua letra?')) return;
      items = []; samples = {}; saveItems();
      try { localStorage.removeItem(KEY); localStorage.removeItem('caderno.sheetIdx'); } catch (_) {}
      fixed = 0; next(); say('Pronto, voltei ao reconhecimento padrão.');
    };
    // Aprende a escrita atual sabendo o que ela significa (texto esperado).
    // Retorna null se não deu para encaixar.
    const learnFrom = (text) => {
      const objs = strokes.map((s) => ({ pts: s.flatMap(([x, y]) => [x, y, 0.5]) }));
      const want = [...expectOf(text)];
      // o app se adapta à escrita: se a separação normal não bate com a conta,
      // encaixa os traços nos símbolos esperados
      const { rows: r1, H: H1 } = segment(objs);
      const row1 = r1.slice().sort((a, b) => b.groups.length - a.groups.length)[0];
      const direct = row1 && row1.groups.length === want.length;
      const fit = direct ? { groups: row1.groups, H: H1, row: row1 } : alignTo(objs, want.length);
      if (!fit) {
        boxes = row1 ? row1.groups.map((g) => g.b) : null;
        draw();
        say(`“${show(want.join(''))}” tem <b>${want.length}</b> símbolos, mas encontrei só <b>${strokes.length}</b> traço${strokes.length === 1 ? '' : 's'}. Parece que dois símbolos foram feitos num traço só — escreva cada um separado.`, 'bad');
        return null;
      }
      const before = row1 ? row1.groups.map((g) => symbolOf(g, H1, row1)).join('') : '';
      const { groups, H, row } = fit;
      groups.forEach((g, i) => { items.push({ lab: want[i], st: normGroup(g, H, row) }); learned++; });
      boxes = null;
      // guarda só os exemplos mais recentes de cada símbolo
      const cnt = {};
      for (let i = items.length - 1; i >= 0; i--) {
        const l = items[i].lab;
        cnt[l] = (cnt[l] || 0) + 1;
        if (cnt[l] > MAX_PER) items.splice(i, 1);
      }
      saveItems();
      return { before, direct, want: want.join('') };
    };

    const guessButtons = (on) => {
      $w('[data-t=yes]').hidden = $w('[data-t=no]').hidden = !on;
      $w('[data-t=ok]').hidden = on;
    };

    const setMode = (m) => {
      mode = m; guessed = null;
      wrap.querySelectorAll('.sheet-tabs button').forEach((b) => b.classList.toggle('active', b.dataset.m === m));
      $w('[data-t=skip]').hidden = $w('[data-t=mine]').hidden = m === 'guess';
      $w('[data-t=ok]').textContent = m === 'guess' ? 'Adivinhar' : 'Aprender e próxima ✓';
      $w('.sheet-help').innerHTML = m === 'guess'
        ? 'Escreva <b>qualquer número ou conta</b> do seu jeito e toque em <b>Adivinhar</b>. Diga se o app acertou; se errou, diga o que era e ele aprende.'
        : 'Escreva a conta abaixo do seu jeito normal, na linha, e toque em <b>Aprender e próxima</b>. Treine quantas contas quiser — sempre aparece uma nova.';
      guessButtons(false);
      say('');
      if (m === 'guess') { current = ''; $w('.sheet-ex').textContent = 'Escreva qualquer número ou conta ✍️'; strokes = []; boxes = null; draw(); status(); }
      else next();
    };
    wrap.querySelectorAll('.sheet-tabs button').forEach((b) => (b.onclick = () => setMode(b.dataset.m)));

    const freshPad = () => { strokes = []; boxes = null; guessed = null; guessButtons(false); draw(); status(); };

    wrap.querySelector('[data-t=ok]').onclick = async () => {
      if (!strokes.length) { say('Escreva primeiro.'); return; }
      try { await loadModel(); } catch (e) { say('Não consegui carregar o reconhecedor: ' + e.message, 'bad'); return; }
      if (mode === 'guess') {
        const objs = strokes.map((s) => ({ pts: s.flatMap(([x, y]) => [x, y, 0.5]) }));
        const { rows, H } = segment(objs);
        const row = rows.slice().sort((a, b) => b.groups.length - a.groups.length)[0];
        guessed = row ? row.groups.map((g) => symbolOf(g, H, row)).join('') : '';
        say(`Acho que você escreveu: <span class="guess">${show(guessed) || '?'}</span>`);
        guessButtons(true);
        return;
      }
      const r = learnFrom(current);
      if (!r) return;
      done++;
      if (!r.direct) say(`Antes eu leria <b>${show(r.before) || '—'}</b>. Me ajustei ao seu jeito de escrever e aprendi <b>${show(r.want)}</b> ✓`, 'good');
      else if (r.before === r.want) say(`Já li certo: <b>${show(r.before)}</b> ✓ — reforcei mesmo assim.`, 'good');
      else say(`Antes eu leria <b>${show(r.before)}</b>. Agora aprendi <b>${show(r.want)}</b> ✓`, 'good');
      next();
    };

    wrap.querySelector('[data-t=yes]').onclick = () => {
      if (guessed === null) return;
      score.ok++; score.tot++;
      learnFrom(guessed); // reforça
      say(`Oba, acertei <b>${show(guessed)}</b> ✓ Escreva outro.`, 'good');
      freshPad();
    };
    wrap.querySelector('[data-t=no]').onclick = () => {
      if (guessed === null) return;
      const t = prompt('O que você escreveu? (números e + − x ÷ / = , ( ))', show(guessed));
      if (t === null) return;
      const norm = t.trim().replace(/\*/g, '×').replace(/[xX]/g, '×').replace(/:/g, '÷').replace(/\./g, ',').replace(/-/g, '−');
      if (!norm || !/^[0-9+−×÷/=,()\s]+$/.test(norm)) { say('Use só números e os sinais + − × ÷ / = , ( )', 'bad'); return; }
      score.tot++;
      const wrong = guessed;
      const r = learnFrom(norm);
      if (!r) { guessButtons(false); guessed = null; status(); return; }
      say(`Eu tinha lido <b>${show(wrong) || '—'}</b>. Aprendi que é <b>${show(r.want)}</b> ✓ Escreva outro.`, 'good');
      freshPad();
    };

    setMode('copy');
    requestAnimationFrame(draw);
    window.addEventListener('resize', draw);
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

  return { calc, sheet, closeBar, evaluate, _forward: (img) => loadModel().then(() => forward(img)), _segment: segment, _symbolOf: symbolOf, _raster: raster, _info: info, _maxTurn: maxTurn, _midBulge: midBulge, _core: core, _crossMid: crossMid, _same: sameSymbol };
})();
