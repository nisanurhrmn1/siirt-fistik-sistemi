/* Siirt Fıstığı Yönetim Paneli — Alpine.js bileşeni. Tüm veri /api/v1 üzerinden gelir. */
const API = '/api/v1';
const fmtTL = new Intl.NumberFormat('tr-TR', { style: 'currency', currency: 'TRY' });
const fmtSayi = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 2 });

class ApiHatasi extends Error {
  constructor(mesaj, detaylar = []) { super(mesaj); this.detaylar = detaylar; }
}

async function api(yol, { method = 'GET', body, params } = {}) {
  let url = API + yol;
  if (params) {
    const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== null && v !== undefined && v !== ''));
    if ([...q].length) url += '?' + q;
  }
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const veri = await res.json().catch(() => null);
  if (!res.ok) throw new ApiHatasi(veri?.hata || `İstek başarısız (HTTP ${res.status})`, veri?.detaylar || []);
  return veri;
}

/** Boş metinleri null'a çevirir (API boş string yerine alanın hiç olmamasını bekler). */
const bosIseNull = (v) => (v === '' || v === undefined ? null : v);

const bosKantar = () => ({
  uretici_id: '', plaka: '', sofor_adi: '', brut_kg: '', dara_kg: '', silo_id: '',
  kalite: { numune_gram: '500', catlak_orani: '', randiman: '', bos_orani: '', curuk_orani: '0', nem_orani: '' },
});
const bosUretici = () => ({
  tip: 'INDIVIDUAL', ad_soyad: '', tc_kimlik_no: '', vergi_no: '', vergi_dairesi: '', telefon: '',
  ilce: 'Merkez', banka_adi: '', iban: '',
});

document.addEventListener('alpine:init', () => {
  Alpine.data('panel', () => ({
    sekmeler: [
      { id: 'kantar', ad: 'Kantar & Teslimat' },
      { id: 'uretici', ad: 'Üretici & Finans' },
      { id: 'fiyat', ad: 'Fiyat & Depo' },
    ],
    tab: 'kantar',
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

    async init() {
      this.saatGuncelle();
      setInterval(() => this.saatGuncelle(), 30_000);
      setInterval(() => this.yukleOzet(), 60_000);
      this.$watch('kantar', () => this.onizlemeIste());
      await this.hepsiniYenile();
    },

    // ─── Yükleme ───
    async hepsiniYenile() {
      this.yenileniyor = true;
      try {
        await Promise.all([this.yukleOzet(), this.yukleUreticiler(), this.yukleTartimlar(), this.yukleFiyatlar(), this.yukleDepo()]);
        if (this.secili) await this.yukleEkstre();
      } catch (e) { this.hataGoster(e); }
      this.yenileniyor = false;
    },
    async yukleOzet() { this.ozet = await api('/raporlar/gunluk-ozet'); },
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
