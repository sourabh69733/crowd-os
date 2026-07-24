import type { SignedEnvelope, StoredQueueItem } from "./types.js";

export type QueueStorage = {
  load(): Promise<StoredQueueItem[]>;
  save(items: StoredQueueItem[]): Promise<void>;
};

export class OfflineMessageQueue {
  constructor(private readonly storage: QueueStorage) {}

  async enqueue(envelope: SignedEnvelope): Promise<void> {
    const items = await this.storage.load();
    if (items.some((item) => item.envelope.message.id === envelope.message.id)) {
      return;
    }

    await this.storage.save([...items, { envelope, attempts: 0 }]);
  }

  async markAttempted(messageId: string, attemptedAt = new Date()): Promise<void> {
    const items = await this.storage.load();
    await this.storage.save(
      items.map((item) =>
        item.envelope.message.id === messageId
          ? {
              ...item,
              attempts: item.attempts + 1,
              lastAttemptAt: attemptedAt.toISOString(),
            }
          : item
      )
    );
  }

  async remove(messageId: string): Promise<void> {
    const items = await this.storage.load();
    await this.storage.save(
      items.filter((item) => item.envelope.message.id !== messageId)
    );
  }

  async nextBatch(limit = 20): Promise<StoredQueueItem[]> {
    const items = await this.storage.load();
    return items
      .slice()
      .sort((a, b) =>
        a.envelope.message.createdAt.localeCompare(b.envelope.message.createdAt)
      )
      .slice(0, limit);
  }
}
