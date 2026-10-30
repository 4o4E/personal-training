#!/usr/bin/env python3
"""Collect the public SmartWorkout Chinese exercise guides from their sitemap."""
from __future__ import annotations

import concurrent.futures
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

BASE = "https://smartworkout.app"
INDEX_URL = f"{BASE}/sitemap.xml"
PREFIX = "/zh/健身练习库/"
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "data" / "smartworkout" / "source.json"
USER_AGENT = "SmartWorkoutGuideResearchCollector/1.0 (public sitemap collection; conservative requests)"
WORKERS = 12
REQUEST_INTERVAL_SECONDS = 1.0
TIMEOUT_SECONDS = 20
_request_lock = threading.Lock()
_next_request_at = 0.0


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def get(url: str) -> tuple[int, bytes, str]:
    global _next_request_at
    parsed = urllib.parse.urlsplit(url)
    request_url = urllib.parse.urlunsplit((
        parsed.scheme,
        parsed.netloc,
        urllib.parse.quote(urllib.parse.unquote(parsed.path), safe="/:@!$&'()*+,;=-._~"),
        urllib.parse.quote(urllib.parse.unquote(parsed.query), safe="=&?/:@!$'()*+,;%-._~"),
        "",
    ))
    request = urllib.request.Request(
        request_url,
        headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/xml;q=0.9,*/*;q=0.8"},
    )
    for attempt in range(4):
        try:
            with _request_lock:
                delay = _next_request_at - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                _next_request_at = time.monotonic() + REQUEST_INTERVAL_SECONDS
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return response.status, response.read(), response.geturl()
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError):
            if attempt == 3:
                raise
            time.sleep(2 ** (attempt + 1))
    raise AssertionError("unreachable")


def sitemap_urls() -> tuple[list[str], list[dict]]:
    status, body, final_url = get(INDEX_URL)
    if status != 200:
        raise RuntimeError(f"sitemap index returned HTTP {status}")
    index = ET.fromstring(body)
    locs = [node.text.strip() for node in index.iter() if node.tag.endswith("loc") and node.text]
    shard_urls = list(dict.fromkeys(locs))
    collected: list[str] = []
    excluded_routes: list[str] = []
    shards: list[dict] = []
    for shard_url in shard_urls:
        shard_status, shard_body, shard_final_url = get(shard_url)
        if shard_status != 200:
            raise RuntimeError(f"sitemap shard returned HTTP {shard_status}: {shard_url}")
        root = ET.fromstring(shard_body)
        urls = [node.text.strip() for node in root.iter() if node.tag.endswith("loc") and node.text]
        zh_detail_urls: list[str] = []
        zh_non_detail_urls: list[str] = []
        for raw_url in urls:
            parsed = urllib.parse.urlsplit(raw_url)
            decoded_path = urllib.parse.unquote(parsed.path).rstrip("/")
            if decoded_path == PREFIX.rstrip("/") or decoded_path.startswith(PREFIX):
                route = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, decoded_path, parsed.query, ""))
                segments = [part for part in decoded_path.split("/") if part]
                if decoded_path.startswith(PREFIX) and len(segments) >= 4:
                    zh_detail_urls.append(route)
                else:
                    zh_non_detail_urls.append(route)
        collected.extend(zh_detail_urls)
        excluded_routes.extend(zh_non_detail_urls)
        shards.append({"url": shard_url, "finalUrl": shard_final_url, "httpStatus": shard_status,
                       "urlCount": len(urls), "chineseExerciseUrlCount": len(zh_detail_urls),
                       "chineseNonDetailRouteCount": len(zh_non_detail_urls),
                       "chineseNonDetailRoutes": zh_non_detail_urls})
    canonical_urls = sorted(set(collected))
    duplicate_count = len(collected) - len(canonical_urls)
    return canonical_urls, [{"indexUrl": INDEX_URL, "finalUrl": final_url, "httpStatus": status,
                             "fetchedAt": now_utc(), "shards": shards,
                             "chineseRouteUrlCount": len(collected) + len(excluded_routes),
                             "chineseExerciseUrlCount": len(collected),
                             "uniqueChineseExerciseUrlCount": len(canonical_urls),
                             "chineseNonDetailRouteCount": len(excluded_routes),
                             "chineseNonDetailRoutes": sorted(set(excluded_routes)),
                             "nonDetailRouteExclusionReason": "Not an exercise-detail route: fewer than four path segments.",
                             "duplicateChineseExerciseUrlCount": duplicate_count}]


class NextDataParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.in_next = False
        self.capture = False
        self.parts: list[str] = []
        self.meta: dict[str, str] = {}
        self.canonical: str | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "script" and values.get("id") == "__NEXT_DATA__":
            self.in_next = True
        elif tag == "meta" and values.get("property") in ("og:image", "og:video", "og:video:url"):
            key = values["property"] or ""
            content = values.get("content")
            if content:
                self.meta[key] = content
        elif tag == "link" and values.get("rel") == "canonical":
            self.canonical = values.get("href")

    def handle_endtag(self, tag: str) -> None:
        if tag == "script" and self.in_next:
            self.in_next = False

    def handle_data(self, data: str) -> None:
        if self.in_next:
            self.parts.append(data)


def canonical_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    path = urllib.parse.unquote(parsed.path).rstrip("/")
    return urllib.parse.urlunsplit((parsed.scheme.lower(), parsed.netloc.lower(), path, "", ""))


def guide_record(sitemap_url: str, body: bytes, final_url: str) -> dict:
    parser = NextDataParser()
    parser.feed(body.decode("utf-8", "replace"))
    if not parser.parts:
        raise ValueError("HTML has no __NEXT_DATA__ payload")
    payload = json.loads("".join(parser.parts))
    page = payload.get("props", {}).get("pageProps", {})
    exercise = page.get("exercise")
    if not isinstance(exercise, dict):
        raise ValueError("__NEXT_DATA__ has no exercise detail object")
    translations = exercise.get("translations") or {}
    raw_source_url = parser.canonical or urllib.parse.urljoin(BASE, page.get("path") or final_url or sitemap_url)
    source_url = canonical_url(raw_source_url)
    # The source page's names and category identifiers are retained as published;
    # missing fields remain null/empty rather than being inferred.
    source_page_data = {key: value for key, value in page.items()
                        if key not in ("_nextI18Next", "exercise")}
    return {
        "id": exercise.get("id"),
        "sourceUrl": source_url,
        "sitemapUrl": sitemap_url,
        "names": {"zh": translations.get("zh"), "en": translations.get("en")},
        "aliases": exercise.get("aliases"),
        "description": (exercise.get("descriptions") or {}).get("zh"),
        "steps": (exercise.get("instructions") or {}).get("zh"),
        "muscles": {
            "bodyPart": exercise.get("bodyPart"),
            "scores": exercise.get("exerciseMuscles"),
        },
        "categories": exercise.get("tags"),
        "equipmentCategories": exercise.get("equipments"),
        "weightType": exercise.get("weightType"),
        "mechanics": exercise.get("mechanics"),
        "laterality": exercise.get("laterality"),
        "tips": (exercise.get("tips") or {}).get("zh"),
        "commonMistakes": (exercise.get("commonMistakes") or {}).get("zh"),
        "recommendedExercises": [recommendation_reference(item)
                                for item in page.get("recommended", [])],
        "plans": page.get("plans"),
        "imageUrl": (exercise.get("image") or {}).get("uri"),
        "thumbnailUrls": list(dict.fromkeys(
            image.get("uri") for image in (
                exercise.get("thumbnail1"), exercise.get("thumbnail2")
            ) if isinstance(image, dict) and image.get("uri")
        )),
        "videoUrls": list(dict.fromkeys(url for url in (
            exercise.get("videoDarkUrl"), exercise.get("videoLightUrl"), parser.meta.get("og:video"),
            parser.meta.get("og:video:url")) if url)),
        "pageMetadata": {"canonicalUrl": parser.canonical, "meta": parser.meta},
        "sourceExerciseFieldNames": sorted(exercise),
        "sourceExercise": exercise,
        "sourcePageFieldNames": sorted(source_page_data),
        "sourcePageData": source_page_data,
    }


def read_output() -> dict:
    if OUTPUT.exists():
        try:
            data = json.loads(OUTPUT.read_text(encoding="utf-8"))
            if isinstance(data, dict) and isinstance(data.get("exercises"), list):
                return data
        except (OSError, json.JSONDecodeError):
            pass
    return {"source": "SmartWorkout public Chinese exercise guide pages",
            "sitemaps": [], "sitemapCrossCheck": {}, "collectedAt": None,
            "exercises": [], "failures": []}


def write_output(data: dict) -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".json.part")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(OUTPUT)


def recommendation_reference(item: object) -> object:
    if not isinstance(item, dict):
        return item
    path = item.get("path")
    source_url = urllib.parse.urljoin(BASE, path) if path else item.get("sourceUrl") or item.get("url")
    return {
        "id": item.get("id"),
        "sourceUrl": source_url,
        "names": item.get("translations") or item.get("names", {}),
    }


def fetch_record(sitemap_url: str) -> dict:
    status, body, final_url = get(sitemap_url)
    if status != 200:
        raise RuntimeError(f"HTTP {status}")
    return guide_record(sitemap_url, body, final_url)


def record_failure(data: dict, key: str, url: str, error: Exception, retry: bool = False) -> None:
    data["failures"] = [failure for failure in data["failures"]
                        if canonical_url(failure.get("sitemapUrl", "")) != key]
    failure = {"sitemapUrl": url, "attemptedAt": now_utc(),
               "error": f"{type(error).__name__}: {error}"}
    if retry:
        failure["retryAttemptedAt"] = now_utc()
        failure["retryError"] = failure["error"]
    data["failures"].append(failure)
    data["failures"].sort(key=lambda value: canonical_url(value.get("sitemapUrl", "")))


def record_success(data: dict, key: str, url: str, record: dict) -> None:
    record_key = canonical_url(record["sourceUrl"])
    record["sitemapUrl"] = url
    record["recommendedExercises"] = [
        recommendation_reference(rec) for rec in record.get("recommendedExercises", [])
    ]
    prior = next((i for i, old in enumerate(data["exercises"])
                  if canonical_url(old.get("sourceUrl", "")) == record_key), None)
    if prior is None:
        data["exercises"].append(record)
    else:
        data["exercises"][prior] = record
    data["exercises"].sort(key=lambda item: canonical_url(item.get("sourceUrl", "")))
    data["failures"] = [failure for failure in data["failures"]
                        if canonical_url(failure.get("sitemapUrl", "")) != key]


def update_crosscheck(data: dict, sitemap_details: list[dict], expected: dict) -> set[str]:
    fetched = {canonical_url(item.get("sitemapUrl") or item.get("sourceUrl") or "")
               for item in data["exercises"]}
    raw_source_keys = {
        canonical_url(item.get("sitemapUrl") or item.get("sourceUrl") or "")
        for item in data["exercises"]
        if isinstance(item.get("sourceExercise"), dict) and isinstance(item.get("sourcePageData"), dict)
    }
    expected_keys = set(expected)
    data["sitemapCrossCheck"] = {
        "sitemapUniqueUrlCount": len(expected_keys),
        "successfulDetailCount": len(fetched & expected_keys),
        "missingDetailCount": len(expected_keys - fetched),
        "completeRawSourceRecordCount": len(raw_source_keys & expected_keys),
        "missingRawSourceRecordCount": len(expected_keys - raw_source_keys),
        "sitemapUrlsMissingRawSourceData": sorted(expected_keys - raw_source_keys),
        "failedDetailCount": len(data["failures"]),
        "sitemapUrlsNotInDetails": sorted(expected_keys - fetched),
        "detailCanonicalUrlsNotInSitemap": sorted(fetched - expected_keys),
        "nonDetailRouteCount": sitemap_details[0]["chineseNonDetailRouteCount"],
        "excludedNonDetailRoutes": sitemap_details[0]["chineseNonDetailRoutes"],
        "nonDetailRouteExclusionReason": sitemap_details[0]["nonDetailRouteExclusionReason"],
        "duplicateSitemapUrlCount": sitemap_details[0]["duplicateChineseExerciseUrlCount"],
    }
    data["sitemaps"] = sitemap_details
    data["collectedAt"] = now_utc()
    return fetched


def main() -> int:
    urls, sitemap_details = sitemap_urls()
    data = read_output()
    by_url: dict[str, dict] = {}
    for item in data["exercises"]:
        key = canonical_url(item.get("sourceUrl") or item.get("sitemapUrl") or "")
        if key:
            item["recommendedExercises"] = [
                recommendation_reference(rec) for rec in item.get("recommendedExercises", [])
            ]
            by_url[key] = item
    expected = {canonical_url(url): url for url in urls}
    data["exercises"] = [by_url[key] for key in sorted(by_url)]
    failures_by_url = {canonical_url(item.get("sitemapUrl", "")): item
                       for item in data.get("failures", []) if item.get("sitemapUrl")}
    data["failures"] = [item for key, item in failures_by_url.items() if key in expected]
    done = {
        canonical_url(item.get("sitemapUrl") or item.get("sourceUrl") or "")
        for item in data["exercises"]
        if isinstance(item.get("sourceExercise"), dict)
        and isinstance(item.get("sourcePageData"), dict)
        and "pageMetadata" in item
    }
    pending = [(key, url) for key, url in expected.items() if key not in done]
    completed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as executor:
        futures = {executor.submit(fetch_record, url): (key, url) for key, url in pending}
        for future in concurrent.futures.as_completed(futures):
            key, url = futures[future]
            try:
                record_success(data, key, url, future.result())
            except Exception as exc:
                record_failure(data, key, url, exc)
            completed += 1
            update_crosscheck(data, sitemap_details, expected)
            if completed % 10 == 0 or completed == len(pending):
                write_output(data)
            if completed % 50 == 0 or completed == len(pending):
                print(f"{len(done) + completed}/{len(expected)} processed; "
                      f"{len(data['exercises'])} successful, {len(data['failures'])} failed", flush=True)
    write_output(data)

    retry_items = list(data["failures"])
    if retry_items:
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as executor:
            futures = {executor.submit(fetch_record, item["sitemapUrl"]): item for item in retry_items}
            for future in concurrent.futures.as_completed(futures):
                item = futures[future]
                key = canonical_url(item["sitemapUrl"])
                try:
                    record_success(data, key, item["sitemapUrl"], future.result())
                except Exception as exc:
                    record_failure(data, key, item["sitemapUrl"], exc, retry=True)
                update_crosscheck(data, sitemap_details, expected)
                write_output(data)

    fetched = update_crosscheck(data, sitemap_details, expected)
    write_output(data)
    print(json.dumps({"output": str(OUTPUT), "sitemapUniqueUrlCount": len(expected),
                      "successfulDetailCount": len(fetched & set(expected)),
                      "failedDetailCount": len(data["failures"]),
                      "completeRawSourceRecordCount": data["sitemapCrossCheck"]["completeRawSourceRecordCount"],
                      "missingRawSourceRecordCount": data["sitemapCrossCheck"]["missingRawSourceRecordCount"],
                      "crossCheck": data["sitemapCrossCheck"]}, ensure_ascii=False))
    return 0 if (
        not data["failures"]
        and not (set(expected) - fetched)
        and not data["sitemapCrossCheck"]["missingRawSourceRecordCount"]
    ) else 1
if __name__ == "__main__":
    raise SystemExit(main())
