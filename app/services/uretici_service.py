from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, selectinload

from app.core.errors import ConflictError, NotFoundError
from app.db.models import Producer, ProducerBankAccount
from app.schemas.uretici import UreticiGirisi


def get_producer(db: Session, producer_id: int) -> Producer:
    producer = db.get(Producer, producer_id, options=[selectinload(Producer.bank_accounts)])
    if producer is None:
        raise NotFoundError(f"Üretici bulunamadı (id={producer_id}).")
    return producer


def create_producer(db: Session, data: UreticiGirisi) -> Producer:
    if data.tc_kimlik_no:
        existing = db.scalar(select(Producer.id).where(Producer.national_id == data.tc_kimlik_no))
        if existing:
            raise ConflictError(f"Bu TC Kimlik No ile kayıtlı bir üretici zaten var (id={existing}).")
    if data.vergi_no:
        existing = db.scalar(select(Producer.id).where(Producer.tax_number == data.vergi_no))
        if existing:
            raise ConflictError(f"Bu Vergi No ile kayıtlı bir üretici zaten var (id={existing}).")

    producer = Producer(
        producer_type=data.tip, national_id=data.tc_kimlik_no, tax_number=data.vergi_no,
        tax_office=data.vergi_dairesi, full_name=data.ad_soyad, phone=data.telefon, email=data.eposta,
        address=data.adres, district=data.ilce, city=data.il, notes=data.notlar,
    )
    db.add(producer)
    db.flush()
    if data.banka_hesabi:
        b = data.banka_hesabi
        db.add(ProducerBankAccount(
            producer_id=producer.id, bank_name=b.banka_adi, iban=b.iban,
            account_holder=b.hesap_sahibi or data.ad_soyad, is_default=True,
        ))
    db.commit()
    db.refresh(producer)
    return producer


def list_producers(db: Session, *, arama: str | None, aktif: bool | None, sayfa: int,
                   sayfa_boyutu: int) -> tuple[int, list[Producer]]:
    stmt = select(Producer)
    if arama:
        like = f"%{arama}%"
        stmt = stmt.where(or_(Producer.full_name.ilike(like), Producer.national_id.like(like),
                              Producer.tax_number.like(like), Producer.phone.like(like)))
    if aktif is not None:
        stmt = stmt.where(Producer.is_active.is_(aktif))
    total = db.scalar(select(func.count()).select_from(stmt.subquery()))
    items = db.scalars(
        stmt.options(selectinload(Producer.bank_accounts))
        .order_by(Producer.full_name, Producer.id)
        .offset((sayfa - 1) * sayfa_boyutu)
        .limit(sayfa_boyutu)
    ).all()
    return total, list(items)
