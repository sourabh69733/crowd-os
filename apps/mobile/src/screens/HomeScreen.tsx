import { useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import * as Crypto from "expo-crypto";

import type { CrowdMessage, MessageKind } from "@crowdos/core";
import { enqueuePendingMessage, listPendingMessages } from "../storage/database";
import { startNearbyTransport, type NearbyStatus } from "../transport/nearby";

const latestAlert = {
  title: "Route update",
  detail: "North gate is crowded. Use the marked south exit.",
  age: "2 min ago",
};

type HomeTab = "Home" | "Alerts" | "Map" | "Report" | "Network";

function nearbyTitle(status: NearbyStatus, fallback: string): string {
  if (status.state === "active") {
    return status.peers > 0 ? "Nearby relay connected" : fallback;
  }
  if (status.state === "permission-denied") return "Nearby permission needed";
  if (status.state === "unavailable") return "Nearby relay unavailable";
  if (status.state === "error") return "Nearby relay error";
  return "Starting nearby relay";
}

function nearbyDetail(status: NearbyStatus, offlineMode: boolean): string {
  if (status.state === "active") return `${status.peers} nearby peer(s)`;
  if (status.state === "permission-denied") return "Allow Bluetooth and nearby-device access";
  if (status.state === "unavailable") return "This platform does not expose native nearby transport";
  if (status.state === "error") return status.message;
  return offlineMode ? "Scanning for nearby CrowdOS devices" : "Gateway connection available";
}

export function HomeScreen({ eventId }: { eventId: string }) {
  const [offlineMode, setOfflineMode] = useState(true);
  const [queuedMessages, setQueuedMessages] = useState<CrowdMessage[]>([]);
  const [note, setNote] = useState("Need doctor near Gate 3");
  const [storageReady, setStorageReady] = useState(false);
  const [activeTab, setActiveTab] = useState<HomeTab>("Home");
  const [nearbyStatus, setNearbyStatus] = useState<NearbyStatus>({ state: "starting" });

  useEffect(() => {
    let active = true;
    listPendingMessages()
      .then((messages) => {
        if (active) {
          setQueuedMessages(messages);
          setStorageReady(true);
        }
      })
      .catch(() => {
        if (active) {
          Alert.alert(
            "Secure storage unavailable",
            "CrowdOS could not open its encrypted offline queue."
          );
        }
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let cleanup: (() => void) | undefined;
    void startNearbyTransport(setNearbyStatus).then((stop) => {
      cleanup = stop;
    });
    return () => cleanup?.();
  }, []);

  const networkLabel = useMemo(
    () => (offlineMode ? "Offline relay active" : "Online gateway sync"),
    [offlineMode]
  );

  async function queueMessage(kind: MessageKind, priority: CrowdMessage["priority"]) {
    const message: CrowdMessage = {
      id: Crypto.randomUUID(),
      eventId,
      kind,
      priority,
      createdAt: new Date().toISOString(),
      senderDeviceId: "this-device",
      zoneId: "gate-3",
      body: { note },
    };

    try {
      await enqueuePendingMessage(message);
      setQueuedMessages(await listPendingMessages());
      Alert.alert("SOS queued", "This request will relay when a trusted route is available.");
    } catch {
      Alert.alert(
        "Message not queued",
        "The encrypted queue could not save this request. Please try again."
      );
    }
  }

  function selectTab(tab: HomeTab) {
    setActiveTab(tab);
    if (tab !== "Home") {
      Alert.alert(`${tab} screen`, "This screen is the next implementation step.");
    }
  }

  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.container}>
        <ScrollView contentContainerStyle={styles.screen}>
          <View style={styles.headerRow}>
            <View>
              <Text style={styles.kicker}>CJP PILOT EVENT</Text>
              <Text style={styles.title}>Safety status</Text>
            </View>
            <Text style={styles.eventCode}>{eventId}</Text>
          </View>

          <View style={styles.statusCard}>
            <View style={styles.statusHeading}>
              <View style={styles.statusDot} />
            <Text style={styles.statusTitle}>{nearbyTitle(nearbyStatus, networkLabel)}</Text>
            </View>
            <Text style={styles.statusMeta}>
              {nearbyDetail(nearbyStatus, offlineMode)} · {queuedMessages.length} pending
            </Text>
            <Switch
              style={styles.statusSwitch}
              value={offlineMode}
              onValueChange={setOfflineMode}
            />
          </View>

          <Text style={styles.sectionTitle}>Emergency help</Text>
          {!storageReady && (
            <View style={styles.loading}>
              <ActivityIndicator />
              <Text style={styles.meta}>Opening encrypted offline queue</Text>
            </View>
          )}
          <View style={styles.actionRow}>
            <Action
              disabled={!storageReady}
              label="Medical SOS"
              onPress={() => void queueMessage("sos", "critical")}
            />
            <Action
              disabled={!storageReady}
              label="Other SOS"
              onPress={() => void queueMessage("sos", "high")}
            />
          </View>

          <Text style={styles.sectionTitle}>Latest verified alert</Text>
          <View style={styles.alertCard}>
            <Text style={styles.alertTitle}>{latestAlert.title}</Text>
            <Text style={styles.body}>{latestAlert.detail}</Text>
            <Text style={styles.meta}>{latestAlert.age} · organizer signed</Text>
          </View>

          <View style={styles.detailsRow}>
            <Text style={styles.body}>Offline map and event plan are stored on this device.</Text>
            <TextInput
              style={styles.noteInput}
              value={note}
              onChangeText={setNote}
              placeholder="SOS note"
              placeholderTextColor="#6b716e"
            />
          </View>
        </ScrollView>

        <View style={styles.bottomNav}>
          {(["Home", "Alerts", "Map", "Report", "Network"] as HomeTab[]).map((tab) => (
            <Pressable key={tab} style={styles.navButton} onPress={() => selectTab(tab)}>
              <Text style={activeTab === tab ? styles.navActive : styles.nav}>{tab}</Text>
            </Pressable>
          ))}
        </View>
      </View>
    </SafeAreaView>
  );
}

function Action({
  disabled,
  label,
  onPress,
}: {
  disabled: boolean;
  label: string;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      style={({ pressed }) => [
        styles.action,
        disabled && styles.actionDisabled,
        pressed && !disabled && styles.actionPressed,
      ]}
      onPress={onPress}
    >
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: "#f7f8f4" },
  container: { flex: 1 },
  screen: { gap: 16, padding: 16, paddingBottom: 28 },
  headerRow: { alignItems: "flex-end", flexDirection: "row", justifyContent: "space-between" },
  kicker: { color: "#66736d", fontSize: 12, fontWeight: "800", letterSpacing: 1, textTransform: "uppercase" },
  title: { color: "#18211f", fontSize: 32, fontWeight: "900", marginTop: 8 },
  eventCode: { color: "#66736d", fontSize: 12, fontWeight: "800", maxWidth: 110, textAlign: "right" },
  statusCard: { backgroundColor: "#ffffff", borderColor: "#18211f", borderRadius: 8, borderWidth: 2, minHeight: 92, padding: 14, position: "relative" },
  statusHeading: { alignItems: "center", flexDirection: "row", gap: 10 },
  statusDot: { backgroundColor: "#18211f", borderRadius: 8, height: 16, width: 16 },
  statusTitle: { color: "#18211f", fontSize: 18, fontWeight: "900" },
  statusMeta: { color: "#66736d", fontSize: 13, fontWeight: "700", marginLeft: 26, marginTop: 6 },
  statusSwitch: { position: "absolute", right: 10, top: 18 },
  sectionTitle: { color: "#18211f", fontSize: 20, fontWeight: "900", marginTop: 4 },
  actionRow: { flexDirection: "row", gap: 12 },
  action: { alignItems: "center", backgroundColor: "#18211f", borderRadius: 8, flex: 1, justifyContent: "center", minHeight: 74, paddingHorizontal: 10 },
  actionText: { color: "#ffffff", fontSize: 16, fontWeight: "900", textAlign: "center" },
  actionDisabled: { opacity: 0.45 },
  actionPressed: { opacity: 0.8 },
  alertCard: { backgroundColor: "#ffffff", borderColor: "#b6beb9", borderRadius: 8, borderWidth: 1, gap: 8, padding: 16 },
  alertTitle: { color: "#18211f", fontSize: 17, fontWeight: "900" },
  body: { color: "#3b4742", lineHeight: 20 },
  meta: { color: "#66736d", fontSize: 12, fontWeight: "700" },
  loading: { alignItems: "center", flexDirection: "row", gap: 8 },
  detailsRow: { backgroundColor: "#ffffff", borderColor: "#d8ded6", borderRadius: 8, borderWidth: 1, gap: 12, padding: 14 },
  noteInput: { borderColor: "#d8ded6", borderRadius: 8, borderWidth: 1, color: "#18211f", minHeight: 44, paddingHorizontal: 12 },
  bottomNav: { backgroundColor: "#ffffff", borderTopColor: "#d8ded6", borderTopWidth: 1, flexDirection: "row", justifyContent: "space-around", paddingBottom: 10, paddingTop: 12 },
  navButton: { alignItems: "center", flex: 1, minHeight: 32 },
  nav: { color: "#66736d", fontSize: 12 },
  navActive: { color: "#18211f", fontSize: 12, fontWeight: "900" },
});
