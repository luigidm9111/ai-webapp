// Nome della cache
const CACHE_NAME = "gptwebapp-cache-v1";

// File da mettere subito in cache
const ASSETS_TO_CACHE = [
  "/",
  "/index.html",
  "/manifest.webmanifest",
  "/icons/icon-192.png",
  "/icons/icon-512.png"
];

// Installazione SW → pre-caching
self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS_TO_CACHE);
    })
  );
});

// Attivazione SW → pulizia cache vecchie
self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      )
    )
  );
});

// Intercetta fetch → usa cache se offline
self.addEventListener("fetch", (event) => {
  event.respondWith(
    caches.match(event.request).then((cached) => {
      return cached || fetch(event.request);
    })
  );
});
