/* Siirt Fıstığı Yönetim Paneli — Alpine.js bileşeni. Tüm veri /api/v1 üzerinden gelir. */
const API = '/api/v1';
const fmtTL = new Intl.NumberFormat('tr-TR', { style: 'currency', currency: 'TRY' });
const fmtSayi = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 2 });

class ApiHatasi extends Error {
  constructor(mesaj, detaylar = [], durum = 0) { super(mesaj); this.detaylar = detaylar; this.durum = durum; }
}

// Giriş token'ı (JWT). Sekme kapanınca silinir; sunucu tarafında oturum tutulmaz.
const TOKEN_ANAHTARI = 'fistik_token';
const tokenOku = () => { try { return sessionStorage.getItem(TOKEN_ANAHTARI); } catch { return null; } };
const tokenYaz = (t) => { try { t ? sessionStorage.setItem(TOKEN_ANAHTARI, t) : sessionStorage.removeItem(TOKEN_ANAHTARI); } catch { /* yok say */ } };

async function api(yol, { method = 'GET', body, params } = {}) {
  let url = API + yol;
  if (params) {
    const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== ''));
    if ([...q].length) url += '?' + q;
  }
  const headers = body ? { 'Content-Type': 'application/json' } : {};
  const token = tokenOku();
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const veri = await res.json().catch(() => null);
  if (res.status === 401 && !yol.startsWith('/oturum/')) window.dispatchEvent(new CustomEvent('oturum-dustu', { detail: veri?.hata }));
  if (!res.ok) throw new ApiHatasi(veri?.hata || `İstek başarısız (HTTP ${res.status})`, veri?.detaylar || [], res.status);
  return veri;
}

// ─── Kantar indikatörü (Web Serial API) ───────────────────────────────────────
// İndikatörler genellikle sürekli ASCII satır gönderir, ör. "ST,GS,+004800kg", "US,GS,+004780kg", "   4800 kg".
// ST = kararlı, US = kararsız (araç hâlâ sallanıyor). Durum bayrağı yoksa art arda 3 aynı değer kararlı sayılır.

