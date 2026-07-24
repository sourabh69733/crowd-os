export type MessageKind =
  | "sos"
  | "verified_broadcast"
  | "incident_report"
  | "lost_person"
  | "volunteer_dispatch"
  | "gateway_sync";

export type MessagePriority = "critical" | "high" | "normal";

export type CrowdMessage = {
  id: string;
  eventId: string;
  kind: MessageKind;
  priority: MessagePriority;
  createdAt: string;
  expiresAt?: string;
  senderDeviceId: string;
  zoneId?: string;
  body: Record<string, unknown>;
};

export type SignedEnvelope = {
  message: CrowdMessage;
  signerKeyId: string;
  signature: string;
};

export type VerificationResult =
  | { ok: true; envelope: SignedEnvelope }
  | { ok: false; reason: "expired" | "bad_signature" | "untrusted_key" };

export type TrustStore = {
  getPublicKey(keyId: string): Promise<CryptoKey | null>;
};

export type StoredQueueItem = {
  envelope: SignedEnvelope;
  attempts: number;
  lastAttemptAt?: string;
};
