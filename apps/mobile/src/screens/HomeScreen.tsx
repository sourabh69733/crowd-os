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

const eventPlan = [
  "Meetup: Blue Flag checkpoint",
  "Medical: Gate 3 first-aid tent",
  "Safe exits: South Exit, East Service Lane",
];

const alerts = [
  {
    id: "alert-1",
    title: "Verified route update",
    detail: "North Spine is crowded. Move toward South Exit overflow.",
    age: "2m",
  },
  {
    id: "alert-2",
    title: "Water point active",
    detail: "Water volunteers opened a new point near Food Lane.",
    age: "8m",
  },
];

export function HomeScreen({ eventId }: { eventId: string }) {
  const [offlineMode, setOfflineMode] = useState(true);
  const [queuedMessages, setQueuedMessages] = useState<CrowdMessage[]>([]);
  const [note, setNote] = useState("Need doctor near Gate 3");
  const [storageReady, setStorageReady] = useState(false);

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
          Alert.alert("Secure storage unavailable", "CrowdOS could not open its encrypted offline queue.");
        }
      });
    return () => {
      active = false;
    };
  }, []);

  const networkLabel = useMemo(
    () => (offlineMode ? "Offline local relay" : "Online gateway sync"),
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
    } catch {
      Alert.alert("Message not queued", "The encrypted queue could not save this request. Please try again.");
    }
  }

  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.screen}>
        <View style={styles.header}>
          <View>
            <Text style={styles.kicker}>CrowdOS field app</Text>
            <Text style={styles.title}>Emergency relay</Text>
          </View>
          <View style={styles.mode}>
            <Text style={styles.modeText}>{networkLabel}</Text>
            <Switch value={offlineMode} onValueChange={setOfflineMode} />
          </View>
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>One-tap SOS</Text>
          {!storageReady && (
            <View style={styles.loading}>
              <ActivityIndicator />
              <Text style={styles.meta}>Opening encrypted offline queue</Text>
            </View>
          )}
          <View style={styles.grid}>
            <Action disabled={!storageReady} label="Doctor" onPress={() => void queueMessage("sos", "critical")} />
            <Action disabled={!storageReady} label="Water" onPress={() => void queueMessage("sos", "high")} />
            <Action disabled={!storageReady} label="Legal" onPress={() => void queueMessage("sos", "high")} />
            <Action disabled={!storageReady} label="Evacuate" onPress={() => void queueMessage("sos", "critical")} />
          </View>
          <TextInput
            style={styles.input}
            value={note}
            onChangeText={setNote}
            placeholder="Add short emergency note"
            placeholderTextColor="#7c8781"
          />
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Verified broadcasts</Text>
          {alerts.map((alert) => (
            <View style={styles.alert} key={alert.id}>
              <Text style={styles.alertTitle}>{alert.title}</Text>
              <Text style={styles.body}>{alert.detail}</Text>
              <Text style={styles.meta}>{alert.age} ago - organizer signed</Text>
            </View>
          ))}
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Offline event plan</Text>
          {eventPlan.map((item) => <Text style={styles.planItem} key={item}>{item}</Text>)}
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelTitle}>Local queue</Text>
          <Text style={styles.body}>{queuedMessages.length} message(s) waiting for nearby relay or internet gateway.</Text>
          {queuedMessages.map((message) => (
            <View style={styles.queueItem} key={message.id}>
              <Text style={styles.alertTitle}>{message.kind.toUpperCase()}</Text>
              <Text style={styles.meta}>{message.priority} - {String(message.body.note)}</Text>
            </View>
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

function Action({ disabled, label, onPress }: { disabled: boolean; label: string; onPress: () => void }) {
  return (
    <Pressable
      disabled={disabled}
      style={({ pressed }) => [styles.action, disabled && styles.actionDisabled, pressed && !disabled && styles.actionPressed]}
      onPress={onPress}
    >
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: "#f7f8f4" },
  screen: { gap: 14, padding: 16 },
  header: { gap: 16 },
  kicker: { color: "#66736d", fontSize: 12, fontWeight: "800", textTransform: "uppercase" },
  title: { color: "#18211f", fontSize: 36, fontWeight: "900" },
  mode: { alignItems: "center", backgroundColor: "#ffffff", borderColor: "#d8ded6", borderRadius: 8, borderWidth: 1, flexDirection: "row", justifyContent: "space-between", minHeight: 50, paddingHorizontal: 12 },
  modeText: { color: "#18211f", fontWeight: "800" },
  panel: { backgroundColor: "#ffffff", borderColor: "#d8ded6", borderRadius: 8, borderWidth: 1, gap: 12, padding: 14 },
  panelTitle: { color: "#18211f", fontSize: 20, fontWeight: "900" },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 10 },
  action: { alignItems: "center", backgroundColor: "#18211f", borderRadius: 8, minHeight: 54, justifyContent: "center", paddingHorizontal: 16, width: "47%" },
  actionText: { color: "#ffffff", fontSize: 16, fontWeight: "900" },
  actionDisabled: { opacity: 0.45 },
  actionPressed: { opacity: 0.8 },
  input: { borderColor: "#d8ded6", borderRadius: 8, borderWidth: 1, color: "#18211f", minHeight: 46, paddingHorizontal: 12 },
  alert: { backgroundColor: "#f8faf6", borderRadius: 8, gap: 4, padding: 12 },
  alertTitle: { color: "#18211f", fontWeight: "900" },
  body: { color: "#3b4742", lineHeight: 20 },
  meta: { color: "#66736d", fontSize: 12, fontWeight: "700" },
  planItem: { borderTopColor: "#d8ded6", borderTopWidth: 1, color: "#3b4742", fontWeight: "700", paddingTop: 10 },
  queueItem: { borderColor: "#d8ded6", borderRadius: 8, borderWidth: 1, gap: 4, padding: 10 },
  loading: { alignItems: "center", flexDirection: "row", gap: 8 },
});
