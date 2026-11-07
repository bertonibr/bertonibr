'use strict';

/* =========================================================
   Caderno — caderno digital para Apple Pencil (PWA)
   Coordenadas das páginas são lógicas: largura = 1000.
   ========================================================= */

const PAGE_W = 1000;
const A4_H = 1414;
const PEN_COLORS = ['#1a1a1a', '#1e4fd8', '#d62828', '#2a9d4b', '#e67700', '#7b2cbf'];
const HL_COLORS = ['#ffe14d', '#7cf27c', '#7cd8ff', '#ff9ad5', '#ffb057'];
const SIZES = { pen: [1.8, 3.2, 6], highlighter: [14, 22, 34], eraser: [8, 20, 45] };
const COVERS = ['#3b5bdb', '#e8590c', '#2b8a3e', '#c2255c', '#5f3dc4', '#495057', '#0c8599'];
const TEMPLATES = { blank: 'Branco', lined: 'Pautado', grid: 'Quadriculado', dots: 'Pontilhado' };
const LIBS = {
  jspdf: 'lib/jspdf.umd.min.js',
  pdfjs: 'lib/pdf.min.js',
  pdfjsWorker: 'lib/pdf.worker.min.js',
};

const $ = (s) => document.querySelector(s);
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/* ---------------- Preferências ---------------- */
const prefs = (() => {
  let p = {};
  try { p = JSON.parse(localStorage.getItem('caderno.prefs') || '{}'); } catch (_) {}
  return Object.assign({
    tool: 'pen',
    color: { pen: PEN_COLORS[0], highlighter: HL_COLORS[0] },
    size: { pen: 1, highlighter: 1, eraser: 1 },
    penOnly: null, // null = automático (liga ao detectar a Pencil)
  }, p);
})();
function savePrefs() { try { localStorage.setItem('caderno.prefs', JSON.stringify(prefs)); } catch (_) {} }

/* ---------------- IndexedDB ---------------- */
const DB = {
  db: null,
  open() {
    return new Promise((res, rej) => {
      const r = indexedDB.open('caderno', 1);
      r.onupgradeneeded = () => {
        const d = r.result;
        d.createObjectStore('notebooks', { keyPath: 'id' });
        d.createObjectStore('pages', { keyPath: 'id' });
        d.createObjectStore('images');
      };
      r.onsuccess = () => { this.db = r.result; res(); };
      r.onerror = () => rej(r.error);
    });
  },
  run(store, mode, fn) {
    return new Promise((res, rej) => {
      const t = this.db.transaction(store, mode);
      const req = fn(t.objectStore(store));
      t.oncomplete = () => res(req ? req.result : undefined);
      t.onerror = () => rej(t.error);
      t.onabort = () => rej(t.error);
    });
  },
  all(s) { return this.run(s, 'readonly', (o) => o.getAll()); },
  get(s, k) { return this.run(s, 'readonly', (o) => o.get(k)); },
  put(s, v, k) { return this.run(s, 'readwrite', (o) => (k === undefined ? o.put(v) : o.put(v, k))); },
  del(s, k) { return this.run(s, 'readwrite', (o) => o.delete(k)); },
};

/* ---------------- Estado do editor ---------------- */
const S = {
  nb: null,          // metadados do caderno aberto
  pages: [],         // páginas do caderno aberto (ordem)
  views: new Map(),  // pageId -> view
  zoom: 1,
  undo: [], redo: [],
  dirtyPages: new Set(),
  metaDirty: false,
  current: 0,
};

/* ---------------- Utilidades de UI ---------------- */
let toastTimer;
function toast(msg, ms = 2200) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  if (ms) toastTimer = setTimeout(() => (t.hidden = true), ms);
}

function showMenu(anchor, items) {
  const m = $('#menu');
  m.innerHTML = '';
  for (const it of items) {
    if (it === '-') { const d = document.createElement('div'); d.className = 'menu-sep'; m.appendChild(d); continue; }
    if (it.info) { const d = document.createElement('div'); d.className = 'menu-info'; d.innerHTML = it.info; m.appendChild(d); continue; }
    const b = document.createElement('button');
    if (it.danger) b.className = 'danger';
    b.innerHTML = `<span class="check">${it.checked ? '✓' : ''}</span>${it.label}`;
    b.onclick = () => { hideMenu(); it.action(); };
    m.appendChild(b);
  }
  m.hidden = false;
  const r = anchor.getBoundingClientRect();
  const mw = m.offsetWidth, mh = m.offsetHeight;
  let x = clamp(r.right - mw, 8, innerWidth - mw - 8);
  let y = r.bottom + 6;
  if (y + mh > innerHeight - 8) y = Math.max(8, r.top - mh - 6);
  m.style.left = x + 'px'; m.style.top = y + 'px';
  setTimeout(() => document.addEventListener('pointerdown', outsideMenu, true), 0);
}
function hideMenu() { $('#menu').hidden = true; document.removeEventListener('pointerdown', outsideMenu, true); }
function outsideMenu(e) { if (!$('#menu').contains(e.target)) hideMenu(); }

function loadScript(src) {
  return new Promise((res, rej) => {
    if (document.querySelector(`script[src="${src}"]`)) return res();
    const s = document.createElement('script');
    s.src = src; s.onload = res; s.onerror = () => rej(new Error('Falha ao carregar ' + src));
    document.head.appendChild(s);
  });
}

