import copy
import json
import logging
import os
from pathlib import Path
from threading import RLock
from typing import Any, Dict, Optional, Tuple

from fastapi import FastAPI, HTTPException

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("document_api")


class DocumentStore:
    """Load documents from JSON files and expose them by primary key."""

    def __init__(self, directory: Path) -> None:
        self._directory = directory
        self._lock = RLock()
        self._state: Tuple[Tuple[str, int, int], ...] = ()
        self._documents: Dict[str, Dict[str, Any]] = {}

    def refresh(self, force: bool = False) -> None:
        with self._lock:
            self._maybe_reload(force=force)

    def get_document(self, document_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            self._maybe_reload()
            document = self._documents.get(document_id)
            if document is None:
                return None
            return copy.deepcopy(document)

    def count(self) -> int:
        with self._lock:
            self._maybe_reload()
            return len(self._documents)

    def _maybe_reload(self, force: bool = False) -> None:
        snapshot = self._snapshot_state()
        if not force and snapshot == self._state:
            return

        documents = self._load_documents()
        self._documents = documents
        self._state = snapshot
        logger.info("Cached %d document(s) from %s.", len(documents), self._directory)

    def _snapshot_state(self) -> Tuple[Tuple[str, int, int], ...]:
        if not self._directory.exists():
            return ()

        entries = []
        for path in self._directory.glob("*.json"):
            try:
                stat = path.stat()
            except OSError as exc:
                logger.warning("Unable to read metadata for %s: %s", path.name, exc)
                continue
            entries.append((path.name, int(stat.st_mtime_ns), stat.st_size))
        return tuple(sorted(entries))

    def _load_documents(self) -> Dict[str, Dict[str, Any]]:
        documents: Dict[str, Dict[str, Any]] = {}
        if not self._directory.exists():
            logger.warning("Document directory %s does not exist; serving empty dataset.", self._directory)
            return documents

        for path in sorted(self._directory.glob("*.json")):
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
            except FileNotFoundError:
                continue
            except json.JSONDecodeError as exc:
                logger.warning("Skipping %s - invalid JSON (%s)", path.name, exc)
                continue

            if isinstance(raw, list):
                for index, item in enumerate(raw):
                    self._store_document(documents, item, f"{path.name}#{index}")
            elif isinstance(raw, dict):
                self._store_document(documents, raw, path.name)
            else:
                logger.warning("Skipping %s - expected JSON object or array of objects.", path.name)

        return documents

    def _store_document(
        self, documents: Dict[str, Dict[str, Any]], payload: Any, source: str
    ) -> None:
        if not isinstance(payload, dict):
            logger.warning("Skipping %s - expected JSON object.", source)
            return

        primary_key = payload.get("primaryKey")
        if primary_key in (None, ""):
            logger.error("Skipping %s - missing required 'primaryKey' field.", source)
            return

        document_id = str(primary_key)
        document = dict(payload)
        document["primaryKey"] = primary_key
        document["id"] = document_id

        if document_id in documents:
            logger.debug("Overwriting document %s with contents from %s.", document_id, source)

        documents[document_id] = document


DOCUMENTS_DIR = Path(os.getenv("DOCUMENTS_DIR", "/documents")).resolve()
store = DocumentStore(DOCUMENTS_DIR)

app = FastAPI(
    title="Document API",
    description="Serve JSON documents directly from the mounted /documents directory.",
    version="1.0.0",
)


@app.on_event("startup")
async def warm_cache() -> None:
    store.refresh(force=True)


@app.get(
    "/",
    summary="API status",
    tags=["status"],
)
async def status() -> Dict[str, Any]:
    return {
        "documentsDirectory": str(DOCUMENTS_DIR),
        "documents": store.count(),
    }


@app.get(
    "/documents/{document_id}",
    response_model=Dict[str, Any],
    summary="Fetch a document by primary key",
    tags=["documents"],
)
async def fetch_document(document_id: str) -> Dict[str, Any]:
    document = store.get_document(document_id)
    if document is None:
        raise HTTPException(status_code=404, detail="Document not found")
    return document
