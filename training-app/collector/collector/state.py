from __future__ import annotations

import hashlib
import json
import sqlite3
from pathlib import Path
from typing import Any


class CollectorState:
    """Private durable inbox/outbox; source and server watermarks are separate."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # APScheduler jobs run on worker threads; CollectorService serializes all state access.
        self.db = sqlite3.connect(path, timeout=30, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS dataset_progress (
              dataset TEXT PRIMARY KEY,
              source_watermark INTEGER NOT NULL DEFAULT 0,
              submitted_watermark INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS source_records (
              dataset TEXT NOT NULL,
              source_id TEXT NOT NULL,
              local_date TEXT NOT NULL,
              measured_at INTEGER NOT NULL,
              fingerprint TEXT NOT NULL,
              PRIMARY KEY(dataset, source_id)
            );
            CREATE TABLE IF NOT EXISTS pending_records (
              dataset TEXT NOT NULL,
              source_id TEXT NOT NULL,
              local_date TEXT NOT NULL,
              measured_at INTEGER NOT NULL,
              payload TEXT NOT NULL,
              PRIMARY KEY(dataset, source_id)
            );
            CREATE INDEX IF NOT EXISTS pending_dataset_order
              ON pending_records(dataset, measured_at, source_id);
            """
        )
        self.db.commit()
        try:
            self.path.chmod(0o600)
        except OSError:
            pass


    def watermark(self, dataset: str, column: str) -> int:
        if column not in {"source_watermark", "submitted_watermark"}:
            raise ValueError("unsupported watermark")
        row = self.db.execute(
            f"SELECT {column} FROM dataset_progress WHERE dataset=?", (dataset,)
        ).fetchone()
        return int(row[column]) if row else 0

    def set_source_watermark(self, dataset: str, value: int) -> None:
        self.db.execute(
            "INSERT INTO dataset_progress(dataset,source_watermark) VALUES(?,?) "
            "ON CONFLICT(dataset) DO UPDATE SET "
            "source_watermark=MAX(dataset_progress.source_watermark,excluded.source_watermark)",
            (dataset, value),
        )
        self.db.commit()
    def source_record_changed(
        self, dataset: str, source_id: str, local_date: str,
        measured_at: int, payload: dict[str, Any], force: bool = False
    ) -> bool:
        fingerprint = hashlib.sha256(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        ).hexdigest()
        previous = self.db.execute(
            "SELECT local_date,measured_at,fingerprint FROM source_records "
            "WHERE dataset=? AND source_id=?",
            (dataset, source_id),
        ).fetchone()
        if not force and previous and (
            previous["local_date"] == local_date
            and int(previous["measured_at"]) == measured_at
            and previous["fingerprint"] == fingerprint
        ):
            return False
        self.db.execute(
            "INSERT INTO source_records(dataset,source_id,local_date,measured_at,fingerprint) "
            "VALUES(?,?,?,?,?) ON CONFLICT(dataset,source_id) DO UPDATE SET "
            "local_date=excluded.local_date,measured_at=excluded.measured_at,fingerprint=excluded.fingerprint",
            (dataset, source_id, local_date, measured_at, fingerprint),
        )
        self.enqueue(dataset, source_id, local_date, measured_at, payload)
        return True


    def enqueue(self, dataset: str, source_id: str, local_date: str, measured_at: int,
                payload: dict[str, Any]) -> None:
        self.db.execute(
            "INSERT INTO pending_records(dataset,source_id,local_date,measured_at,payload) "
            "VALUES(?,?,?,?,?) ON CONFLICT(dataset,source_id) DO UPDATE SET "
            "local_date=excluded.local_date, measured_at=excluded.measured_at, payload=excluded.payload",
            (dataset, source_id, local_date, measured_at,
             json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False)),
        )

    def commit_enqueue(self) -> None:
        self.db.commit()

    def next_batch(self, dataset: str, limit: int) -> list[sqlite3.Row]:
        return self.db.execute(
            "SELECT source_id,local_date,measured_at,payload FROM pending_records "
            "WHERE dataset=? ORDER BY measured_at,source_id LIMIT ?",
            (dataset, limit),
        ).fetchall()

    def pending_count(self, dataset: str) -> int:
        row = self.db.execute(
            "SELECT COUNT(*) AS n FROM pending_records WHERE dataset=?", (dataset,)
        ).fetchone()
        return int(row["n"])

    def acknowledge(self, dataset: str, rows: list[sqlite3.Row]) -> None:
        if not rows:
            return
        ids = [str(row["source_id"]) for row in rows]
        placeholders = ",".join("?" for _ in ids)
        max_time = max(int(row["measured_at"]) for row in rows)
        with self.db:
            self.db.execute(
                f"DELETE FROM pending_records WHERE dataset=? AND source_id IN ({placeholders})",
                [dataset, *ids],
            )
            self.db.execute(
                "INSERT INTO dataset_progress(dataset,submitted_watermark) VALUES(?,?) "
                "ON CONFLICT(dataset) DO UPDATE SET "
                "submitted_watermark=MAX(dataset_progress.submitted_watermark,excluded.submitted_watermark)",
                (dataset, max_time),
            )

    def close(self) -> None:
        self.db.close()
