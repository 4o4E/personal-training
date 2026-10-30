"""手动刷新：从云端拉取数据、保存原文、写入 SQLite。"""

from __future__ import annotations

import json
import sqlite3
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Callable

from .client import CN_TZ, MiClient
from .db import get_last_synced, record_capture, update_sync_state
from .fds import (
    FDS_FILE_TYPE_GPS_TRACK,
    FDS_FILE_TYPE_SPORT_RECORD,
    SUPPORTED_RECORD_PROTO_TYPES,
    FdsParseError,
    build_fds_suffix,
    fds_server_key,
)
from .importers import (
    import_fds,
    import_fitness_key,
    import_sport_records,
)

FITNESS_KEYS = [
    "steps",
    "calories",
    "heart_rate",
    "resting_heart_rate",
    "sleep",
    "weight",
]


def save_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def date_windows(start_date: str, end_date: str, max_days: int) -> list[tuple[str, str]]:
    start = datetime.fromisoformat(start_date).date()
    end = datetime.fromisoformat(end_date).date()
    windows: list[tuple[str, str]] = []
    while start <= end:
        window_end = min(end, start + timedelta(days=max_days - 1))
        windows.append((start.isoformat(), window_end.isoformat()))
        start = window_end + timedelta(days=1)
    return windows


def sync_fitness_key(
    conn: sqlite3.Connection,
    client: MiClient,
    root: Path,
    key: str,
    start_date: str,
    end_date: str,
    capture_dir: Path,
    progress: Callable[[str], None],
    window_label: str | None = None,
) -> int:
    out_dir = capture_dir / "fitness" / key
    if window_label:
        out_dir = out_dir / window_label
    out_dir.mkdir(parents=True, exist_ok=True)

    def on_page(page_no: int, body: dict) -> None:
        save_json(out_dir / f"page_{page_no:03d}.json", body)

    items = client.fetch_key(key, start_date, end_date, on_page=on_page)
    save_json(out_dir / "all.json", items)
    raw_ref = str(out_dir.relative_to(root))
    timestamps = import_fitness_key(conn, key, items, raw_ref)
    update_sync_state(conn, "fitness", key, timestamps)
    record_capture(conn, datetime.now(CN_TZ).isoformat(), "fitness", f"{raw_ref}/all.json", len(items))
    conn.commit()
    progress(f"  {key}: {len(items)} 条")
    return len(items)


def sync_sport_records(
    conn: sqlite3.Connection,
    client: MiClient,
    root: Path,
    start_date: str,
    end_date: str,
    capture_dir: Path,
    progress: Callable[[str], None],
    window_label: str | None = None,
) -> int:
    out_dir = capture_dir / "sport_records"
    if window_label:
        out_dir = out_dir / window_label
    out_dir.mkdir(parents=True, exist_ok=True)

    def on_page(page_no: int, body: dict) -> None:
        save_json(out_dir / f"page_{page_no:03d}.json", body)

    items = client.fetch_sport_records(start_date, end_date, on_page=on_page)
    save_json(out_dir / "all.json", items)
    raw_ref = str(out_dir.relative_to(root))
    timestamps = import_sport_records(conn, items, raw_ref)
    update_sync_state(conn, "fitness", "sport_records", timestamps)
    record_capture(
        conn, datetime.now(CN_TZ).isoformat(), "sport_records", f"{raw_ref}/all.json", len(items)
    )
    conn.commit()
    progress(f"  运动记录: {len(items)} 条")
    return len(items)