/* =========================================================
   BIBLIOTECA
   ========================================================= */
async function showLibrary() {
  await flushSave();
  closeEditor();
  $('#editor').hidden = true;
  $('#library').hidden = false;
  await renderLibrary();
  window.dispatchEvent(new Event('caderno:library'));
}

async function renderLibrary() {
  const list = (await DB.all('notebooks')).filter((n) => !n.deleted).sort((a, b) => b.updated - a.updated);
  const grid = $('#grid');
  grid.innerHTML = '';
  $('#emptyMsg').hidden = list.length > 0;
  for (const nb of list) {
    const card = document.createElement('div');
    card.className = 'card';
    const date = new Date(nb.updated).toLocaleDateString('pt-BR', { day: '2-digit', month: 'short' });
    card.innerHTML = `
      <button class="cover" style="background:${nb.color}"><span class="cover-name"></span></button>
      <div class="card-meta">
        <div><div class="t"></div><div class="m">${nb.pageIds.length} pág. · ${date}</div></div>
        <button class="card-more" aria-label="Opções">⋯</button>
      </div>`;
    card.querySelector('.cover-name').textContent = nb.name;
    card.querySelector('.t').textContent = nb.name;
    card.querySelector('.cover').onclick = () => openNotebook(nb.id);
    card.querySelector('.card-more').onclick = (e) => showMenu(e.currentTarget, [
      { label: 'Abrir', action: () => openNotebook(nb.id) },
      { label: 'Renomear', action: async () => {
        const n = prompt('Nome do caderno', nb.name);
        if (n && n.trim()) { nb.name = n.trim(); nb.updated = Date.now(); await DB.put('notebooks', nb); Sync.schedule(); renderLibrary(); }
      } },
      { label: 'Exportar PDF', action: async () => { await openNotebook(nb.id); exportPdf(); } },
      '-',
      { label: 'Excluir', danger: true, action: async () => {
        if (!confirm(`Excluir “${nb.name}”? Isso não pode ser desfeito.`)) return;
        await deleteNotebook(nb); showLibrary();
      } },
    ]);
    grid.appendChild(card);
  }
}

async function deleteNotebook(nb) {
  const imgIds = [];
  for (const pid of nb.pageIds) {
    const p = await DB.get('pages', pid);
    if (p && p.bgId) { imgIds.push(p.bgId); await DB.del('images', p.bgId); }
    await DB.del('pages', pid);
  }
  if (nb.syncedAt) {
    // marca de exclusão para os outros aparelhos
    await DB.put('notebooks', { id: nb.id, name: nb.name, deleted: true, updated: Date.now(), syncedAt: nb.syncedAt, pageIds: [], imgIds });
    Sync.schedule(500);
  } else {
    await DB.del('notebooks', nb.id);
  }
}

function newPage(template) {
  return { id: uid(), h: A4_H, template: template || 'lined', strokes: [], bgId: null };
}

async function createNotebook(name, color, template, pages) {
  const ps = pages && pages.length ? pages : [newPage(template)];
  const nb = { id: uid(), name: name || 'Sem título', color, template, pageIds: ps.map((p) => p.id), created: Date.now(), updated: Date.now() };
  for (const p of ps) await DB.put('pages', p);
  await DB.put('notebooks', nb);
  Sync.schedule();
  return nb;
}

function openNewDialog() {
  const dlg = $('#dlgNew');
  let color = COVERS[Math.floor(Math.random() * COVERS.length)];
  let template = 'lined';
  $('#newName').value = '';
  const covers = $('#newCovers'); covers.innerHTML = '';
  for (const c of COVERS) {
    const b = document.createElement('button');
    b.type = 'button'; b.style.background = c;
    b.className = c === color ? 'active' : '';
    b.onclick = () => { color = c; [...covers.children].forEach((x) => x.classList.toggle('active', x === b)); };
    covers.appendChild(b);
  }
  const tpl = $('#newTemplates'); tpl.innerHTML = '';
  for (const [k, v] of Object.entries(TEMPLATES)) {
    const b = document.createElement('button');
    b.type = 'button'; b.textContent = v;
    b.className = k === template ? 'active' : '';
    b.onclick = () => { template = k; [...tpl.children].forEach((x) => x.classList.toggle('active', x === b)); };
    tpl.appendChild(b);
  }
  dlg.onclose = async () => {
    if (dlg.returnValue !== 'ok') return;
    const nb = await createNotebook($('#newName').value.trim(), color, template);
    openNotebook(nb.id);
  };
  dlg.returnValue = '';
  dlg.showModal();
}

/* =========================================================
   IMPORTAÇÃO (PDF / imagem)
   ========================================================= */
async function canvasToBlob(c, q = 0.85) {
  return new Promise((res) => c.toBlob(res, 'image/jpeg', q));
}

