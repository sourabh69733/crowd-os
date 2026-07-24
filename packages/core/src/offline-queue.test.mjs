import assert from "node:assert/strict";
import test from "node:test";

import { OfflineMessageQueue } from "./offline-queue.ts";

test("offline queue deduplicates and tracks attempts", async () => {
  let items = [];
  const queue = new OfflineMessageQueue({
    async load() {
      return items;
    },
    async save(next) {
      items = next;
    },
  });

  const envelope = {
    signerKeyId: "organizer-1",
    signature: "signature",
    message: {
      id: "msg-1",
      eventId: "event-1",
      kind: "sos",
      priority: "critical",
      createdAt: "2026-07-24T00:00:00.000Z",
      senderDeviceId: "device-1",
      body: { need: "doctor" },
    },
  };

  await queue.enqueue(envelope);
  await queue.enqueue(envelope);
  await queue.markAttempted("msg-1", new Date("2026-07-24T00:01:00.000Z"));

  const batch = await queue.nextBatch();
  assert.equal(batch.length, 1);
  assert.equal(batch[0].attempts, 1);
  assert.equal(batch[0].lastAttemptAt, "2026-07-24T00:01:00.000Z");

  await queue.remove("msg-1");
  assert.deepEqual(await queue.nextBatch(), []);
});
