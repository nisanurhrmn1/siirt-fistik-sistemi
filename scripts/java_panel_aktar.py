"""Web panelini Java (Spring Boot) projesine aktarır.

Jinja2 şablonunu (app/templates) düz bir index.html'e çevirir ve app/static klasörünü
java-backend/src/main/resources/static/static altına kopyalar. Panelde değişiklik yaptıktan sonra çalıştırın:

    .\\.venv\\Scripts\\python.exe -m scripts.java_panel_aktar
"""
import shutil
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

ROOT = Path(__file__).resolve().parents[1]
HEDEF = ROOT / "java-backend" / "src" / "main" / "resources" / "static"
SURUM = "2.6.0"

REDOC = """<!doctype html>
<html lang="tr">
<head>
  <meta charset="utf-8">
  <title>Siirt Fıstığı Alım ve Üretici Ödeme Sistemi — ReDoc</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="icon" href="/static/img/logo.svg" type="image/svg+xml">
  <style>body { margin: 0; padding: 0; }</style>
</head>
<body>
  <redoc spec-url="/openapi.json"></redoc>
  <script src="/static/vendor/redoc-2.1.5.standalone.js"></script>
</body>
</html>
"""


def main() -> None:
    env = Environment(loader=FileSystemLoader(ROOT / "app" / "templates"), autoescape=True)
    env.globals["url_for"] = lambda _ad, path: f"/static/{path}"
    html = env.get_template("index.html").render(surum=SURUM)

    HEDEF.mkdir(parents=True, exist_ok=True)
    (HEDEF / "index.html").write_text(html, encoding="utf-8")
    (HEDEF / "redoc.html").write_text(REDOC, encoding="utf-8")
    shutil.rmtree(HEDEF / "static", ignore_errors=True)
    shutil.copytree(ROOT / "app" / "static", HEDEF / "static")
    print(f"Panel aktarıldı → {HEDEF.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