async function fileToPages(file) {
  const pages = [];
  if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) {
    toast('Carregando PDF…', 0);
    await loadScript(LIBS.pdfjs);
    pdfjsLib.GlobalWorkerOptions.workerSrc = LIBS.pdfjsWorker;
    const pdf = await pdfjsLib.getDocument({ data: await file.arrayBuffer() }).promise;
    for (let i = 1; i <= pdf.numPages; i++) {
      toast(`Importando página ${i} de ${pdf.numPages}…`, 0);
      const pg = await pdf.getPage(i);
      const vp1 = pg.getViewport({ scale: 1 });
      const scale = 1400 / vp1.width;
      const vp = pg.getViewport({ scale });
      const c = document.createElement('canvas');
      c.width = Math.round(vp.width); c.height = Math.round(vp.height);
      const ctx = c.getContext('2d');
      ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
      await pg.render({ canvasContext: ctx, viewport: vp }).promise;
      const bgId = uid();
      await DB.put('images', await canvasToBlob(c), bgId);
      c.width = c.height = 0;
      pages.push({ id: uid(), h: Math.round(PAGE_W * vp1.height / vp1.width), template: 'blank', strokes: [], bgId });
    }
  } else if (file.type.startsWith('image/')) {
    toast('Importando imagem…', 0);
    const img = await blobToImage(file);
    const w = Math.min(1400, img.naturalWidth);
    const h = Math.round(w * img.naturalHeight / img.naturalWidth);
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h);
    ctx.drawImage(img, 0, 0, w, h);
    const bgId = uid();
    await DB.put('images', await canvasToBlob(c), bgId);
    pages.push({ id: uid(), h: Math.round(PAGE_W * h / w), template: 'blank', strokes: [], bgId });
  }
  toast('Importado ✓');
  return pages;
}

function blobToImage(blob) {
  return new Promise((res, rej) => {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => res(img);
    img.onerror = () => { URL.revokeObjectURL(url); rej(new Error('imagem inválida')); };
    img.src = url;
  });
}

const bgCache = new Map(); // bgId -> Promise<HTMLImageElement>
function getBg(bgId) {
  if (!bgCache.has(bgId)) {
    bgCache.set(bgId, DB.get('images', bgId).then((b) => (b ? blobToImage(b) : null)).catch(() => null));
  }
  return bgCache.get(bgId);
}

/* =========================================================
   EDITOR
   ========================================================= */
const scroller = $('#scroller');
const pagesEl = $('#pages');

async function openNotebook(id) {
  await flushSave();
  const nb = await DB.get('notebooks', id);
  if (!nb) return;
  closeEditor();
  S.nb = nb;
  S.pages = (await Promise.all(nb.pageIds.map((pid) => DB.get('pages', pid)))).filter(Boolean);
  if (!S.pages.length) { const p = newPage(nb.template); S.pages.push(p); S.dirtyPages.add(p.id); }
  S.undo = []; S.redo = []; S.zoom = 1; S.current = 0;
  $('#library').hidden = true;
  $('#editor').hidden = false;
  $('#nbTitle').textContent = nb.name;
  buildPages();
  scroller.scrollTo(0, 0);
  updateUndoButtons();
  updatePageInd();
}

/* Recarrega o caderno aberto (após receber versão do Drive), mantendo a posição */
async function reloadOpenNotebook() {
  if (!S.nb) return;
  const nb = await DB.get('notebooks', S.nb.id);
  if (!nb) return;
  const top = scroller.scrollTop, left = scroller.scrollLeft;
  S.nb = nb;
  S.pages = (await Promise.all(nb.pageIds.map((pid) => DB.get('pages', pid)))).filter(Boolean);
  S.undo = []; S.redo = [];
  $('#nbTitle').textContent = nb.name;
  buildPages();
  scroller.scrollTop = top; scroller.scrollLeft = left;
  updateUndoButtons();
  updatePageInd();
}

function closeEditor() {
  for (const v of S.views.values()) detach(v);
  S.views.clear();
  pagesEl.innerHTML = '';
  S.nb = null; S.pages = [];
}

function baseWidth() { return Math.min(scroller.clientWidth - 32, 980); }
function pageCssW() { return Math.round(baseWidth() * S.zoom); }

const io = new IntersectionObserver((entries) => {
  for (const en of entries) {
    const v = S.views.get(en.target.dataset.id);
    if (!v) continue;
    if (en.isIntersecting) attach(v); else detach(v);
  }
  updatePageInd();
}, { root: scroller, rootMargin: '120% 0px' });

function buildPages() {
  for (const v of S.views.values()) { io.unobserve(v.el); detach(v); }
  S.views.clear();
  pagesEl.innerHTML = '';
  S.pages.forEach((p, i) => {
    const el = document.createElement('div');
    el.className = 'page';
    el.dataset.id = p.id;
    const n = document.createElement('span');
    n.className = 'pnum'; n.textContent = i + 1;
    el.appendChild(n);
    pagesEl.appendChild(el);
    const v = { page: p, el, bg: null, ink: null, attached: false };
    S.views.set(p.id, v);
    sizeView(v);
    io.observe(el);
  });
}

function sizeView(v) {
  const w = pageCssW();
  v.el.style.width = w + 'px';
  v.el.style.height = Math.round(w * v.page.h / PAGE_W) + 'px';
}

function canvasScale(v) {
  const w = pageCssW(), h = w * v.page.h / PAGE_W;
  const dpr = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(5e6 / (w * h)));
  return { pw: Math.round(w * dpr), ph: Math.round(h * dpr) };
}

function attach(v) {
  if (v.attached) return;
  v.bg = document.createElement('canvas');
  v.ink = document.createElement('canvas');
  v.el.prepend(v.ink); v.el.prepend(v.bg);
  v.attached = true;
  renderView(v);
}

