from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends, Header
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.errors import AuthError
from app.db import session as db_session
from app.db.enums import UserRole
from app.db.models import User


def get_db() -> Iterator[Session]:
    """İstek başına bir oturum. Yazma endpoint'leri servis içinde açıkça commit eder;
    hata olursa commit edilmemiş her şey close() ile geri alınır."""
    db = db_session.SessionLocal()
    try:
        yield db
    finally:
        db.close()


DbSession = Annotated[Session, Depends(get_db)]


def get_current_user(
    db: DbSession,
    x_kullanici_id: Annotated[int | None, Header(alias="X-Kullanici-Id",
                                                 description="İşlemi yapan kullanıcı. Boşsa ilk aktif yönetici.")] = None,
) -> User:
    # TODO: Kimlik doğrulama (JWT/OAuth2) eklendiğinde bu başlık yerine token'dan okunacak.
    if x_kullanici_id is not None:
        user = db.get(User, x_kullanici_id)
        if user is None or not user.is_active:
            raise AuthError(f"Kullanıcı bulunamadı veya pasif (id={x_kullanici_id}).")
        return user
    user = db.scalar(select(User).where(User.is_active.is_(True), User.role == UserRole.ADMIN).order_by(User.id))
    if user is None:
        raise AuthError("Sistemde aktif kullanıcı yok; X-Kullanici-Id başlığı gönderin.")
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]
