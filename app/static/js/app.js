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

/** Boş metinleri null'a çevirir (API boş string yerine alanın hiç olmamasını bekler). */
const bosIseNull = (v) => (v === '' || v === undefined ? null : v);

const bosKantar = () => ({
  uretici_id: '', plaka: '', sofor_adi: '', brut_kg: '', dara_kg: '', silo_id: '',
  kalite: { numune_gram: '500', catlak_orani: '', randiman: '', bos_orani: '', curuk_orani: '0', nem_orani: '' },
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
    girisModu: 'giris',           // 'giris' | 'talep'
    talepForm: bosTalep(),
    demoHesaplar: [],
    rolBilgisi: {
      ADMIN: { ad: 'Yönetici', aciklama: 'Tüm işlemler', sekme: 'kantar', renk: 'from-emerald-600 to-fistik-700' },
      SCALE_OPERATOR: { ad: 'Kantar memuru', aciklama: 'Tartım ve fiş kesme', sekme: 'kantar', renk: 'from-emerald-500 to-teal-600' },
      QUALITY: { ad: 'Kalite sorumlusu', aciklama: 'Kalite analizlerini izleme', sekme: 'kantar', renk: 'from-sky-500 to-indigo-600' },
      ACCOUNTING: { ad: 'Muhasebe', aciklama: 'Üretici, avans ve ödeme', sekme: 'uretici', renk: 'from-teal-500 to-cyan-700' },
      WAREHOUSE: { ad: 'Depo sorumlusu', aciklama: 'Stok ve raporları izleme', sekme: 'fiyat', renk: 'from-lime-500 to-fistik-600' },
      PRICE_MANAGER: { ad: 'Fiyat yöneticisi', aciklama: 'Günlük fiyat belirleme', sekme: 'fiyat', renk: 'from-amber-400 to-amber-600' },
    },

    // kullanıcı yönetimi
    kullanicilar: [],
    onayRolleri: {},              // talep id → onaylanırken verilecek rol
    kullaniciForm: {},
    sifirlamaForm: {},
    sifreForm: {},

    async init() {
      this.saatGuncelle();
      setInterval(() => this.saatGuncelle(), 30_000);
      setInterval(() => { if (!this.girisGerekli) this.yukleOzet().catch(() => {}); }, 60_000);
      this.$watch('kantar', () => this.onizlemeIste());
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
      try { this.demoHesaplar = await api('/oturum/demo-hesaplar'); } catch { this.demoHesaplar = []; }
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
                    avans_ver: 'muhasebe', odeme_yap: 'muhasebe' }[islem];
      return this.yetkili(islem) ? '' : `Bu işlem için ${kim} veya yönetici yetkisi gerekir.`;
    },
    /** Yetki gerektiren sekmeler (Kullanıcılar) yalnızca girişli sürümde ve yetkiliye görünür. */
    get gorunurSekmeler() {
      return this.sekmeler.filter((s) => !s.yetki || (this.kimlikDogrulama && this.yetkili(s.yetki)));
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
                           this.yukleKullanicilar()]);
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
    get netKg() {
      const b = parseFloat(this.kantar.brut_kg), d = parseFloat(this.kantar.dara_kg);
      return Number.isFinite(b) && Number.isFinite(d) ? b - d : null;
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
          body: { uretici_id: bosIseNull(k.uretici_id) && Number(k.uretici_id), brut_kg: k.brut_kg, dara_kg: k.dara_kg, kalite: { ...k.kalite } },
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
            brut_kg: k.brut_kg, dara_kg: k.dara_kg, kalite: { ...k.kalite },
            silo_id: k.silo_id ? Number(k.silo_id) : null,
          },
        });
        this.fis = sonuc;
        this.modal = 'fis';
        this.bildir(`${sonuc.fis_no} kesildi · ${this.kg(sonuc.net_kg)} · ${this.tl(sonuc.fiyat.birim_fiyat)}/kg kilitlendi`);
        const uretici = k.uretici_id;
        this.kantar = bosKantar();
        this.kantar.uretici_id = uretici;
        this.onizleme = null;
        await Promise.all([this.yukleOzet(), this.yukleTartimlar(), this.yukleDepo(), this.yukleUreticiler()]);
      } catch (e) { this.hataGoster(e); }
      this.kaydediliyor = false;
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
    kg(v) { return v === null || v === undefined || v === '' ? '—' : `${fmtSayi.format(Number(v))} kg`; },
    yuzde(v) { return v === null || v === undefined ? '—' : `%${fmtSayi.format(Number(v))}`; },
    tarihYaz(v) { return v ? new Date(v).toLocaleString('tr-TR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—'; },
    saatYaz(v) { return v ? new Date(v).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' }) : '—'; },
    ibanYaz(v) { return (v || '').replace(/(.{4})/g, '$1 ').trim(); },
    basHarfler(ad) { return (ad || '?').split(/\s+/).slice(0, 2).map((p) => p[0]).join('').toLocaleUpperCase('tr'); },

    kaliteRenk(kod) {
      return ({
        A: { rozet: 'bg-fistik-100 text-fistik-800 ring-fistik-300', dolgu: 'bg-fistik-500' },
        B: { rozet: 'bg-amber-100 text-amber-800 ring-amber-300', dolgu: 'bg-amber-400' },
        ISK: { rozet: 'bg-rose-100 text-rose-800 ring-rose-300', dolgu: 'bg-rose-400' },
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
