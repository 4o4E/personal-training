from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    state_dir: Path
    misports_dir: Path
    seed_db: Path
    training_url: str
    training_token: str
    interval_hours: int = 1

    @classmethod
    def from_env(cls) -> "Settings":
        state_dir = Path(os.environ.get("COLLECTOR_STATE_DIR", "/data/collector")).resolve()
        misports_dir = Path(os.environ.get("MISPORTS_SOURCE_DIR", "/app/misports")).resolve()
        seed_db = Path(os.environ.get("COLLECTOR_SEED_DB", "/data/misports.db")).resolve()
        training_url = os.environ.get("TRAINING_URL", "").rstrip("/")
        token_env = os.environ.get("TRAINING_TOKEN", "").strip()
        token_file = os.environ.get("TRAINING_TOKEN_FILE")
        training_token = token_env or (
            Path(token_file).read_text(encoding="utf-8").strip() if token_file else ""
        )
        try:
            interval_hours = int(os.environ.get("COLLECTOR_INTERVAL_HOURS", "1"))
        except ValueError as exc:
            raise ValueError("COLLECTOR_INTERVAL_HOURS must be a positive integer") from exc
        if interval_hours < 1:
            raise ValueError("COLLECTOR_INTERVAL_HOURS must be a positive integer")
        if not training_url or not training_token:
            raise ValueError("TRAINING_URL and TRAINING_TOKEN are required")
        if not (misports_dir / "milib" / "sync.py").is_file():
            raise ValueError(f"misports source package is missing under {misports_dir}")
        return cls(state_dir, misports_dir, seed_db, training_url, training_token, interval_hours)
