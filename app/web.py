"""Web yönetim paneli ve API dokümantasyon sayfaları.

Panel (Jinja2 + Alpine.js + Tailwind) tüm verisini /api/v1 üzerinden çeker. Swagger UI ve ReDoc da dahil
olmak üzere bütün dış kütüphaneler app/static/vendor/ altındadır; uygulama internetsiz çalışır.
"""
from pathlib import Path

from fastapi import APIRouter, FastAPI, Request
from fastapi.openapi.docs import get_redoc_html, get_swagger_ui_html
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

BASE_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=BASE_DIR / "templates")
router = APIRouter(include_in_schema=False)

VENDOR = "/static/vendor"
LOGO = "/static/img/logo.svg"


@router.get("/", response_class=HTMLResponse)
def panel(request: Request):
    return templates.TemplateResponse(request, "index.html", {"surum": request.app.version})


@router.get("/sw.js")
def service_worker(request: Request):
    """Kök kapsamlı service worker (önbellek adı sürüme bağlı; yeni sürümde eskisi silinir)."""
    return templates.TemplateResponse(request, "sw.js", {"surum": request.app.version},
                                      media_type="application/javascript", headers={"Cache-Control": "no-cache"})


@router.get("/docs", response_class=HTMLResponse)
def swagger(request: Request):
    return get_swagger_ui_html(
        openapi_url=request.app.openapi_url, title=f"{request.app.title} — Swagger",
        swagger_js_url=f"{VENDOR}/swagger-ui-5.17.14/swagger-ui-bundle.js",
        swagger_css_url=f"{VENDOR}/swagger-ui-5.17.14/swagger-ui.css",
        swagger_favicon_url=LOGO,
    )


@router.get("/redoc", response_class=HTMLResponse)
def redoc(request: Request):
    return get_redoc_html(
        openapi_url=request.app.openapi_url, title=f"{request.app.title} — ReDoc",
        redoc_js_url=f"{VENDOR}/redoc-2.1.5.standalone.js", redoc_favicon_url=LOGO, with_google_fonts=False,
    )


def mount_web(app: FastAPI) -> None:
    """FastAPI(docs_url=None, redoc_url=None) ile oluşturulmuş uygulamaya panel ve yerel doküman sayfalarını ekler."""
    app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")
    app.include_router(router)
