"""小米运动健康云端客户端。

签名和登录流程来自社区已验证实现 mi-fitness-mcp-cn（MIT）。
只做认证、分页取数和 FDS 文件下载，不解析业务数据。
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import struct
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable
from urllib.parse import urlencode

import httpx
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

CN_TZ = timezone(timedelta(hours=8))
LOGIN_PREFIX = "&&&START&&&"
AES_IV = b"1234567887654321"
MIN_REQUEST_INTERVAL = 0.3
MAX_PAGES = 200


class AuthError(RuntimeError):
    pass


class MiClient:
    def __init__(self, user_id: str, pass_token: str, region: str = "cn") -> None:
        self.user_id = str(user_id)
        self.pass_token = pass_token
        self.region = region
        self.base_url = (
            "https://hlth.io.mi.com"
            if region in ("", "cn")
            else f"https://{region}.hlth.io.mi.com"
        )
        self.ssecurity = b""
        self.cookies = ""
        self.client = httpx.Client(timeout=30.0, follow_redirects=False, trust_env=False)
        self._last_request_at = 0.0

    def close(self) -> None:
        self.client.close()

    def login(self) -> tuple[str, str]:
        response = self.client.get(
            "https://account.xiaomi.com/pass/serviceLogin?_json=true&sid=miothealth",
            headers={"Cookie": f"userId={self.user_id}; passToken={self.pass_token}"},
        )
        response.raise_for_status()
        text = response.text
        if not text.startswith(LOGIN_PREFIX):
            raise AuthError("小米登录响应格式不符合预期，passToken 可能已经失效")
        payload = json.loads(text[len(LOGIN_PREFIX) :])
        required = ("passToken", "userId", "ssecurity", "location")
        missing = [name for name in required if not payload.get(name)]
        if missing:
            raise AuthError(f"小米登录响应缺少字段: {', '.join(missing)}")

        self.pass_token = payload["passToken"]
        self.user_id = str(payload["userId"])
        self.ssecurity = base64.b64decode(payload["ssecurity"])

        redirect = self.client.get(payload["location"])
        redirect.raise_for_status()
        cookie_parts = [value.split(";", 1)[0] for value in redirect.headers.get_list("set-cookie")]
        if not any(
            part.startswith("serviceToken=") and part != "serviceToken="
            for part in cookie_parts
        ):
            raise AuthError("登录重定向没有返回 serviceToken")
        self.cookies = "; ".join(cookie_parts)
        return self.user_id, self.pass_token

    def request(
        self, path: str, payload: dict[str, Any], sign_path: str | None = None
    ) -> tuple[dict, dict]:
        signature_path = sign_path or path
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                self._throttle()
                form = {"data": json.dumps(payload, separators=(",", ":"))}
                nonce = _gen_nonce()
                signed_nonce = _gen_signed_nonce(self.ssecurity, nonce)
                form["rc4_hash__"] = _signature("POST", signature_path, form, signed_nonce)

                encrypted: dict[str, str] = {}
                for key, value in form.items():
                    encrypted[key] = base64.b64encode(
                        _rc4(signed_nonce, value.encode())
                    ).decode()
                encrypted["signature"] = _signature(
                    "POST", signature_path, encrypted, signed_nonce
                )
                encrypted["_nonce"] = base64.b64encode(nonce).decode()

                response = self.client.post(
                    self.base_url + path,
                    headers={
                        "Cookie": self.cookies,
                        "Content-Type": "application/x-www-form-urlencoded",
                    },
                    content=urlencode(encrypted),
                )
                response.raise_for_status()
                plaintext = _rc4(signed_nonce, base64.b64decode(response.text))
                body = json.loads(plaintext)
                if body.get("code") != 0:
                    message = str(body.get("message", "unknown error"))
                    if _is_auth_error(body.get("code"), message):
                        raise AuthError(message)
                    raise RuntimeError(f"云端返回错误 code={body.get('code')}: {message}")
                return body, body.get("result", {}) or {}
            except AuthError:
                raise
            except Exception as exc:
                last_error = exc
                retryable = isinstance(exc, (httpx.TimeoutException, httpx.NetworkError))
                if isinstance(exc, httpx.HTTPStatusError):
                    retryable = exc.response.status_code == 429 or exc.response.status_code >= 500
                if attempt == 2 or not retryable:
                    break
                time.sleep(min(4.0, 0.5 * (2**attempt)))
        raise RuntimeError(f"请求失败: {last_error}") from last_error

    def fetch_key(
        self,
        key: str,
        start_date: str,
        end_date: str,
        on_page: Callable[[int, dict], None] | None = None,
    ) -> list[dict]:
        start_ts, end_ts = date_range_timestamps(start_date, end_date)
        next_key: str | None = None
        seen: set[str] = set()
        items: list[dict] = []
        page_no = 0
        while True:
            page_no += 1
            if page_no > MAX_PAGES:
                raise RuntimeError("分页超过安全上限")
            payload: dict[str, Any] = {
                "start_time": start_ts,
                "end_time": end_ts,
                "key": key,
            }
            if next_key:
                payload["next_key"] = next_key
            body, result = self.request("/app/v1/data/get_fitness_data_by_time", payload)
            if on_page:
                on_page(page_no, body)
            items.extend(result.get("data_list", []) or [])
            if not result.get("has_more") or not result.get("next_key"):
                break
            candidate = str(result["next_key"])
            if candidate in seen:
                raise RuntimeError("分页游标出现循环")
            seen.add(candidate)
            next_key = candidate
        return items

    def fetch_sport_records(
        self,
        start_date: str,
        end_date: str,
        on_page: Callable[[int, dict], None] | None = None,
    ) -> list[dict]:
        start_ts, end_ts = date_range_timestamps(start_date, end_date)
        next_key: str | None = None
        seen: set[str] = set()
        items: list[dict] = []
        page_no = 0
        while True:
            page_no += 1
            if page_no > MAX_PAGES:
                raise RuntimeError("运动记录分页超过安全上限")
            payload: dict[str, Any] = {
                "start_time": start_ts,
                "end_time": end_ts,
                "limit": 50,
            }
            if next_key:
                payload["next_key"] = next_key
            body, result = self.request("/app/v1/data/get_sport_records_by_time", payload)
            if on_page:
                on_page(page_no, body)
            items.extend(result.get("sport_records", []) or [])
            if not result.get("has_more") or not result.get("next_key"):
                break
            candidate = str(result["next_key"])
            if candidate in seen:
                raise RuntimeError("运动记录分页游标出现循环")
            seen.add(candidate)
            next_key = candidate
        return items

    def download_fds(self, url: str, obj_key: str) -> bytes:
        response = self.client.get(url)
        response.raise_for_status()
        raw = response.text.strip()
        if raw.startswith('"') and raw.endswith('"'):
            raw = raw[1:-1]
        ciphertext = _b64url_decode(raw)
        key = _b64url_decode(obj_key)
        if len(key) != 16:
            raise RuntimeError(f"FDS obj_key 长度不是 16 字节: {len(key)}")
        decryptor = Cipher(algorithms.AES(key), modes.CBC(AES_IV)).decryptor()
        padded = decryptor.update(ciphertext) + decryptor.finalize()
        pad_len = padded[-1]
        if not 1 <= pad_len <= 16 or padded[-pad_len:] != bytes((pad_len,)) * pad_len:
            raise RuntimeError("FDS 解密后的 PKCS7 填充不合法")
        return padded[:-pad_len]

    def _throttle(self) -> None:
        elapsed = time.time() - self._last_request_at
        if elapsed < MIN_REQUEST_INTERVAL:
            time.sleep(MIN_REQUEST_INTERVAL - elapsed)
        self._last_request_at = time.time()


def _rc4(key: bytes, payload: bytes) -> bytes:
    state = list(range(256))
    j = 0
    for i in range(256):
        j = (j + state[i] + key[i % len(key)]) % 256
        state[i], state[j] = state[j], state[i]
    i = 0
    j = 0

    def next_byte() -> int:
        nonlocal i, j
        i = (i + 1) % 256
        j = (j + state[i]) % 256
        state[i], state[j] = state[j], state[i]
        return state[(state[i] + state[j]) % 256]

    for _ in range(1024):
        next_byte()
    return bytes(value ^ next_byte() for value in payload)


def _gen_nonce() -> bytes:
    return os.urandom(8) + struct.pack(">I", int(time.time() // 60))


def _gen_signed_nonce(ssecurity: bytes, nonce: bytes) -> bytes:
    return hashlib.sha256(ssecurity + nonce).digest()


def _signature(method: str, path: str, values: dict[str, str], signed_nonce: bytes) -> str:
    base = method + "&" + path + "&data=" + values["data"]
    if "rc4_hash__" in values:
        base += "&rc4_hash__=" + values["rc4_hash__"]
    base += "&" + base64.b64encode(signed_nonce).decode()
    return base64.b64encode(hashlib.sha1(base.encode()).digest()).decode()


def _is_auth_error(code: Any, message: str) -> bool:
    if code in {401, 403, -6, -10001}:
        return True
    markers = (
        "authentication failed",
        "invalid credential",
        "invalid pass token",
        "invalid passtoken",
        "login required",
        "not logged in",
        "session expired",
        "unauthorized",
    )
    return any(marker in message.casefold() for marker in markers)


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def date_range_timestamps(start_date: str, end_date: str) -> tuple[int, int]:
    start = datetime.fromisoformat(start_date).replace(tzinfo=CN_TZ)
    end = datetime.fromisoformat(end_date).replace(tzinfo=CN_TZ) + timedelta(days=1, seconds=-1)
    return int(start.timestamp()), int(end.timestamp())