function detach(v) {
  if (!v.attached) return;
  for (const c of [v.bg, v.ink]) { c.width = c.height = 0; c.remove(); }
  v.bg = v.ink = null;
  v.attached = false;
}

function renderView(v) {
  if (!v.attached) return;
  const { pw, ph } = canvasScale(v);
  for (const c of [v.bg, v.ink]) { if (c.width !== pw) c.width = pw; if (c.height !== ph) c.height = ph; }
  renderBg(v);
  renderInk(v);
}

function renderBg(v) {
  const c = v.bg; if (!c) return;
  const ctx = c.getContext('2d');
  const k = c.width / PAGE_W;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  ctx.setTransform(k, 0, 0, k, 0, 0);
  drawTemplate(ctx, v.page);
  if (v.page.bgId) {
    const id = v.page.bgId;
    getBg(id).then((img) => {
      if (!img || v.bg !== c || v.page.bgId !== id) return;
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.drawImage(img, 0, 0, c.width, c.height);
    });
  }
}

function drawTemplate(ctx, page) {
  const h = page.h, t = page.template;
  ctx.save();
  if (t === 'lined') {
    ctx.strokeStyle = '#b9cbe6'; ctx.lineWidth = 1.2;
    ctx.beginPath();
    for (let y = 120; y < h - 30; y += 40) { ctx.moveTo(0, y); ctx.lineTo(PAGE_W, y); }
    ctx.stroke();
    ctx.strokeStyle = '#eba1a1'; ctx.beginPath(); ctx.moveTo(110, 0); ctx.lineTo(110, h); ctx.stroke();
  } else if (t === 'grid') {
    ctx.strokeStyle = '#d3dbe7'; ctx.lineWidth = 1;
    ctx.beginPath();
    for (let x = 25; x < PAGE_W; x += 40) { ctx.moveTo(x, 0); ctx.lineTo(x, h); }
    for (let y = 25; y < h; y += 40) { ctx.moveTo(0, y); ctx.lineTo(PAGE_W, y); }
    ctx.stroke();
  } else if (t === 'dots') {
    ctx.fillStyle = '#aab4c3';
    for (let x = 25; x < PAGE_W; x += 40) for (let y = 25; y < h; y += 40) { ctx.beginPath(); ctx.arc(x, y, 1.8, 0, 7); ctx.fill(); }
  }
  ctx.restore();
}

function renderInk(v) {
  const c = v.ink; if (!c) return;
  const ctx = c.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, c.width, c.height);
  const k = c.width / PAGE_W;
  ctx.setTransform(k, 0, 0, k, 0, 0);
  for (const s of v.page.strokes) drawStroke(ctx, s);
}

/* Desenha um traço. s.pts = [x, y, pressão, x, y, pressão, ...] */
function drawStroke(ctx, s) {
  const p = s.pts, n = p.length / 3;
  if (!n) return;
  ctx.save();
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  ctx.strokeStyle = ctx.fillStyle = s.color;
  if (s.tool === 'highlighter') {
    ctx.globalAlpha = 0.35;
    ctx.lineWidth = s.size;
    ctx.beginPath();
    ctx.moveTo(p[0], p[1]);
    if (n === 1) ctx.lineTo(p[0] + 0.1, p[1]);
    for (let i = 1; i < n - 1; i++) {
      const mx = (p[i * 3] + p[i * 3 + 3]) / 2, my = (p[i * 3 + 1] + p[i * 3 + 4]) / 2;
      ctx.quadraticCurveTo(p[i * 3], p[i * 3 + 1], mx, my);
    }
    if (n > 1) ctx.lineTo(p[(n - 1) * 3], p[(n - 1) * 3 + 1]);
    ctx.stroke();
  } else {
    const w = (pr) => s.size * (0.35 + 1.1 * pr);
    if (n === 1) {
      ctx.beginPath(); ctx.arc(p[0], p[1], w(p[2]) / 2, 0, 7); ctx.fill();
    } else {
      let sx = p[0], sy = p[1];
      for (let i = 1; i < n; i++) {
        const cx = p[i * 3 - 3], cy = p[i * 3 - 2];
        const last = i === n - 1;
        const ex = last ? p[i * 3] : (cx + p[i * 3]) / 2;
        const ey = last ? p[i * 3 + 1] : (cy + p[i * 3 + 1]) / 2;
        ctx.lineWidth = w((p[i * 3 - 1] + p[i * 3 + 2]) / 2);
        ctx.beginPath();
        ctx.moveTo(sx, sy);
        if (i === 1) ctx.lineTo(ex, ey); else ctx.quadraticCurveTo(cx, cy, ex, ey);
        ctx.stroke();
        sx = ex; sy = ey;
      }
    }
  }
  ctx.restore();
}

/* ---------------- Desenho (Pointer Events) ---------------- */
let cur = null;          // gesto de desenho/apagar em andamento
const live = document.createElement('canvas');
let liveRaf = 0;
let fingerCount = 0;     // dedos na tela (touch)

function penOnly() { return prefs.penOnly === true; }
function setPenOnly(on, user) {
  prefs.penOnly = on;
  if (user) savePrefs();
  document.body.classList.toggle('pen-only', on);
  $('#btnPenOnly').classList.toggle('active', on);
}

