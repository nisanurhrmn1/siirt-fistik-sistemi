"""Hesap talebinde yöneticiye Telegram bildirimi — uçtan uca (yalnızca Java sürümü).

Gerçek Telegram'a gidilmez: test, 127.0.0.1 üzerinde sahte bir Telegram API'si açar ve Java sunucusu ona yönlendirilir.
Sunucuyu şu ortam değişkenleriyle başlatın, testi aynı portla çalıştırın:

    TELEGRAM_API_ADRESI=http://127.0.0.1:8029  TELEGRAM_BOT_TOKEN=123456:TEST  TELEGRAM_BOT_CHAT_ID=42
    $env:FISTIK_TELEGRAM_SAHTE_PORT=8029; .\\.venv\\Scripts\\python.exe -m pytest --java http://127.0.0.1:8020 tests/test_telegram.py

FISTIK_TELEGRAM_SAHTE_PORT tanımlı değilse yalnızca "Telegram ayarsızken talep yine kaydedilir" testi çalışır.
"""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from tests.test_kullanicilar import talep

API = "/api/v1"
SAHTE_PORT = os.environ.get("FISTIK_TELEGRAM_SAHTE_PORT")


@pytest.fixture(autouse=True)
def _yalnizca_java(request):
    if not request.config.getoption("--java"):
        pytest.skip("Telegram bildirimi yalnızca Java sürümünde var (--java ile çalıştırın)")


@pytest.fixture
def anonim(request, db_engine):
    with httpx.Client(base_url=request.config.getoption("--java"), follow_redirects=True, timeout=30) as c:
        yield c


@pytest.fixture
def telegram():
    """Sahte Telegram API: gelen sendMessage isteklerini toplar."""
    if not SAHTE_PORT:
        pytest.skip("FISTIK_TELEGRAM_SAHTE_PORT tanımlı değil (sunucu sahte Telegram'a yönlendirilmemiş)")
    gelenler = []

    class Isleyici(BaseHTTPRequestHandler):
        def do_POST(self):
            govde = self.rfile.read(int(self.headers["Content-Length"]))
            gelenler.append((self.path, json.loads(govde)))
            yanit = b'{"ok":true,"result":{}}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(yanit)))
            self.end_headers()
            self.wfile.write(yanit)

        def log_message(self, *a):
            pass

    sunucu = ThreadingHTTPServer(("127.0.0.1", int(SAHTE_PORT)), Isleyici)
    threading.Thread(target=sunucu.serve_forever, daemon=True).start()
    yield gelenler
    sunucu.shutdown()
    sunucu.server_close()


def bekle(gelenler, adet, sure=5.0):
    son = time.monotonic() + sure
    while len(gelenler) < adet and time.monotonic() < son:
        time.sleep(0.05)
    return gelenler


def test_talep_kaydedilince_yoneticiye_telegram_mesaji_gider(anonim, telegram):
    r = talep(anonim, "tg.deneme", rol="ACCOUNTING", aciklama="Merkez <b>muhasebe</b> & finans")
    assert r.status_code == 201, r.text
    yol, govde = bekle(telegram, 1)[0]
    assert yol == "/bot123456:TEST/sendMessage"
    assert govde["chat_id"] == "42" and govde["parse_mode"] == "HTML"
    metin = govde["text"]
    for parca in ("Yeni Hesap Talebi Var!", "<b>Ad Soyad:</b> Ayşe Kaya", "<b>Kullanıcı Adı:</b> tg.deneme",
                  "<b>Talep Edilen Rol:</b> Muhasebe", "&lt;b&gt;muhasebe&lt;/b&gt; &amp; finans",
                  "Onaylamak için panele gidin: https://siirt-fistik.onrender.com"):
        assert parca in metin, parca
    assert "Deneme-1234" not in json.dumps(govde)              # şifre asla gönderilmez


def test_basarisiz_talepte_bildirim_gitmez(anonim, telegram):
    assert talep(anonim, "tg.iki").status_code == 201
    bekle(telegram, 1)
    assert talep(anonim, "tg.iki").status_code == 409              # kullanıcı adı alınmış: kayıt yok
    assert talep(anonim, "tg.uc", rol="ADMIN").status_code == 422  # yönetici talep edilemez
    time.sleep(1.5)
    assert [g[1]["text"].count("tg.iki") for g in telegram] == [1]


def test_talep_yaniti_telegrami_beklemez(anonim, telegram):
    """Gönderim arka planda: yanıt süresi Telegram'a bağlı değildir."""
    bas = time.monotonic()
    assert talep(anonim, "tg.hizli").status_code == 201
    assert time.monotonic() - bas < 3
    assert len(bekle(telegram, 1)) == 1


def test_telegram_ayarsizken_de_talep_kaydedilir(anonim):
    """Sunucu Telegram ayarı olmadan da çalışır (canlı demo ve diğer testler bu durumda)."""
    r = talep(anonim, "tg.ayarsiz")
    assert r.status_code == 201 and "Talebiniz alındı" in r.json()["mesaj"]
