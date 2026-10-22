"""Canlı sunum için örnek veri: üreticiler, günlük fiyatlar, avans, teslimatlar ve bir ödeme.
DATABASE_URL'deki veritabanına (varsayılan siirt_fistik.db) yazar. Boş bir veritabanında çalıştırın.

    .\\.venv\\Scripts\\python.exe -m scripts.demo_verisi
"""
from decimal import Decimal as D

from sqlalchemy import select

from app.db.enums import PaymentMethod
from app.db.models import Producer, User
from app.db.seed import seed_reference_data
from app.db.session import SessionLocal, engine, init_db
from app.schemas.fiyat import FiyatGirisi
from app.schemas.finans import AvansGirisi, OdemeGirisi
from app.schemas.tartim import TartimGirisi
from app.schemas.uretici import UreticiGirisi
from app.services import finans_service, fiyat_service, tartim_service, uretici_service

URETICILER = [
    # TC'ler algoritmik olarak geçerli, gerçek kişilere ait olmayan test numaralarıdır.
    {"tc_kimlik_no": "10000000146", "ad_soyad": "Ahmet Yılmaz", "telefon": "0532 111 22 33", "ilce": "Kurtalan",
     "banka_hesabi": {"banka_adi": "Ziraat Bankası", "iban": "TR330006100519786457841326"}},
    {"tc_kimlik_no": "10000000078", "ad_soyad": "Fatma Demir", "telefon": "0533 444 55 66", "ilce": "Merkez"},
    {"tc_kimlik_no": "10000000214", "ad_soyad": "Mehmet Kaya", "telefon": "0544 777 88 99", "ilce": "Pervari"},
    {"tip": "COMPANY", "vergi_no": "1234567890", "vergi_dairesi": "Siirt", "ad_soyad": "Botan Tarım Ltd. Şti.",
     "telefon": "0484 223 45 67", "ilce": "Merkez"},
]

TARTIMLAR = [  # (üretici sırası, plaka, brüt, dara, çatlak, randıman, boş, çürük, nem)
    (0, "56 ABC 123", 4800, 3800, 91, 58, 2, 1, 6),
    (1, "56 DE 456", 5350, 3900, 88, 56, 3, 1, 7),
    (2, "56 FG 789", 4200, 3600, 76, 50, 6, 2, 9),
    (0, "56 ABC 123", 4550, 3800, 90, 57, 2, 1, 6),
    (3, "56 BT 001", 9800, 7200, 86, 55, 4, 1, 7.5),
    (2, "56 FG 789", 3900, 3600, 60, 40, 15, 5, 12),
]


def main() -> None:
    init_db(engine)
    with SessionLocal() as db:
        seed_reference_data(db)
        if db.scalar(select(Producer.id).limit(1)) is not None:
            print("Veritabanında zaten üretici var; demo verisi eklenmedi. (Boş veritabanı için siirt_fistik.db'yi silin.)")
            return
        user_id = db.scalar(select(User.id).order_by(User.id))

        ureticiler = [uretici_service.create_producer(db, UreticiGirisi(**u)) for u in URETICILER]
        fiyat_service.enter_prices(db, FiyatGirisi(fiyatlar=[
            {"kalite_kodu": "A", "birim_fiyat": "300"}, {"kalite_kodu": "B", "birim_fiyat": "260"},
            {"kalite_kodu": "ISK", "birim_fiyat": "150"}]), user_id)
        finans_service.give_advance(db, AvansGirisi(uretici_id=ureticiler[2].id, tutar=D("250000"),
                                                    odeme_yontemi=PaymentMethod.CASH, aciklama="Gübre avansı"), user_id)

        tickets = []
        for i, plaka, brut, dara, catlak, randiman, bos, curuk, nem in TARTIMLAR:
            tickets.append(tartim_service.register_delivery(db, TartimGirisi(
                uretici_id=ureticiler[i].id, plaka=plaka, brut_kg=brut, dara_kg=dara,
                kalite={"catlak_orani": catlak, "randiman": randiman, "bos_orani": bos,
                        "curuk_orani": curuk, "nem_orani": nem}), user_id))

        finans_service.pay_settlement(db, tickets[0].settlement.id,
                                      OdemeGirisi(tutar=D("150000"), referans_no="EFT-DEMO-1"), user_id)
        print(f"{len(ureticiler)} üretici, {len(tickets)} teslimat, 1 avans ve 1 ödeme eklendi.")


if __name__ == "__main__":
    main()
