# Adding PWA Support to InkeepX (Laravel)

**Audience:** the developer of the inkeepx.com Laravel app.
**Goal:** make the site load fast on slow/unstable connections by caching its static assets and app shell in the browser via a service worker. This also benefits the Android WebView app (WebView fully supports service workers over HTTPS).

**Is PWA OK for an inventory app?** Yes. We are *not* trying to make the whole app work offline (inventory data must stay fresh — showing stale stock counts silently would be dangerous). The win is: CSS, JS, fonts, and images load from the device instead of the network, so repeat visits are near-instant even on 2G, and only the actual data travels over the wire.

---

## Overview — 4 small pieces

1. `public/manifest.json` — app metadata (name, icons, colors)
2. `public/sw.js` — the service worker (the caching logic)
3. `public/offline.html` — a friendly fallback page when there's no connection at all
4. A few lines in the main Blade layout to link the manifest and register the worker

No Composer packages needed. Everything lives in `public/`, so no route or controller changes.

**Requirement:** the site must be served over HTTPS (it already is). Service workers do not run on plain HTTP.

---

## Step 1 — `public/manifest.json`

```json
{
  "name": "InkeepX",
  "short_name": "InkeepX",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#000000",
  "theme_color": "#E8000D",
  "icons": [
    { "src": "/images/icon-192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/images/icon-512.png", "sizes": "512x512", "type": "image/png" }
  ]
}
```

Create the two icon PNGs (192×192 and 512×512) from the InkeepX logo and put them in `public/images/`.

---

## Step 2 — `public/offline.html`

A tiny, self-contained page (inline CSS, no external assets) shown only when a page isn't cached and there is no network:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>InkeepX — Offline</title>
  <style>
    body { margin:0; min-height:100vh; display:flex; flex-direction:column;
           align-items:center; justify-content:center; background:#000; color:#fff;
           font-family:system-ui, sans-serif; text-align:center; padding:24px; }
    .logo { background:#E8000D; border-radius:16px; padding:20px 28px;
            font-weight:900; font-size:20px; margin-bottom:24px; }
    p { color:#999; line-height:1.5; }
    button { margin-top:24px; background:#E8000D; color:#fff; border:none;
             border-radius:8px; padding:14px 40px; font-size:15px; font-weight:700; }
  </style>
</head>
<body>
  <div class="logo">inkeepX</div>
  <h2>You're offline</h2>
  <p>Connect to Wi-Fi or mobile data,<br>then try again.</p>
  <button onclick="location.reload()">Try Again</button>
</body>
</html>
```

---

## Step 3 — `public/sw.js` (the service worker)

The strategy, chosen specifically for an **inventory app**:

| Request type | Strategy | Why |
|---|---|---|
| CSS / JS / fonts / images | **Cache-first** | Vite/Mix fingerprints filenames, so a cached copy is never stale. This is where the slow-network speedup comes from. |
| HTML pages (navigations) | **Network-first, cache fallback** | Users always see fresh inventory data when online; if the network dies, they get the last-seen copy of that page plus can navigate. |
| POST / PUT / DELETE, `/login`, `/logout`, anything under `/api/` | **Never touched** | Writes and auth must always hit the server. |

```js
// public/sw.js
// Bump this version string on every deploy that changes cached behavior —
// it invalidates all old caches.
const VERSION = 'inkeepx-v1';
const STATIC_CACHE = VERSION + '-static';
const PAGES_CACHE = VERSION + '-pages';
const OFFLINE_URL = '/offline.html';

// Cached immediately on install
const PRECACHE = [
  OFFLINE_URL,
  '/manifest.json',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(STATIC_CACHE)
      .then((cache) => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting())
  );
});

// Delete caches from previous versions
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

function isStaticAsset(url) {
  return /\.(css|js|woff2?|ttf|otf|png|jpe?g|gif|svg|webp|ico)$/.test(url.pathname)
      || url.pathname.startsWith('/build/');   // Vite output; use '/css/', '/js/' for Mix
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // Only handle same-origin GET requests. Everything else (POSTs, logins,
  // third-party requests, API calls) goes straight to the network untouched.
  if (req.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/')) return;
  if (url.pathname === '/login' || url.pathname === '/logout') return;

  // Static assets: cache-first (fingerprinted filenames never go stale)
  if (isStaticAsset(url)) {
    event.respondWith(
      caches.match(req).then((cached) =>
        cached ||
        fetch(req).then((res) => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(STATIC_CACHE).then((c) => c.put(req, copy));
          }
          return res;
        })
      )
    );
    return;
  }

  // Page navigations: network-first, fall back to last cached copy,
  // then to the offline page.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(PAGES_CACHE).then((c) => c.put(req, copy));
          }
          return res;
        })
        .catch(() =>
          caches.match(req).then((cached) => cached || caches.match(OFFLINE_URL))
        )
    );
  }
});
```

**Note on Vite vs Mix:** the `isStaticAsset` check includes `/build/` (Laravel Vite's default output dir). If the project uses Laravel Mix, change it to match your output paths (usually `/css/` and `/js/`) — the file-extension check already covers most of it either way.

---

## Step 4 — Wire it into the Blade layout

In the main layout (e.g. `resources/views/layouts/app.blade.php`), inside `<head>`:

```html
<link rel="manifest" href="/manifest.json">
<meta name="theme-color" content="#E8000D">
```

And before `</body>`:

```html
<script>
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('/sw.js');
    });
  }
</script>
```

---

## Step 5 — One server header (important)

The service worker file itself must **not** be cached long-term by the browser, or users can get stuck on an old version. Make sure `/sw.js` is served with:

```
Cache-Control: no-cache
```

In nginx:

```nginx
location = /sw.js {
    add_header Cache-Control "no-cache";
}
```

(Or an equivalent rule in Apache `.htaccess` / your CDN.)

Conversely, fingerprinted assets under `/build/` should have long cache lifetimes if they don't already:

```
Cache-Control: public, max-age=31536000, immutable
```

---

## Deploying updates

- Whenever you deploy changed assets, Vite/Mix already renames the files, so nothing special is needed — new filenames bypass the old cache automatically.
- If you change `sw.js` itself (e.g. adjust the strategy), bump the `VERSION` string. Old caches are deleted on activation.

## How to verify it works

1. Open inkeepx.com in Chrome → DevTools → **Application** tab → *Service Workers*: `sw.js` should show as "activated and running".
2. **Application → Cache Storage**: you should see `inkeepx-v1-static` filling up with CSS/JS/fonts as you browse.
3. **Network tab**: reload a page — static assets should say "(ServiceWorker)" in the Size column.
4. DevTools → Network → set throttling to "Offline" → reload: you should see the last-visited page (or the offline page), not Chrome's dinosaur.
5. Run **Lighthouse** (DevTools → Lighthouse → PWA category) for a checklist.

## What deliberately does NOT work offline (by design)

- Logging in, saving/editing inventory, any form submission — these need the server.
- Fresh stock numbers — offline users see the numbers from their last successful load (the page itself is the last cached copy).

If full offline data entry with background sync is ever wanted, that's a much bigger project (IndexedDB + Background Sync API + conflict handling on the server). The setup above is the right first step regardless.