function toLogical(v, e) {
  const r = v.el.getBoundingClientRect();
  const pr = e.pointerType === 'pen' ? (e.pressure || 0.5) : 0.5;
  return {
    x: Math.round(((e.clientX - r.left) / r.width) * PAGE_W * 10) / 10,
    y: Math.round(((e.clientY - r.top) / r.height) * v.page.h * 10) / 10,
    p: Math.round(clamp(pr, 0.05, 1) * 100) / 100,
  };
}

function onPointerDown(e) {
  if (e.pointerType === 'pen' && prefs.penOnly === null) setPenOnly(true, false);
  if (e.pointerType === 'touch' && (penOnly() || fingerCount > 1)) return;
  if (e.pointerType === 'mouse' && e.button !== 0) return;
  if (cur) return;
  const pageEl = e.target.closest('.page');
  if (!pageEl) return;
  const v = S.views.get(pageEl.dataset.id);
  if (!v) return;
  attach(v);
  e.preventDefault();
  try { pageEl.setPointerCapture(e.pointerId); } catch (_) {}
  const pt = toLogical(v, e);
  const { pw, ph } = canvasScale(v);
  live.width = pw; live.height = ph;
  v.el.appendChild(live);
  const before = v.page.strokes.slice();
  if (prefs.tool === 'eraser') {
    cur = { id: e.pointerId, type: e.pointerType, v, erase: true, before, changed: false, last: pt };
    eraseAt(pt);
  } else {
    const tool = prefs.tool;
    const stroke = { id: Math.random().toString(36).slice(2, 10), tool, color: prefs.color[tool], size: SIZES[tool][prefs.size[tool]], pts: [pt.x, pt.y, pt.p] };
    cur = { id: e.pointerId, type: e.pointerType, v, stroke, before, last: pt };
  }
  scheduleLive();
}

function onPointerMove(e) {
  if (!cur || e.pointerId !== cur.id) return;
  e.preventDefault();
  const evs = (e.getCoalescedEvents && e.getCoalescedEvents().length) ? e.getCoalescedEvents() : [e];
  for (const ev of evs) {
    const pt = toLogical(cur.v, ev);
    const d = Math.hypot(pt.x - cur.last.x, pt.y - cur.last.y);
    if (cur.erase) { cur.last = pt; eraseAt(pt); continue; }
    if (d < 0.8) continue;
    cur.stroke.pts.push(pt.x, pt.y, pt.p);
    cur.last = pt;
  }
  scheduleLive();
}

function onPointerUp(e) {
  if (!cur || e.pointerId !== cur.id) return;
  const c = cur; cur = null;
  live.remove();
  if (c.erase) {
    if (c.changed) commitChange(c.v.page, c.before);
    return;
  }
  c.v.page.strokes.push(c.stroke);
  if (c.v.ink) {
    const ctx = c.v.ink.getContext('2d');
    const k = c.v.ink.width / PAGE_W;
    ctx.setTransform(k, 0, 0, k, 0, 0);
    drawStroke(ctx, c.stroke);
  }
  commitChange(c.v.page, c.before);
}

function cancelStroke() {
  if (!cur) return;
  if (cur.erase && cur.changed) { cur.v.page.strokes = cur.before; renderInk(cur.v); }
  cur = null; live.remove();
}

function scheduleLive() {
  if (liveRaf) return;
  liveRaf = requestAnimationFrame(() => {
    liveRaf = 0;
    if (!cur) return;
    const ctx = live.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, live.width, live.height);
    const k = live.width / PAGE_W;
    ctx.setTransform(k, 0, 0, k, 0, 0);
    if (cur.erase) {
      ctx.strokeStyle = 'rgba(80,80,80,.7)'; ctx.lineWidth = 1.5 / k * 1.5;
      ctx.beginPath(); ctx.arc(cur.last.x, cur.last.y, SIZES.eraser[prefs.size.eraser], 0, 7); ctx.stroke();
    } else {
      drawStroke(ctx, cur.stroke);
    }
  });
}

