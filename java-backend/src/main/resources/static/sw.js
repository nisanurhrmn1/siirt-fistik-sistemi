// Siirt Fıstığı Alım Paneli — service worker (sürüm 2.11.0)
// Panel kabuğu, yerel kütüphaneler ve fıstık görselleri önbellekte tutulur; bağlantı kopsa da arayüz açılır.
// API yanıtları (/api/) asla önbelleğe alınmaz: para ve stok verisi her zaman sunucudan gelir.
const ONBELLEK = 'fistik-panel-2.11.0';
const ON_YUKLE = [
  '/',
  '/static/css/app.css?v=2.11.0',
  '/static/js/app.js?v=2.11.0',
  '/static/vendor/tailwindcss-3.4.5.js',
  '/static/vendor/alpinejs-3.14.1.min.js',
  '/static/vendor/fonts/inter-latin-ext-wght-normal.woff2',
  '/static/img/logo.svg',
  '/static/img/fistik-desen.svg',
  '/static/img/foto/dalda-fistik.webp',
  '/static/img/foto/kavlak-fistik.webp',
  '/static/img/foto/rozet-yas.webp',
  '/static/img/foto/rozet-kavlak.webp',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(ONBELLEK).then((c) => c.addAll(ON_YUKLE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((adlar) => Promise.all(adlar.filter((a) => a.startsWith('fistik-panel-') && a !== ONBELLEK).map((a) => caches.delete(a))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const istek = e.request;
  const url = new URL(istek.url);
  if (istek.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')) return;

  if (istek.mode === 'navigate') {
    // Sayfa: önce ağ (yeni sürüm hemen gelsin), bağlantı yoksa önbellekteki kabuk
    e.respondWith(fetch(istek)
      .then((yanit) => {
        if (yanit.ok && url.pathname === '/') { const kopya = yanit.clone(); caches.open(ONBELLEK).then((c) => c.put('/', kopya)); }
        return yanit;
      })
      .catch(() => caches.match('/')));
    return;
  }

  if (url.pathname.startsWith('/static/')) {
    // Statik dosya: önce önbellek (app.js / app.css ?v=sürüm ile değiştiği için eskimez)
    e.respondWith(caches.match(istek).then((var_) => var_ || fetch(istek).then((yanit) => {
      if (yanit.ok) { const kopya = yanit.clone(); caches.open(ONBELLEK).then((c) => c.put(istek, kopya)); }
      return yanit;
    })));
  }
});