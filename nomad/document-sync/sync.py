#!/usr/bin/env python3
"""Replicate JSON documents into configured data sources."""

import json
import logging
import os
import socket
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Set, Tuple
from urllib.parse import urlparse

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError
from pymongo import MongoClient
from pymongo.errors import PyMongoError
from pymongo.uri_parser import parse_uri
from watchdog.events import FileMovedEvent, FileSystemEvent, FileSystemEventHandler
from watchdog.observers import Observer
from watchdog.observers.api import BaseObserver
from watchdog.observers.polling import PollingObserver

from kafka import KafkaProducer
from kafka.errors import KafkaError, NoBrokersAvailable

import happybase

DocumentRecord = Tuple[str, Dict[str, Any], str]

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

WAIT_SECONDS = int(os.getenv("REPLICATOR_WAIT_SECONDS", "60"))
DNS_WAIT_SECONDS = int(os.getenv("REPLICATOR_DNS_WAIT_SECONDS", "15"))
CHANGE_DEBOUNCE_SECONDS = float(os.getenv("REPLICATOR_CHANGE_DEBOUNCE_SECONDS", "1.0"))

# Default to polling to ensure host bind mounts reliably trigger change notifications.
OBSERVER_MODE = os.getenv("REPLICATOR_OBSERVER_MODE", "polling").strip().lower()


DATA_SOURCE_PROFILE_NAMES = {"mongo", "s3", "hbase"}


def _split_profile_list(value: str) -> List[str]:
    return [profile.lower() for profile in value.split(',')]


def _load_data_source_profiles() -> Optional[Set[str]]:
    env_data_sources = os.getenv("REPLICATOR_ONLINE_DATA_SOURCES")

    if not env_data_sources:
        return set()

    configured_profiles = _split_profile_list(env_data_sources)

    recognized_profiles = {profile for profile in configured_profiles if profile in DATA_SOURCE_PROFILE_NAMES}
    ignored = sorted(set(configured_profiles) - recognized_profiles)

    message = (
        "Restricting document replication to data sources active in %s: %s."
        if recognized_profiles
        else "Profiles from %s (%s) disable all data source replication."
    )
    logging.info(message, env_data_sources, ", ".join(sorted(recognized_profiles or configured_profiles)))

    if ignored:
        logging.debug(
            "Ignoring unrecognised data source profile(s) from %s: %s.",
            env_data_sources,
            ", ".join(ignored),
        )

    return recognized_profiles or set()


EXPLICIT_DATA_SOURCE_PROFILES = _load_data_source_profiles()
INACTIVE_DATA_SOURCE_REASONS: Dict[str, str] = {}


EVENT_DATA_SOURCE_SETTING = os.getenv("REPLICATOR_EVENT_DATA_SOURCE", "").strip()

def extract_primary_key(document: Dict[str, Any], source: str) -> Optional[str]:
    value = document.get("primaryKey")
    if value in (None, ""):
        logging.error("Skipping %s - missing required 'primaryKey' field.", source)
        return None
    return str(value)


