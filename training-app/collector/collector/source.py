from __future__ import annotations

import json
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

from .config import Settings
from .state import CollectorState

UTC8 = timezone(timedelta(hours=8))
DATASETS = (
    "heartRate", "steps", "calories", "restingHeartRate", "sleep", "body", "activity"
)


def _source_modules(settings: Settings) -> tuple[Any, Any, Any, Any, Any]:
    source = str(settings.misports_dir)
    if source not in sys.path:
        sys.path.insert(0, source)
    from milib.auth import load_credentials, save_credentials
    from milib.client import MiClient
    from milib.db import connect
    from milib.sync import sync
    return load_credentials, save_credentials, MiClient, connect, sync


def _create_private_source(settings: Settings) -> tuple[Path, Path, Path]:
    root = settings.state_dir / "source"
    db_path = root / "data" / "misports.db"
    schema_path = settings.misports_dir / "schema.sql"
    root.mkdir(parents=True, exist_ok=True)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if not db_path.exists():
        if not settings.seed_db.is_file():
            raise FileNotFoundError(
                f"seed database not found at {settings.seed_db}; install a consistent private copy first"
            )
        source = sqlite3.connect(f"{settings.seed_db.as_uri()}?mode=ro", uri=True)
        target = sqlite3.connect(db_path)
        try:
            source.backup(target)
            target.commit()
        finally:
            target.close()
            source.close()
        try:
            db_path.chmod(0o600)
        except OSError:
            pass
    if not schema_path.is_file():
        raise FileNotFoundError(f"misports schema.sql not found under {settings.misports_dir}")
    return root, db_path, schema_path


def _json_object(value: str | None) -> dict[str, Any] | None:
    if not value:
        return None
    parsed = json.loads(value)
    if not isinstance(parsed, dict):
        raise ValueError("source extra_json must contain a JSON object")
    return parsed




def _body_payload(row: sqlite3.Row) -> dict[str, Any]:
    payload = {
        "weightKg": row["weight_kg"], "bmi": row["bmi"],
        "bodyFatPct": row["body_fat_pct"], "muscleMassKg": row["muscle_mass_kg"],
        "muscleRatePct": row["muscle_rate_pct"],
        "skeletalMuscleMassKg": row["skeletal_muscle_mass_kg"],
        "bodyMoistureMassKg": row["body_moisture_mass_kg"],
        "moistureRatePct": row["moisture_rate_pct"], "boneMassKg": row["bone_mass_kg"],
        "proteinMassKg": row["protein_mass_kg"], "proteinRatePct": row["protein_rate_pct"],
        "fatMassKg": row["fat_mass_kg"], "fatFreeBodyKg": row["fat_free_body_kg"],
        "visceralFat": row["visceral_fat"], "basalMetabolismKcal": row["basal_metabolism_kcal"],
        "bodyAge": row["body_age"], "bodyScore": row["body_score"], "whr": row["whr"],
        "standardWeightKg": row["standard_weight_kg"],
        "extra": _json_object(row["extra_json"]), "deviceId": row["device_id"],
    }
    return payload


