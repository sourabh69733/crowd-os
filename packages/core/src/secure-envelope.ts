import { canonicalJson } from "./canonical-json.js";
import { base64ToBytes, bytesToBase64, utf8Bytes } from "./encoding.js";
import type {
  CrowdMessage,
  SignedEnvelope,
  TrustStore,
  VerificationResult,
} from "./types.js";

export async function signMessage(
  message: CrowdMessage,
  signerKeyId: string,
  privateKey: CryptoKey
): Promise<SignedEnvelope> {
  const signature = await crypto.subtle.sign(
    "Ed25519",
    privateKey,
    utf8Bytes(canonicalJson(message))
  );

  return {
    message,
    signerKeyId,
    signature: bytesToBase64(signature),
  };
}

export async function verifyEnvelope(
  envelope: SignedEnvelope,
  trustStore: TrustStore,
  now = new Date()
): Promise<VerificationResult> {
  if (envelope.message.expiresAt && new Date(envelope.message.expiresAt) < now) {
    return { ok: false, reason: "expired" };
  }

  const publicKey = await trustStore.getPublicKey(envelope.signerKeyId);
  if (!publicKey) {
    return { ok: false, reason: "untrusted_key" };
  }

  const ok = await crypto.subtle.verify(
    "Ed25519",
    publicKey,
    base64ToBytes(envelope.signature),
    utf8Bytes(canonicalJson(envelope.message))
  );

  return ok ? { ok: true, envelope } : { ok: false, reason: "bad_signature" };
}

export async function encryptPayload(
  payload: Record<string, unknown>,
  key: CryptoKey
): Promise<{ iv: string; ciphertext: string }> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    utf8Bytes(canonicalJson(payload))
  );

  return {
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(ciphertext),
  };
}
