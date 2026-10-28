"""读取小米账号凭据。

优先级：最新的 credentials.json 或 cookie.txt。
cookie.txt 是浏览器导出的 `name=value; name=value` 文本。
"""

from __future__ import annotations

import json
from pathlib import Path


class CredentialError(RuntimeError):
    pass


def parse_cookie_text(text: str) -> dict[str, str]:
    cookies: dict[str, str] = {}
    for part in text.split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        cookies[name.strip()] = value.strip()
    return cookies


def _candidate_files(root: Path) -> list[Path]:
    return [
        root / "credentials.json",
        root / "cookie.txt",
    ]


def load_credentials(root: Path) -> tuple[str, str, Path]:
    candidates = [path for path in _candidate_files(root) if path.exists()]
    if not candidates:
        raise CredentialError(
            "没有找到凭据。请把浏览器 cookie 保存为 cookie.txt，"
            "格式是 userId=...; passToken=..."
        )

    path = max(candidates, key=lambda item: item.stat().st_mtime)
    if path.suffix == ".json":
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
            user_id = str(value["user_id"])
            pass_token = str(value["pass_token"])
        except (ValueError, KeyError, OSError) as exc:
            raise CredentialError(f"读取 {path.name} 失败: {exc}") from exc
    else:
        cookies = parse_cookie_text(path.read_text(encoding="utf-8"))
        user_id = cookies.get("userId", "")
        pass_token = cookies.get("passToken", "")
        if not user_id or not pass_token:
            raise CredentialError(
                f"{path.name} 缺少 userId 或 passToken。"
                "请从浏览器 cookie 中复制完整内容。"
            )
    return user_id, pass_token, path


def save_credentials(root: Path, user_id: str, pass_token: str) -> Path:
    path = root / "credentials.json"
    path.write_text(
        json.dumps(
            {"user_id": str(user_id), "pass_token": pass_token},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return path
