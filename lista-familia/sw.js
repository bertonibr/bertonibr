/* Service worker: o app abre mesmo sem internet.
   Cada versão guarda TODOS os arquivos juntos (cache-first) e só troca de
   versão inteira, para nunca misturar arquivos antigos e novos. */
const CACHE = 'lista-familia-v21';
const SHELL = [
  './', './index.html', './app.js', './compras.js', './config.js', './padrao.js', './style.css', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './apple-touch-icon.png',
  './lib/firebase-app-compat.js', './lib/firebase-auth-compat.js', './lib/firebase-firestore-compat.js',
];

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const c = await caches.open(CACHE);
    await c.addAll(SHELL.map((u) => new Request(u, { cache: 'reload' })));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    // apaga só versões antigas DESTE app (o Caderno usa o mesmo site)
    for (const k of await caches.keys()) if (k.startsWith('lista-familia-') && k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin || !url.pathname.startsWith(new URL('./', self.registration.scope).pathname)) return;
  if (url.pathname.endsWith('/reset.html')) return;   // página de socorro sempre da internet
  e.respondWith((async () => {
    const c = await caches.open(CACHE);
    const hit = await c.match(req, { ignoreSearch: true });
    if (hit) return hit;
    try { return await fetch(req); }
    catch (_) { return (req.mode === 'navigate' && await c.match('./index.html')) || Response.error(); }
  })());
});
