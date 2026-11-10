/* Service worker: funciona offline (cache do app + bibliotecas de PDF). */
const CACHE = 'caderno-v13';
const SHELL = [
  './', './index.html', './app.js', './sync.js', './math.js', './config.js', './style.css', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './apple-touch-icon.png',
  './lib/jspdf.umd.min.js', './lib/pdf.min.js', './lib/pdf.worker.min.js', './model/digits.bin',
];

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const c = await caches.open(CACHE);
    await c.addAll(SHELL.map((u) => new Request(u, { cache: 'reload' })));
    self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

/* Responde do cache e atualiza em segundo plano (stale-while-revalidate). */
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  e.respondWith((async () => {
    const c = await caches.open(CACHE);
    const hit = await c.match(req, { ignoreSearch: true });
    const net = fetch(req).then((res) => {
      if (res && res.ok) c.put(req, res.clone());
      return res;
    }).catch(() => null);
    if (hit) { e.waitUntil(net); return hit; }
    const res = await net;
    return res || (req.mode === 'navigate' ? c.match('./index.html') : Response.error());
  })());
});