def load_documents(directory: Path) -> List[DocumentRecord]:
    documents: List[DocumentRecord] = []
    if not directory.exists():
        logging.warning("Document directory %s does not exist.", directory)
        return documents

    def append_document(payload: Dict[str, Any], source: str) -> None:
        doc_id = extract_primary_key(payload, source)
        if doc_id is not None:
            documents.append((doc_id, payload, source))

    for path in sorted(directory.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            logging.error("Skipping %s - invalid JSON (%s)", path.name, exc)
            continue

        if isinstance(data, list):
            for index, item in enumerate(data):
                if isinstance(item, dict):
                    append_document(item, f"{path.name}#{index}")
                else:
                    logging.warning("Skipping %s item %d - expected JSON object.", path.name, index)
        elif isinstance(data, dict):
            append_document(data, path.name)
        else:
            logging.warning("Skipping %s - expected JSON object or array of objects.", path.name)

    return documents


def _is_json_file(path: Optional[str]) -> bool:
    if not path:
        return False
    return Path(path).suffix.lower() == ".json"


class DocumentChangeHandler(FileSystemEventHandler):
    def __init__(self, notify: Callable[[str], None], debounce_seconds: float = 1.0) -> None:
        super().__init__()
        self._notify = notify
        self._debounce_seconds = debounce_seconds
        self._lock = threading.Lock()
        self._timer: Optional[threading.Timer] = None
        self._pending_reason = "change detected"

    def close(self) -> None:
        with self._lock:
            if self._timer is not None:
                self._timer.cancel()
                self._timer = None

    def _schedule(self, reason: str) -> None:
        with self._lock:
            self._pending_reason = reason
            if self._timer is not None:
                self._timer.cancel()
            self._timer = threading.Timer(self._debounce_seconds, self._fire)
            self._timer.daemon = True
            self._timer.start()

    def _fire(self) -> None:
        with self._lock:
            self._timer = None
            reason = self._pending_reason
        self._notify(reason)

    def _handle_path(self, path: Optional[str], action: str) -> None:
        if path and _is_json_file(path):
            self._schedule(f"{action} {Path(path).name}")

    def on_created(self, event: FileSystemEvent) -> None:  # noqa: D401 - overrides parent method
        if not event.is_directory:
            self._handle_path(event.src_path, "created")

    def on_modified(self, event: FileSystemEvent) -> None:  # noqa: D401 - overrides parent method
        if not event.is_directory:
            self._handle_path(event.src_path, "modified")

    def on_deleted(self, event: FileSystemEvent) -> None:  # noqa: D401 - overrides parent method
        if not event.is_directory:
            self._handle_path(event.src_path, "deleted")

    def on_moved(self, event: FileMovedEvent) -> None:  # noqa: D401 - overrides parent method
        if event.is_directory:
            return

        dest_path = getattr(event, "dest_path", None)
        if dest_path and _is_json_file(dest_path):
            self._schedule(f"moved to {Path(dest_path).name}")
        elif _is_json_file(event.src_path):
            self._schedule(f"moved from {Path(event.src_path).name}")


def start_file_observer(handler: FileSystemEventHandler, directory: Path) -> BaseObserver:
    modes: Dict[str, List[Tuple[str, Callable[[], BaseObserver]]]] = {
        "native": [("native", Observer)],
        "polling": [("polling", PollingObserver)],
        "auto": [("native", Observer), ("polling", PollingObserver)],
    }

    candidates = modes.get(OBSERVER_MODE, modes["polling"])
    if candidates is modes["polling"] and OBSERVER_MODE not in modes:
        logging.warning("Unknown REPLICATOR_OBSERVER_MODE '%s'; defaulting to polling observer.", OBSERVER_MODE)

    last_error: Optional[Exception] = None
    for label, factory in candidates:
        try:
            observer = factory()
            observer.schedule(handler, str(directory), recursive=False)
            observer.start()
            logging.info("Watching %s for document changes using %s observer.", directory, label)
            return observer
        except Exception as exc:  # pragma: no cover - defensive fallback
            logging.warning("Unable to start %s observer on %s: %s", label, directory, exc)
            last_error = exc

    raise RuntimeError(f"Failed to start a file observer for {directory}") from last_error


def parse_host_port(endpoint: str, default_port: int) -> Tuple[str, int]:
    parsed = urlparse(endpoint)
    host = parsed.hostname or endpoint
    port = parsed.port or {"https": 443, "http": 80}.get(parsed.scheme, default_port)
    return host, port


def _parse_mongo_connection(uri: str) -> Tuple[str, int, Optional[str]]:
    host, port, database = "mongo", 27017, None
    try:
        parsed = parse_uri(uri)
    except Exception as exc:  # pragma: no cover - defensive
        logging.debug("Unable to parse Mongo URI '%s': %s", uri, exc)
        return host, port, database

    nodelist = parsed.get("nodelist")
    if nodelist:
        host, port = nodelist[0]
    return host, port, parsed.get("database")


def _mongo_host_port() -> Tuple[str, int]:
    uri = os.getenv("MONGO_URI", "mongodb://mongo:27017")
    host, port, _ = _parse_mongo_connection(uri)
    return host, port


def _s3_host_port() -> Tuple[str, int]:
    endpoint = os.getenv("S3_ENDPOINT", "http://minio:9000")
    return parse_host_port(endpoint, 9000)


def _hbase_host_port() -> Tuple[str, int]:
    host = os.getenv("HBASE_HOST", "hbase")
    port = os.getenv("HBASE_PORT")
    if not port:
        return host, 9090

    try:
        return host, int(port)
    except ValueError:
        logging.warning("Invalid HBASE_PORT '%s'; defaulting to 9090.", port)
        return host, 9090


def _host_resolves(host: str) -> bool:
    try:
        return bool(host and socket.getaddrinfo(host, None))
    except OSError:
        return False


def _record_inactive_data_source(name: str, reason: str) -> None:
    previous = INACTIVE_DATA_SOURCE_REASONS.get(name)
    if previous == reason:
        return
    logging.info("Skipping %s replication (%s).", name.upper(), reason)
    INACTIVE_DATA_SOURCE_REASONS[name] = reason


def _mark_data_source_active(name: str) -> None:
    if name in INACTIVE_DATA_SOURCE_REASONS:
        del INACTIVE_DATA_SOURCE_REASONS[name]


def wait_for_host(host: str, port: int, label: str) -> bool:
    dns_deadline = time.time() + DNS_WAIT_SECONDS
    while time.time() < dns_deadline:
        try:
            socket.getaddrinfo(host, None)
            break
        except socket.gaierror:
            time.sleep(1)
    else:
        logging.info("%s host '%s' could not be resolved within %s seconds - skipping.", label, host, DNS_WAIT_SECONDS)
        return False

    deadline = time.time() + WAIT_SECONDS
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=5):
                return True
        except OSError:
            time.sleep(2)

    logging.warning("Timed out waiting for %s at %s:%s after %s seconds.", label, host, port, WAIT_SECONDS)
    return False

