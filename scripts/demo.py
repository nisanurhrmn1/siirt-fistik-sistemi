"""Tek komutla sunum: demo.db'yi sıfırdan kurar, örnek veriyi yükler, sunucuyu başlatır ve paneli açar.

    .\\.venv\\Scripts\\python.exe -m scripts.demo                 # her çalıştırmada temiz demo
    .\\.venv\\Scripts\\python.exe -m scripts.demo --koru          # önceki demo verisini koru
    .\\.venv\\Scripts\\python.exe -m scripts.demo --port 8080 --tarayici-acma

Asıl çalışma veritabanına (siirt_fistik.db) dokunmaz; ayrı bir demo.db kullanır.
"""
import argparse
import os
import threading
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEMO_DB = ROOT / "demo.db"


def main() -> None:
    parser = argparse.ArgumentParser(description="Siirt Fıstığı demo sunucusu")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--koru", action="store_true", help="Mevcut demo.db'yi silme")
    parser.add_argument("--tarayici-acma", action="store_true", help="Paneli tarayıcıda otomatik açma")
    args = parser.parse_args()

    # app.db.session engine'i import anında kurar; ortam değişkeni ondan ÖNCE ayarlanmalı.
    os.environ["DATABASE_URL"] = f"sqlite:///{DEMO_DB.as_posix()}"
    if not args.koru and DEMO_DB.exists():
        DEMO_DB.unlink()

    from scripts import demo_verisi
    demo_verisi.main()

    import uvicorn

    url = f"http://127.0.0.1:{args.port}/"
    print(f"\n  Panel   : {url}\n  Swagger : {url}docs\n  Durdurmak için Ctrl+C\n")
    if not args.tarayici_acma:
        threading.Timer(1.5, webbrowser.open, [url]).start()
    uvicorn.run("app.main:app", host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
