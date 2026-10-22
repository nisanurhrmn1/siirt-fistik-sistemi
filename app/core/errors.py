"""Uygulama hataları → HTTP durum kodları (eşleme: app/api/errors.py)."""
from app.domain.rules import BusinessRuleError  # 400

__all__ = ["BusinessRuleError", "NotFoundError", "ConflictError", "AuthError"]


class NotFoundError(Exception):
    """404 — istenen kayıt yok."""


class ConflictError(Exception):
    """409 — tekrarlı kayıt (ör. aynı TC ile ikinci üretici)."""


class AuthError(Exception):
    """401 — işlemi yapan kullanıcı belirlenemedi."""
