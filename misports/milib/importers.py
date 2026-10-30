"""把云端原始 JSON 和 FDS 明细写入 SQLite。"""

from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .db import record_capture, update_sync_state
from .fds import (
    TYPE_CALORIES,
    TYPE_CADENCE,
    TYPE_DISTANCE,
    TYPE_HEIGHT_VALUE,
    TYPE_HR,
    TYPE_INTEGER_KM,
    TYPE_PACE,
    TYPE_SPEED,
    parse_gps_record,
    parse_sport_record,
)

CN_TZ = timezone(timedelta(hours=8))

SLEEP_STAGES = {
    2: "deep",
    3: "light",
    4: "rem",
    5: "awake",
}

BODY_CORE_FIELDS = {
    "weight",
    "bmi",
    "body_fat_rate",
    "muscle_mass",
    "muscle_rate",
    "skeletal_muscle_mass",
    "body_moisture_mass",
    "moisture_rate",
    "bone_mass",
    "protein_mass",
    "protein_rate",
    "fat_mass",
    "fat_free_body",
    "visceral_fat",
    "basal_metabolism",
    "body_age",
    "body_score",
    "whr",
    "standard_weight",
    "standard_weight_v2",
}

ACTIVITY_CORE_FIELDS = {
    "start_time",
    "end_time",
    "duration",
    "distance",
    "calories",
    "total_cal",
    "steps",
    "avg_hrm",
    "max_hrm",
    "min_hrm",
    "avg_cadence",
    "max_cadence",
    "avg_stride",
    "avg_speed",
    "max_speed",
    "avg_pace",
    "max_pace",
    "min_pace",
    "hrm_warm_up_duration",
    "hrm_fat_burning_duration",
    "hrm_aerobic_duration",
    "hrm_anaerobic_duration",
    "hrm_extreme_duration",
    "avg_height",
    "max_height",
    "min_height",
    "vo2max",
    "train_effect",
    "anaerobic_train_effect",
    "training_load",
    "train_load",
    "recovery_time",
    "recover_time",
    "avg_spo2",
    "valid_duration",
    "stroke_count",
    "avg_stroke_frequency",
    "max_stroke_freq",
    "avg_swolf",
    "best_swolf",
    "turn_count",
    "pool_width",
    "main_posture",
    "proto_type",
    "version",
    "time",
    "timezone",
    "sport_type",
}


def value_of(item: dict) -> dict:
    value = item.get("value", "{}")
    if isinstance(value, dict):
        return value
    try:
        parsed = json.loads(value)
        return parsed if isinstance(parsed, dict) else {}
    except (TypeError, ValueError):
        return {}


def local_date(timestamp: int) -> str:
    return datetime.fromtimestamp(int(timestamp), tz=CN_TZ).date().isoformat()


def item_timestamp(item: dict, key: str) -> int | None:
    value = value_of(item)
    if key == "resting_heart_rate":
        candidate = value.get("date_time") or item.get("time")
    elif key == "sleep":
        candidate = value.get("wake_up_time") or item.get("time")
    else:
        candidate = item.get("time") or value.get("time")
    try:
        return int(candidate) if candidate else None
    except (TypeError, ValueError):
        return None


def import_steps(conn: sqlite3.Connection, items: list[dict], raw_ref: str | None) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        ts = item_timestamp(item, "steps")
        if ts is None:
            continue
        minute_ts = ts - ts % 60
        conn.execute(
            """
            INSERT INTO step_minutes(
              minute_ts, ts, date_local, steps, distance_m, calories_kcal, device_id, source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'cloud')
            ON CONFLICT(minute_ts, source) DO UPDATE SET
              ts=excluded.ts,
              date_local=excluded.date_local,
              steps=excluded.steps,
              distance_m=excluded.distance_m,
              calories_kcal=excluded.calories_kcal,
              device_id=excluded.device_id
            WHERE excluded.steps > step_minutes.steps
               OR (excluded.steps = step_minutes.steps
                   AND excluded.distance_m > step_minutes.distance_m)
            """,
            (
                minute_ts,
                ts,
                local_date(ts),
                int(value.get("steps", 0)),
                float(value.get("distance", 0)),
                float(value.get("calories", 0)),
                item.get("sid"),
            ),
        )
        timestamps.append(ts)
    return timestamps


