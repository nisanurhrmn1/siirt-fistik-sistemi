"""PostgreSQL kurulumu: uygulama kullanıcısını (fistik) ve veritabanlarını oluşturur, .env dosyasını yazar.

    .\\.venv\\Scripts\\python.exe -m scripts.postgres_kurulum

- PostgreSQL yönetici (postgres) şifresini psql SİZE sorar; şifre hiçbir yere kaydedilmez.
- Uygulama kullanıcısının şifresi rastgele üretilir ve yalnızca .env dosyasına yazılır (.env Git'e yüklenmez).
- Tekrar çalıştırmak güvenlidir: var olan kullanıcı/veritabanı silinmez, şifre .env ile eşitlenir.
"""
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env"
KULLANICI = "fistik"
VERITABANLARI = ("siirt_fistik", "siirt_fistik_test")
HOST, PORT = "localhost", 5432

SQL = f"""
SELECT format('CREATE ROLE {KULLANICI} LOGIN PASSWORD %L', :'sifre')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{KULLANICI}') \\gexec
ALTER ROLE {KULLANICI} WITH LOGIN PASSWORD :'sifre';
""" + "".join(
    f"""
SELECT 'CREATE DATABASE {db} OWNER {KULLANICI} ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '{db}') \\gexec
"""
    for db in VERITABANLARI
)


def psql_bul() -> str:
    yol = shutil.which("psql")
    if yol:
        return yol
    adaylar = sorted(Path(r"C:\Program Files\PostgreSQL").glob("*/bin/psql.exe"),
                     key=lambda p: int(p.parts[-3]) if p.parts[-3].isdigit() else 0, reverse=True)
    if not adaylar:
        sys.exit("psql bulunamadı. PostgreSQL kurulu mu? (https://www.postgresql.org/download/windows/)")
    return str(adaylar[0])


def env_oku() -> dict[str, str]:
    if not ENV_FILE.is_file():
        return {}
    satirlar = (s.split("=", 1) for s in ENV_FILE.read_text(encoding="utf-8").splitlines()
                if "=" in s and not s.lstrip().startswith("#"))
    return {k.strip(): v.strip() for k, v in satirlar}


def env_yaz(degerler: dict[str, str]) -> None:
    """Var olan .env satırlarını korur, yalnızca verilen anahtarları ekler/günceller."""
    satirlar = ENV_FILE.read_text(encoding="utf-8").splitlines() if ENV_FILE.is_file() else []
    kalan = dict(degerler)
    for i, s in enumerate(satirlar):
        anahtar = s.split("=", 1)[0].strip()
        if anahtar in kalan and not s.lstrip().startswith("#"):
            satirlar[i] = f"{anahtar}={kalan.pop(anahtar)}"
    satirlar += [f"{k}={v}" for k, v in kalan.items()]
    ENV_FILE.write_text("\n".join(satirlar) + "\n", encoding="utf-8")


def main() -> None:
    psql = psql_bul()
    mevcut = re.search(rf"//{KULLANICI}:([^@]+)@", env_oku().get("DATABASE_URL", ""))
    sifre = mevcut.group(1) if mevcut else secrets.token_urlsafe(18)

    print(f"psql: {psql}")
    print("PostgreSQL yönetici (postgres) şifresi sorulacak — kurulumda belirlediğiniz şifre.\n")
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as f:
        f.write(SQL)
        sql_dosyasi = f.name
    try:
        sonuc = subprocess.run([
            psql, "-h", HOST, "-p", str(PORT), "-U", "postgres", "-d", "postgres",
            "-v", "ON_ERROR_STOP=1", "-v", f"sifre={sifre}", "-q", "-f", sql_dosyasi,
        ])
    finally:
        Path(sql_dosyasi).unlink(missing_ok=True)
    if sonuc.returncode != 0:
        sys.exit("\nKurulum başarısız. Şifre yanlış olabilir; tekrar deneyin.")

    url = f"postgresql+psycopg://{KULLANICI}:{sifre}@{HOST}:{PORT}"
    env_yaz({"DATABASE_URL": f"{url}/{VERITABANLARI[0]}", "TEST_DATABASE_URL": f"{url}/{VERITABANLARI[1]}"})
    print(f"\n✓ '{KULLANICI}' kullanıcısı ve {', '.join(VERITABANLARI)} veritabanları hazır.")
    print(f"✓ Bağlantı bilgileri {ENV_FILE.name} dosyasına yazıldı (Git'e yüklenmez).")
    print("  Uygulama artık PostgreSQL ile çalışır: .\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --reload")


if __name__ == "__main__":
    main()
