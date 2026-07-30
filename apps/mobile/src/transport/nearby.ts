import {
  DeviceEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
} from "react-native";

const nativeNearby = NativeModules.CrowdOSNearby as {
  start: (serviceId: string) => Promise<void>;
  stop: () => Promise<void>;
  sendMessage: (base64Payload: string) => Promise<void>;
} | undefined;

export type NearbyStatus =
  | { state: "starting" }
  | { state: "active"; peers: number }
  | { state: "permission-denied" }
  | { state: "unavailable" }
  | { state: "error"; message: string };

const nearbyPermissions = [
  "android.permission.BLUETOOTH_ADVERTISE",
  "android.permission.BLUETOOTH_CONNECT",
  "android.permission.BLUETOOTH_SCAN",
  "android.permission.NEARBY_WIFI_DEVICES",
];

async function requestNearbyPermissions(): Promise<boolean> {
  if (Platform.OS !== "android") {
    return false;
  }

  const permissions = (Platform.Version >= 31
    ? nearbyPermissions
    : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION]) as Parameters<
    typeof PermissionsAndroid.requestMultiple
  >[0];
  const result = await PermissionsAndroid.requestMultiple(permissions);
  return permissions.every((permission) => result[permission] === PermissionsAndroid.RESULTS.GRANTED);
}

export async function startNearbyTransport(
  onStatus: (status: NearbyStatus) => void
): Promise<() => void> {
  if (!nativeNearby || Platform.OS !== "android") {
    onStatus({ state: "unavailable" });
    return () => undefined;
  }

  onStatus({ state: "starting" });
  if (!(await requestNearbyPermissions())) {
    onStatus({ state: "permission-denied" });
    return () => undefined;
  }

  let peerCount = 0;
  const subscriptions = [
    DeviceEventEmitter.addListener("peerConnected", () => {
      peerCount += 1;
      onStatus({ state: "active", peers: peerCount });
    }),
    DeviceEventEmitter.addListener("peerDisconnected", () => {
      peerCount = Math.max(0, peerCount - 1);
      onStatus({ state: "active", peers: peerCount });
    }),
    DeviceEventEmitter.addListener("nearbyError", (error: { message?: string }) => {
      onStatus({ state: "error", message: error.message ?? "Nearby transport error" });
    }),
  ];

  try {
    await nativeNearby.start("com.crowdos.mobile");
    onStatus({ state: "active", peers: peerCount });
  } catch (error) {
    subscriptions.forEach((subscription) => subscription.remove());
    onStatus({ state: "error", message: error instanceof Error ? error.message : "Unable to start nearby transport" });
    return () => undefined;
  }

  return () => {
    subscriptions.forEach((subscription) => subscription.remove());
    void nativeNearby.stop();
  };
}