def import_calories(conn: sqlite3.Connection, items: list[dict], raw_ref: str | None) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        ts = item_timestamp(item, "calories")
        if ts is None:
            continue
        minute_ts = ts - ts % 60
        conn.execute(
            """
            INSERT INTO calorie_minutes(
              minute_ts, ts, date_local, calories_kcal, device_id, source
            ) VALUES (?, ?, ?, ?, ?, 'cloud')
            ON CONFLICT(minute_ts, source) DO UPDATE SET
              ts=excluded.ts,
              date_local=excluded.date_local,
              calories_kcal=excluded.calories_kcal,
              device_id=excluded.device_id
            WHERE excluded.calories_kcal > calorie_minutes.calories_kcal
            """,
            (
                minute_ts,
                ts,
                local_date(ts),
                float(value.get("calories", 0)),
                item.get("sid"),
            ),
        )
        timestamps.append(ts)
    return timestamps


def import_heart_rate(conn: sqlite3.Connection, items: list[dict], raw_ref: str | None) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        ts = item_timestamp(item, "heart_rate")
        bpm = value.get("bpm")
        if ts is None or bpm is None:
            continue
        sample_type = "active" if int(value.get("type", 0) or 0) == 1 else "passive"
        conn.execute(
            """
            INSERT INTO heart_rate_samples(
              ts, date_local, bpm, sample_type, device_id, source
            ) VALUES (?, ?, ?, ?, ?, 'cloud')
            ON CONFLICT(ts, sample_type, source) DO UPDATE SET
              bpm=excluded.bpm,
              date_local=excluded.date_local,
              device_id=excluded.device_id
            """,
            (ts, local_date(ts), int(bpm), sample_type, item.get("sid")),
        )
        timestamps.append(ts)
    return timestamps


def import_resting_heart_rate(
    conn: sqlite3.Connection, items: list[dict], raw_ref: str | None
) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        ts = item_timestamp(item, "resting_heart_rate")
        bpm = value.get("bpm")
        if ts is None or bpm is None:
            continue
        conn.execute(
            """
            INSERT INTO resting_heart_rate(date_local, ts, bpm, source)
            VALUES (?, ?, ?, 'cloud')
            ON CONFLICT(date_local, source) DO UPDATE SET
              ts=excluded.ts,
              bpm=excluded.bpm
            """,
            (local_date(ts), ts, int(bpm)),
        )
        timestamps.append(ts)
    return timestamps


