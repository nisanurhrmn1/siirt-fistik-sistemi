"""Hata → HTTP yanıtı eşlemesi. Tüm hata gövdeleri {"hata": "..."} biçimindedir;
doğrulama hatalarında ek olarak alan bazlı Türkçe "detaylar" listesi döner."""
from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError

from app.core.errors import AuthError, BusinessRuleError, ConflictError, NotFoundError

_MESAJLAR = {
    "missing": "Bu alan zorunludur.",
    "greater_than": "{gt} değerinden büyük olmalıdır.",
    "greater_than_equal": "{ge} veya daha büyük olmalıdır.",
    "less_than": "{lt} değerinden küçük olmalıdır.",
    "less_than_equal": "En fazla {le} olabilir.",
    "decimal_parsing": "Geçerli bir sayı olmalıdır.",
    "decimal_type": "Geçerli bir sayı olmalıdır.",
    "float_parsing": "Geçerli bir sayı olmalıdır.",
    "int_parsing": "Tam sayı olmalıdır.",
    "int_type": "Tam sayı olmalıdır.",
    "decimal_max_places": "En fazla {decimal_places} ondalık basamak girilebilir.",
    "decimal_max_digits": "En fazla {max_digits} basamak girilebilir.",
    "decimal_whole_digits": "Tam kısım en fazla {whole_digits} basamak olabilir.",
    "string_type": "Metin olmalıdır.",
    "string_too_short": "En az {min_length} karakter olmalıdır.",
    "string_too_long": "En fazla {max_length} karakter olabilir.",
    "string_pattern_mismatch": "Biçim geçersiz.",
    "too_short": "En az {min_length} öğe içermelidir.",
    "enum": "Şu değerlerden biri olmalıdır: {expected}.",
    "literal_error": "Şu değerlerden biri olmalıdır: {expected}.",
    "bool_parsing": "true veya false olmalıdır.",
    "date_parsing": "Geçerli bir tarih olmalıdır (YYYY-AA-GG).",
    "date_from_datetime_parsing": "Geçerli bir tarih olmalıdır (YYYY-AA-GG).",
    "datetime_parsing": "Geçerli bir tarih-saat olmalıdır (ISO 8601, ör. 2026-10-22T09:00:00+03:00).",
    "datetime_from_date_parsing": "Geçerli bir tarih-saat olmalıdır (ISO 8601).",
    "extra_forbidden": "Tanımsız alan; adını kontrol edin.",
    "model_attributes_type": "JSON nesnesi olmalıdır.",
    "dict_type": "JSON nesnesi olmalıdır.",
    "list_type": "Liste olmalıdır.",
    "json_invalid": "Geçersiz JSON.",
}


def _cevir(err: dict) -> dict:
    loc = list(err.get("loc", ()))
    if loc and loc[0] in ("body", "query", "path", "header"):
        loc = loc[1:]
    alan = ".".join(str(p) for p in loc) or "istek"
    tip, ctx = err.get("type", ""), err.get("ctx") or {}
    if tip == "value_error":
        mesaj = str(ctx.get("error", err.get("msg", ""))).removeprefix("Value error, ")
    elif tip in _MESAJLAR:
        try:
            mesaj = _MESAJLAR[tip].format(**ctx)
        except (KeyError, IndexError):
            mesaj = err.get("msg", "Geçersiz değer.")
    else:
        mesaj = err.get("msg", "Geçersiz değer.")
    return {"alan": alan, "mesaj": mesaj}


def _hata(code: int, mesaj: str) -> JSONResponse:
    return JSONResponse(status_code=code, content={"hata": mesaj})


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def _dogrulama(_: Request, exc: RequestValidationError):
        return JSONResponse(
            status_code=422,
            content={"hata": "Gönderilen veriler geçersiz.", "detaylar": [_cevir(e) for e in exc.errors()]},
        )

    @app.exception_handler(BusinessRuleError)
    async def _is_kurali(_: Request, exc: BusinessRuleError):
        return _hata(status.HTTP_400_BAD_REQUEST, str(exc))

    @app.exception_handler(NotFoundError)
    async def _bulunamadi(_: Request, exc: NotFoundError):
        return _hata(status.HTTP_404_NOT_FOUND, str(exc))

    @app.exception_handler(ConflictError)
    async def _cakisma(_: Request, exc: ConflictError):
        return _hata(status.HTTP_409_CONFLICT, str(exc))

    @app.exception_handler(AuthError)
    async def _yetki(_: Request, exc: AuthError):
        return _hata(status.HTTP_401_UNAUTHORIZED, str(exc))

    @app.exception_handler(IntegrityError)
    async def _butunluk(_: Request, exc: IntegrityError):
        return _hata(status.HTTP_409_CONFLICT,
                     "Kayıt veritabanı kısıtlarını ihlal ediyor (tekrarlı veya tutarsız veri).")
