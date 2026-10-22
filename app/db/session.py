from pathlib import Path

from sqlalchemy import Engine, create_engine, event, text
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.config import settings
from app.db.base import Base
from app.db import models  # noqa: F401  (tabloları metadata'ya kaydeder)

PG_EXTRAS_SQL = Path(__file__).resolve().parents[2] / "db" / "postgres_triggers_views.sql"


def create_db_engine(url: str, echo: bool = False) -> Engine:
    if url.startswith("sqlite"):
        kwargs: dict = {"connect_args": {"check_same_thread": False}}
        if ":memory:" in url or url.rstrip("/").endswith(("sqlite:", "pysqlite:")):
            kwargs["poolclass"] = StaticPool  # bellek içi DB tüm bağlantılarda aynı kalsın
        engine = create_engine(url, echo=echo, **kwargs)

        @event.listens_for(engine, "connect")
        def _enable_foreign_keys(dbapi_conn, _record):
            dbapi_conn.execute("PRAGMA foreign_keys=ON")

        return engine
    return create_engine(url, echo=echo, pool_pre_ping=True)


def make_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False)


def init_db(engine: Engine) -> None:
    """Geliştirme ortamı için tabloları oluşturur. (Üretimde Alembic migration'ları kullanılmalı.)"""
    if engine.dialect.name == "postgresql":
        with engine.begin() as conn:
            conn.execute(text("CREATE EXTENSION IF NOT EXISTS btree_gist"))
    Base.metadata.create_all(engine)
    if engine.dialect.name == "postgresql":
        with engine.begin() as conn:
            # Parametresiz, doğrudan sürücüye: dosyadaki RAISE '... %' ifadeleri yer tutucu sanılmasın.
            conn.connection.driver_connection.execute(PG_EXTRAS_SQL.read_text(encoding="utf-8"))


engine = create_db_engine(settings.database_url, echo=settings.sql_echo)
SessionLocal = make_session_factory(engine)