/** Tek bir indikatör satırını çözer → { kg, durum: 'kararli'|'kararsiz'|'bilinmiyor' } ya da null. */
function kantarSatiriniCoz(satir) {
  const temiz = String(satir).replace(/[\x00-\x1f\x7f]/g, ' ').trim();
  if (!temiz) return null;
  const ust = temiz.toUpperCase();
  const kararsiz = /(^|[^A-Z])(US|MO)([^A-Z]|$)/.test(ust);
  const kararli = /(^|[^A-Z])ST([^A-Z]|$)/.test(ust);
  // Sayı: binlik gruplu ("4 800", "4.800", "12.345,5") ya da düz ("004800", "4800.0", "48,5")
  const sayilar = [...temiz.matchAll(/([+-])?\s*(\d{1,3}(?:[ .,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d+)?)\s*(kg)?/gi)];
  if (!sayilar.length) return null;
  const kgli = sayilar.filter((m) => m[3]);
  const m = (kgli.length ? kgli : sayilar).at(-1);
  const gruplu = m[2].match(/^(\d{1,3}(?:[ .,]\d{3})+)(?:[.,](\d{1,2}))?$/);
  let kg = Number(gruplu ? `${gruplu[1].replace(/\D/g, '')}.${gruplu[2] || 0}` : m[2].replace(',', '.'));
  if (!Number.isFinite(kg)) return null;
  if (m[1] === '-') kg = -kg;
  return { kg, durum: kararsiz ? 'kararsiz' : kararli ? 'kararli' : 'bilinmiyor' };
}

class SeriKantar {
  constructor() {
    this.port = null;
    this.okuyucu = null;
    this.tampon = '';
    this.son = [];             // son okumalar (kararlılık için)
    this.bekleyenler = [];     // oku() çağrıları
    this.dinleyici = () => {}; // durum değişince panele haber verir
  }
  static get destekleniyor() { return 'serial' in navigator && window.isSecureContext; }
  get bagli() { return !!this.port; }

  /** Daha önce izin verilmiş portu sormadan açmayı dener; yoksa (soru=true ise) tarayıcının port seçicisini açar. */
  async baglan(baud, soru = true) {
    if (!SeriKantar.destekleniyor) throw new Error('Bu tarayıcı seri portu desteklemiyor (Chrome veya Edge masaüstü ve HTTPS gerekir). Ağırlığı elle girebilirsiniz.');
    if (this.port) return;
    let port = (await navigator.serial.getPorts())[0];
    if (!port) {
      if (!soru) return;
      try { port = await navigator.serial.requestPort(); } catch (e) {
        if (e.name === 'NotFoundError') throw new Error('Port seçilmedi.');
        throw e;
      }
    }
    try {
      await port.open({ baudRate: Number(baud) || 9600, dataBits: 8, stopBits: 1, parity: 'none' });
    } catch (e) {
      throw new Error(e.name === 'InvalidStateError' || e.name === 'NetworkError'
        ? 'Port açılamadı: başka bir program (ör. eski kantar yazılımı) kullanıyor olabilir ya da cihaz takılı değil.'
        : `Port açılamadı: ${e.message}`);
    }
    this.port = port;
    this.son = [];
    if (!this._kopmaDinleniyor) {
      navigator.serial.addEventListener('disconnect', (e) => { if (e.target === this.port) this.kapat('Kantar bağlantısı koptu.'); });
      this._kopmaDinleniyor = true;
    }
    this.dinleyici({ bagli: true, mesaj: `Bağlı · ${baud} baud` });
    this.okumaDongusu();
  }

  async okumaDongusu() {
    const cozucu = new TextDecoder();
    try {
      while (this.port?.readable) {
        this.okuyucu = this.port.readable.getReader();
        try {
          for (;;) {
            const { value, done } = await this.okuyucu.read();
            if (done) break;
            this.veriGeldi(cozucu.decode(value, { stream: true }));
          }
        } finally {
          this.okuyucu.releaseLock();
          this.okuyucu = null;
        }
      }
    } catch (e) {
      this.kapat(`Kantardan okuma hatası: ${e.message}`);
    }
  }

  veriGeldi(parca) {
    this.tampon += parca;
    const satirlar = this.tampon.split(/\r\n|\r|\n|\x03/);
    this.tampon = satirlar.pop().slice(-200);
    for (const s of satirlar) {
      const okuma = kantarSatiriniCoz(s);
      if (!okuma) continue;
      this.son = [...this.son.slice(-4), okuma];
      this.dinleyici({ sonOkuma: okuma });
      const kararli = okuma.durum === 'kararli'
        || (okuma.durum === 'bilinmiyor' && this.son.length >= 3 && this.son.slice(-3).every((o) => o.kg === okuma.kg));
      if (kararli) { this.bekleyenler.forEach((b) => b.coz(okuma.kg)); this.bekleyenler = []; }
    }
  }

  /** Bir sonraki KARARLI değeri bekler (araç sallanırken okunmaz). */
  oku(sureMs = 6000) {
    if (!this.port) return Promise.reject(new Error('Kantar bağlı değil.'));
    return new Promise((coz, reddet) => {
      const b = { coz: (kg) => { clearTimeout(b.z); coz(kg); } };
      b.z = setTimeout(() => {
        this.bekleyenler = this.bekleyenler.filter((x) => x !== b);
        reddet(new Error(this.son.length ? 'Kararlı değer alınamadı (araç hareket ediyor olabilir). Tekrar deneyin.'
          : 'Kantardan veri gelmiyor. Kablo ve baud hızını kontrol edin.'));
      }, sureMs);
      this.bekleyenler.push(b);
    });
  }

  async kapat(mesaj = 'Bağlantı kapatıldı.') {
    const port = this.port;
    this.port = null;
    this.bekleyenler.forEach((b) => { clearTimeout(b.z); }); this.bekleyenler = [];
    try { await this.okuyucu?.cancel(); } catch { /* yok say */ }
    try { await port?.close(); } catch { /* yok say */ }
    this.dinleyici({ bagli: false, mesaj, sonOkuma: null });
  }
}
const seriKantar = new SeriKantar();   // Alpine'in reaktif vekiline sokulmaz (SerialPort nesneleri vekillenemez)

// Kantar bilgisayarına özgü ayarlar (tarayıcıda saklanır)
const SAHA_ANAHTARI = 'fistik_saha';
const SAHA_VARSAYILAN = { baud: 9600, cuval_darasi: '1', otomatik_yazdir: true, kagit: '80' };
const sahaAyarOku = () => { try { return { ...SAHA_VARSAYILAN, ...JSON.parse(localStorage.getItem(SAHA_ANAHTARI) || '{}') }; } catch { return { ...SAHA_VARSAYILAN }; } };
const sahaAyarYaz = (a) => { try { localStorage.setItem(SAHA_ANAHTARI, JSON.stringify(a)); } catch { /* yok say */ } };

/** Boş metinleri null'a çevirir (API boş string yerine alanın hiç olmamasını bekler). */
const bosIseNull = (v) => (v === '' || v === undefined ? null : v);

const bosKantar = () => ({
  uretici_id: '', plaka: '', sofor_adi: '', brut_kg: '', dara_kg: '', silo_id: '',
  urun_turu: 'DRY', cuval_sayisi: '', brut_kaynagi: 'MANUAL', dara_kaynagi: 'MANUAL', emanet: false,
  numune: { acik: false, numune: '100', catlak: '', bos: '' },   // randıman analizi (gram)
  kalite: { numune_gram: '500', catlak_orani: '', randiman: '', bos_orani: '', curuk_orani: '0', nem_orani: '' },
});
const bosSevk = () => ({
  musteri_adi: '', plaka: '', sofor_adi: '', silo_id: '', dara_kg: '', brut_kg: '', birim_fiyat: '',
  tahsilat: 'BANK_TRANSFER', aciklama: '', brut_kaynagi: 'MANUAL', dara_kaynagi: 'MANUAL',
});
const bosTalep = () => ({ ad_soyad: '', kullanici_adi: '', istenen_rol: 'SCALE_OPERATOR', sifre: '', tekrar: '', aciklama: '' });
const bosUretici = () => ({
  tip: 'INDIVIDUAL', ad_soyad: '', tc_kimlik_no: '', vergi_no: '', vergi_dairesi: '', telefon: '',
  ilce: 'Merkez', banka_adi: '', iban: '',
});

document.addEventListener('alpine:init', () => {
  Alpine.data('panel', () => ({
    sekmeler: [
      { id: 'kantar', ad: 'Kantar & Teslimat', ikon: '<svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M12 3v17.25M5.25 4.97 2.63 15.7a6 6 0 0 0 5.25 1.56L5.25 4.97Zm13.5 0-2.62 10.72a6 6 0 0 0 5.25 1.56L18.75 4.97ZM5.25 4.97A48.4 48.4 0 0 1 18.75 4.97"/></svg>' },
      { id: 'uretici', ad: 'Üretici & Finans', ikon: '<svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M15 19.13a9.38 9.38 0 0 0 2.63.37 9.34 9.34 0 0 0 4.12-.95 4.13 4.13 0 0 0-7.53-2.49M15 19.13v-.01c0-1.11-.29-2.16-.79-3.07M15 19.13v.11A12.32 12.32 0 0 1 8.62 21c-2.33 0-4.51-.65-6.37-1.76v-.11a6.38 6.38 0 0 1 11.96-3.08M12 6.38a3.38 3.38 0 1 1-6.75 0 3.38 3.38 0 0 1 6.75 0Zm8.25 2.25a2.63 2.63 0 1 1-5.25 0 2.63 2.63 0 0 1 5.25 0Z"/></svg>' },
      { id: 'fiyat', ad: 'Fiyat & Depo', ikon: '<svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M3 13.13C3 12.5 3.5 12 4.13 12h2.25c.62 0 1.12.5 1.12 1.13v6.75C7.5 20.5 7 21 6.38 21H4.13C3.5 21 3 20.5 3 19.88v-6.75ZM9.75 8.63c0-.63.5-1.13 1.13-1.13h2.25c.62 0 1.12.5 1.12 1.13v11.25c0 .62-.5 1.12-1.12 1.12h-2.25c-.63 0-1.13-.5-1.13-1.12V8.63ZM16.5 4.13c0-.63.5-1.13 1.13-1.13h2.25C20.5 3 21 3.5 21 4.13v15.75c0 .62-.5 1.12-1.12 1.12h-2.25c-.63 0-1.13-.5-1.13-1.12V4.13Z"/></svg>' },
      { id: 'sevkiyat', ad: 'Sevkiyat', yalnizJava: true, ikon: '<svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M8.25 18.75a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m3 0h6m-9 0H3.38a1.13 1.13 0 0 1-1.13-1.13V14.25m17.25 4.5a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m3 0h1.13c.62 0 1.13-.5 1.13-1.13v-5.07a2.25 2.25 0 0 0-.44-1.34l-2.71-3.62a1.13 1.13 0 0 0-.9-.44H14.25M16.5 18.75h-2.25m0-11.25v11.25m0-11.25V4.88c0-.62-.5-1.13-1.13-1.13H3.38c-.62 0-1.13.5-1.13 1.13v9.37m12 0H2.25"/></svg>' },
      { id: 'alicilar', ad: 'Alıcılar', yalnizJava: true, ikon: '<svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M2.25 18.75a60.07 60.07 0 0 1 15.8 2.1c.73.2 1.45-.34 1.45-1.1V18.75M3.75 4.5v.75A.75.75 0 0 1 3 6h-.75m0 0v-.38c0-.62.5-1.12 1.13-1.12H20.25M2.25 6v9m18-10.5v.75c0 .41.34.75.75.75h.75m-1.5-1.5h.38c.62 0 1.12.5 1.12 1.13v9.75c0 .62-.5 1.12-1.12 1.12h-.38m1.5-1.5H21a.75.75 0 0 0-.75.75v.75m0 0H3.75m0 0h-.38c-.62 0-1.12-.5-1.12-1.12V15m1.5 1.5v-.75A.75.75 0 0 0 3 15h-.75M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm3 0h.01v.01H18v-.01Zm-12 0h.01v.01H6v-.01Z"/></svg>' },
      { id: 'kullanicilar', ad: 'Kullanıcılar', yetki: 'kullanici_yonet', ikon: '<svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M18 7.5v3m0 0v3m0-3h3m-3 0h-3m-2.25-4.13a3.38 3.38 0 1 1-6.75 0 3.38 3.38 0 0 1 6.75 0ZM3 19.24v-.12a6.38 6.38 0 0 1 12.75 0v.12A12.32 12.32 0 0 1 9.37 21 12.3 12.3 0 0 1 3 19.24Z"/></svg>' },
    ],
    tab: 'kantar',
    // Ekranda sayarak değişen sayılar (özet kartları ve tahmini net ödeme)
    anim: { alim: 0, brut: 0, odenen: 0, randiman: 0, net: 0 },
    _animKare: {},
    saat: '',
    yenileniyor: false,
    toasts: [],
    alanHatalari: {},
    modal: null,
    islemde: false,

    // veri
    ozet: null,
    ureticiler: [],
    bakiyeler: {},
    tartimlar: [],
    fiyatlar: [],
    silolar: [],
    kaliteStok: [],
    toplamStok: 0,

    // kantar
    kantar: bosKantar(),
    kantarArama: '',
    onizleme: null,
    onizlemeMesaj: 'Üretici, ağırlık ve kalite değerlerini girin.',
    onizlemeYukleniyor: false,
    kaydediliyor: false,
    fis: null,
    ayarlar: sahaAyarOku(),       // baud, çuval darası, otomatik yazdır, kâğıt genişliği (bu bilgisayara özgü)
    sahaAyarAcik: false,
    kantarDestek: SeriKantar.destekleniyor,
    kantarDurum: { bagli: false, mesaj: 'Bağlı değil', sonOkuma: null },
    kantarOkunan: '',             // şu an kantardan okunan alan: 'brut' | 'dara'
    urunTurleri: { DRY: 'Kuru-kavlak', FRESH: 'Yaş fıstık' },
    urunFotolari: { DRY: '/static/img/foto/rozet-kavlak.webp', FRESH: '/static/img/foto/rozet-yas.webp' },
    // sevkiyat (satış / depodan çıkış)
    sevk: bosSevk(),
    sevkiyatlar: [],
    musteriler: [],
    sevkKaydediliyor: false,
    irsaliye: null,
    tahsilatlar: { CASH: 'Nakit alındı', BANK_TRANSFER: 'Havale alındı', ON_ACCOUNT: 'Vadeli / açık hesap' },
    emanetForm: { tartim: null, onizleme: null, miktar: '' },
    tahsilatForm: {},
    musteriEkstre: null,
    musteriEkstreForm: { musteri_id: null, baslangic: '', bitis: '' },
    kesintiler: [],
    catlakPrimleri: [],
    dokum: null,                  // yazdırılabilir cari hesap ekstresi
    dokumForm: { baslangic: '', bitis: '' },
    gunSonuTarihi: '',
    gunSonuIniyor: false,
    _onizlemeZamanlayici: null,
    _onizlemeSira: 0,
    kaliteAlanlari: [
      { alan: 'catlak_orani', ad: 'Çatlak oranı', birim: '%' },
      { alan: 'randiman', ad: 'Randıman', birim: '%' },
      { alan: 'nem_orani', ad: 'Nem', birim: '%' },
      { alan: 'bos_orani', ad: 'Boş', birim: '%' },
      { alan: 'curuk_orani', ad: 'Çürük', birim: '%' },
      { alan: 'numune_gram', ad: 'Numune', birim: 'g' },
    ],

    // üretici & finans
    ureticiArama: '',
    secili: null,
    ekstre: null,
    yeniUretici: bosUretici(),
    avansForm: { tutar: '', odeme_yontemi: 'CASH', aciklama: '' },
    odemeForm: { hakedis: null, tutar: '', odeme_yontemi: 'BANK_TRANSFER', referans_no: '' },
    odemeYontemleri: { BANK_TRANSFER: 'Havale / EFT', CASH: 'Nakit', CHEQUE: 'Çek' },
    ilceler: ['Merkez', 'Kurtalan', 'Pervari', 'Baykan', 'Eruh', 'Şirvan', 'Tillo'],

    // fiyat
    yeniFiyatlar: {},
    fiyatGecerlilik: '',
    fiyatKaydediliyor: false,

    // oturum
    oturum: null,                 // { id, kullanici_adi, ad_soyad, rol, rol_adi, yetkiler }
    kimlikDogrulama: true,        // Python prototipinde giriş yok → false
    girisGerekli: false,
    girisForm: { kullanici_adi: '', sifre: '' },
    girisHata: '',
    girisBilgi: '',
    girisYapiliyor: false,
    girisModu: 'giris',           // 'giris' | 'talep' | 'sorgu'
    talepForm: bosTalep(),
    sorguVar: false,              // halka açık üretici sorgusu (yalnızca Java sürümünde)
    sorguForm: { tc_kimlik_no: '', telefon_son4: '' },
    sorguSonuc: null,
    _sorguZamanlayici: null,
    demoHesaplar: [],
    rolBilgisi: {
      ADMIN: { ad: 'Yönetici', aciklama: 'Tüm işlemler', sekme: 'kantar', renk: 'from-emerald-600 to-fistik-700' },
      SCALE_OPERATOR: { ad: 'Kantar memuru', aciklama: 'Tartım ve fiş kesme', sekme: 'kantar', renk: 'from-emerald-500 to-teal-600' },
      QUALITY: { ad: 'Kalite sorumlusu', aciklama: 'Kalite analizlerini izleme', sekme: 'kantar', renk: 'from-sky-500 to-indigo-600' },
      ACCOUNTING: { ad: 'Muhasebe', aciklama: 'Üretici, avans ve ödeme', sekme: 'uretici', renk: 'from-teal-500 to-cyan-700' },
      WAREHOUSE: { ad: 'Depo sorumlusu', aciklama: 'Sevkiyat ve stok', sekme: 'sevkiyat', renk: 'from-lime-500 to-fistik-600' },
      PRICE_MANAGER: { ad: 'Fiyat yöneticisi', aciklama: 'Günlük fiyat belirleme', sekme: 'fiyat', renk: 'from-amber-400 to-amber-600' },
    },

    // kullanıcı yönetimi
    kullanicilar: [],
    onayRolleri: {},              // talep id → onaylanırken verilecek rol
    kullaniciForm: {},
    sifirlamaForm: {},
    sifreForm: {},
    telegramSonuc: null,
    telegramTestEdiliyor: false,

    async init() {
      this.saatGuncelle();
      this.gunSonuTarihi = this.bugunIso();
      setInterval(() => this.saatGuncelle(), 30_000);
      setInterval(() => { if (!this.girisGerekli) this.yukleOzet().catch(() => {}); }, 60_000);
      this.$watch('kantar', () => this.onizlemeIste());
      // Numune analizi: gramlardan çatlak ve boş oranı (sunucu da aynı hesabı yapar)
      this.$watch('kantar.numune', (n) => {
        if (!n.acik) return;
        const num = parseFloat(n.numune), c = parseFloat(n.catlak), b = parseFloat(n.bos);
        if (!(num > 0)) return;
        this.kantar.kalite.numune_gram = n.numune;
        if (Number.isFinite(c)) this.kantar.kalite.catlak_orani = String(Math.round((c * 100 / num) * 100) / 100);
        if (Number.isFinite(b)) this.kantar.kalite.bos_orani = String(Math.round((b * 100 / num) * 100) / 100);
      });
      this.$watch('ayarlar', (a) => { sahaAyarYaz(a); this.onizlemeIste(); });
      // Fiş kapanınca operatör doğrudan bir sonraki aracın plakasına geçer
      this.$watch('modal', (m) => {
        if (m || this.girisGerekli) return;
        if (this.tab === 'kantar') this.$nextTick(() => this.$refs.plaka?.focus());
        else if (this.tab === 'sevkiyat') this.$nextTick(() => this.$refs.sevkMusteri?.focus());
      });
      seriKantar.dinleyici = (d) => Object.assign(this.kantarDurum, d);
      if (this.kantarDestek) seriKantar.baglan(this.ayarlar.baud, false).catch(() => {});   // izinli port varsa sormadan aç
      window.addEventListener('oturum-dustu', (e) => this.oturumuKapat(e.detail || 'Oturum süresi doldu; lütfen tekrar giriş yapın.'));
      await this.oturumKontrol();
    },

    // ─── Oturum ───
    async oturumKontrol() {
      try {
        this.oturum = await api('/oturum/ben');
        this.girisGerekli = false;
        await this.hepsiniYenile();
      } catch (e) {
        if (e.durum === 404) {            // sunucuda kimlik doğrulama yok (Python prototipi)
          this.kimlikDogrulama = false;
          await this.hepsiniYenile();
        } else {
          await this.girisEkraniAc();
        }
      }
    },
    async girisEkraniAc(mesaj = '') {
      tokenYaz(null);
      this.oturum = null;
      this.kullanicilar = [];
      this.girisGerekli = true;
      this.girisModuAc();
      this.girisHata = mesaj;
      try { this.demoHesaplar = await api('/oturum/demo-hesaplar'); this.sorguVar = true; } catch { this.demoHesaplar = []; }
    },
    async oturumAc(istek) {
      this.girisYapiliyor = true;
      this.girisHata = '';
      try {
        const sonuc = await istek();
        tokenYaz(sonuc.token);
        this.oturum = sonuc.kullanici;
        this.girisGerekli = false;
        this.girisForm = { kullanici_adi: '', sifre: '' };
        this.tab = this.rolBilgisi[this.oturum.rol]?.sekme || 'kantar';
        this.bildir(`Hoş geldiniz, ${this.oturum.ad_soyad} (${this.oturum.rol_adi})`);
        if (this.oturum.sifre_degistirmeli) this.sifreModalAc();
        await this.hepsiniYenile();
      } catch (e) {
        this.girisHata = e.detaylar?.length ? 'Kullanıcı adı ve şifre zorunludur.' : e.message;
      }
      this.girisYapiliyor = false;
    },
    girisModuAc() {
      this.girisModu = 'giris';
      this.girisHata = '';
      this.girisBilgi = '';
      this.alanHatalari = {};
    },
    sorguEkraniAc() {
      this.girisModuAc();
      this.sorguKapat();
      this.girisModu = 'sorgu';
      this.$nextTick(() => document.getElementById('s_tc')?.focus());
    },
    /** Sonuç kartı 3 dakika sonra kendiliğinden kapanır (ortak kullanılan cihazda açık kalmasın). */
    async ureticiSorgula() {
      this.girisHata = '';
      this.alanHatalari = {};
      this.girisYapiliyor = true;
      try {
        this.sorguSonuc = await api('/sorgu/uretici', { method: 'POST', body: { ...this.sorguForm } });
        this.sorguForm = { tc_kimlik_no: '', telefon_son4: '' };
        clearTimeout(this._sorguZamanlayici);
        this._sorguZamanlayici = setTimeout(() => this.sorguKapat(), 180_000);
      } catch (e) {
        this.alanHatalari = Object.fromEntries((e.detaylar || []).map((d) => [d.alan, d.mesaj]));
        this.girisHata = e.detaylar?.length ? 'Lütfen işaretli alanları düzeltin.' : e.message;
      }
      this.girisYapiliyor = false;
    },
    sorguKapat() {
      clearTimeout(this._sorguZamanlayici);
      this.sorguSonuc = null;
      this.sorguForm = { tc_kimlik_no: '', telefon_son4: '' };
    },
    talepEkraniAc() {
      this.girisModuAc();
      this.talepForm = bosTalep();
      this.girisModu = 'talep';
    },
    async talepGonder() {
      const f = this.talepForm;
      this.girisHata = '';
      this.alanHatalari = {};
      if (f.sifre !== f.tekrar) { this.alanHatalari = { tekrar: 'Şifreler aynı değil.' }; return; }
      this.girisYapiliyor = true;
      try {
        const sonuc = await api('/oturum/hesap-talebi', {
          method: 'POST',
          body: { ad_soyad: f.ad_soyad, kullanici_adi: f.kullanici_adi, istenen_rol: f.istenen_rol, sifre: f.sifre,
                  aciklama: bosIseNull(f.aciklama) },
        });
        const kullanici_adi = f.kullanici_adi.trim().toLowerCase();
        this.girisModuAc();
        this.girisForm = { kullanici_adi, sifre: '' };
        this.girisBilgi = sonuc.mesaj;
      } catch (e) {
        this.alanHatalari = Object.fromEntries((e.detaylar || []).map((d) => [d.alan, d.mesaj]));
        this.girisHata = e.detaylar?.length ? 'Lütfen işaretli alanları düzeltin.' : e.message;
      }
      this.girisYapiliyor = false;
    },
    girisYap() {
      return this.oturumAc(() => api('/oturum/giris', { method: 'POST', body: this.girisForm }));
    },
    demoGiris(kullanici_adi) {
      return this.oturumAc(() => api('/oturum/demo-giris', { method: 'POST', body: { kullanici_adi } }));
    },
    cikisYap() {
      this.bildir('Oturum kapatıldı.');
      this.oturumuKapat();
    },
    oturumuKapat(mesaj = '') {
      if (this.girisGerekli) return;
      this.modal = null;
      this.secili = null;
      this.ekstre = null;
      this.girisEkraniAc(mesaj);
    },
    /** Oturumdaki kullanıcı bu işlemi yapabilir mi? (Sunucu da ayrıca denetler; bu yalnızca arayüz kilidi.) */
    yetkili(islem) {
      return !this.kimlikDogrulama || !!this.oturum?.yetkiler?.includes(islem);
    },
    yetkiYok(islem) {
      const kim = { tartim: 'kantar memuru', fiyat_gir: 'fiyat yöneticisi', uretici_ekle: 'muhasebe',
                    avans_ver: 'muhasebe', odeme_yap: 'muhasebe', emanet_cevir: 'muhasebe', tahsilat: 'muhasebe',
                    kesinti_ayar: 'muhasebe' }[islem];
      return this.yetkili(islem) ? '' : `Bu işlem için ${kim} veya yönetici yetkisi gerekir.`;
    },
    /** Yetki gerektiren sekmeler (Kullanıcılar) yalnızca girişli sürümde ve yetkiliye görünür. */
    get gorunurSekmeler() {
      return this.sekmeler.filter((s) => (!s.yetki && !s.yalnizJava) || (this.kimlikDogrulama && (!s.yetki || this.yetkili(s.yetki))));
    },
    get roller() {
      return Object.entries(this.rolBilgisi).map(([rol, b]) => ({ rol, rol_adi: b.ad }));
    },
    get talepRolleri() { return this.roller.filter((r) => r.rol !== 'ADMIN'); },

    // ─── Kullanıcı yönetimi ───
    get bekleyenTalepler() { return this.kullanicilar.filter((u) => u.durum === 'BEKLIYOR'); },
    get kayitliKullanicilar() { return this.kullanicilar.filter((u) => u.durum !== 'BEKLIYOR'); },
    async yukleKullanicilar() {
      if (!this.kimlikDogrulama || !this.yetkili('kullanici_yonet')) { this.kullanicilar = []; return; }
      this.kullanicilar = await api('/kullanicilar');
      for (const u of this.bekleyenTalepler) if (!this.onayRolleri[u.id]) this.onayRolleri[u.id] = u.rol;
    },
    async talepOnayla(u) {
      await this.islem(async () => {
        const k = await api(`/kullanicilar/${u.id}/onayla`, { method: 'POST', body: { rol: this.onayRolleri[u.id] || u.rol } });
        this.bildir(`${k.ad_soyad} onaylandı (${k.rol_adi}). Artık giriş yapabilir.`);
        await this.yukleKullanicilar();
      });
    },
    async telegramTest() {
      this.telegramTestEdiliyor = true;
      try {
        this.telegramSonuc = await api('/kullanicilar/telegram-test', { method: 'POST' });
      } catch (e) {
        this.telegramSonuc = { basarili: false, durum: 'HATA', aciklama: e.message };
      } finally {
        this.telegramTestEdiliyor = false;
      }
    },
    telegramDurumAd(d) {
      return ({
        BASARILI: 'Çalışıyor', AYAR_YOK: 'Ayar eksik', TOKEN_HATALI: 'Token geçersiz', SOHBET_YOK: 'Sohbet bulunamadı',
        START_YOK: 'Bota /start yazılmamış', BOT_ENGELLI: 'Bot engellenmiş', GRUPTA_YOK: 'Bot grupta değil',
        ULASILAMADI: "Telegram'a ulaşılamadı", BEKLE: 'Biraz bekleyin', HATA: 'Telegram hatası',
      })[d] || 'Hata';
    },
    async talepReddet(u) {
      if (!confirm(`${u.ad_soyad} (@${u.kullanici_adi}) talebi reddedilsin mi? Talep silinir.`)) return;
      await this.islem(async () => {
        await api(`/kullanicilar/${u.id}/talep`, { method: 'DELETE' });
        this.bildir(`${u.ad_soyad} talebi reddedildi.`);
        await this.yukleKullanicilar();
      });
    },
    async kullaniciGuncelle(u, degisiklik, mesaj) {
      await this.islem(async () => {
        await api(`/kullanicilar/${u.id}`, { method: 'PATCH', body: degisiklik });
        this.bildir(mesaj);
      });
      await this.yukleKullanicilar().catch((e) => this.hataGoster(e));   // hata olsa da seçim kutusu gerçek rolü göstersin
    },
    kullaniciModalAc() {
      this.kullaniciForm = { ad_soyad: '', kullanici_adi: '', rol: 'SCALE_OPERATOR', gecici_sifre: '' };
      this.alanHatalari = {};
      this.modal = 'kullanici';
    },
    async kullaniciEkle() {
      await this.islem(async () => {
        const k = await api('/kullanicilar', { method: 'POST', body: this.kullaniciForm });
        this.modal = null;
        this.bildir(`${k.ad_soyad} eklendi (${k.rol_adi}). Geçici şifreyi kullanıcıya iletin.`);
        await this.yukleKullanicilar();
      });
    },
    sifreSifirlaModalAc(u) {
      this.sifirlamaForm = { kullanici: u, yeni_sifre: '' };
      this.alanHatalari = {};
      this.modal = 'sifre-sifirla';
    },
    async sifreSifirla() {
      const f = this.sifirlamaForm;
      await this.islem(async () => {
        await api(`/kullanicilar/${f.kullanici.id}/sifre-sifirla`, { method: 'POST', body: { yeni_sifre: f.yeni_sifre } });
        this.modal = null;
        this.bildir(`${f.kullanici.ad_soyad} için geçici şifre belirlendi.`);
        await this.yukleKullanicilar();
      });
    },
    sifreModalAc() {
      this.sifreForm = { mevcut_sifre: '', yeni_sifre: '', tekrar: '' };
      this.alanHatalari = {};
      this.modal = 'sifre';
    },
    async sifreDegistir() {
      const f = this.sifreForm;
      if (f.yeni_sifre !== f.tekrar) { this.alanHatalari = { tekrar: 'Şifreler aynı değil.' }; return; }
      await this.islem(async () => {
        const s = await api('/oturum/sifre-degistir', { method: 'POST', body: { mevcut_sifre: f.mevcut_sifre, yeni_sifre: f.yeni_sifre } });
        this.modal = null;
        this.oturum.sifre_degistirmeli = false;
        this.bildir(s.mesaj);
      });
    },

    // ─── Yükleme ───
    async hepsiniYenile() {
      this.yenileniyor = true;
      try {
        await Promise.all([this.yukleOzet(), this.yukleUreticiler(), this.yukleTartimlar(), this.yukleFiyatlar(), this.yukleDepo(),
                           this.yukleKullanicilar(), this.yukleSevkiyatlar(), this.yukleCatlakPrimi(), this.yukleKesintiler()]);
        if (this.secili) await this.yukleEkstre();
      } catch (e) { this.hataGoster(e); }
      this.yenileniyor = false;
    },
    async yukleOzet() {
      this.ozet = await api('/raporlar/gunluk-ozet');
      this.sayiAnimasyonu('alim', this.ozet.toplam_net_kg);
      this.sayiAnimasyonu('brut', this.ozet.brut_alim_tutari);
      this.sayiAnimasyonu('odenen', this.ozet.odenen_toplam);
      this.sayiAnimasyonu('randiman', this.ozet.ortalama_randiman || 0);
    },
    async yukleTartimlar() { this.tartimlar = await api('/tartimlar'); },
    async yukleFiyatlar() { this.fiyatlar = (await api('/fiyatlar/guncel')).kalemler; },
    async yukleUreticiler() {
      const [liste, bakiyeler] = await Promise.all([
        api('/ureticiler', { params: { sayfa_boyutu: 100 } }),
        api('/raporlar/uretici-bakiyeleri'),
      ]);
      this.ureticiler = liste.kayitlar;
      this.bakiyeler = Object.fromEntries(bakiyeler.map((b) => [b.uretici_id, b]));
    },
    async yukleDepo() {
      const [silo, kalite] = await Promise.all([
        api('/depo/stok-durumu'),
        api('/depo/stok-durumu', { params: { gruplama: 'kalite' } }),
      ]);
      this.silolar = silo.silolar;
      this.kaliteStok = kalite.kaliteler;
      this.toplamStok = Number(silo.toplam_stok_kg);
    },
    async yukleEkstre() {
      this.ekstre = await api(`/ureticiler/${this.secili.id}/bakiye-ekstre`);
    },

    // ─── Kantar ───
    /** Ürün türü, çuval darası ve ağırlık kaynağı yalnızca Java sürümünde var (Python prototipi bu alanları reddeder). */
    get sahaModu() { return this.kimlikDogrulama; },
    get cuvalDarasiToplam() {
      if (!this.sahaModu) return 0;
      const n = parseInt(this.kantar.cuval_sayisi, 10), d = parseFloat(this.ayarlar.cuval_darasi);
      return Number.isFinite(n) && Number.isFinite(d) ? n * d : 0;
    },
    /** Net = Brüt − Araç darası − (Çuval sayısı × Çuval darası) */
    get netKg() {
      const b = parseFloat(this.kantar.brut_kg), d = parseFloat(this.kantar.dara_kg);
      return Number.isFinite(b) && Number.isFinite(d) ? Math.round((b - d - this.cuvalDarasiToplam) * 100) / 100 : null;
    },
    /** Kalite analizi gövdesi; numune analizi açıksa gramlar da gider (oranları sunucu da gramdan hesaplar). */
    kaliteGovdesi() {
      const k = this.kantar, g = { ...k.kalite };
      if (this.sahaModu && k.numune.acik) {
        g.numune_gram = bosIseNull(k.numune.numune);
        g.catlak_gram = bosIseNull(k.numune.catlak);
        g.bos_gram = bosIseNull(k.numune.bos);
      }
      return g;
    },
    /** API'ye giden ağırlık alanları (önizleme ve kayıt için ortak). */
    agirlikGovdesi() {
      const k = this.kantar;
      const g = { brut_kg: k.brut_kg, dara_kg: k.dara_kg };
      if (this.sahaModu) {
        g.urun_turu = k.urun_turu;
        g.cuval_sayisi = k.cuval_sayisi === '' ? 0 : Number(k.cuval_sayisi);
        g.cuval_darasi_kg = this.ayarlar.cuval_darasi === '' ? 0 : this.ayarlar.cuval_darasi;
      }
      return g;
    },
    get kantarUreticileri() {
      const q = this.kantarArama.toLocaleLowerCase('tr');
      return this.ureticiler.filter((u) => !q || u.ad_soyad.toLocaleLowerCase('tr').includes(q) || (u.tc_kimlik_no || '').includes(q));
    },
    get seciliKantarBakiye() { return this.bakiyeler[this.kantar.uretici_id] || null; },

    onizlemeIste() {
      clearTimeout(this._onizlemeZamanlayici);
      this._onizlemeZamanlayici = setTimeout(() => this.onizlemeGetir(), 300);
    },
    async onizlemeGetir() {
      const k = this.kantar;
      if (!this.yetkili('tartim')) {
        this.onizleme = null;
        this.onizlemeMesaj = this.yetkiYok('tartim');
        return;
      }
      const eksik = ['brut_kg', 'dara_kg'].some((a) => k[a] === '')
        || ['catlak_orani', 'randiman', 'bos_orani', 'nem_orani'].some((a) => k.kalite[a] === '');
      if (eksik) {
        this.onizleme = null;
        this.onizlemeMesaj = 'Üretici, ağırlık ve kalite değerlerini girin.';
        return;
      }
      const sira = ++this._onizlemeSira;
      this.onizlemeYukleniyor = true;
      try {
        const sonuc = await api('/tartimlar/onizleme', {
          method: 'POST',
          body: { uretici_id: bosIseNull(k.uretici_id) && Number(k.uretici_id), ...this.agirlikGovdesi(), kalite: this.kaliteGovdesi() },
        });
        if (sira !== this._onizlemeSira) return; // eski yanıt
        this.onizleme = sonuc;
        this.sayiAnimasyonu('net', sonuc.tahmini_net_odenecek || 0);
      } catch (e) {
        if (sira !== this._onizlemeSira) return;
        this.onizleme = null;
        this.onizlemeMesaj = e.detaylar?.length ? e.detaylar.map((d) => d.mesaj).join(' ') : e.message;
      } finally {
        if (sira === this._onizlemeSira) this.onizlemeYukleniyor = false;
      }
    },
    async fisKes() {
      const k = this.kantar;
      this.kaydediliyor = true;
      this.alanHatalari = {};
      try {
        const sonuc = await api('/tartimlar', {
          method: 'POST',
          body: {
            uretici_id: k.uretici_id ? Number(k.uretici_id) : null,
            plaka: k.plaka, sofor_adi: bosIseNull(k.sofor_adi),
            ...this.agirlikGovdesi(), kalite: this.kaliteGovdesi(),
            silo_id: k.silo_id ? Number(k.silo_id) : null,
            ...(this.sahaModu ? { brut_kaynagi: k.brut_kaynagi, dara_kaynagi: k.dara_kaynagi, emanet: k.emanet } : {}),
          },
        });
        this.fis = sonuc;
        this.modal = 'fis';
        if (this.ayarlar.otomatik_yazdir) this.$nextTick(() => setTimeout(() => this.fisYazdir(), 350));
        this.bildir(sonuc.fiyat ? `${sonuc.fis_no} kesildi · ${this.kg(sonuc.net_kg)} · ${this.tl(sonuc.fiyat.birim_fiyat)}/kg kilitlendi`
          : `${sonuc.fis_no}: ${this.kg(sonuc.net_kg)} emanete alındı (fiyat satışa çevrilince belirlenir).`);
        this.smsBildir(sonuc.sms);
        const uretici = k.uretici_id, numuneAcik = k.numune.acik;
        this.kantar = bosKantar();
        this.kantar.uretici_id = uretici;
        this.kantar.numune.acik = numuneAcik;   // analiz alışkanlığı korunur
        this.onizleme = null;
        await Promise.all([this.yukleOzet(), this.yukleTartimlar(), this.yukleDepo(), this.yukleUreticiler()]);
      } catch (e) { this.hataGoster(e); }
      this.kaydediliyor = false;
    },
    /** Emanette fiyat gerekmez (kalite sınıfı yeter); hemen satışta onaylı fiyat şart. */
    get fisKesilebilir() {
      return !this.kaydediliyor && this.yetkili('tartim') && (this.kantar.emanet ? !!this.onizleme : !!this.onizleme?.birim_fiyat);
    },

    // ─── Kantar indikatörü ───
    async kantarBaglan() {
      try {
        await seriKantar.baglan(this.ayarlar.baud);
        if (seriKantar.bagli) this.bildir('Kantar bağlandı. F2: brüt, F3: dara oku.');
      } catch (e) {
        this.bildir(e.message, 'hata');
      }
    },
    async kantarKes() { await seriKantar.kapat(); },
    /** Kantardan kararlı değeri okuyup alana kilitli yazar. Okunamazsa elle girişe bırakır (sistem durmaz). */
    /** Aktif ekranın tartım formu: Kantar (alım) veya Sevkiyat (çıkış). */
    agirlikFormu() {
      return this.tab === 'sevkiyat' ? { veri: this.sevk, onek: 's_' } : { veri: this.kantar, onek: 'k_' };
    },
    async kantardanOku(alan) {
      const { veri, onek } = this.agirlikFormu();
      const girdi = () => document.getElementById(`${onek}${alan}`);
      try {
        if (!seriKantar.bagli) await seriKantar.baglan(this.ayarlar.baud);
        if (!seriKantar.bagli) return;
        this.kantarOkunan = alan;
        const kg = await seriKantar.oku();
        if (kg <= 0) throw new Error(`Kantar ${kg} kg gösteriyor; araç kantarın üzerinde mi?`);
        veri[`${alan}_kg`] = String(kg);
        veri[`${alan}_kaynagi`] = 'SCALE';
        this.bildir(`${alan === 'brut' ? 'Brüt' : 'Dara'}: ${this.kg(kg)} kantardan okundu.`);
        this.$nextTick(() => this.sonrakiAlan(girdi()));
      } catch (e) {
        this.bildir(e.message, 'hata', ['Ağırlığı elle girebilirsiniz; fişte "elle girildi" yazar.']);
        girdi()?.focus();
      } finally {
        this.kantarOkunan = '';
      }
    },
    /** Kantardan okunan (kilitli) değeri elle düzeltmeye açar; kaynak "elle" olur. */
    agirlikKilidiAc(alan) {
      const { veri, onek } = this.agirlikFormu();
      veri[`${alan}_kaynagi`] = 'MANUAL';
      this.$nextTick(() => document.getElementById(`${onek}${alan}`)?.select());
    },

    // ─── Klavye ───
    /** Formda Enter bir sonraki alana geçer (Tab gibi); son alanda fişi keser. */
    kantarTus(e) {
      if (e.key !== 'Enter' || e.ctrlKey || e.metaKey || e.altKey || e.isComposing) return;
      if (e.target.tagName === 'BUTTON' || e.target.tagName === 'TEXTAREA') return;
      e.preventDefault();
      this.sonrakiAlan(e.target);
    },
    sonrakiAlan(el) {
      const form = el.closest('form');
      const alanlar = [...form.querySelectorAll('input, select, button[type="submit"]')].filter((x) =>
        !x.disabled && x.tabIndex >= 0 && x.offsetParent !== null && !(x.type === 'radio' && !x.checked));
      const sonraki = alanlar[alanlar.indexOf(el) + 1];
      if (!sonraki || sonraki.type === 'submit') form.requestSubmit();
      else { sonraki.focus(); if (sonraki.select && sonraki.type !== 'radio') sonraki.select(); }
    },
    /** Sayfa genelindeki kısayollar (Kantar ve Sevkiyat sekmelerinde, açık pencere yokken). */
    kisayol(e) {
      if (this.girisGerekli || this.modal || !['kantar', 'sevkiyat'].includes(this.tab)) return;
      if (e.key === 'F9' || (e.key === 'Enter' && (e.ctrlKey || e.metaKey))) {
        e.preventDefault();
        (this.tab === 'sevkiyat' ? this.$refs.sevkForm : this.$refs.kantarForm)?.requestSubmit();
      }
      else if (e.key === 'F2') { e.preventDefault(); this.kantardanOku('brut'); }
      else if (e.key === 'F3') { e.preventDefault(); this.kantardanOku('dara'); }
    },
    fisKesKisayol() {
      if (this.fisKesilebilir) return this.fisKes();
      const neden = !this.yetkili('tartim') ? this.yetkiYok('tartim')
        : this.onizleme && !this.onizleme.birim_fiyat ? this.onizleme.uyarilar?.[0] || 'Bu sınıf için fiyat yok.'
          : this.onizlemeMesaj;
      this.bildir(neden || 'Fiş kesilemiyor; eksik alanları doldurun.', 'hata');
    },
    /** Üretici arama kutusu: Enter ilk eşleşeni seçer, ↑/↓ eşleşenler arasında gezer. */
    ureticiAramaTus(e) {
      const liste = this.kantarUreticileri;
      if (!liste.length) return;
      const i = liste.findIndex((u) => String(u.id) === String(this.kantar.uretici_id));
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const j = e.key === 'ArrowDown' ? Math.min(i + 1, liste.length - 1) : Math.max(i - 1, 0);
        this.kantar.uretici_id = String(liste[j].id);
      } else if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
        e.preventDefault();
        e.stopPropagation();
        if (i < 0) this.kantar.uretici_id = String(liste[0].id);
        this.sonrakiAlan(e.target);
      }
    },
    get seciliKantarUreticisi() { return this.ureticiler.find((u) => String(u.id) === String(this.kantar.uretici_id)) || null; },

    // ─── Sevkiyat (satış / depodan çıkış) ───
    async yukleSevkiyatlar() {
      if (!this.kimlikDogrulama) return;
      [this.sevkiyatlar, this.musteriler] = await Promise.all([
        api('/sevkiyatlar', { params: { tarih: this.bugunIso() } }), api('/musteriler')]);
    },
    get sevkNetKg() {
      const b = parseFloat(this.sevk.brut_kg), d = parseFloat(this.sevk.dara_kg);
      return Number.isFinite(b) && Number.isFinite(d) ? Math.round((b - d) * 100) / 100 : null;
    },
    get sevkTutar() {
      const f = parseFloat(this.sevk.birim_fiyat);
      return this.sevkNetKg !== null && Number.isFinite(f) ? Math.round(this.sevkNetKg * f * 100) / 100 : null;
    },
    /** Stoğu olan silolar (çıkış yalnızca bunlardan yapılabilir). */
    get stokluSilolar() { return this.silolar.filter((s) => Number(s.stok_kg) > 0); },
    get sevkSilo() { return this.silolar.find((s) => String(s.silo_id) === String(this.sevk.silo_id)) || null; },
    get sevkKalan() {
      return this.sevkSilo && this.sevkNetKg !== null ? Number(this.sevkSilo.stok_kg) - this.sevkNetKg : null;
    },
    /** Uyarı (engellemez): çıkışın bir kısmı üreticilerin emanetinden karşılanacaksa. */
    get sevkEmanetUyari() {
      if (!this.sevkSilo || this.sevkNetKg === null) return '';
      const kendi = Number(this.sevkSilo.stok_kg) - Number(this.sevkSilo.emanet_kg || 0);
      return this.sevkNetKg > kendi
        ? `Dikkat: bu çıkışın ${this.kg(this.sevkNetKg - Math.max(kendi, 0))} kadarı üreticilerin emanet fıstığından karşılanacak.` : '';
    },
    get sevkUyari() {
      if (!this.yetkili('sevkiyat')) return 'Sevkiyat için depo sorumlusu veya yönetici yetkisi gerekir.';
      if (this.sevkNetKg !== null && this.sevkNetKg <= 0) return 'Dara (boş araç) brüt (dolu araç) ağırlığından küçük olmalıdır.';
      if (this.sevkKalan !== null && this.sevkKalan < 0)
        return `${this.sevkSilo.silo_kodu} silosunda ${this.kg(this.sevkSilo.stok_kg)} stok var; ${this.kg(this.sevkNetKg)} çıkış yapılamaz.`;
      return '';
    },
    async sevkiyatKaydet() {
      if (this.sevkUyari) { this.bildir(this.sevkUyari, 'hata'); return; }
      const f = this.sevk;
      this.sevkKaydediliyor = true;
      this.alanHatalari = {};
      try {
        const s = await api('/sevkiyatlar', {
          method: 'POST',
          body: {
            musteri_adi: f.musteri_adi, plaka: f.plaka, sofor_adi: bosIseNull(f.sofor_adi),
            silo_id: f.silo_id ? Number(f.silo_id) : null, brut_kg: bosIseNull(f.brut_kg), dara_kg: bosIseNull(f.dara_kg),
            birim_fiyat: bosIseNull(f.birim_fiyat), tahsilat: f.tahsilat, aciklama: bosIseNull(f.aciklama),
            brut_kaynagi: f.brut_kaynagi, dara_kaynagi: f.dara_kaynagi,
          },
        });
        this.irsaliye = s;
        this.modal = 'irsaliye';
        this.bildir(`${s.sevkiyat_no}: ${this.kg(s.net_kg)} ${s.silo_kodu} silosundan ${s.musteri_adi} için çıkış yapıldı.`);
        if (this.ayarlar.otomatik_yazdir) this.$nextTick(() => setTimeout(() => this.fisYazdir(), 350));
        this.sevk = { ...bosSevk(), tahsilat: f.tahsilat };
        await Promise.all([this.yukleSevkiyatlar(), this.yukleDepo()]);
      } catch (e) { this.hataGoster(e); }
      this.sevkKaydediliyor = false;
    },
    async irsaliyeGoster(id) {
      try { this.irsaliye = await api(`/sevkiyatlar/${id}`); this.modal = 'irsaliye'; } catch (e) { this.hataGoster(e); }
    },

    // ─── Alıcılar: tahsilat ve ekstre ───
    aliciToplam(alan) { return this.musteriler.reduce((t, m) => t + Number(m[alan] || 0), 0); },
    get tahsilatMusteri() { return this.musteriler.find((m) => String(m.id) === String(this.tahsilatForm.musteri_id)) || null; },
    tahsilatAc(m) {
      this.tahsilatForm = { musteri_id: m ? String(m.id) : '', tutar: '', odeme_yontemi: 'BANK_TRANSFER', referans_no: '',
                            tarih: this.bugunIso(), aciklama: '' };
      this.tahsilatTutarOner();
      this.alanHatalari = {};
      this.modal = 'tahsilat';
    },
    tahsilatTutarOner() {
      const a = Number(this.tahsilatMusteri?.acik_hesap);
      this.tahsilatForm.tutar = a > 0 ? String(a) : '';
    },
    async tahsilatKaydet() {
      const f = this.tahsilatForm;
      await this.islem(async () => {
        const t = await api(`/musteriler/${f.musteri_id}/tahsilatlar`, {
          method: 'POST',
          body: { tutar: bosIseNull(f.tutar), odeme_yontemi: f.odeme_yontemi, referans_no: bosIseNull(f.referans_no),
                  tarih: bosIseNull(f.tarih), aciklama: bosIseNull(f.aciklama) },
        });
        this.modal = null;
        this.bildir(`${t.makbuz_no}: ${t.musteri_adi} için ${this.tl(t.tutar)} tahsilat kaydedildi. Kalan açık hesap ${this.tl(t.kalan_acik_hesap)}.`);
        await Promise.all([this.yukleSevkiyatlar(), this.yukleOzet()]);
      });
    },
    async musteriEkstreAc(m) {
      this.musteriEkstre = null;
      this.musteriEkstreForm = { musteri_id: m.id, baslangic: '', bitis: '' };
      this.modal = 'musteri-ekstre';
      await this.musteriEkstreGetir();
    },
    async musteriEkstreGetir() {
      const f = this.musteriEkstreForm;
      await this.islem(async () => {
        this.musteriEkstre = await api(`/musteriler/${f.musteri_id}/ekstre`, { params: { baslangic: f.baslangic, bitis: f.bitis } });
      });
    },
    musteriEkstreBakiye() {
      const h = this.musteriEkstre?.hareketler || [];
      return h.length ? h.at(-1).bakiye : this.musteriEkstre?.devreden_bakiye ?? 0;
    },
    donemYaz(f) {
      const t = (v) => v.split('-').reverse().join('.');
      return f.baslangic && f.bitis ? `${t(f.baslangic)} – ${t(f.bitis)}` : f.baslangic ? `${t(f.baslangic)} sonrası`
        : f.bitis ? `${t(f.bitis)} öncesi` : 'Tüm zamanlar';
    },
    /** A4 döküm yazdırma (PDF olarak kaydederken önerilen dosya adı başlık olur). */
    a4Yazdir(baslik) {
      document.getElementById('sayfa-boyutu').textContent = '@page { size: A4 portrait; margin: 12mm; }';
      const eski = document.title;
      document.title = `${baslik} - ${this.bugunIso()}`;
      window.print();
      document.title = eski;
    },

    // ─── Resmî kesintiler ───
    async yukleKesintiler() {
      if (!this.kimlikDogrulama) return;
      this.kesintiler = (await api('/kesintiler')).map((k) => ({ ...k, oran: String(Number(k.oran)) }));   // 2.0000 → 2
    },
    async kesintiKaydet(k) {
      await this.islem(async () => {
        const y = await api(`/kesintiler/${k.kod}`, { method: 'PUT', body: { oran: k.oran, aktif: !!k.aktif } });
        this.bildir(`${y.ad}: ${y.aktif ? 'uygulanıyor' : 'kapalı'}${y.hesaplama === 'PERCENT' ? ` (%${Number(y.oran)})` : ''}.`);
        await this.yukleKesintiler();
        this.onizlemeIste();
      });
    },

    // ─── Emanet ───
    async emanetCevirAc(t) {
      this.emanetForm = { tartim: t, onizleme: null, miktar: String(Number(t.emanet_kalan_kg)) };
      this.modal = 'emanet';
      await this.emanetOnizle();
      this.$nextTick(() => document.getElementById('e_miktar')?.select());
    },
    get emanetMiktarHata() {
      const f = this.emanetForm, m = Number(f.miktar);
      if (!f.tartim) return '';
      if (!(m > 0)) return 'Satılacak miktarı girin.';
      if (m > Number(f.tartim.emanet_kalan_kg)) return `Kalan emanet ${this.kg(f.tartim.emanet_kalan_kg)}; daha fazlası satılamaz.`;
      return '';
    },
    async emanetOnizle() {
      if (this.emanetMiktarHata) { this.emanetForm.onizleme = null; return; }
      try {
        this.emanetForm.onizleme = await api(`/tartimlar/${this.emanetForm.tartim.tartim_id}/satis-onizleme`,
          { params: { miktar_kg: this.emanetForm.miktar } });
      } catch (e) { this.emanetForm.onizleme = null; this.hataGoster(e); }
    },
    async emanetCevir() {
      if (this.emanetMiktarHata) return;
      await this.islem(async () => {
        const t = await api(`/tartimlar/${this.emanetForm.tartim.tartim_id}/satisa-cevir`,
          { method: 'POST', body: { miktar_kg: this.emanetForm.miktar } });
        this.bildir(`${t.fis_no}: ${this.kg(t.net_kg)} × ${this.tl(t.fiyat.birim_fiyat)}/kg satışa çevrildi · net hakediş ${this.tl(t.hakedis.net_odenecek)}`
          + (Number(t.emanet_kalan_kg) > 0 ? ` · kalan emanet ${this.kg(t.emanet_kalan_kg)}` : ''));
        this.smsBildir(t.sms);
        this.fis = t;
        this.modal = 'fis';
        await Promise.all([this.yukleEkstre(), this.yukleUreticiler(), this.yukleOzet(), this.yukleDepo()]);
      });
    },

    // ─── Çatlak primi ───
    async yukleCatlakPrimi() {
      if (!this.kimlikDogrulama) return;
      this.catlakPrimleri = await api('/fiyatlar/catlak-primi');
    },
    async catlakPrimiKaydet(k) {
      await this.islem(async () => {
        const y = await api(`/fiyatlar/catlak-primi/${k.urun_turu}`, {
          method: 'PUT',
          body: { baz_catlak_orani: k.baz_catlak_orani, puan_basi_tl: k.puan_basi_tl,
                  azami_fark_tl: bosIseNull(k.azami_fark_tl), aktif: !!k.aktif,
                  bos_esik_orani: k.bos_esik_orani, bos_puan_basi_tl: k.bos_puan_basi_tl, bos_aktif: !!k.bos_aktif },
        });
        this.bildir(`${y.urun_turu_adi}: çatlak primi ${y.aktif ? 'uygulanıyor' : 'kapalı'}, boş tane iskontosu ${y.bos_aktif ? 'uygulanıyor' : 'kapalı'}.`);
        await this.yukleCatlakPrimi();
        this.onizlemeIste();
      });
    },
    /** "%6 boş → −15,00 ₺/kg" */
    bosOrnek(k) {
      if (!k.bos_aktif) return 'Kapalı: boş oranı fiyatı etkilemez.';
      const esik = Number(k.bos_esik_orani), ceza = Number(k.bos_puan_basi_tl);
      return `Örnek: %${esik + 3} boş → −${this.tl(3 * ceza)}/kg; %${esik} ve altı kesintisiz.`;
    },
    /** Kural açıklaması: "%91 çatlak → +12,00 ₺/kg". */
    catlakOrnek(k) {
      if (!k.aktif) return 'Kapalı: baz fiyat aynen uygulanır.';
      const baz = Number(k.baz_catlak_orani), adim = Number(k.puan_basi_tl), azami = k.azami_fark_tl === null || k.azami_fark_tl === '' ? Infinity : Number(k.azami_fark_tl);
      const ornek = (c) => { const p = Math.max(-azami, Math.min(azami, (c - baz) * adim)); return `%${c} → ${p >= 0 ? '+' : '−'}${this.tl(Math.abs(p))}`; };
      return `Örnek: ${ornek(baz + 5)}, ${ornek(baz - 5)} /kg`;
    },

    // ─── Müstahsil cari hesap dökümü ───
    async dokumAc() {
      this.dokum = null;
      this.dokumForm = { baslangic: '', bitis: '' };
      this.modal = 'dokum';
      await this.dokumGetir();
    },
    async dokumGetir() {
      await this.islem(async () => {
        this.dokum = await api(`/ureticiler/${this.secili.id}/bakiye-ekstre`,
          { params: { baslangic: this.dokumForm.baslangic, bitis: this.dokumForm.bitis } });
      });
    },
    dokumDonemi() {
      const t = (v) => v.split('-').reverse().join('.');
      const { baslangic: b, bitis: s } = this.dokumForm;
      return b && s ? `${t(b)} – ${t(s)}` : b ? `${t(b)} sonrası` : s ? `${t(s)} öncesi` : 'Tüm zamanlar';
    },
    dokumHareketToplam(alan) { return (this.dokum?.hareketler || []).reduce((t, h) => t + Number(h[alan] || 0), 0); },
    dokumTurToplam(tur, alan) {
      return (this.dokum?.hareketler || []).filter((h) => h.tur === tur).reduce((t, h) => t + Number(h[alan] || 0), 0);
    },
    dokumOdemeToplam(yontem) {
      return (this.dokum?.odemeler || []).filter((o) => o.odeme_yontemi === yontem).reduce((t, o) => t + Number(o.tutar), 0);
    },
    /** Bakiye: (A) merkez üreticiye borçlu, (B) üretici merkeze borçlu. */
    bakiyeYaz(v) {
      const n = Number(v);
      return n === 0 ? this.tl(0) : `${this.tl(Math.abs(n))} ${n > 0 ? '(A)' : '(B)'}`;
    },
    /** Dönem sonundaki bakiye: son hareketin yürüyen bakiyesi (hareket yoksa devreden). */
    dokumBakiye() {
      const h = this.dokum?.hareketler || [];
      if (h.length) return h.at(-1).bakiye;
      return this.dokum?.donem_baslangic ? this.dokum.devreden_bakiye : this.dokum?.ozet.bakiye;
    },
    dokumYazdir() {
      document.getElementById('sayfa-boyutu').textContent = '@page { size: A4 portrait; margin: 12mm; }';
      const eski = document.title;
      // PDF olarak kaydederken önerilen dosya adı
      document.title = `Ekstre - ${this.dokum.uretici.ad_soyad} - ${this.bugunIso()}`;
      window.print();
      document.title = eski;
    },

    // ─── Gün sonu raporu (CSV / Excel) ───
    bugunIso() { return new Date().toLocaleDateString('sv-SE', { timeZone: 'Europe/Istanbul' }); },
    async gunSonuIndir() {
      this.gunSonuIniyor = true;
      try {
        const tarih = this.gunSonuTarihi || this.bugunIso();
        const res = await fetch(`${API}/raporlar/gun-sonu.csv?tarih=${encodeURIComponent(tarih)}`,
          { headers: { Authorization: `Bearer ${tokenOku()}` } });
        if (!res.ok) {
          const veri = await res.json().catch(() => null);
          throw new ApiHatasi(veri?.hata || `Rapor alınamadı (HTTP ${res.status})`, veri?.detaylar || [], res.status);
        }
        const url = URL.createObjectURL(await res.blob());
        const a = Object.assign(document.createElement('a'), { href: url, download: `gun-sonu-${tarih}.csv` });
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        this.bildir(`Gün sonu raporu indirildi (${tarih.split('-').reverse().join('.')}). Excel ile açabilirsiniz.`);
      } catch (e) { this.hataGoster(e); }
      this.gunSonuIniyor = false;
    },

    // ─── Termal fiş ───
    fisYazdir() {
      document.getElementById('sayfa-boyutu').textContent = this.ayarlar.kagit === 'A5'
        ? '@page { size: A5 portrait; margin: 10mm; }'
        : `@page { size: ${this.ayarlar.kagit === '58' ? '58mm' : '80mm'} auto; margin: 0; }`;
      window.print();
    },
    /** Müstahsile giden bilgilendirme SMS'ini bildirim olarak gösterir. */
    smsBildir(s) {
      if (!s) return;
      if (s.durum === 'FAILED') this.bildir(`SMS gönderilemedi (${s.telefon})`, 'hata', [s.hata || 'Bilinmeyen hata']);
      else this.bildir(`SMS Gönderildi${s.simulasyon ? ' (Simülasyon)' : ''}: ${s.telefon}`, 'basari', [s.mesaj]);
    },
    async fisGoster(id) {
      try { this.fis = await api(`/tartimlar/${id}`); this.modal = 'fis'; } catch (e) { this.hataGoster(e); }
    },

    // ─── Üretici & finans ───
    get listelenenUreticiler() {
      const q = this.ureticiArama.toLocaleLowerCase('tr');
      if (!q) return this.ureticiler;
      return this.ureticiler.filter((u) => [u.ad_soyad, u.tc_kimlik_no, u.vergi_no, u.telefon]
        .some((v) => (v || '').toLocaleLowerCase('tr').includes(q)));
    },
    async ureticiSec(u) {
      this.secili = u;
      this.ekstre = null;
      try { await this.yukleEkstre(); } catch (e) { this.hataGoster(e); }
    },
    ureticiModalAc() {
      this.yeniUretici = bosUretici();
      this.alanHatalari = {};
      this.modal = 'uretici';
    },
    async ureticiKaydet() {
      const f = this.yeniUretici;
      const kurumsal = f.tip === 'COMPANY';
      const body = {
        tip: f.tip, ad_soyad: f.ad_soyad, telefon: f.telefon, ilce: f.ilce,
        tc_kimlik_no: kurumsal ? null : bosIseNull(f.tc_kimlik_no),
        vergi_no: kurumsal ? bosIseNull(f.vergi_no) : null,
        vergi_dairesi: kurumsal ? bosIseNull(f.vergi_dairesi) : null,
        banka_hesabi: f.iban ? { banka_adi: f.banka_adi, iban: f.iban } : null,
      };
      await this.islem(async () => {
        const u = await api('/ureticiler', { method: 'POST', body });
        this.modal = null;
        this.bildir(`${u.ad_soyad} kaydedildi.`);
        await this.yukleUreticiler();
        this.tab = 'uretici';
        await this.ureticiSec(this.ureticiler.find((x) => x.id === u.id) || u);
      });
    },
    avansModalAc() {
      this.avansForm = { tutar: '', aciklama: '', odeme_yontemi: this.secili.banka_hesaplari.length ? 'BANK_TRANSFER' : 'CASH' };
      this.alanHatalari = {};
      this.modal = 'avans';
    },
    async avansVer() {
      await this.islem(async () => {
        const a = await api('/avanslar', {
          method: 'POST',
          body: { uretici_id: this.secili.id, tutar: this.avansForm.tutar, odeme_yontemi: this.avansForm.odeme_yontemi,
                  aciklama: bosIseNull(this.avansForm.aciklama) },
        });
        this.modal = null;
        this.bildir(`${a.avans_no}: ${this.tl(a.tutar)} avans verildi. Yeni bakiye ${this.tl(a.uretici_guncel_bakiye)}`);
        this.smsBildir(a.sms);
        await Promise.all([this.yukleEkstre(), this.yukleUreticiler(), this.yukleOzet()]);
      });
    },
    odemeModalAc(teslimat) {
      this.odemeForm = {
        hakedis: teslimat, tutar: '', referans_no: '',
        odeme_yontemi: this.secili.banka_hesaplari.length ? 'BANK_TRANSFER' : 'CASH',
      };
      this.alanHatalari = {};
      this.modal = 'odeme';
    },
    async odemeYap() {
      const f = this.odemeForm;
      await this.islem(async () => {
        const s = await api(`/hakedisler/${f.hakedis.hakedis_id}/odeme-yap`, {
          method: 'POST',
          body: { tutar: bosIseNull(f.tutar), odeme_yontemi: f.odeme_yontemi, referans_no: bosIseNull(f.referans_no) },
        });
        this.modal = null;
        this.bildir(s.mesaj);
        await Promise.all([this.yukleEkstre(), this.yukleUreticiler(), this.yukleOzet(), this.yukleTartimlar()]);
      });
    },

    // ─── Fiyat ───
    async fiyatYayinla() {
      const kalemler = Object.entries(this.yeniFiyatlar)
        .filter(([, v]) => v !== '' && v !== null && v !== undefined)
        .map(([kalite_kodu, birim_fiyat]) => ({ kalite_kodu, birim_fiyat }));
      if (!kalemler.length) { this.hataGoster(new ApiHatasi('En az bir kalite sınıfı için yeni fiyat girin.')); return; }
      this.fiyatKaydediliyor = true;
      try {
        await api('/fiyatlar', { method: 'POST', body: { fiyatlar: kalemler, gecerlilik_baslangici: bosIseNull(this.fiyatGecerlilik) } });
        this.bildir(`${kalemler.length} kalite sınıfı için fiyat yayınlandı.`);
        this.yeniFiyatlar = {};
        this.fiyatGecerlilik = '';
        await this.yukleFiyatlar();
        this.onizlemeIste();
      } catch (e) { this.hataGoster(e); }
      this.fiyatKaydediliyor = false;
    },

    // ─── Görsel efektler ───
    get selamlama() {
      const s = new Date().getHours();
      return s < 6 ? 'İyi geceler' : s < 12 ? 'Günaydın' : s < 18 ? 'İyi çalışmalar' : 'İyi akşamlar';
    },
    fiyatAd(kod) {
      const f = this.fiyatlar.find((k) => k.kalite_kodu === kod)?.fiyat;
      return f ? `${this.tl(f.birim_fiyat)}/kg` : 'girilmedi';
    },
    /** Sayıyı eski değerinden yeni değerine ~0,6 sn'de yumuşakça sayarak götürür. */
    sayiAnimasyonu(anahtar, hedef) {
      hedef = Number(hedef) || 0;
      cancelAnimationFrame(this._animKare[anahtar]);
      const hareketAz = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      const bas = Number(this.anim[anahtar]) || 0, sure = 600, t0 = performance.now();
      if (hareketAz || bas === hedef) { this.anim[anahtar] = hedef; return; }
      const adim = (t) => {
        const k = Math.min(1, (t - t0) / sure), e = 1 - Math.pow(1 - k, 3);   // ease-out
        this.anim[anahtar] = k < 1 ? bas + (hedef - bas) * e : hedef;
        if (k < 1) this._animKare[anahtar] = requestAnimationFrame(adim);
      };
      this._animKare[anahtar] = requestAnimationFrame(adim);
      // Sekme arka plandayken requestAnimationFrame durur; değer yine de doğru kalsın.
      clearTimeout(this._animKare[anahtar + '_son']);
      this._animKare[anahtar + '_son'] = setTimeout(() => {
        cancelAnimationFrame(this._animKare[anahtar]);
        this.anim[anahtar] = hedef;
      }, sure + 100);
    },
    /** Öğeye kısa bir parlama/büyüme animasyonu uygular (her çağrıda baştan başlatır). */
    parlat(el, sinif = 'parla') {
      el.classList.remove(sinif);
      void el.offsetWidth;   // reflow: animasyonu yeniden tetikle
      el.classList.add(sinif);
    },

    // ─── Yardımcılar ───
    async islem(fn) {
      this.islemde = true;
      this.alanHatalari = {};
      try { await fn(); } catch (e) { this.hataGoster(e); }
      this.islemde = false;
    },
    hata(alan) { return this.alanHatalari[alan] || ''; },
    hataGoster(e) {
      const detaylar = e.detaylar || [];
      this.alanHatalari = Object.fromEntries(detaylar.map((d) => [d.alan, d.mesaj]));
      this.bildir(e.message, 'hata', detaylar.map((d) => `${d.alan}: ${d.mesaj}`));
    },
    bildir(mesaj, tur = 'basari', detaylar = []) {
      const id = Date.now() + Math.random();
      this.toasts.push({ id, mesaj, tur, detaylar });
      setTimeout(() => this.toastKapat(id), tur === 'hata' ? 8000 : 4500);
    },
    toastKapat(id) { this.toasts = this.toasts.filter((t) => t.id !== id); },
    saatGuncelle() {
      this.saat = new Date().toLocaleString('tr-TR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    },

    tl(v) { return v === null || v === undefined || v === '' ? '—' : fmtTL.format(Number(v)); },
    sayi(v) { return v === null || v === undefined || v === '' ? '—' : fmtSayi.format(Number(v)); },
    kg(v) { return v === null || v === undefined || v === '' ? '—' : `${fmtSayi.format(Number(v))} kg`; },
    yuzde(v) { return v === null || v === undefined ? '—' : `%${fmtSayi.format(Number(v))}`; },
    tarihYaz(v) { return v ? new Date(v).toLocaleString('tr-TR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—'; },
    saatYaz(v) { return v ? new Date(v).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' }) : '—'; },
    ibanYaz(v) { return (v || '').replace(/(.{4})/g, '$1 ').trim(); },
    basHarfler(ad) { return (ad || '?').split(/\s+/).slice(0, 2).map((p) => p[0]).join('').toLocaleUpperCase('tr'); },

    /** Silo/kalite kodundan ürün türü minyatürü: Y ile başlayan sınıflar yaş fıstık, diğerleri kuru-kavlak. */
    kaliteFoto(kod) {
      if (!kod) return null;
      return this.urunFotolari[String(kod).startsWith('Y') ? 'FRESH' : 'DRY'];
    },
    kaliteRenk(kod) {
      return ({
        A: { rozet: 'bg-fistik-100 text-fistik-800 ring-fistik-300', dolgu: 'bg-fistik-500' },
        B: { rozet: 'bg-amber-100 text-amber-800 ring-amber-300', dolgu: 'bg-amber-400' },
        ISK: { rozet: 'bg-rose-100 text-rose-800 ring-rose-300', dolgu: 'bg-rose-400' },
        YA: { rozet: 'bg-teal-100 text-teal-800 ring-teal-300', dolgu: 'bg-teal-500' },
        YB: { rozet: 'bg-orange-100 text-orange-800 ring-orange-300', dolgu: 'bg-orange-400' },
        YISK: { rozet: 'bg-rose-100 text-rose-800 ring-rose-300', dolgu: 'bg-rose-300' },
      })[kod] || { rozet: 'bg-stone-100 text-stone-700 ring-stone-300', dolgu: 'bg-kabuk-500' };
    },
    durumAd(d) {
      return ({ UNPAID: 'Ödenmedi', PARTIALLY_PAID: 'Kısmi ödendi', PAID: 'Ödendi', OFFSET_ONLY: 'Avansa mahsup' })[d] || '—';
    },
    durumRenk(d) {
      return ({
        UNPAID: 'bg-stone-100 text-stone-700', PARTIALLY_PAID: 'bg-sky-100 text-sky-800',
        PAID: 'bg-fistik-100 text-fistik-800', OFFSET_ONLY: 'bg-amber-100 text-amber-800',
      })[d] || 'bg-stone-100 text-stone-500';
    },
    hareketAd(t) {
      return ({ DELIVERY: 'Teslimat', DEDUCTION: 'Kesinti', ADVANCE: 'Avans', PAYMENT: 'Ödeme', ADJUSTMENT: 'Düzeltme', REVERSAL: 'Ters kayıt' })[t] || t;
    },
    bakiyeRenk(v) { const n = Number(v); return n < 0 ? 'text-rose-700' : n > 0 ? 'text-fistik-700' : 'text-stone-600'; },
    bakiyeDurumAd(d) { return ({ ALACAKLI: 'Üreticiye borçluyuz', BORCLU: 'Üretici avans borçlu', KAPALI: 'Hesap kapalı' })[d] || ''; },
  }));
});
