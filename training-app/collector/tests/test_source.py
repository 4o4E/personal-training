from __future__ import annotations

import json
import sqlite3
import sys
import threading
import tempfile
import unittest
from pathlib import Path

COLLECTOR_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(COLLECTOR_ROOT))

from collector.source import DATASETS, enqueue_source_records
from collector.state import CollectorState

REPO_ROOT = Path(__file__).resolve().parents[3]
SCHEMA_PATH = REPO_ROOT / "misports" / "schema.sql"


class SourceNormalizationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA_PATH.read_text(encoding="utf-8"))
        ts = 1_700_000_000
        date = "2023-11-15"
        self.conn.execute(
            "INSERT INTO step_minutes(minute_ts,ts,date_local,steps,distance_m,calories_kcal) "
            "VALUES(?,?,?,?,?,?)", (ts - ts % 60, ts, date, 12, 8.5, 0.3),
        )
        self.conn.execute(
            "INSERT INTO calorie_minutes(minute_ts,ts,date_local,calories_kcal) VALUES(?,?,?,?)",
            (ts - ts % 60, ts, date, 1.25),
        )
        self.conn.execute(
            "INSERT INTO heart_rate_samples(ts,date_local,bpm,sample_type) VALUES(?,?,?,?)",
            (ts, date, 72, "passive"),
        )
        self.conn.execute(
            "INSERT INTO resting_heart_rate(date_local,ts,bpm) VALUES(?,?,?)",
            (date, ts, 55),
        )
        self.conn.execute(
            "INSERT INTO sleep_sessions(source_id,date_local,start_ts,end_ts,duration_min,asleep_min,awake_min) "
            "VALUES(?,?,?,?,?,?,?)", ("sleep-source", date, ts - 3600, ts, 60, None, None),
        )
        session_id = self.conn.execute(
            "SELECT id FROM sleep_sessions WHERE source_id='sleep-source'"
        ).fetchone()["id"]
        self.conn.execute(
            "INSERT INTO sleep_stages(session_id,seq,start_ts,end_ts,stage) VALUES(?,?,?,?,?)",
            (session_id, 0, ts - 3600, ts - 1800, "deep"),
        )
        self.conn.execute(
            "INSERT INTO body_measurements(source_id,ts,date_local,weight_kg) VALUES(?,?,?,?)",
            ("body-source", ts, date, 71.5),
        )
        self.conn.execute(
            "INSERT INTO activities(source_id,sport_key,start_ts,end_ts,date_local,duration_s,main_posture) "
            "VALUES(?,?,?,?,?,?,?)", ("activity-source", "walking", ts, ts + 120, date, None, 9),
        )
        self.conn.commit()

    def tearDown(self) -> None:
        self.conn.close()

    def test_all_datasets_keep_real_nulls_units_and_epoch_milliseconds(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            state = CollectorState(Path(temp) / "state.sqlite3")
            try:
                counts = enqueue_source_records(self.conn, state)
                self.assertEqual(set(counts), set(DATASETS))
                self.assertEqual(counts, {dataset: 1 for dataset in DATASETS})
                expected_ms = 1_700_000_000_000
                for dataset in DATASETS:
                    row = state.next_batch(dataset, 1)[0]
                    self.assertEqual(row["measured_at"], expected_ms)
                sleep = state.next_batch("sleep", 1)[0]
                sleep_payload = json.loads(sleep["payload"])
                self.assertIsNone(sleep_payload["asleepMinutes"])
                self.assertIsNone(sleep_payload["awakeMinutes"])
                self.assertEqual(sleep_payload["stages"][0]["startAt"], 1_699_996_400_000)
                activity = state.next_batch("activity", 1)[0]
                activity_payload = json.loads(activity["payload"])
                self.assertIsNone(activity_payload["durationSeconds"])
                self.assertEqual(activity_payload["mainPosture"], 9)
            finally:
                state.close()

    def test_overlap_does_not_requeue_unchanged_rows_but_rescan_does(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            state = CollectorState(Path(temp) / "state.sqlite3")
            try:
                enqueue_source_records(self.conn, state)
                for dataset in DATASETS:
                    state.acknowledge(dataset, state.next_batch(dataset, 500))
                enqueue_source_records(self.conn, state)
                self.assertEqual([state.pending_count(dataset) for dataset in DATASETS], [0] * 7)
                enqueue_source_records(
                    self.conn, state, start_date="2023-11-15", end_date="2023-11-15"
                )
                self.assertEqual([state.pending_count(dataset) for dataset in DATASETS], [1] * 7)
            finally:
                state.close()

    def test_pending_batches_survive_restart_with_per_dataset_acknowledgement(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            state_path = Path(temp) / "state.sqlite3"
            state = CollectorState(state_path)
            enqueue_source_records(self.conn, state)
            state.acknowledge("steps", state.next_batch("steps", 500))
            state.close()

            reopened = CollectorState(state_path)
            try:
                self.assertEqual(reopened.pending_count("steps"), 0)
                self.assertEqual(
                    [reopened.pending_count(dataset) for dataset in DATASETS if dataset != "steps"],
                    [1] * 6,
                )
                self.assertEqual(
                    reopened.watermark("steps", "submitted_watermark"),
                    1_700_000_000_000,
                )
                self.assertEqual(
                    reopened.watermark("calories", "submitted_watermark"), 0
                )
            finally:
                reopened.close()

    def test_sqlite_state_connection_can_be_used_by_serial_scheduler_worker(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            state = CollectorState(Path(temp) / "state.sqlite3")
            errors: list[BaseException] = []

            def scheduler_job() -> None:
                try:
                    state.enqueue("steps", "source:1", "2023-11-15", 1_700_000_000_000,
                                  {"steps": 1})
                    state.commit_enqueue()
                except BaseException as exc:
                    errors.append(exc)

            worker = threading.Thread(target=scheduler_job)
            try:
                worker.start()
                worker.join(timeout=5)
                self.assertFalse(worker.is_alive(), "scheduler worker did not finish")
                self.assertEqual(errors, [])
                row = state.next_batch("steps", 1)[0]
                self.assertEqual(row["source_id"], "source:1")
            finally:
                if worker.is_alive():
                    worker.join(timeout=5)
                state.close()


if __name__ == "__main__":
    unittest.main()
