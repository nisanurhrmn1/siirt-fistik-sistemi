"""ORM modellerinden PostgreSQL DDL'i üretir → db/schema.sql  (Çalıştır: python -m scripts.export_ddl)"""
from pathlib import Path

from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateIndex, CreateTable

from app.db import Base

HEADER = """-- Otomatik üretildi: python -m scripts.export_ddl  (kaynak: app/db/models.py)
-- Sonra çalıştırın: db/postgres_triggers_views.sql
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- daily_prices EXCLUDE kısıtı için
"""


def main() -> None:
    dialect = postgresql.dialect()
    parts = [HEADER]
    for table in Base.metadata.sorted_tables:
        parts.append(str(CreateTable(table).compile(dialect=dialect)).strip() + ";\n")
        for index in sorted(table.indexes, key=lambda i: i.name):
            parts.append(str(CreateIndex(index).compile(dialect=dialect)).strip() + ";\n")
    out = Path(__file__).resolve().parents[1] / "db" / "schema.sql"
    out.write_text("\n".join(parts), encoding="utf-8")
    print(f"{out} yazıldı ({len(Base.metadata.sorted_tables)} tablo)")


if __name__ == "__main__":
    main()
