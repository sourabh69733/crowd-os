export function bytesToBase64(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const binary = Array.from(view, (byte) =>
    String.fromCharCode(byte)
  ).join("");

  if (typeof btoa === "function") {
    return btoa(binary);
  }

  return Buffer.from(binary, "binary").toString("base64");
}

export function base64ToBytes(value: string): ArrayBuffer {
  if (typeof atob === "function") {
    return Uint8Array.from(atob(value), (char) => char.charCodeAt(0)).buffer;
  }

  const buffer = Buffer.from(value, "base64");
  return buffer.buffer.slice(
    buffer.byteOffset,
    buffer.byteOffset + buffer.byteLength
  ) as ArrayBuffer;
}

export function utf8Bytes(value: string): ArrayBuffer {
  return new TextEncoder().encode(value).buffer;
}
