#!/usr/bin/env python3
"""通过应用使用的 /v1 接口操作训练资料；凭据仅从环境或专用 token 文件读取。"""
import argparse
import json
import mimetypes
import os
from pathlib import Path
import shutil
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, quote, urlsplit
from urllib.request import Request, urlopen

KINDS = ("muscle", "media", "exercise", "gym", "equipment", "program", "plan", "session", "arrangement", "activity")


class Client:
    def __init__(self):
        self.url = os.environ.get("TRAINING_URL", "").rstrip("/")
        self.token = os.environ.get("TRAINING_TOKEN", "")
        token_file = os.environ.get("TRAINING_TOKEN_FILE")
        if not self.token and token_file:
            self.token = Path(token_file).read_text(encoding="utf-8").strip()
        parsed = urlsplit(self.url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("请在 TRAINING_URL 配置实际后端地址")
        if not self.token:
            raise ValueError("请通过 TRAINING_TOKEN 或 TRAINING_TOKEN_FILE 配置连接 token")

    def request(self, method, path, data=None, content_type="application/json", output=None):
        headers = {"Authorization": f"Bearer {self.token}"}
        if data is not None:
            headers["Content-Type"] = content_type
        request = Request(self.url + path, data=data, headers=headers, method=method)
        with urlopen(request, timeout=120) as response:
            if output is not None:
                target = Path(output)
                temporary = target.with_name(target.name + ".part")
                try:
                    with temporary.open("wb") as stream:
                        shutil.copyfileobj(response, stream)
                    temporary.replace(target)
                finally:
                    temporary.unlink(missing_ok=True)
                return {"saved": str(target.resolve())}
            body = response.read()
            return json.loads(body) if body else {"status": response.status}


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def endpoint(kind, identity):
    return f"/v1/documents/{kind}/{quote(identity, safe='')}"


def parser():
    result = argparse.ArgumentParser(description="个人训练应用开放接口工具")
    sub = result.add_subparsers(dest="command", required=True)
    listing = sub.add_parser("list", help="读取资料，可包含删除标记")
    listing.add_argument("kind", choices=KINDS, nargs="?")
    listing.add_argument("--include-deleted", action="store_true")
    get = sub.add_parser("get", help="读取单份资料和修订号")
    get.add_argument("kind", choices=KINDS)
    get.add_argument("id")
    put = sub.add_parser("put", help="使用读取时的修订号保存资料")
    put.add_argument("kind", choices=KINDS)
    put.add_argument("id")
    put.add_argument("--file", required=True)
    put.add_argument("--base-revision", type=int)
    delete = sub.add_parser("delete", help="使用明确修订号删除资料")
    delete.add_argument("kind", choices=KINDS)
    delete.add_argument("id")
    delete.add_argument("--base-revision", type=int, required=True)
    sync = sub.add_parser("sync", help="提交完整 SyncRequest JSON，并读取修订变化")
    sync.add_argument("--file", required=True)
    history = sub.add_parser("history", help="按真实训练开始日期查询，日期为 UTC+8")
    history.add_argument("--from", dest="from_date")
    history.add_argument("--to", dest="to_date")
    recommend = sub.add_parser("recommend", help="按部位、已有动作和健身房查找训练或拉伸")
    recommend.add_argument("--type", choices=("strength", "stretch"), default="strength")
    recommend.add_argument("--muscles", help="部位 ID，逗号分隔")
    recommend.add_argument("--exercises", help="已有动作 ID，逗号分隔")
    recommend.add_argument("--gym")
    next_training = sub.add_parser("next", help="查询分化方案的下一项训练")
    next_training.add_argument("program_id")
    export = sub.add_parser("export", help="导出含版本化 data.json 和素材的 ZIP")
    export.add_argument("file")
    import_data = sub.add_parser("import", help="导入 ZIP，默认不替换已有不同资料")
    import_data.add_argument("file")
    import_data.add_argument("--replace", action="store_true")
    upload = sub.add_parser("upload", help="上传独立图片；另行保存 media 资料与引用")
    upload.add_argument("file")
    upload.add_argument("--name")
    download = sub.add_parser("download", help="下载独立素材")
    download.add_argument("name")
    download.add_argument("file")
    return result


def run(args, client):
    if args.command == "list":
        query = {"includeDeleted": str(args.include_deleted).lower()}
        if args.kind:
            query["kind"] = args.kind
        return client.request("GET", "/v1/documents?" + urlencode(query))
    if args.command == "get":
        return client.request("GET", endpoint(args.kind, args.id))
    if args.command == "put":
        value = json.loads(Path(args.file).read_text(encoding="utf-8-sig"))
        if "kind" in value and "data" in value:
            if value["kind"] != args.kind or value["id"] != args.id:
                raise ValueError("文件中的资料类型及标识必须与命令一致")
            base = args.base_revision if args.base_revision is not None else value.get("revision")
            document = value
        else:
            base = args.base_revision
            document = {"kind": args.kind, "id": args.id, "data": value, "revision": base, "deleted": False}
        if base is None or base < 0:
            raise ValueError("必须使用读取时的 revision；新资料明确指定 --base-revision 0")
        response = client.request("PUT", endpoint(args.kind, args.id), encoded({"document": document, "baseRevision": base}))
        return {key: response[key] for key in ("accepted", "conflicts")}
    if args.command == "delete":
        response = client.request("DELETE", endpoint(args.kind, args.id) + "?" + urlencode({"revision": args.base_revision}))
        return {key: response[key] for key in ("accepted", "conflicts")}
    if args.command == "sync":
        request = json.loads(Path(args.file).read_text(encoding="utf-8-sig"))
        return client.request("POST", "/v1/sync", encoded(request))
    if args.command == "history":
        query = {key: value for key, value in (("from", args.from_date), ("to", args.to_date)) if value}
        return client.request("GET", "/v1/history?" + urlencode(query))
    if args.command == "recommend":
        query = {key: value for key, value in (("type", args.type), ("muscles", args.muscles), ("exercises", args.exercises), ("gymId", args.gym)) if value}
        return client.request("GET", "/v1/recommendations?" + urlencode(query))
    if args.command == "next":
        return client.request("GET", f"/v1/programs/{quote(args.program_id, safe='')}/next")
    if args.command == "export":
        return client.request("GET", "/v1/export", output=args.file)
    if args.command == "import":
        with Path(args.file).open("rb") as stream:
            return client.request("POST", "/v1/import?" + urlencode({"replace": str(args.replace).lower()}), stream, "application/zip")
    if args.command == "upload":
        file = Path(args.file)
        name = args.name or file.name
        with file.open("rb") as stream:
            return client.request("PUT", "/v1/files/" + quote(name, safe=""), stream, mimetypes.guess_type(name)[0] or "application/octet-stream")
    if args.command == "download":
        return client.request("GET", "/v1/files/" + quote(args.name, safe=""), output=args.file)
    raise ValueError("不支持该操作")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    args = parser().parse_args()
    try:
        value = run(args, Client())
        print(json.dumps(value, ensure_ascii=False, indent=2))
        return 3 if isinstance(value, dict) and value.get("conflicts") else 0
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        print(json.dumps({"status": error.code, "response": body}, ensure_ascii=False), file=sys.stderr)
        return 3 if error.code == 409 else 1
    except (ValueError, OSError, URLError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
