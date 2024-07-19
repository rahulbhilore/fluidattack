// seems that we'd need to ignore this because of issue with loading index.html
// retry with fallback mechanism for reloading
workbox.precaching.precacheAndRoute(self.__precacheManifest);

workbox.routing.registerRoute(
  /media/,
  new workbox.strategies.CacheFirst({
    cacheName: "AK_media-cache",
    plugins: [
      new workbox.expiration.Plugin({
        maxEntries: 50,
        maxAgeSeconds: 7 * 24 * 60 * 60
      })
    ]
  })
);

workbox.routing.registerRoute(
  /\.css$/,
  new workbox.strategies.StaleWhileRevalidate({
    cacheName: "AK_css-cache"
  })
);

workbox.routing.registerRoute(
  /\.html$/,
  new workbox.strategies.NetworkFirst({
    cacheName: "AK_html-cache"
  })
);

// probably XENON-36868
// workbox.routing.setDefaultHandler(
//   new workbox.strategies.NetworkFirst()
// );

addEventListener("message", event => {
  if (event.data && event.data.type === "SKIP_WAITING") {
    skipWaiting();
  }
});