def replicate_s3(documents: Sequence[DocumentRecord]) -> None:
    if not documents:
        logging.info("No documents to upload to S3.")
        return

    endpoint = os.getenv("S3_ENDPOINT", "http://minio:9000")
    bucket = os.getenv("S3_BUCKET", "documents")
    region = os.getenv("S3_REGION", "us-east-1")
    access_key = os.getenv("S3_ACCESS_KEY", "minio")
    secret_key = os.getenv("S3_SECRET_KEY", "minio123")

    host, port = parse_host_port(endpoint, 9000)
    if not wait_for_host(host, port, "S3"):
        return

    session = boto3.session.Session(
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name=region,
    )
    client = session.client("s3", endpoint_url=endpoint, config=Config(signature_version="s3v4"))

    try:
        client.head_bucket(Bucket=bucket)
    except ClientError as exc:
        error_code = exc.response.get("Error", {}).get("Code")
        if error_code in {"404", "NoSuchBucket"}:
            try:
                client.create_bucket(Bucket=bucket)
                logging.info("Created S3 bucket '%s'.", bucket)
            except (ClientError, BotoCoreError) as bucket_exc:
                logging.error("Unable to create bucket %s: %s", bucket, bucket_exc)
                return
        else:
            logging.error("Unable to access bucket %s: %s", bucket, exc)
            return

    success = 0
    for doc_id, document, source in documents:
        key = f"{doc_id}.json"
        body = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        try:
            client.put_object(Bucket=bucket, Key=key, Body=body, ContentType="application/json")
            success += 1
            logging.info("Uploaded %s from %s to S3 bucket %s.", doc_id, source, bucket)
        except (ClientError, BotoCoreError) as exc:
            logging.error("Failed to upload %s to S3: %s", doc_id, exc)

    logging.info("S3 replication complete (%d/%d).", success, len(documents))