def sync_fds(
    conn: sqlite3.Connection,
    client: MiClient,
    root: Path,
    capture_dir: Path,
    progress: Callable[[str], None],
) -> int:
    rows = conn.execute(
        """
        SELECT a.id, a.source_id, a.fds_sid, a.proto_type, a.report_time,
               a.report_version, a.tz_in_15min, a.start_ts
        FROM activities a
        WHERE COALESCE(a.fds_collected, 0) = 0
          AND a.report_version > 0
          AND a.fds_sid IS NOT NULL
          AND a.proto_type IS NOT NULL
          AND a.report_time IS NOT NULL
        ORDER BY a.start_ts DESC
        """
    ).fetchall()
    if not rows:
        return 0

    completed = 0
    for row in rows:
        proto_type = int(row["proto_type"])
        supported = proto_type in SUPPORTED_RECORD_PROTO_TYPES
        sid = str(row["fds_sid"])
        report_time = int(row["report_time"])
        tz_byte = int(row["tz_in_15min"] or 0) & 0xFF
        fds_dir = capture_dir / "fds" / str(row["source_id"])
        fds_dir.mkdir(parents=True, exist_ok=True)

        blobs: dict[int, bytes] = {}
        for file_type, name in (
            (FDS_FILE_TYPE_SPORT_RECORD, "record"),
            (FDS_FILE_TYPE_GPS_TRACK, "gps"),
        ):
            suffix = build_fds_suffix(
                sid=sid,
                timestamp=report_time,
                tz_in_15min=tz_byte,
                proto_type=proto_type,
                file_type=file_type,
            )
            payload = {
                "did": sid,
                "items": [{"timestamp": report_time, "suffix": suffix}],
            }
            body, result = client.request(
                "/healthapp/service/gen_download_url",
                payload,
                sign_path="/service/gen_download_url",
            )
            save_json(fds_dir / f"{name}_download.json", body)
            entry = result.get(fds_server_key(suffix, report_time))
            if not isinstance(entry, dict) or not entry.get("url") or not entry.get("obj_key"):
                continue
            blob = client.download_fds(entry["url"], entry["obj_key"])
            (fds_dir / f"{name}.bin").write_bytes(blob)
            blobs[file_type] = blob

        status = "missing"
        if blobs:
            if supported:
                conn.execute("SAVEPOINT fds_parse")
                try:
                    import_fds(
                        conn,
                        int(row["id"]),
                        int(row["start_ts"]),
                        blobs.get(FDS_FILE_TYPE_SPORT_RECORD),
                        blobs.get(FDS_FILE_TYPE_GPS_TRACK),
                        proto_type,
                        str(fds_dir.relative_to(root)),
                    )
                    conn.execute("RELEASE fds_parse")
                    status = "parsed"
                except FdsParseError as exc:
                    conn.execute("ROLLBACK TO fds_parse")
                    conn.execute("RELEASE fds_parse")
                    status = "parse_error"
                    progress(f"  FDS 解析失败: {row['source_id']}: {exc}")
                except Exception as exc:
                    conn.execute("ROLLBACK TO fds_parse")
                    conn.execute("RELEASE fds_parse")
                    status = "parse_error"
                    progress(
                        f"  FDS 解析失败: {row['source_id']}: "
                        f"{type(exc).__name__}: {exc}"
                    )
            else:
                status = "raw_only"
        conn.execute(
            "UPDATE activities SET fds_status=?, fds_collected=1, fds_collected_at=? "
            "WHERE id=?",
            (status, int(time.time()), int(row["id"])),
        )
        if blobs:
            record_capture(
                conn,
                datetime.now(CN_TZ).isoformat(),
                "fds",
                str(fds_dir.relative_to(root)),
                1,
            )
        conn.commit()
        completed += 1
        progress(f"  FDS 明细: {row['source_id']} {status}")
    return completed


def sync(
    conn: sqlite3.Connection,
    client: MiClient,
    root: Path,
    days: int = 7,
    start: str | None = None,
    end: str | None = None,
    include_fds: bool = True,
    sport_only: bool = False,
    progress: Callable[[str], None] = print,
) -> dict[str, int]:
    today = datetime.now(CN_TZ).date()
    end_date = end or today.isoformat()
    capture_dir = root / "data" / "raw" / datetime.now(CN_TZ).strftime("%Y%m%d-%H%M%S")
    capture_dir.mkdir(parents=True, exist_ok=True)

    results: dict[str, int] = {}
    errors: dict[str, str] = {}

    for key in ([] if sport_only else FITNESS_KEYS):
        last = get_last_synced(conn, "fitness", key)
        if start:
            key_start = start
        elif last:
            key_start = (datetime.fromtimestamp(last, CN_TZ).date() - timedelta(days=1)).isoformat()
        else:
            key_start = (today - timedelta(days=days)).isoformat()
        if key_start > end_date:
            continue
        progress(f"抓取 {key} ({key_start} 到 {end_date}) ...")
        try:
            total = 0
            windows = date_windows(key_start, end_date, 31)
            for window_start, window_end in windows:
                label = f"{window_start}_{window_end}" if len(windows) > 1 else None
                total += sync_fitness_key(
                    conn,
                    client,
                    root,
                    key,
                    window_start,
                    window_end,
                    capture_dir,
                    progress,
                    label,
                )
            results[key] = total
        except Exception as exc:
            errors[key] = f"{type(exc).__name__}: {exc}"
            progress(f"  {key} 失败: {errors[key]}")

    last = get_last_synced(conn, "fitness", "sport_records")
    if start:
        sport_start = start
    elif last:
        sport_start = (datetime.fromtimestamp(last, CN_TZ).date() - timedelta(days=1)).isoformat()
    else:
        sport_start = (today - timedelta(days=max(days, 90))).isoformat()
    if sport_start <= end_date:
        progress(f"抓取运动记录 ({sport_start} 到 {end_date}) ...")
        try:
            total = 0
            windows = date_windows(sport_start, end_date, 90)
            for window_start, window_end in windows:
                label = f"{window_start}_{window_end}" if len(windows) > 1 else None
                total += sync_sport_records(
                    conn,
                    client,
                    root,
                    window_start,
                    window_end,
                    capture_dir,
                    progress,
                    label,
                )
            results["sport_records"] = total
        except Exception as exc:
            errors["sport_records"] = f"{type(exc).__name__}: {exc}"
            progress(f"  运动记录失败: {errors['sport_records']}")

    if include_fds:
        progress("补全运动 FDS 明细 ...")
        try:
            results["fds"] = sync_fds(conn, client, root, capture_dir, progress)
        except Exception as exc:
            errors["fds"] = f"{type(exc).__name__}: {exc}"
            progress(f"  FDS 补全失败: {errors['fds']}")

    meta = {
        "fetched_at": datetime.now(CN_TZ).isoformat(),
        "range": [start or f"last-{days}d", end_date],
        "counts": results,
        "errors": errors,
    }
    save_json(capture_dir / "meta.json", meta)
    return results