function distSeg(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  const l2 = dx * dx + dy * dy;
  let t = l2 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0;
  t = clamp(t, 0, 1);
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

function eraseAt(pt) {
  const v = cur.v, r = SIZES.eraser[prefs.size.eraser];
  const keep = [];
  let removed = false;
  for (const s of v.page.strokes) {
    const p = s.pts, n = p.length / 3, lim = r + s.size / 2;
    let hit = false;
    if (n === 1) hit = Math.hypot(pt.x - p[0], pt.y - p[1]) < lim;
    for (let i = 1; i < n && !hit; i++) {
      if (distSeg(pt.x, pt.y, p[i * 3 - 3], p[i * 3 - 2], p[i * 3], p[i * 3 + 1]) < lim) hit = true;
    }
    if (hit) removed = true; else keep.push(s);
  }
  if (removed) { v.page.strokes = keep; cur.changed = true; renderInk(v); }
}

/* ---------------- Desfazer / refazer ---------------- */
function commitChange(page, before) {
  S.undo.push({ pageId: page.id, before, after: page.strokes.slice() });
  if (S.undo.length > 200) S.undo.shift();
  S.redo = [];
  markDirty(page);
  updateUndoButtons();
}

function applyHistory(from, to, key) {
  const a = from.pop();
  if (!a) return;
  const v = S.views.get(a.pageId);
  if (!v) return;
  v.page.strokes = a[key].slice();
  to.push(a);
  renderInk(v);
  markDirty(v.page);
  updateUndoButtons();
  v.el.scrollIntoView({ block: 'nearest' });
}
function undo() { cancelStroke(); applyHistory(S.undo, S.redo, 'before'); }
function redo() { cancelStroke(); applyHistory(S.redo, S.undo, 'after'); }
function updateUndoButtons() {
  $('#btnUndo').disabled = !S.undo.length;
  $('#btnRedo').disabled = !S.redo.length;
}

/* ---------------- Salvamento ---------------- */
let saveTimer = 0;
function markDirty(page) {
  if (page) S.dirtyPages.add(page.id);
  S.metaDirty = true;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(flushSave, 700);
}
async function flushSave() {
  clearTimeout(saveTimer);
  if (!S.nb || (!S.metaDirty && !S.dirtyPages.size)) return;
  const ids = [...S.dirtyPages];
  S.dirtyPages.clear();
  S.metaDirty = false;
  for (const id of ids) {
    const p = S.pages.find((x) => x.id === id);
    if (p) await DB.put('pages', p);
  }
  S.nb.pageIds = S.pages.map((p) => p.id);
  S.nb.updated = Date.now();
  await DB.put('notebooks', S.nb);
  Sync.schedule();
}
document.addEventListener('visibilitychange', () => { if (document.hidden) flushSave(); });
window.addEventListener('pagehide', () => flushSave());

/* ---------------- Páginas ---------------- */
function currentIndex() {
  const mid = scroller.getBoundingClientRect().top + scroller.clientHeight / 2;
  let best = 0, bestD = Infinity;
  S.pages.forEach((p, i) => {
    const v = S.views.get(p.id); if (!v) return;
    const r = v.el.getBoundingClientRect();
    const d = mid < r.top ? r.top - mid : mid > r.bottom ? mid - r.bottom : 0;
    if (d < bestD) { bestD = d; best = i; }
  });
  return best;
}
function updatePageInd() {
  if (!S.pages.length) return;
  S.current = currentIndex();
  $('#pageInd').textContent = `${S.current + 1} / ${S.pages.length}`;
}

function insertPages(pages, at) {
  S.pages.splice(at, 0, ...pages);
  pages.forEach((p) => S.dirtyPages.add(p.id));
  buildPages();
  markDirty();
  const v = S.views.get(pages[0].id);
  requestAnimationFrame(() => v && v.el.scrollIntoView({ block: 'start', behavior: 'smooth' }));
}

async function deleteCurrentPage() {
  if (S.pages.length <= 1) { toast('O caderno precisa de ao menos uma página'); return; }
  const i = currentIndex();
  if (!confirm(`Excluir a página ${i + 1}?`)) return;
  const [p] = S.pages.splice(i, 1);
  S.undo = S.undo.filter((a) => a.pageId !== p.id);
  S.redo = S.redo.filter((a) => a.pageId !== p.id);
  updateUndoButtons();
  await DB.del('pages', p.id);
  if (p.bgId) await DB.del('images', p.bgId);
  buildPages();
  markDirty();
  updatePageInd();
}

function setTemplate(t, all) {
  const targets = all ? S.pages : [S.pages[currentIndex()]];
  for (const p of targets) {
    if (p.bgId) continue;
    p.template = t;
    const v = S.views.get(p.id);
    if (v) renderBg(v);
    S.dirtyPages.add(p.id);
  }
  S.nb.template = t;
  markDirty();
}

/* ---------------- Zoom ---------------- */
function setZoom(z, fx, fy, tx, ty) {
  z = clamp(z, 0.5, 3);
  const old = S.zoom;
  if (Math.abs(z - old) < 0.001) return;
  if (fx === undefined) { fx = tx = scroller.clientWidth / 2; fy = ty = scroller.clientHeight / 2; }
  const cx = scroller.scrollLeft + fx - pagesEl.offsetLeft;
  const cy = scroller.scrollTop + fy - pagesEl.offsetTop;
  const oldW = pageCssW();
  S.zoom = z;
  const ratio = pageCssW() / oldW;
  for (const v of S.views.values()) sizeView(v);
  for (const v of S.views.values()) if (v.attached) renderView(v);
  scroller.scrollLeft = cx * ratio + pagesEl.offsetLeft - tx;
  scroller.scrollTop = cy * ratio + pagesEl.offsetTop - ty;
}

/* Pinça com dois dedos + bloqueio de rolagem pela Pencil */
let pinch = null;
function fingers(list) { return [...list].filter((t) => t.touchType !== 'stylus'); }
function centroid(a, b) { return { x: (a.clientX + b.clientX) / 2, y: (a.clientY + b.clientY) / 2 }; }

scroller.addEventListener('touchstart', (e) => {
  fingerCount = fingers(e.touches).length;
  if ([...e.changedTouches].some((t) => t.touchType === 'stylus')) { e.preventDefault(); return; }
  const f = fingers(e.touches);
  if (f.length === 2) {
    e.preventDefault();
    if (cur && cur.type === 'touch') cancelStroke();
    const r = scroller.getBoundingClientRect();
    const c = centroid(f[0], f[1]);
    pinch = {
      d0: Math.hypot(f[0].clientX - f[1].clientX, f[0].clientY - f[1].clientY) || 1,
      c0: { x: c.x - r.left, y: c.y - r.top }, c1: { x: c.x - r.left, y: c.y - r.top },
      scale: 1, z0: S.zoom,
    };
    pagesEl.style.transformOrigin =
      `${scroller.scrollLeft + pinch.c0.x - pagesEl.offsetLeft}px ${scroller.scrollTop + pinch.c0.y - pagesEl.offsetTop}px`;
  }
}, { passive: false });

scroller.addEventListener('touchmove', (e) => {
  if ([...e.changedTouches].some((t) => t.touchType === 'stylus')) { e.preventDefault(); return; }
  const f = fingers(e.touches);
  if (pinch && f.length >= 2) {
    e.preventDefault();
    const r = scroller.getBoundingClientRect();
    const c = centroid(f[0], f[1]);
    const d = Math.hypot(f[0].clientX - f[1].clientX, f[0].clientY - f[1].clientY);
    pinch.scale = clamp(d / pinch.d0, 0.5 / pinch.z0, 3 / pinch.z0);
    pinch.c1 = { x: c.x - r.left, y: c.y - r.top };
    const dx = pinch.c1.x - pinch.c0.x, dy = pinch.c1.y - pinch.c0.y;
    pagesEl.style.transform = `translate(${dx}px, ${dy}px) scale(${pinch.scale})`;
  }
}, { passive: false });

function endTouch(e) {
  fingerCount = fingers(e.touches).length;
  if (pinch && fingerCount < 2) {
    const p = pinch; pinch = null;
    pagesEl.style.transform = '';
    setZoom(p.z0 * p.scale, p.c0.x, p.c0.y, p.c1.x, p.c1.y);
  }
}
scroller.addEventListener('touchend', endTouch);
scroller.addEventListener('touchcancel', endTouch);
document.addEventListener('gesturestart', (e) => e.preventDefault());

/* =========================================================
   EXPORTAR PDF
   ========================================================= */
async function exportPdf() {
  if (!S.nb) return;
  await flushSave();
  try {
    toast('Gerando PDF…', 0);
    await loadScript(LIBS.jspdf);
    const { jsPDF } = window.jspdf;
    let doc = null;
    for (let i = 0; i < S.pages.length; i++) {
      toast(`Gerando PDF… ${i + 1}/${S.pages.length}`, 0);
      const p = S.pages[i];
      const W = 1240, H = Math.round(W * p.h / PAGE_W);
      const c = document.createElement('canvas');
      c.width = W; c.height = H;
      const ctx = c.getContext('2d');
      ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, W, H);
      const k = W / PAGE_W;
      ctx.setTransform(k, 0, 0, k, 0, 0);
      drawTemplate(ctx, p);
      if (p.bgId) {
        const img = await getBg(p.bgId);
        if (img) { ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(img, 0, 0, W, H); ctx.setTransform(k, 0, 0, k, 0, 0); }
      }
      for (const s of p.strokes) drawStroke(ctx, s);
      const wPt = 595.28, hPt = wPt * p.h / PAGE_W;
      const orient = wPt > hPt ? 'l' : 'p';
      if (!doc) doc = new jsPDF({ unit: 'pt', format: [wPt, hPt], orientation: orient, compress: true });
      else doc.addPage([wPt, hPt], orient);
      doc.addImage(c.toDataURL('image/jpeg', 0.9), 'JPEG', 0, 0, wPt, hPt);
      c.width = c.height = 0;
    }
    const name = (S.nb.name || 'caderno').replace(/[\\/:*?"<>|]/g, '_') + '.pdf';
    const blob = doc.output('blob');
    const file = new File([blob], name, { type: 'application/pdf' });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      $('#toast').hidden = true;
      try { await navigator.share({ files: [file], title: S.nb.name }); } catch (_) {}
    } else {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob); a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 10000);
      toast('PDF exportado ✓');
    }
  } catch (err) {
    console.error(err);
    toast('Erro ao exportar: ' + err.message, 4000);
  }
}

