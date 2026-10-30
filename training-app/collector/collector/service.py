from __future__ import annotations

import json
import logging
import os
import sqlite3
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx

from .config import Settings
from .source import (
    DATASETS, _create_private_source, _source_modules,
    enqueue_recent_workout_heart_rate, enqueue_source_records,
    enqueue_workout_heart_rate_backfill,
)
from .state import CollectorState
if os.name == "nt":
    import msvcrt
else:
    import fcntl


def _try_process_lock(file: Any) -> None:
    if os.name == "nt":
        file.seek(0)
        if file.read(1) == b"":
            file.seek(0)
            file.write(b"\0")
            file.flush()
        file.seek(0)
        msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        fcntl.flock(file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)


def _release_process_lock(file: Any) -> None:
    if os.name == "nt":
        file.seek(0)
        msvcrt.locking(file.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        fcntl.flock(file.fileno(), fcntl.LOCK_UN)


LOG = logging.getLogger("collector")
API_VERSION = 1
FORMAT_VERSION = 6
BATCH_SIZE = 500


class CollectorService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        settings.state_dir.mkdir(parents=True, exist_ok=True)
        self.state = CollectorState(settings.state_dir / "collector.sqlite3")
        self.lock_path = settings.state_dir / "collector.lock"
        try:
            self.lock_path.chmod(0o600)
        except OSError:
            pass
        self._local_lock = threading.Lock()
        self._secrets = {settings.training_token}
        self._http = httpx.Client(
            base_url=settings.training_url,
            headers={"Authorization": f"Bearer {settings.training_token}"},
            timeout=30.0,
        )
        self.source_root, self.source_db_path, self.schema_path = _create_private_source(settings)
        self.load_credentials, self.save_credentials, self.MiClient, self.connect, self.sync = _source_modules(settings)

    def _endpoint(self, path: str) -> str:
        return f"/v1/{path.lstrip('/')}"
    def _redact(self, text: str) -> str:
        for secret in self._secrets:
            if secret:
                text = text.replace(secret, "[redacted]")
        return text

    def _safe_reason(self, exc: Exception) -> str:
        return self._redact(f"{type(exc).__name__}: {exc}")


    def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        response = self._http.request(method, self._endpoint(path), **kwargs)
        if response.is_error:
            raise RuntimeError(f"backend {method} {path} returned HTTP {response.status_code}")
        try:
            value = response.json()
        except ValueError as exc:
            raise RuntimeError(f"backend {method} {path} returned invalid JSON") from exc
        if not isinstance(value, dict):
            raise RuntimeError(f"backend {method} {path} returned a non-object response")
        return value

    def check_capabilities(self) -> None:
        capabilities = self._request("GET", "capabilities")
        expected = {
            "healthApiVersion": API_VERSION,
            "formatVersion": FORMAT_VERSION,
            "backupFormatVersion": FORMAT_VERSION,
        }
        mismatches = [
            name for name, required in expected.items()
            if capabilities.get(name) != required
        ]
        if mismatches:
            raise RuntimeError("backend capability mismatch: " + ", ".join(mismatches))

    def _record_state(self, event: str, phase: str | None = None,
                      error: str | None = None) -> None:
        body: dict[str, Any] = {"event": event}
        if phase is not None:
            body["phase"] = phase
        if error is not None:
            body["error"] = error[:1000]
        self._request("POST", "health/collection/state", json=body)

    def poll_request(self) -> bool:
        state = self._request("GET", "health/status").get("collection")
        return isinstance(state, dict) and state.get("requested") is True

    def _enqueue_local_source(self, start_date: str | None = None,
                              end_date: str | None = None) -> dict[str, int]:
        conn = self.connect(self.source_db_path, self.schema_path)
        try:
            return enqueue_source_records(
                conn, self.state, start_date=start_date, end_date=end_date
            )
        finally:
            conn.close()

    def _enqueue_recent_workout_heart_rate(self) -> int:
        conn = self.connect(self.source_db_path, self.schema_path)
        try:
            return enqueue_recent_workout_heart_rate(conn, self.state)
        finally:
            conn.close()

    def _enqueue_workout_backfill_batch(self) -> tuple[int, bool]:
        conn = self.connect(self.source_db_path, self.schema_path)
        try:
            return enqueue_workout_heart_rate_backfill(conn, self.state)
        finally:
            conn.close()


    def _run_source_sync(self, start_date: str | None, end_date: str | None) -> Path:
        load_credentials, save_credentials, mi_client_type, connect, sync = (
            self.load_credentials, self.save_credentials, self.MiClient, self.connect, self.sync
        )
        user_id, pass_token, _ = load_credentials(self.source_root)
        self._secrets.update((user_id, pass_token))
        client = mi_client_type(user_id, pass_token)
        try:
            rotated_user, rotated_token = client.login()
            self._secrets.update((rotated_user, rotated_token))
            saved = save_credentials(self.source_root, rotated_user, rotated_token)
            try:
                saved.chmod(0o600)
            except OSError:
                pass
            conn = connect(self.source_db_path, self.schema_path)
            try:
                started = time.time()
                self.sync(
                    conn,
                    client,
                    self.source_root,
                    days=7,
                    start=start_date,
                    end=end_date,
                    include_fds=True,
                )
                meta_files = sorted(
                    (self.source_root / "data" / "raw").glob("*/meta.json"),
                    key=lambda path: path.stat().st_mtime,
                )
                if not meta_files:
                    raise RuntimeError("milib.sync returned without writing meta.json")
                meta_path = meta_files[-1]
                try:
                    meta = json.loads(meta_path.read_text(encoding="utf-8"))
                except (OSError, ValueError) as exc:
                    raise RuntimeError("unable to read this round's meta.json") from exc
                errors = meta.get("errors")
                if not isinstance(errors, dict):
                    raise RuntimeError("this round's meta.json has no errors object")
                if errors:
                    details = "; ".join(
                        self._redact(f"{name}: {value}")
                        for name, value in sorted(errors.items(), key=lambda item: str(item[0]))
                    )
                    raise RuntimeError(f"milib collection dataset errors: {details}")
                fetched_at = meta.get("fetched_at")
                try:
                    fetched_timestamp = datetime.fromisoformat(fetched_at).timestamp()
                except (TypeError, ValueError) as exc:
                    raise RuntimeError("this round's meta.json has no valid fetched_at") from exc
                if fetched_timestamp < started:
                    raise RuntimeError("latest meta.json does not belong to this collection round")
                return meta_path
            finally:
                conn.close()
        finally:
            client.close()

    def _submit_pending(self) -> int:
        sent = 0
        for dataset in DATASETS:
            while batch := self.state.next_batch(dataset, BATCH_SIZE):
                records = [
                    {
                        "sourceId": row["source_id"],
                        "localDate": row["local_date"],
                        "measuredAt": int(row["measured_at"]),
                        "payload": json.loads(row["payload"]),
                    }
                    for row in batch
                ]
                result = self._request(
                    "POST", "health/import", json={"dataset": dataset, "records": records}
                )
                accepted = result.get("accepted")
                unchanged = result.get("unchanged")
                if (not isinstance(accepted, int) or not isinstance(unchanged, int)
                        or accepted < 0 or unchanged < 0 or accepted + unchanged != len(records)):
                    raise RuntimeError(f"backend returned an incomplete {dataset} import acknowledgement")
                self.state.acknowledge(dataset, batch)
                sent += len(records)
        return sent

    def _publish_workout_samples(self) -> int:
        sent = 0
        recent = self._enqueue_recent_workout_heart_rate()
        if recent:
            LOG.info("recent workout heart-rate samples staged: %d", recent)
        sent += self._submit_pending()
        while True:
            staged, complete = self._enqueue_workout_backfill_batch()
            LOG.info(
                "workout heart-rate backfill batch staged=%d complete=%s",
                staged, complete,
            )
            sent += self._submit_pending()
            if complete:
                return sent

    def run_collection(self, start_date: str | None = None,
                       end_date: str | None = None) -> bool:
        if not self._local_lock.acquire(blocking=False):
            LOG.info("collection already running; this trigger is coalesced")
            return False
        try:
            with self.lock_path.open("a+b") as process_lock:
                try:
                    _try_process_lock(process_lock)
                except (BlockingIOError, PermissionError):
                    LOG.info("another collector process owns the run lock")
                    return False
                try:
                    self._record_state("started", "mi-collection")
                except Exception as exc:
                    # Backend outages do not prevent acquiring real data into the persistent outbox.
                    LOG.warning("unable to report collection start (%s)", type(exc).__name__)
                phase = "mi-collection"
                try:
                    phase = "seed-read"
                    counts = self._enqueue_local_source(start_date, end_date)
                    LOG.info("source database staged for publication: %s", counts)
                    phase = "mi-collection"
                    self._run_source_sync(start_date, end_date)
                    counts = self._enqueue_local_source(start_date, end_date)
                    LOG.info("latest source collection staged for publication: %s", counts)
                    phase = "health-submit"
                    self.check_capabilities()
                    self._record_state("started", "health-submit")
                    sent = self._publish_workout_samples()
                    self._record_state("submitted", "health-submit")
                    self._record_state("succeeded", "complete")
                    LOG.info("health submission confirmed; records acknowledged: %d", sent)
                    return True
                except Exception as exc:
                    reason = self._safe_reason(exc)
                    if phase == "mi-collection":
                        try:
                            staged = self._enqueue_local_source(start_date, end_date)
                            staged["workoutHeartRate"] = (
                                self._enqueue_recent_workout_heart_rate()
                            )
                            LOG.info("partial successful source data retained: %s", staged)
                        except Exception as stage_exc:
                            LOG.warning(
                                "unable to stage partial source data (%s)",
                                type(stage_exc).__name__,
                            )
                    LOG.error("collector run failed at phase %s: %s", phase, reason)
                    try:
                        self._record_state("failed", phase, reason)
                    except Exception as state_exc:
                        LOG.warning("unable to report failure state (%s)", type(state_exc).__name__)
                    raise
                finally:
                    try:
                        _release_process_lock(process_lock)
                    except OSError:
                        pass
        finally:
            self._local_lock.release()

    def publish_existing(self, start_date: str | None = None,
                         end_date: str | None = None) -> bool:
        """Publish actual persisted source rows without Xiaomi login or cloud access."""
        if not self._local_lock.acquire(blocking=False):
            LOG.info("collection already running; publish trigger is coalesced")
            return False
        try:
            with self.lock_path.open("a+b") as process_lock:
                try:
                    _try_process_lock(process_lock)
                except (BlockingIOError, PermissionError):
                    LOG.info("another collector process owns the run lock")
                    return False
                phase = "existing-data-publish"
                try:
                    self.check_capabilities()
                    self._record_state("started", phase)
                    counts = self._enqueue_local_source(start_date, end_date)
                    LOG.info("persisted source rows staged for publication: %s", counts)
                    sent = self._publish_workout_samples()
                    self._record_state("published", phase)
                    LOG.info("persisted health rows published; records acknowledged: %d", sent)
                    return True
                except Exception as exc:
                    reason = self._safe_reason(exc)
                    LOG.error("publish failed at phase %s: %s", phase, reason)
                    try:
                        self._record_state("failed", phase, reason)
                    except Exception as state_exc:
                        LOG.warning("unable to report publish failure (%s)", type(state_exc).__name__)
                    raise
                finally:
                    try:
                        _release_process_lock(process_lock)
                    except OSError:
                        pass
        finally:
            self._local_lock.release()

    def close(self) -> None:
        self._http.close()
        self.state.close()
