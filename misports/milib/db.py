"""SQLite 连接和同步状态维护。"""

from __future__ import annotations

import sqlite3
from pathlib import Path


def connect(db_path: Path, schema_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(schema_path.read_text(encoding="utf-8"))
    _migrate_activities(conn)
    conn.commit()
    return conn


_ACTIVITY_COLUMNS = {
    "valid_duration_s": "INTEGER",
    "stroke_count": "INTEGER",
    "avg_stroke_frequency": "REAL",
    "max_stroke_freq": "INTEGER",
    "avg_swolf": "INTEGER",
    "best_swolf": "INTEGER",
    "turn_count": "INTEGER",
    "pool_width": "REAL",
    "main_posture": "INTEGER",
    "total_calories_kcal": "REAL",
    "avg_pace_s_per_100m": "REAL",
    "max_pace_s_per_100m": "REAL",
    "min_pace_s_per_100m": "REAL",
    "extra_json": "TEXT",
    "fds_status": "TEXT",
    "fds_collected": "INTEGER NOT NULL DEFAULT 0",
    "fds_collected_at": "INTEGER",
}


def _migrate_activities(conn: sqlite3.Connection) -> None:
    existing = {row["name"] for row in conn.execute("PRAGMA table_info(activities)")}
    for name, sql_type in _ACTIVITY_COLUMNS.items():
        if name not in existing:
            conn.execute(f"ALTER TABLE activities ADD COLUMN {name} {sql_type}")


def update_sync_state(
    conn: sqlite3.Connection,
    dataset: str,
    data_key: str,
    timestamps: list[int],
) -> None:
    if not timestamps:
        return
    start = min(timestamps)
    end = max(timestamps)
    conn.execute(
        """
        INSERT INTO sync_state(dataset, data_key, last_synced_at, coverage_start, coverage_end)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(dataset, data_key) DO UPDATE SET
          last_synced_at = MAX(COALESCE(sync_state.last_synced_at, 0), excluded.last_synced_at),
          coverage_start = MIN(COALESCE(sync_state.coverage_start, excluded.coverage_start), excluded.coverage_start),
          coverage_end = MAX(COALESCE(sync_state.coverage_end, 0), excluded.coverage_end)
        """,
        (dataset, data_key, end, start, end),
    )
    conn.commit()


def get_last_synced(conn: sqlite3.Connection, dataset: str, data_key: str) -> int | None:
    row = conn.execute(
        "SELECT last_synced_at FROM sync_state WHERE dataset=? AND data_key=?",
        (dataset, data_key),
    ).fetchone()
    return int(row["last_synced_at"]) if row and row["last_synced_at"] is not None else None


def record_capture(
    conn: sqlite3.Connection,
    captured_at: str,
    dataset: str,
    path: str,
    record_count: int,
    sha256: str | None = None,
) -> None:
    conn.execute(
        """
        INSERT INTO raw_captures(captured_at, dataset, path, record_count, sha256)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(dataset, path) DO UPDATE SET
          captured_at=excluded.captured_at,
          record_count=excluded.record_count,
          sha256=excluded.sha256
        """,
        (captured_at, dataset, path, record_count, sha256),
    )


def table_counts(conn: sqlite3.Connection) -> list[tuple[str, int]]:
    tables = [
        "step_minutes",
        "calorie_minutes",
        "heart_rate_samples",
        "resting_heart_rate",
        "sleep_sessions",
        "sleep_stages",
        "body_measurements",
        "activities",
        "activity_samples",
        "activity_track_points",
        "raw_captures",
    ]
    result = []
    for table in tables:
        count = conn.execute(f"SELECT COUNT(*) AS n FROM {table}").fetchone()["n"]
        result.append((table, int(count)))
    return result


def date_ranges(conn: sqlite3.Connection) -> list[tuple[str, str | None, str | None]]:
    queries = [
        ("step_minutes", "SELECT MIN(date_local), MAX(date_local) FROM step_minutes"),
        ("heart_rate_samples", "SELECT MIN(date_local), MAX(date_local) FROM heart_rate_samples"),
        ("sleep_sessions", "SELECT MIN(date_local), MAX(date_local) FROM sleep_sessions"),
        ("body_measurements", "SELECT MIN(date_local), MAX(date_local) FROM body_measurements"),
        ("activities", "SELECT MIN(date_local), MAX(date_local) FROM activities"),
    ]
    result = []
    for name, query in queries:
        row = conn.execute(query).fetchone()
        result.append((name, row[0], row[1]))
    return result