/* =========================================================
   TOOLBAR
   ========================================================= */
function renderToolbar() {
  document.querySelectorAll('.tool').forEach((b) => b.classList.toggle('active', b.dataset.tool === prefs.tool));
  const colors = $('#colors');
  colors.innerHTML = '';
  if (prefs.tool !== 'eraser') {
    const list = prefs.tool === 'pen' ? PEN_COLORS : HL_COLORS;
    const curColor = prefs.color[prefs.tool];
    for (const c of list) {
      const b = document.createElement('button');
      b.className = 'swatch' + (c === curColor ? ' active' : '');
      b.style.background = c;
      b.onclick = () => { prefs.color[prefs.tool] = c; savePrefs(); renderToolbar(); };
      colors.appendChild(b);
    }
    const custom = document.createElement('label');
    custom.className = 'swatch custom' + (list.includes(curColor) ? '' : ' active');
    custom.title = 'Outra cor';
    const inp = document.createElement('input');
    inp.type = 'color'; inp.value = curColor;
    inp.oninput = () => { prefs.color[prefs.tool] = inp.value; savePrefs(); };
    inp.onchange = () => renderToolbar();
    custom.appendChild(inp);
    colors.appendChild(custom);
  }
  const sizes = $('#sizes');
  sizes.innerHTML = '';
  SIZES[prefs.tool].forEach((_, i) => {
    const b = document.createElement('button');
    b.className = 'size-btn' + (prefs.size[prefs.tool] === i ? ' active' : '');
    const d = [5, 9, 14][i];
    b.innerHTML = `<i style="width:${d}px;height:${d}px"></i>`;
    b.onclick = () => { prefs.size[prefs.tool] = i; savePrefs(); renderToolbar(); };
    sizes.appendChild(b);
  });
}

