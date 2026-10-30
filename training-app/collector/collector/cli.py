from __future__ import annotations

import argparse
import logging
import signal
import sys
import threading
from datetime import datetime

from apscheduler.schedulers.blocking import BlockingScheduler

from .config import Settings
from .service import CollectorService

LOG = logging.getLogger("collector")


def _date(value: str) -> str:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError as exc:
        raise argparse.ArgumentTypeError("date must use YYYY-MM-DD") from exc
    return parsed.isoformat()


def _serve(settings: Settings) -> int:
    service = CollectorService(settings)
    scheduler = BlockingScheduler(timezone="UTC")
    shutdown = threading.Event()

    def scheduled_collection() -> None:
        service.run_collection()

    def poll_backend_request() -> None:
        try:
            if service.poll_request():
                service.run_collection()
        except Exception as exc:
            LOG.warning("backend request poll failed (%s)", type(exc).__name__)

    scheduler.add_job(
        scheduled_collection, "interval", hours=settings.interval_hours,
        id="hourly-collection", max_instances=1, coalesce=True, misfire_grace_time=3600,
    )
    scheduler.add_job(
        poll_backend_request, "interval", minutes=1,
        id="backend-request-poll", max_instances=1, coalesce=True, misfire_grace_time=60,
    )
    scheduler.add_job(scheduled_collection, "date", id="startup-collection")
    scheduler.add_job(poll_backend_request, "date", id="startup-request-poll")

    def stop(_signum: int, _frame: object) -> None:
        if not shutdown.is_set():
            shutdown.set()
            scheduler.shutdown(wait=True)

    signal.signal(signal.SIGTERM, stop)
    if hasattr(signal, "SIGINT"):
        signal.signal(signal.SIGINT, stop)
    try:
        LOG.info("collector scheduler started (hour interval=%d)", settings.interval_hours)
        scheduler.start()
    except (KeyboardInterrupt, SystemExit):
        pass
    finally:
        if scheduler.running:
            scheduler.shutdown(wait=True)
        service.close()
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Xiaomi health collector service")
    commands = parser.add_subparsers(dest="command")
    commands.add_parser("serve", help="run startup, hourly, and backend-request jobs")
    commands.add_parser("run", help="run one incremental collection and submission")
    commands.add_parser("publish", help="publish actual persisted source data without Xiaomi login")
    rescan = commands.add_parser("rescan", help="collect and resubmit a selected historical range")
    rescan.add_argument("--from", dest="start_date", required=True, type=_date)
    rescan.add_argument("--to", dest="end_date", type=_date)
    return parser


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )
    parser = build_parser()
    args = parser.parse_args(argv)
    # No command is the container's default service entry point.
    command = args.command or "serve"
    if command == "rescan" and args.end_date and args.end_date < args.start_date:
        parser.error("--to must not be earlier than --from")
    try:
        settings = Settings.from_env()
        if command == "serve":
            return _serve(settings)
        service = CollectorService(settings)
        try:
            if command == "publish":
                return 0 if service.publish_existing() else 1
            if command == "run":
                return 0 if service.run_collection() else 1
            return 0 if service.run_collection(args.start_date, args.end_date) else 1
        finally:
            service.close()
    except Exception as exc:
        LOG.error("collector command failed (%s): %s", type(exc).__name__, exc)
        return 1
