// Sauti service worker.
// Opens instantly from the saved copy, then checks for a newer version in the background
// and tells the app when one is ready. Music downloads live in IndexedDB, not here.
const VERSION = 'sauti-v17';
const SHELL = ['./', './index.html', './install.html', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png', './icons/apple-touch-icon.png', './icons/logo.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});

async function refreshPage(req, key){
  try {
    const res = await fetch(req, { cache: 'no-cache' });
    if (!res.ok) return;
    const cache = await caches.open(VERSION), old = await cache.match(key);
    const [a, b] = await Promise.all([res.clone().text(), old ? old.clone().text() : Promise.resolve('')]);
    await cache.put(key, res);
    if (old && a !== b) (await self.clients.matchAll({ type: 'window' })).forEach(c => c.postMessage({ type: 'sauti-updated' }));
  } catch {}
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // App pages: saved copy first (instant), update quietly in the background
  if (req.mode === 'navigate') {
    const key = url.pathname.endsWith('install.html') ? './install.html' : './index.html';
    e.respondWith(caches.match(key).then(hit => {
      const net = refreshPage(req, key);
      if (hit) { e.waitUntil(net); return hit; }
      return net.then(() => caches.match(key)).then(r => r || fetch(req));
    }));
    return;
  }

  // Own static files and Google Fonts: cache first, refresh in background
  if (url.origin === location.origin || url.hostname.endsWith('fonts.googleapis.com') || url.hostname.endsWith('fonts.gstatic.com')) {
    e.respondWith(caches.match(req).then(hit => {
      const net = fetch(req).then(res => { if (res.ok || res.type === 'opaque') { const copy = res.clone(); caches.open(VERSION).then(c => c.put(req, copy)); } return res; }).catch(() => hit);
      if (hit) { e.waitUntil(net); return hit; }
      return net;
    }));
  }
  // Everything else (music API, audio streams, artwork) goes straight to the network.
});
