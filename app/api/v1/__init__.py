from fastapi import APIRouter

from app.api.v1 import depo_rapor, finans, fiyatlar, tartimlar, ureticiler

api_router = APIRouter()
api_router.include_router(ureticiler.router)
api_router.include_router(tartimlar.router)
api_router.include_router(fiyatlar.router)
api_router.include_router(finans.router)
api_router.include_router(depo_rapor.depo_router)
api_router.include_router(depo_rapor.rapor_router)