def _sleep_payload(conn: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
    stages = conn.execute(
        "SELECT start_ts,end_ts,stage FROM sleep_stages WHERE session_id=? ORDER BY seq",
        (row["id"],),
    ).fetchall()
    return {
        "startAt": int(row["start_ts"]) * 1000,
        "endAt": int(row["end_ts"]) * 1000,
        "durationMinutes": row["duration_min"], "asleepMinutes": row["asleep_min"],
        "awakeMinutes": row["awake_min"], "awakeCount": row["awake_count"],
        "avgHeartRate": row["avg_hr"], "minHeartRate": row["min_hr"],
        "maxHeartRate": row["max_hr"], "deepMinutes": row["deep_min"],
        "lightMinutes": row["light_min"], "remMinutes": row["rem_min"],
        "score": row["score"], "isNap": bool(row["is_nap"]),
        "algorithmVersion": row["algorithm_version"],
        "stages": [
            {"startAt": int(stage["start_ts"]) * 1000,
             "endAt": int(stage["end_ts"]) * 1000, "stage": stage["stage"]}
            for stage in stages
        ],
    }


def _activity_payload(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "sportKey": row["sport_key"], "sportCategory": row["sport_category"],
        "sportTypeCode": row["sport_type_code"],
        "startAt": int(row["start_ts"]) * 1000, "endAt": int(row["end_ts"]) * 1000,
        "durationSeconds": row["duration_s"], "distanceMeters": row["distance_m"],
        "caloriesKcal": row["calories_kcal"], "steps": row["steps"],
        "avgHeartRate": row["avg_hr"], "maxHeartRate": row["max_hr"],
        "minHeartRate": row["min_hr"], "avgCadence": row["avg_cadence"],
        "maxCadence": row["max_cadence"], "avgStrideCm": row["avg_stride_cm"],
        "avgSpeedMps": row["avg_speed_mps"], "maxSpeedMps": row["max_speed_mps"],
        "avgPaceSecondsPerKm": row["avg_pace_s_per_km"],
        "maxPaceSecondsPerKm": row["max_pace_s_per_km"],
        "minPaceSecondsPerKm": row["min_pace_s_per_km"],
        "hrWarmUpSeconds": row["hr_warm_up_s"],
        "hrFatBurningSeconds": row["hr_fat_burning_s"],
        "hrAerobicSeconds": row["hr_aerobic_s"],
        "hrAnaerobicSeconds": row["hr_anaerobic_s"],
        "hrExtremeSeconds": row["hr_extreme_s"],
        "avgHeightMeters": row["avg_height_m"], "maxHeightMeters": row["max_height_m"],
        "minHeightMeters": row["min_height_m"], "vo2max": row["vo2max"],
        "trainEffect": row["train_effect"], "anaerobicTrainEffect": row["anaerobic_train_effect"],
        "trainingLoad": row["training_load"], "recoveryTime": row["recovery_time"],
        "avgSpo2": row["avg_spo2"], "validDurationSeconds": row["valid_duration_s"],
        "strokeCount": row["stroke_count"], "avgStrokeFrequency": row["avg_stroke_frequency"],
        "maxStrokeFrequency": row["max_stroke_freq"], "avgSwolf": row["avg_swolf"],
        "bestSwolf": row["best_swolf"], "turnCount": row["turn_count"],
        "poolWidth": row["pool_width"],
        "mainPosture": row["main_posture"],
        "totalCaloriesKcal": row["total_calories_kcal"],
        "avgPaceSecondsPer100m": row["avg_pace_s_per_100m"],
        "maxPaceSecondsPer100m": row["max_pace_s_per_100m"],
        "minPaceSecondsPer100m": row["min_pace_s_per_100m"],
        "extra": _json_object(row["extra_json"]),
    }


def _entries(conn: sqlite3.Connection, dataset: str, min_seconds: int) -> Iterable[tuple[str, str, int, dict[str, Any]]]:
    min_date = datetime.fromtimestamp(min_seconds, UTC8).date().isoformat()
    if dataset == "steps":
        for row in conn.execute(
            "SELECT * FROM step_minutes WHERE date_local>=? AND ts>=? ORDER BY ts,minute_ts",
            (min_date, min_seconds),
        ):
            ts = int(row["ts"])
            yield f"{row['source']}:{row['minute_ts']}", row["date_local"], ts * 1000, {
                "steps": row["steps"], "distanceMeters": row["distance_m"],
                "caloriesKcal": row["calories_kcal"], "deviceId": row["device_id"], "source": row["source"],
            }
    elif dataset == "calories":
        for row in conn.execute(
            "SELECT * FROM calorie_minutes WHERE date_local>=? AND ts>=? ORDER BY ts,minute_ts",
            (min_date, min_seconds),
        ):
            ts = int(row["ts"])
            yield f"{row['source']}:{row['minute_ts']}", row["date_local"], ts * 1000, {
                "caloriesKcal": row["calories_kcal"], "deviceId": row["device_id"], "source": row["source"],
            }
    elif dataset == "heartRate":
        for row in conn.execute(
            "SELECT * FROM heart_rate_samples WHERE date_local>=? AND ts>=? ORDER BY ts,sample_type",
            (min_date, min_seconds),
        ):
            ts = int(row["ts"])
            yield f"{row['source']}:{row['sample_type']}:{ts}", row["date_local"], ts * 1000, {
                "bpm": row["bpm"], "sampleType": row["sample_type"],
                "deviceId": row["device_id"], "source": row["source"],
            }
    elif dataset == "restingHeartRate":
        for row in conn.execute(
            "SELECT * FROM resting_heart_rate WHERE date_local>=? AND ts>=? ORDER BY ts,date_local",
            (min_date, min_seconds),
        ):
            ts = int(row["ts"])
            yield f"{row['source']}:{row['date_local']}", row["date_local"], ts * 1000, {
                "bpm": row["bpm"], "source": row["source"],
            }
    elif dataset == "sleep":
        for row in conn.execute(
            "SELECT * FROM sleep_sessions WHERE date_local>=? AND end_ts>=? ORDER BY end_ts,source_id",
            (min_date, min_seconds),
        ):
            end = int(row["end_ts"])
            yield row["source_id"], row["date_local"], end * 1000, _sleep_payload(conn, row)
    elif dataset == "body":
        for row in conn.execute(
            "SELECT * FROM body_measurements WHERE date_local>=? AND ts>=? ORDER BY ts,source_id",
            (min_date, min_seconds),
        ):
            ts = int(row["ts"])
            yield row["source_id"], row["date_local"], ts * 1000, _body_payload(row)
    elif dataset == "activity":
        for row in conn.execute("SELECT * FROM activities WHERE start_ts>=? ORDER BY start_ts,source_id", (min_seconds,)):
            ts = int(row["start_ts"])
            yield row["source_id"], row["date_local"], ts * 1000, _activity_payload(row)
    else:
        raise ValueError(f"unknown health dataset: {dataset}")


def _workout_heart_rate_entries(
    conn: sqlite3.Connection, activity_ids: Iterable[int]
) -> Iterable[tuple[str, str, int, dict[str, Any]]]:
    for activity_id in activity_ids:
        activity = conn.execute(
            "SELECT source_id FROM activities WHERE id=?", (activity_id,)
        ).fetchone()
        if activity is None:
            continue
        for row in conn.execute(
            "SELECT offset_s,ts,hr FROM activity_samples "
            "WHERE activity_id=? AND hr>0 ORDER BY offset_s",
            (activity_id,),
        ):
            ts = int(row["ts"])
            local_date = datetime.fromtimestamp(ts, UTC8).date().isoformat()
            yield (
                f"workout:{activity['source_id']}:{int(row['offset_s'])}",
                local_date,
                ts * 1000,
                {"bpm": row["hr"], "sampleType": "active", "source": "xiaomi_workout"},
            )


def enqueue_recent_workout_heart_rate(
    conn: sqlite3.Connection, state: CollectorState, overlap_seconds: int = 3_600
) -> int:
    """Stage FDS rows collected recently, including late arrivals, in bounded memory."""
    key = "workoutHeartRateLate"
    watermark = state.watermark(key, "source_watermark")
    floor = max(0, watermark - overlap_seconds)
    activities = conn.execute(
        "SELECT id,fds_collected_at FROM activities "
        "WHERE fds_collected=1 AND fds_collected_at>=? "
        "ORDER BY fds_collected_at DESC,id DESC",
        (floor,),
    )
    count = 0
    newest = watermark
    for activity in activities:
        newest = max(newest, int(activity["fds_collected_at"] or 0))
        for source_id, local_date, measured_at, payload in _workout_heart_rate_entries(
            conn, (int(activity["id"]),)
        ):
            if state.source_record_changed(
                "heartRate", source_id, local_date, measured_at, payload
            ):
                count += 1
    state.commit_enqueue()
    if newest > watermark:
        state.set_source_watermark(key, newest)
    return count


def enqueue_workout_heart_rate_backfill(
    conn: sqlite3.Connection, state: CollectorState, activity_limit: int = 100
) -> tuple[int, bool]:
    """Stage newest-first historical batches with a durable cursor."""
    key = "workoutHeartRateBackfill"
    cursor = state.watermark(key, "source_watermark")
    status = state.watermark(key, "submitted_watermark")
    if status == 1:
        return 0, True
    if status == 0:
        maximum = conn.execute("SELECT COALESCE(MAX(id),0) AS n FROM activities").fetchone()["n"]
        cursor = int(maximum) + 1
        state.db.execute(
            "INSERT INTO dataset_progress(dataset,source_watermark,submitted_watermark) "
            "VALUES(?,?,2) ON CONFLICT(dataset) DO UPDATE SET "
            "source_watermark=excluded.source_watermark,submitted_watermark=2",
            (key, cursor),
        )
        state.db.commit()
    activities = [
        int(row["id"]) for row in conn.execute(
            "SELECT id FROM activities WHERE id<? ORDER BY id DESC LIMIT ?",
            (cursor, activity_limit),
        )
    ]
    count = 0
    for source_id, local_date, measured_at, payload in _workout_heart_rate_entries(
        conn, activities
    ):
        if state.source_record_changed(
            "heartRate", source_id, local_date, measured_at, payload
        ):
            count += 1
    if activities:
        state.commit_enqueue()
        state.db.execute(
            "UPDATE dataset_progress SET source_watermark=? WHERE dataset=?",
            (activities[-1], key),
        )
        state.db.commit()
        return count, False
    state.commit_enqueue()
    state.db.execute(
        "INSERT INTO dataset_progress(dataset,source_watermark,submitted_watermark) "
        "VALUES(?,0,1) ON CONFLICT(dataset) DO UPDATE SET "
        "source_watermark=0,submitted_watermark=1",
        (key,),
    )
    state.db.commit()
    return count, True


def enqueue_source_records(
    conn: sqlite3.Connection,
    state: CollectorState,
    overlap_seconds: int = 86_400,
    start_date: str | None = None,
    end_date: str | None = None,
) -> dict[str, int]:
    counts: dict[str, int] = {}
    max_seen: dict[str, int] = {}
    explicit_rescan = start_date is not None
    lower_date = (
        int(datetime.fromisoformat(start_date).replace(tzinfo=UTC8).timestamp())
        if start_date else None
    )
    upper_date = (
        int((datetime.fromisoformat(end_date).replace(tzinfo=UTC8) + timedelta(days=1)).timestamp())
        if end_date else None
    )
    for dataset in DATASETS:
        watermark = state.watermark(dataset, "source_watermark")
        lower_bound = (
            lower_date if lower_date is not None
            else max(0, watermark - overlap_seconds) if watermark else 0
        )
        count = 0
        for source_id, local_date, measured_at, payload in _entries(conn, dataset, lower_bound):
            measured_seconds = measured_at // 1000
            if upper_date is not None and measured_seconds >= upper_date:
                continue
            if state.source_record_changed(
                dataset, source_id, local_date, measured_at, payload,
                force=explicit_rescan,
            ):
                count += 1
            max_seen[dataset] = max(max_seen.get(dataset, 0), measured_seconds)
        counts[dataset] = count
    for dataset in DATASETS:
        if dataset in max_seen:
            state.set_source_watermark(dataset, max_seen[dataset])
    state.commit_enqueue()
    return counts
