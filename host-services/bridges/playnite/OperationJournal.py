"""Durable install/uninstall state for the Game Provider Bridge."""

from __future__ import annotations

import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any


ACTIVE_STATES = {"queued", "preparing", "downloading", "installing",
                 "uninstalling", "attention_required", "verifying"}


class OperationJournal:
    def __init__(self, path: Path | None) -> None:
        self.path = path
        self.lock = threading.RLock()
        self.memory = sqlite3.connect(":memory:", check_same_thread=False) \
            if path is None else None
        if self.memory is not None:
            self.memory.row_factory = sqlite3.Row
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
        with self._database() as database:
            database.execute("""
                CREATE TABLE IF NOT EXISTS operations (
                    game_id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    game_name TEXT NOT NULL,
                    state TEXT NOT NULL,
                    requested_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    detail TEXT NOT NULL DEFAULT '',
                    progress INTEGER,
                    window_handle INTEGER NOT NULL DEFAULT 0,
                    window_title TEXT NOT NULL DEFAULT '',
                    launcher TEXT NOT NULL DEFAULT ''
                )
            """)

    def _connect(self) -> sqlite3.Connection:
        if self.memory is not None:
            return self.memory
        connection = sqlite3.connect(self.path, timeout=5)
        connection.row_factory = sqlite3.Row
        return connection

    def close(self) -> None:
        with self.lock:
            if self.memory is not None:
                self.memory.close()
                self.memory = None

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass

    @contextmanager
    def _database(self):
        database = self._connect()
        try:
            with database:
                yield database
        finally:
            if database is not self.memory:
                database.close()

    def begin(self, game_id: str, kind: str, provider: str, game_name: str) -> dict[str, Any]:
        now = time.time()
        with self.lock, self._database() as database:
            current = database.execute(
                "SELECT * FROM operations WHERE game_id = ?", (game_id,)).fetchone()
            if current is not None and current["state"] in ACTIVE_STATES:
                return dict(current)
            database.execute("""
                INSERT INTO operations
                    (game_id, kind, provider, game_name, state, requested_at, updated_at)
                VALUES (?, ?, ?, ?, 'preparing', ?, ?)
                ON CONFLICT(game_id) DO UPDATE SET
                    kind=excluded.kind, provider=excluded.provider,
                    game_name=excluded.game_name, state=excluded.state,
                    requested_at=excluded.requested_at, updated_at=excluded.updated_at,
                    detail='', progress=NULL, window_handle=0, window_title='', launcher=''
            """, (game_id, kind, provider, game_name, now, now))
        return self.get(game_id) or {}

    def update(self, game_id: str, state: str, *, detail: str = "",
               progress: int | None = None, window_handle: int = 0,
               window_title: str = "", launcher: str = "") -> None:
        if state not in ACTIVE_STATES | {"completed", "cancelled", "failed"}:
            raise ValueError(f"Invalid operation state: {state}")
        with self.lock, self._database() as database:
            database.execute("""
                UPDATE operations SET state=?, updated_at=?, detail=?, progress=?,
                    window_handle=?, window_title=?, launcher=? WHERE game_id=?
            """, (state, time.time(), detail[:500], progress, int(window_handle),
                  window_title[:300], launcher[:200], game_id))

    def get(self, game_id: str) -> dict[str, Any] | None:
        with self.lock, self._database() as database:
            row = database.execute(
                "SELECT * FROM operations WHERE game_id = ?", (game_id,)).fetchone()
            return dict(row) if row is not None else None

    def active(self) -> list[dict[str, Any]]:
        placeholders = ",".join("?" for _ in ACTIVE_STATES)
        with self.lock, self._database() as database:
            rows = database.execute(
                f"SELECT * FROM operations WHERE state IN ({placeholders}) ORDER BY requested_at",
                tuple(ACTIVE_STATES)).fetchall()
            return [dict(row) for row in rows]
