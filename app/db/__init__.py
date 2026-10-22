from .base import Base
from . import models  # noqa: F401  (tüm tabloları Base.metadata'ya kaydeder)

__all__ = ["Base", "models"]