def replicate_mongo(documents: Sequence[DocumentRecord]) -> None:
    if not documents:
        logging.info("No documents to replicate to MongoDB.")
        return

    uri = os.getenv("MONGO_URI", "mongodb://mongo:27017")
    collection_name = os.getenv("MONGO_COLLECTION", "documents")

    host, port, uri_database = _parse_mongo_connection(uri)
    database = os.getenv("MONGO_DB") or uri_database or "documents"

    if not wait_for_host(host, port, "MongoDB"):
        return

    try:
        client = MongoClient(uri, serverSelectionTimeoutMS=WAIT_SECONDS * 1000)
        client.admin.command("ping")
    except PyMongoError as exc:
        logging.error("MongoDB unavailable at %s: %s", uri, exc)
        return

    collection = client[database][collection_name]

    success = 0
    for doc_id, document, source in documents:
        payload = dict(document)
        primary_value = document.get("primaryKey", doc_id)
        payload["primaryKey"] = primary_value
        payload["id"] = doc_id
        payload["_id"] = doc_id
        try:
            collection.replace_one({"_id": doc_id}, payload, upsert=True)
            success += 1
            logging.info("Upserted %s from %s into MongoDB collection %s.%s.", doc_id, source, database, collection_name)
        except PyMongoError as exc:
            logging.error("Failed to upsert %s into MongoDB: %s", doc_id, exc)

    logging.info("MongoDB replication complete (%d/%d).", success, len(documents))


def _ensure_hbase_table(
    connection: "happybase.Connection", table: str, family: str
) -> Optional["happybase.Table"]:
    try:
        tables = {
            name.decode("utf-8") if isinstance(name, bytes) else str(name)
            for name in connection.tables()
        }
    except Exception as exc:
        logging.error("Unable to list HBase tables: %s", exc)
        return None

    if table not in tables:
        try:
            connection.create_table(table, {family: {}})
            logging.info("Created HBase table '%s' with column family '%s'.", table, family)
        except Exception as exc:
            logging.error("Failed to create HBase table %s: %s", table, exc)
            return None

    try:
        table_ref = connection.table(table)
        families = {
            name.decode("utf-8") if isinstance(name, bytes) else str(name)
            for name in table_ref.families()
        }
    except Exception as exc:
        logging.error("Unable to access HBase table %s: %s", table, exc)
        return None

    if family not in families:
        logging.error("HBase table %s is missing column family %s.", table, family)
        return None

    return table_ref


def replicate_hbase(documents: Sequence[DocumentRecord]) -> None:
    if not documents:
        logging.info("No documents to replicate to HBase.")
        return

    table = os.getenv("HBASE_TABLE", "documents")
    family = os.getenv("HBASE_COLUMN_FAMILY", "data")
    qualifier = os.getenv("HBASE_QUALIFIER", "json")

    host, port = _hbase_host_port()
    if not wait_for_host(host, port, "HBase Thrift"):
        return

    try:
        connection = happybase.Connection(
            host=host,
            port=port,
            autoconnect=False,
            timeout=WAIT_SECONDS * 1000,
        )
        connection.open()
    except Exception as exc:
        logging.error("Unable to connect to HBase at %s:%s: %s", host, port, exc)
        return

    try:
        table_ref = _ensure_hbase_table(connection, table, family)
        if table_ref is None:
            return

        column = f"{family}:{qualifier}".encode("utf-8")
        success = 0
        for doc_id, document, source in documents:
            row_key = str(doc_id).encode("utf-8")
            payload = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            try:
                table_ref.put(row_key, {column: payload})
                success += 1
                logging.info("Upserted %s from %s into HBase table %s.", doc_id, source, table)
            except Exception as exc:
                logging.error("Failed to write %s to HBase: %s", doc_id, exc)

        logging.info("HBase replication complete (%d/%d).", success, len(documents))
    finally:
        try:
            connection.close()
        except Exception:
            pass


