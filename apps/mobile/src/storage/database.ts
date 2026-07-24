import * as Crypto from "expo-crypto";
import * as SecureStore from "expo-secure-store";
import * as SQLite from "expo-sqlite";

import type { CrowdMessage } from "@crowdos/core";

const DATABASE_NAME = "crowdos.db";
const DATABASE_KEY_NAME = "crowdos.database-key.v1";

let databasePromise: Promise<SQLite.SQLiteDatabase> | undefined;

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function getDatabaseKey(): Promise<string> {
  const storedKey = await SecureStore.getItemAsync(DATABASE_KEY_NAME);
  if (storedKey) {
    return storedKey;
  }

  const key = bytesToHex(await Crypto.getRandomBytesAsync(32));
  await SecureStore.setItemAsync(DATABASE_KEY_NAME, key, {
    keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
  });
  return key;
}

async function createDatabase(): Promise<SQLite.SQLiteDatabase> {
  const database = await SQLite.openDatabaseAsync(DATABASE_NAME);
  const key = await getDatabaseKey();

  // This value is generated hexadecimal, so it is safe in SQLCipher's key pragma.
  await database.execAsync(`PRAGMA key = "x'${key}'";`);
  await database.execAsync(`
    PRAGMA foreign_keys = ON;
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS pending_messages (
      id TEXT PRIMARY KEY NOT NULL,
      event_id TEXT NOT NULL,
      kind TEXT NOT NULL,
      priority TEXT NOT NULL,
      created_at TEXT NOT NULL,
      expires_at TEXT,
      sender_device_id TEXT NOT NULL,
      zone_id TEXT,
      body_json TEXT NOT NULL,
      attempts INTEGER NOT NULL DEFAULT 0,
      last_attempt_at TEXT
    );
    CREATE INDEX IF NOT EXISTS pending_messages_priority_created_idx
      ON pending_messages(priority, created_at);
    PRAGMA user_version = 1;
  `);

  return database;
}

export function getDatabase(): Promise<SQLite.SQLiteDatabase> {
  databasePromise ??= createDatabase();
  return databasePromise;
}

export async function enqueuePendingMessage(message: CrowdMessage): Promise<void> {
  const database = await getDatabase();
  await database.runAsync(
    `INSERT OR IGNORE INTO pending_messages (
      id, event_id, kind, priority, created_at, expires_at,
      sender_device_id, zone_id, body_json
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    message.id,
    message.eventId,
    message.kind,
    message.priority,
    message.createdAt,
    message.expiresAt ?? null,
    message.senderDeviceId,
    message.zoneId ?? null,
    JSON.stringify(message.body)
  );
}

type PendingMessageRow = {
  id: string;
  event_id: string;
  kind: CrowdMessage["kind"];
  priority: CrowdMessage["priority"];
  created_at: string;
  expires_at: string | null;
  sender_device_id: string;
  zone_id: string | null;
  body_json: string;
};

export async function listPendingMessages(): Promise<CrowdMessage[]> {
  const database = await getDatabase();
  const rows = await database.getAllAsync<PendingMessageRow>(
    `SELECT id, event_id, kind, priority, created_at, expires_at,
      sender_device_id, zone_id, body_json
    FROM pending_messages
    ORDER BY
      CASE priority WHEN 'critical' THEN 0 WHEN 'high' THEN 1 ELSE 2 END,
      created_at ASC`
  );

  return rows.map((row) => ({
    id: row.id,
    eventId: row.event_id,
    kind: row.kind,
    priority: row.priority,
    createdAt: row.created_at,
    expiresAt: row.expires_at ?? undefined,
    senderDeviceId: row.sender_device_id,
    zoneId: row.zone_id ?? undefined,
    body: JSON.parse(row.body_json) as Record<string, unknown>,
  }));
}