def import_sleep(conn: sqlite3.Connection, items: list[dict], raw_ref: str | None) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        bedtime = value.get("bedtime") or value.get("device_bedtime") or value.get("bed_timestamp")
        wake = (
            value.get("wake_up_time")
            or value.get("device_wake_up_time")
            or value.get("out_bed_timestamp")
            or item.get("time")
        )
        if not bedtime or not wake:
            continue
        start_ts = int(bedtime)
        end_ts = int(wake)
        duration = int(value.get("duration") or max(0, (end_ts - start_ts) // 60))
        awake = int(value.get("sleep_awake_duration") or value.get("awake_duration") or 0)
        asleep = max(0, duration - awake)
        source_id = f"{item.get('sid')}_{item.get('time') or end_ts}"
        is_nap = 1 if str(value.get("is_nap", False)).lower() in {"true", "1", "yes"} else 0
        conn.execute(
            """
            INSERT INTO sleep_sessions(
              source_id, date_local, start_ts, end_ts, duration_min, asleep_min,
              awake_min, awake_count, avg_hr, min_hr, max_hr, deep_min, light_min,
              rem_min, score, is_nap, algorithm_version, raw_ref
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_id) DO UPDATE SET
              date_local=excluded.date_local,
              start_ts=excluded.start_ts,
              end_ts=excluded.end_ts,
              duration_min=excluded.duration_min,
              asleep_min=excluded.asleep_min,
              awake_min=excluded.awake_min,
              awake_count=excluded.awake_count,
              avg_hr=excluded.avg_hr,
              min_hr=excluded.min_hr,
              max_hr=excluded.max_hr,
              deep_min=excluded.deep_min,
              light_min=excluded.light_min,
              rem_min=excluded.rem_min,
              score=excluded.score,
              is_nap=excluded.is_nap,
              algorithm_version=excluded.algorithm_version,
              raw_ref=excluded.raw_ref
            """,
            (
                source_id,
                local_date(end_ts),
                start_ts,
                end_ts,
                duration,
                asleep,
                awake,
                value.get("awake_count"),
                value.get("avg_hr"),
                value.get("min_hr"),
                value.get("max_hr"),
                value.get("sleep_deep_duration"),
                value.get("sleep_light_duration"),
                value.get("sleep_rem_duration"),
                value.get("score"),
                is_nap,
                value.get("sleep_algorithm_version"),
                raw_ref,
            ),
        )
        session_id = conn.execute(
            "SELECT id FROM sleep_sessions WHERE source_id=?",
            (source_id,),
        ).fetchone()["id"]
        conn.execute("DELETE FROM sleep_stages WHERE session_id=?", (session_id,))
        for seq, segment in enumerate(value.get("items", []) or []):
            segment_start = segment.get("start_time")
            segment_end = segment.get("end_time")
            if not segment_start or not segment_end:
                continue
            conn.execute(
                """
                INSERT INTO sleep_stages(session_id, seq, start_ts, end_ts, stage)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    session_id,
                    seq,
                    int(segment_start),
                    int(segment_end),
                    SLEEP_STAGES.get(int(segment.get("state", 0) or 0), "unknown"),
                ),
            )
        timestamps.append(end_ts)
    return timestamps


def import_body_measurements(
    conn: sqlite3.Connection, items: list[dict], raw_ref: str | None
) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        weight = value.get("weight")
        ts = item_timestamp(item, "weight")
        if weight is None or ts is None:
            continue
        extra = {key: val for key, val in value.items() if key not in BODY_CORE_FIELDS}
        source_id = f"{item.get('sid')}_{item.get('time')}"
        standard_weight = value.get("standard_weight_v2")
        if standard_weight is None:
            standard_weight = value.get("standard_weight")
        conn.execute(
            """
            INSERT INTO body_measurements(
              source_id, ts, date_local, device_id, weight_kg, bmi, body_fat_pct,
              muscle_mass_kg, muscle_rate_pct, skeletal_muscle_mass_kg,
              body_moisture_mass_kg, moisture_rate_pct, bone_mass_kg,
              protein_mass_kg, protein_rate_pct, fat_mass_kg, fat_free_body_kg,
              visceral_fat, basal_metabolism_kcal, body_age, body_score, whr,
              standard_weight_kg, extra_json, raw_ref
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_id) DO UPDATE SET
              ts=excluded.ts,
              date_local=excluded.date_local,
              device_id=excluded.device_id,
              weight_kg=excluded.weight_kg,
              bmi=excluded.bmi,
              body_fat_pct=excluded.body_fat_pct,
              muscle_mass_kg=excluded.muscle_mass_kg,
              muscle_rate_pct=excluded.muscle_rate_pct,
              skeletal_muscle_mass_kg=excluded.skeletal_muscle_mass_kg,
              body_moisture_mass_kg=excluded.body_moisture_mass_kg,
              moisture_rate_pct=excluded.moisture_rate_pct,
              bone_mass_kg=excluded.bone_mass_kg,
              protein_mass_kg=excluded.protein_mass_kg,
              protein_rate_pct=excluded.protein_rate_pct,
              fat_mass_kg=excluded.fat_mass_kg,
              fat_free_body_kg=excluded.fat_free_body_kg,
              visceral_fat=excluded.visceral_fat,
              basal_metabolism_kcal=excluded.basal_metabolism_kcal,
              body_age=excluded.body_age,
              body_score=excluded.body_score,
              whr=excluded.whr,
              standard_weight_kg=excluded.standard_weight_kg,
              extra_json=excluded.extra_json,
              raw_ref=excluded.raw_ref
            """,
            (
                source_id,
                ts,
                local_date(ts),
                item.get("sid"),
                float(weight),
                value.get("bmi"),
                value.get("body_fat_rate"),
                value.get("muscle_mass"),
                value.get("muscle_rate"),
                value.get("skeletal_muscle_mass"),
                value.get("body_moisture_mass"),
                value.get("moisture_rate"),
                value.get("bone_mass"),
                value.get("protein_mass"),
                value.get("protein_rate"),
                value.get("fat_mass"),
                value.get("fat_free_body"),
                value.get("visceral_fat"),
                value.get("basal_metabolism"),
                value.get("body_age"),
                value.get("body_score"),
                value.get("whr"),
                standard_weight,
                json.dumps(extra, ensure_ascii=False) if extra else None,
                raw_ref,
            ),
        )
        timestamps.append(ts)
    return timestamps


def import_sport_records(
    conn: sqlite3.Connection, items: list[dict], raw_ref: str | None
) -> list[int]:
    timestamps: list[int] = []
    for item in items:
        value = value_of(item)
        start_ts = value.get("start_time") or item.get("time")
        end_ts = value.get("end_time")
        if not start_ts or not end_ts:
            continue
        start_ts = int(start_ts)
        end_ts = int(end_ts)
        duration = value.get("duration")
        if duration is None:
            duration = max(0, end_ts - start_ts)
        source_id = f"{item.get('sid')}_{item.get('key')}_{item.get('time')}"
        is_swim = value.get("sport_type") == 9 or item.get("category") == "swimming"
        distance_m = value.get("distance")
        duration_s = int(duration)
        avg_pace = value.get("avg_pace")
        if is_swim:
            if avg_pace is None and distance_m and duration_s:
                avg_pace = duration_s / (float(distance_m) / 100.0)
            avg_pace_km = None
            avg_pace_100m = avg_pace
        else:
            if avg_pace is None and distance_m and duration_s:
                avg_pace = duration_s / (float(distance_m) / 1000.0)
            avg_pace_km = avg_pace
            avg_pace_100m = None
        training_load = value.get("training_load")
        if training_load is None:
            training_load = value.get("train_load")
        recovery_time = value.get("recovery_time")
        if recovery_time is None:
            recovery_time = value.get("recover_time")
        extra = {key: val for key, val in value.items() if key not in ACTIVITY_CORE_FIELDS}
        params = {
            "source_id": source_id,
            "sport_key": item.get("key") or "workout",
            "sport_category": item.get("category"),
            "sport_type_code": value.get("sport_type"),
            "start_ts": start_ts,
            "end_ts": end_ts,
            "date_local": local_date(start_ts),
            "duration_s": duration_s,
            "distance_m": distance_m,
            "calories_kcal": value.get("calories"),
            "steps": value.get("steps"),
            "avg_hr": value.get("avg_hrm"),
            "max_hr": value.get("max_hrm"),
            "min_hr": value.get("min_hrm"),
            "avg_cadence": value.get("avg_cadence"),
            "max_cadence": value.get("max_cadence"),
            "avg_stride_cm": value.get("avg_stride"),
            "avg_speed_mps": value.get("avg_speed"),
            "max_speed_mps": value.get("max_speed"),
            "avg_pace_s_per_km": avg_pace_km,
            "max_pace_s_per_km": None if is_swim else value.get("max_pace"),
            "min_pace_s_per_km": None if is_swim else value.get("min_pace"),
            "hr_warm_up_s": value.get("hrm_warm_up_duration"),
            "hr_fat_burning_s": value.get("hrm_fat_burning_duration"),
            "hr_aerobic_s": value.get("hrm_aerobic_duration"),
            "hr_anaerobic_s": value.get("hrm_anaerobic_duration"),
            "hr_extreme_s": value.get("hrm_extreme_duration"),
            "avg_height_m": value.get("avg_height"),
            "max_height_m": value.get("max_height"),
            "min_height_m": value.get("min_height"),
            "vo2max": value.get("vo2max"),
            "train_effect": value.get("train_effect"),
            "anaerobic_train_effect": value.get("anaerobic_train_effect"),
            "training_load": training_load,
            "recovery_time": recovery_time,
            "avg_spo2": value.get("avg_spo2"),
            "valid_duration_s": value.get("valid_duration"),
            "stroke_count": value.get("stroke_count"),
            "avg_stroke_frequency": value.get("avg_stroke_frequency"),
            "max_stroke_freq": value.get("max_stroke_freq"),
            "avg_swolf": value.get("avg_swolf"),
            "best_swolf": value.get("best_swolf"),
            "turn_count": value.get("turn_count"),
            "pool_width": value.get("pool_width"),
            "main_posture": value.get("main_posture"),
            "total_calories_kcal": value.get("total_cal"),
            "avg_pace_s_per_100m": avg_pace_100m,
            "max_pace_s_per_100m": value.get("max_pace") if is_swim else None,
            "min_pace_s_per_100m": value.get("min_pace") if is_swim else None,
            "extra_json": json.dumps(extra, ensure_ascii=False) if extra else None,
            "fds_sid": item.get("sid"),
            "proto_type": value.get("proto_type"),
            "report_version": value.get("version"),
            "report_time": value.get("time") or item.get("time"),
            "tz_in_15min": value.get("timezone"),
            "raw_ref": raw_ref,
        }
        conn.execute(
            """
            INSERT INTO activities(
              source_id, sport_key, sport_category, sport_type_code,
              start_ts, end_ts, date_local, duration_s, distance_m, calories_kcal,
              steps, avg_hr, max_hr, min_hr, avg_cadence, max_cadence, avg_stride_cm,
              avg_speed_mps, max_speed_mps, avg_pace_s_per_km, max_pace_s_per_km,
              min_pace_s_per_km, hr_warm_up_s, hr_fat_burning_s, hr_aerobic_s,
              hr_anaerobic_s, hr_extreme_s, avg_height_m, max_height_m, min_height_m,
              vo2max, train_effect, anaerobic_train_effect, training_load,
              recovery_time, avg_spo2, valid_duration_s, stroke_count,
              avg_stroke_frequency, max_stroke_freq, avg_swolf, best_swolf,
              turn_count, pool_width, main_posture, total_calories_kcal,
              avg_pace_s_per_100m, max_pace_s_per_100m, min_pace_s_per_100m,
              extra_json, fds_sid, proto_type, report_version, report_time,
              tz_in_15min, raw_ref
            ) VALUES (
              :source_id, :sport_key, :sport_category, :sport_type_code,
              :start_ts, :end_ts, :date_local, :duration_s, :distance_m, :calories_kcal,
              :steps, :avg_hr, :max_hr, :min_hr, :avg_cadence, :max_cadence, :avg_stride_cm,
              :avg_speed_mps, :max_speed_mps, :avg_pace_s_per_km, :max_pace_s_per_km,
              :min_pace_s_per_km, :hr_warm_up_s, :hr_fat_burning_s, :hr_aerobic_s,
              :hr_anaerobic_s, :hr_extreme_s, :avg_height_m, :max_height_m, :min_height_m,
              :vo2max, :train_effect, :anaerobic_train_effect, :training_load,
              :recovery_time, :avg_spo2, :valid_duration_s, :stroke_count,
              :avg_stroke_frequency, :max_stroke_freq, :avg_swolf, :best_swolf,
              :turn_count, :pool_width, :main_posture, :total_calories_kcal,
              :avg_pace_s_per_100m, :max_pace_s_per_100m, :min_pace_s_per_100m,
              :extra_json, :fds_sid, :proto_type, :report_version, :report_time,
              :tz_in_15min, :raw_ref
            )
            ON CONFLICT(source_id) DO UPDATE SET
              sport_key=excluded.sport_key,
              sport_category=excluded.sport_category,
              sport_type_code=excluded.sport_type_code,
              start_ts=excluded.start_ts,
              end_ts=excluded.end_ts,
              date_local=excluded.date_local,
              duration_s=excluded.duration_s,
              distance_m=excluded.distance_m,
              calories_kcal=excluded.calories_kcal,
              steps=excluded.steps,
              avg_hr=excluded.avg_hr,
              max_hr=excluded.max_hr,
              min_hr=excluded.min_hr,
              avg_cadence=excluded.avg_cadence,
              max_cadence=excluded.max_cadence,
              avg_stride_cm=excluded.avg_stride_cm,
              avg_speed_mps=excluded.avg_speed_mps,
              max_speed_mps=excluded.max_speed_mps,
              avg_pace_s_per_km=excluded.avg_pace_s_per_km,
              max_pace_s_per_km=excluded.max_pace_s_per_km,
              min_pace_s_per_km=excluded.min_pace_s_per_km,
              hr_warm_up_s=excluded.hr_warm_up_s,
              hr_fat_burning_s=excluded.hr_fat_burning_s,
              hr_aerobic_s=excluded.hr_aerobic_s,
              hr_anaerobic_s=excluded.hr_anaerobic_s,
              hr_extreme_s=excluded.hr_extreme_s,
              avg_height_m=excluded.avg_height_m,
              max_height_m=excluded.max_height_m,
              min_height_m=excluded.min_height_m,
              vo2max=excluded.vo2max,
              train_effect=excluded.train_effect,
              anaerobic_train_effect=excluded.anaerobic_train_effect,
              training_load=excluded.training_load,
              recovery_time=excluded.recovery_time,
              avg_spo2=excluded.avg_spo2,
              valid_duration_s=excluded.valid_duration_s,
              stroke_count=excluded.stroke_count,
              avg_stroke_frequency=excluded.avg_stroke_frequency,
              max_stroke_freq=excluded.max_stroke_freq,
              avg_swolf=excluded.avg_swolf,
              best_swolf=excluded.best_swolf,
              turn_count=excluded.turn_count,
              pool_width=excluded.pool_width,
              main_posture=excluded.main_posture,
              total_calories_kcal=excluded.total_calories_kcal,
              avg_pace_s_per_100m=excluded.avg_pace_s_per_100m,
              max_pace_s_per_100m=excluded.max_pace_s_per_100m,
              min_pace_s_per_100m=excluded.min_pace_s_per_100m,
              extra_json=excluded.extra_json,
              fds_sid=excluded.fds_sid,
              proto_type=excluded.proto_type,
              report_version=excluded.report_version,
              report_time=excluded.report_time,
              tz_in_15min=excluded.tz_in_15min,
              raw_ref=excluded.raw_ref
            """,
            params,
        )
        timestamps.append(start_ts)
    return timestamps


def import_fds(
    conn: sqlite3.Connection,
    activity_id: int,
    start_ts: int,
    record_blob: bytes | None,
    gps_blob: bytes | None,
    proto_type: int,
    raw_ref: str | None = None,
) -> None:
    if record_blob:
        for segment_start, records in parse_sport_record(record_blob, proto_type):
            for index, row in enumerate(records):
                ts = segment_start + index
                conn.execute(
                    """
                    INSERT OR REPLACE INTO activity_samples(
                      activity_id, offset_s, ts, hr, cadence, distance_m,
                      calories_kcal, stride_cm, integer_km_flag, speed_mps, altitude_m
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        activity_id,
                        ts - start_ts,
                        ts,
                        row.get(TYPE_HR),
                        row.get(TYPE_CADENCE),
                        (row.get(TYPE_DISTANCE) / 10.0) if TYPE_DISTANCE in row else None,
                        row.get(TYPE_CALORIES),
                        row.get(40),
                        row.get(TYPE_INTEGER_KM),
                        row.get(TYPE_SPEED),
                        row.get(TYPE_HEIGHT_VALUE),
                    ),
                )
    if gps_blob:
        for seq, point in enumerate(parse_gps_record(gps_blob)):
            conn.execute(
                """
                INSERT OR REPLACE INTO activity_track_points(
                  activity_id, seq, ts, lat, lon, accuracy_m, speed_mps,
                  gps_source, altitude_m, hdop
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    activity_id,
                    seq,
                    point.timestamp,
                    point.latitude,
                    point.longitude,
                    point.accuracy,
                    point.speed_mps,
                    point.gps_source,
                    point.altitude_m,
                    point.hdop,
                ),
            )


def import_fitness_key(
    conn: sqlite3.Connection, key: str, items: list[dict], raw_ref: str | None
) -> list[int]:
    if key == "steps":
        return import_steps(conn, items, raw_ref)
    if key == "calories":
        return import_calories(conn, items, raw_ref)
    if key == "heart_rate":
        return import_heart_rate(conn, items, raw_ref)
    if key == "resting_heart_rate":
        return import_resting_heart_rate(conn, items, raw_ref)
    if key == "sleep":
        return import_sleep(conn, items, raw_ref)
    if key == "weight":
        return import_body_measurements(conn, items, raw_ref)
    return []


def import_raw_dir(conn: sqlite3.Connection, raw_dir: Path, root: Path) -> dict[str, int]:
    raw_dir = raw_dir.resolve()
    try:
        raw_ref = str(raw_dir.relative_to(root))
    except ValueError:
        raw_ref = str(raw_dir)
    meta_path = raw_dir / "meta.json"
    captured_at = datetime.now(CN_TZ).isoformat()
    if meta_path.exists():
        try:
            captured_at = json.loads(meta_path.read_text(encoding="utf-8")).get("fetched_at", captured_at)
        except (ValueError, OSError):
            pass

    counts: dict[str, int] = {}

    fitness_dir = raw_dir / "fitness"
    if fitness_dir.exists():
        for key_dir in sorted(fitness_dir.iterdir()):
            all_path = key_dir / "all.json"
            if not key_dir.is_dir() or not all_path.exists():
                continue
            items = json.loads(all_path.read_text(encoding="utf-8"))
            timestamps = import_fitness_key(conn, key_dir.name, items, f"{raw_ref}/fitness/{key_dir.name}/all.json")
            update_sync_state(conn, "fitness", key_dir.name, timestamps)
            record_capture(
                conn,
                captured_at,
                "fitness",
                f"{raw_ref}/fitness/{key_dir.name}/all.json",
                len(items),
            )
            counts[key_dir.name] = len(items)

    sport_path = raw_dir / "sport_records" / "all.json"
    if sport_path.exists():
        items = json.loads(sport_path.read_text(encoding="utf-8"))
        timestamps = import_sport_records(conn, items, f"{raw_ref}/sport_records/all.json")
        update_sync_state(conn, "fitness", "sport_records", timestamps)
        record_capture(
            conn,
            captured_at,
            "sport_records",
            f"{raw_ref}/sport_records/all.json",
            len(items),
        )
        counts["sport_records"] = len(items)

    fds_root = raw_dir / "fds"
    fds_count = 0
    if fds_root.exists():
        for fds_dir in sorted(fds_root.iterdir()):
            meta_file = fds_dir / "meta.json"
            if not fds_dir.is_dir() or not meta_file.exists():
                continue
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
            source_id = f"{meta.get('sid')}_{meta.get('key')}_{meta.get('report_time')}"
            row = conn.execute(
                "SELECT id, start_ts FROM activities WHERE source_id=?",
                (source_id,),
            ).fetchone()
            if row is None:
                continue
            record_path = fds_dir / "record.bin"
            gps_path = fds_dir / "gps.bin"
            record_blob = record_path.read_bytes() if record_path.exists() else None
            gps_blob = gps_path.read_bytes() if gps_path.exists() else None
            import_fds(
                conn,
                int(row["id"]),
                int(row["start_ts"]),
                record_blob,
                gps_blob,
                int(meta.get("proto_type", 0)),
                f"{raw_ref}/fds/{fds_dir.name}",
            )
            fds_count += 1
            record_capture(
                conn,
                captured_at,
                "fds",
                f"{raw_ref}/fds/{fds_dir.name}",
                1,
                hashlib.sha256(record_blob or b"").hexdigest() if record_blob else None,
            )
    if fds_count:
        counts["fds"] = fds_count

    conn.commit()
    return counts