def publish_kafka_events(
    documents: Sequence[DocumentRecord], active_data_sources: Set[str]
) -> None:
    configured_value = EVENT_DATA_SOURCE_SETTING.lower()
    if configured_value not in DATA_SOURCE_PROFILE_NAMES and configured_value != "streaming":
        logging.warning(
            "Unknown Kafka event data source '%s'; skipping event publication.",
            EVENT_DATA_SOURCE_SETTING,
        )
        return

    if configured_value != "streaming" and configured_value not in active_data_sources:
        reason = INACTIVE_DATA_SOURCE_REASONS.get(configured_value)
        if reason:
            logging.warning(
                "Kafka event data source '%s' is inactive (%s); skipping event publication.",
                configured_value,
                reason,
            )
        else:
            logging.warning(
                "Kafka event data source '%s' is not active; skipping event publication.",
                configured_value,
            )
        return

    bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    topic = os.getenv("KAFKA_TOPIC", "documents")
    servers = [server.strip() for server in bootstrap_servers.split(",") if server.strip()]
    if not servers:
        logging.warning("Kafka bootstrap servers not configured; skipping event publication.")
        return
    if not topic:
        logging.warning("Kafka topic not configured; skipping event publication.")
        return

    try:
        producer = KafkaProducer(
            bootstrap_servers=servers,
            value_serializer=lambda value: json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            api_version_auto_timeout_ms=5000,
            request_timeout_ms=5000,
        )
    except NoBrokersAvailable:
        logging.info("Kafka unavailable at %s; skipping indexing events.", bootstrap_servers)
        return
    except Exception as exc:  # pragma: no cover - defensive
        logging.error("Failed to create Kafka producer for %s: %s", bootstrap_servers, exc)
        return

    success = 0
    for doc_id, document, _ in documents:
        payload = {
            "primaryKey": document.get("primaryKey", doc_id),
        }
        if configured_value == "streaming":
            payload["inlineDocument"] = document
        try:
            future = producer.send(topic, value=payload)
            future.get(timeout=10)
            success += 1
            logging.info(
                "Published indexing event for %s to Kafka topic %s (data source: %s).",
                doc_id,
                topic,
                configured_value,
            )
        except KafkaError as exc:
            logging.error("Failed to publish indexing event for %s: %s", doc_id, exc)
        except Exception as exc:  # pragma: no cover - defensive
            logging.error("Unexpected error while publishing event for %s: %s", doc_id, exc)

    try:
        producer.flush(timeout=10)
    except KafkaError as exc:
        logging.error("Error flushing Kafka producer: %s", exc)
    finally:
        try:
            producer.close(timeout=5)
        except Exception:  # pragma: no cover - defensive
            producer.close()

    logging.info(
        "Kafka event publication complete (%d/%d) for data source %s.",
        success,
        len(documents),
        configured_value,
    )


def replicate_all(directory: Path) -> None:
    documents = load_documents(directory)

    if documents:
        logging.info("Loaded %d document(s) from %s.", len(documents), directory)
    else:
        logging.info("No JSON documents found in %s.", directory)
        return

    data_sources = {
        "mongo": (replicate_mongo, _mongo_host_port),
        "s3": (replicate_s3, _s3_host_port),
        "hbase": (replicate_hbase, _hbase_host_port),
    }

    profiles = EXPLICIT_DATA_SOURCE_PROFILES
    active_data_sources: Set[str] = set()
    for name, (handler, host_factory) in data_sources.items():
        if name not in profiles:
            _record_inactive_data_source(name, "profile not enabled")
            continue

        host, _ = host_factory()
        if profiles is None and not _host_resolves(host):
            _record_inactive_data_source(name, f"host '{host}' unavailable")
            continue

        _mark_data_source_active(name)
        handler(documents)
        active_data_sources.add(name)

    if len(active_data_sources) == 0:
        logging.warning("No active data sources! No replication was done.")

    if EVENT_DATA_SOURCE_SETTING:
        publish_kafka_events(documents, active_data_sources)

    logging.info("Document replication finished.")


def main() -> None:
    directory = Path(os.getenv("DOCUMENT_SOURCE_DIR", "/documents"))
    logging.info("Document replicator monitoring %s.", directory)

    change_event = threading.Event()

    def request_sync(reason: str) -> None:
        logging.info("Document change detected (%s); triggering replication.", reason)
        change_event.set()

    handler = DocumentChangeHandler(request_sync, CHANGE_DEBOUNCE_SECONDS)
    observer: Optional[BaseObserver] = None

    try:
        while not directory.exists():
            logging.warning("Document directory %s does not exist; waiting for it to be created.", directory)
            time.sleep(2)

        observer = start_file_observer(handler, directory)

        request_sync("initial sync")

        while True:
            change_event.wait()
            change_event.clear()
            try:
                replicate_all(directory)
            except Exception:
                logging.exception("Unexpected error while replicating documents.")
    except KeyboardInterrupt:
        logging.info("Shutting down document replicator.")
    finally:
        handler.close()
        if observer is not None:
            observer.stop()
            observer.join()


if __name__ == "__main__":
    main()
