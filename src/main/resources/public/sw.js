/* DocSmelt service worker: makes the app installable and loads the interface
   instantly. Only the app shell is cached; files and API calls always go to
   the server and are never stored. */
const CACHE = "docsmelt-shell-v1";
const SHELL = [
  "./", "index.html", "style.css", "app.js", "i18n.js", "favicon.svg", "manifest.json",
  "icon-192.png", "webjars/font-awesome/6.7.2/css/all.min.css",
  "webjars/font-awesome/6.7.2/webfonts/fa-solid-900.woff2",
  "webjars/font-awesome/6.7.2/webfonts/fa-regular-400.woff2",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin || url.pathname.includes("/api/")) {
    return; // uploads and API calls: straight to the network
  }
  // Network first (so updates show up), cache as the offline fallback.
  event.respondWith(fetch(event.request)
    .then((response) => {
      if (response.ok) {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(event.request, copy));
      }
      return response;
    })
    .catch(() => caches.match(event.request).then((hit) => hit || caches.match("index.html"))));
});