function bindUI() {
  $('#btnNew').onclick = openNewDialog;
  $('#btnImportPdf').onclick = () => $('#filePdf').click();
  $('#filePdf').onchange = async (e) => {
    const f = e.target.files[0]; e.target.value = '';
    if (!f) return;
    try {
      const pages = await fileToPages(f);
      if (!pages.length) return;
      const nb = await createNotebook(f.name.replace(/\.pdf$/i, ''), COVERS[5], 'blank', pages);
      openNotebook(nb.id);
    } catch (err) { console.error(err); toast('Erro ao importar: ' + err.message, 4000); }
  };
  $('#fileInsert').onchange = async (e) => {
    const f = e.target.files[0]; e.target.value = '';
    if (!f || !S.nb) return;
    try {
      const pages = await fileToPages(f);
      if (pages.length) insertPages(pages, currentIndex() + 1);
    } catch (err) { console.error(err); toast('Erro ao importar: ' + err.message, 4000); }
  };

  $('#btnBack').onclick = showLibrary;
  document.querySelectorAll('.sync-btn').forEach((b) => (b.onclick = (e) => Sync.menu(e.currentTarget)));
  $('#nbTitle').onclick = () => {
    const n = prompt('Nome do caderno', S.nb.name);
    if (n && n.trim()) { S.nb.name = n.trim(); $('#nbTitle').textContent = S.nb.name; markDirty(); }
  };
  document.querySelectorAll('.tool').forEach((b) => (b.onclick = () => { prefs.tool = b.dataset.tool; savePrefs(); renderToolbar(); }));
  $('#btnUndo').onclick = undo;
  $('#btnRedo').onclick = redo;
  $('#btnPenOnly').onclick = () => {
    setPenOnly(!penOnly(), true);
    toast(penOnly() ? 'Só a Pencil escreve — o dedo rola a página' : 'Dedo também escreve — use 2 dedos para rolar/zoom');
  };
  $('#btnZoomIn').onclick = () => setZoom(S.zoom * 1.25);
  $('#btnZoomOut').onclick = () => setZoom(S.zoom / 1.25);
  $('#btnAddPage').onclick = () => insertPages([newPage(S.nb.template)], currentIndex() + 1);
  $('#btnTemplate').onclick = (e) => {
    const cp = S.pages[currentIndex()];
    showMenu(e.currentTarget, [
      ...Object.entries(TEMPLATES).map(([k, v]) => ({ label: `${v} (esta página)`, checked: cp.template === k && !cp.bgId, action: () => setTemplate(k, false) })),
      '-',
      ...Object.entries(TEMPLATES).map(([k, v]) => ({ label: `${v} (todas)`, action: () => setTemplate(k, true) })),
    ]);
  };
  $('#btnMore').onclick = (e) => showMenu(e.currentTarget, [
    { label: 'Inserir PDF ou imagem', action: () => $('#fileInsert').click() },
    { label: 'Exportar PDF', action: exportPdf },
    '-',
    { label: 'Limpar página', action: () => {
      const p = S.pages[currentIndex()];
      if (!p.strokes.length) return;
      const before = p.strokes.slice();
      p.strokes = [];
      renderInk(S.views.get(p.id));
      commitChange(p, before);
    } },
    { label: 'Excluir página', danger: true, action: deleteCurrentPage },
  ]);

  pagesEl.addEventListener('pointerdown', onPointerDown);
  window.addEventListener('pointermove', onPointerMove, { passive: false });
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', (e) => { if (cur && e.pointerId === cur.id) cancelStroke(); });
  scroller.addEventListener('scroll', () => requestAnimationFrame(updatePageInd), { passive: true });

  let rzTimer;
  window.addEventListener('resize', () => {
    clearTimeout(rzTimer);
    rzTimer = setTimeout(() => {
      if (!S.nb) return;
      for (const v of S.views.values()) sizeView(v);
      for (const v of S.views.values()) if (v.attached) renderView(v);
    }, 150);
  });

  document.addEventListener('keydown', (e) => {
    if (!S.nb || e.target.tagName === 'INPUT') return;
    const mod = e.metaKey || e.ctrlKey;
    if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); }
    else if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); }
  });
}

/* =========================================================
   INÍCIO
   ========================================================= */
(async function init() {
  const returnNb = Sync.handleRedirect();
  bindUI();
  setPenOnly(prefs.penOnly === true, false);
  renderToolbar();
  try {
    await DB.open();
  } catch (err) {
    toast('Armazenamento indisponível: ' + err.message, 0);
    return;
  }
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
    // nova versão instalada: recarrega se estiver na biblioteca
    let reloading = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (reloading) return;
      const go = () => { if (!reloading) { reloading = true; location.reload(); } };
      if (!S.nb) go(); else window.addEventListener('caderno:library', go, { once: true });
    });
  }
  if (Sync.start()) return; // renovando acesso ao Google
  if (returnNb && (await DB.get('notebooks', returnNb))) await openNotebook(returnNb);
  else await showLibrary();
})();
