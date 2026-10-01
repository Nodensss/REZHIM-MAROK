"""Export every current database table and the retained recognition work to one JSON."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import sqlite3
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "output" / "VSE-MARKI-VSE-NARABOTKI.json"


def json_value(value):
    if isinstance(value, bytes):
        return {"encoding": "base64", "data": base64.b64encode(value).decode("ascii")}
    return value


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path: Path):
    hash_value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hash_value.update(chunk)
    return hash_value.hexdigest()


def main():
    connection = sqlite3.connect(ROOT / "data" / "rezhim.sqlite3")
    connection.row_factory = sqlite3.Row
    connection.execute("BEGIN")
    names = [row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
    )]
    tables = {}
    for name in names:
        safe_name = '"' + name.replace('"', '""') + '"'
        tables[name] = [
            {key: json_value(row[key]) for key in row.keys()}
            for row in connection.execute(f"SELECT * FROM {safe_name} ORDER BY rowid")
        ]
    connection.rollback()
    connection.close()

    sources_by_path = {item["path"]: item for item in tables["source"]}
    source_files = []
    stored_source_json = []
    for path in sorted((ROOT / "data" / "sources").iterdir()):
        if not path.is_file():
            continue
        source = sources_by_path.get(path.name)
        item = {
            "file": path.name,
            "bytes": path.stat().st_size,
            "sha256": path.stem if re.fullmatch(r"[0-9a-f]{64}", path.stem) else None,
            "original_name": source["name"] if source else None,
            "source_id": source["id"] if source else None,
            "kind": source["kind"] if source else None,
        }
        source_files.append(item)
        if path.suffix.lower() == ".json":
            stored_source_json.append({**item, "content": read_json(path)})

    imported_recognitions = {
        item["name"] for item in tables["source"] if item["kind"] == "ai_json"
    }
    recognition_files = []
    for path in sorted((ROOT / "output").glob("recognition-*.json")):
        recognition_files.append({
            "file": path.name,
            "sha256": digest(path),
            "imported_to_database": path.name in imported_recognitions,
            "content": read_json(path),
        })

    other_work = []
    for name in ("migration-15803-020.json", "migration-result.json"):
        path = ROOT / name
        other_work.append({"file": name, "content": read_json(path)})
    path = ROOT / "output" / "rezhim-15803-020.json"
    other_work.append({"file": "output/" + path.name, "content": read_json(path)})

    screen_templates = {
        path.name: read_json(path)
        for path in sorted((ROOT / "screen_templates").glob("*.json"))
    }
    ai_dataset = read_json(ROOT / "mobile" / "ai-dataset.json")
    statuses = dict(Counter(item["status"] for item in tables["measurement"]))

    result = {
        "format": "rezhim-all-work",
        "schema_version": 1,
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
        "description": "Все марки, замеры, техкарты, распознавания, промежуточные варианты, источники и история изменений из рабочей базы.",
        "includes_unreviewed": True,
        "grade_names": [item["name"] for item in tables["grade"]],
        "counts": {
            "database_tables": {name: len(rows) for name, rows in tables.items()},
            "measurement_statuses": statuses,
            "recognition_files": len(recognition_files),
            "recognition_files_not_imported": sum(
                not item["imported_to_database"] for item in recognition_files
            ),
            "source_files": len(source_files),
        },
        "data": tables,
        "readable_observations": ai_dataset,
        "recognition_files": recognition_files,
        "stored_source_json": stored_source_json,
        "legacy_work": other_work,
        "screen_templates": screen_templates,
        "pending_grade_report": (ROOT / "output" / "recognition-pending-grade-report.md").read_text(encoding="utf-8"),
        "source_files": source_files,
        "source_files_note": "Фотографии JPEG и HTML перечислены с метаданными; двоичное содержимое файлов не включено в JSON.",
    }

    assert len(tables["grade"]) == 5
    assert len(ai_dataset["observations"]) == len(tables["observation"])
    assert sum(statuses.values()) == len(tables["measurement"])
    assert all(item["content"] is not None for item in recognition_files)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".json.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
    os.replace(temporary, OUTPUT)
    print(OUTPUT)
    print(f"bytes={OUTPUT.stat().st_size}")
    print(f"sha256={digest(OUTPUT)}")
    print("counts=" + json.dumps(result["counts"], ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
