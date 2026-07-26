import { useMemo, useState } from "react";
import {
  Alert,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import type { JoinedEvent } from "../navigation/types";

export function JoinEventScreen({
  onJoined,
}: {
  onJoined: (event: JoinedEvent) => void;
}) {
  const [inviteCode, setInviteCode] = useState("");
  const canContinue = useMemo(() => inviteCode.trim().length >= 4, [inviteCode]);

  return (
    <SafeAreaView style={styles.safe}>
      <KeyboardAvoidingView
        behavior={Platform.OS === "ios" ? "padding" : undefined}
        style={styles.keyboard}
      >
        <View style={styles.content}>
          <Text style={styles.kicker}>CrowdOS</Text>
          <Text style={styles.title}>Join a verified event</Text>
          <Text style={styles.description}>
            Scan the organizer QR code to load offline plans and trusted signing keys.
          </Text>

          <Pressable
            accessibilityRole="button"
            style={styles.qrBox}
            onPress={() =>
              Alert.alert(
                "QR scanner",
                "Camera scanning will be connected after the event verification service is ready."
              )
            }
          >
            <View style={styles.qrPattern}>
              <View style={styles.qrCorner} />
              <View style={styles.qrCorner} />
              <View style={styles.qrCorner} />
              <View style={styles.qrDot} />
            </View>
            <Text style={styles.qrTitle}>Scan event QR</Text>
          </Pressable>

          <TextInput
            autoCapitalize="characters"
            autoCorrect={false}
            onChangeText={setInviteCode}
            placeholder="Enter invite code"
            placeholderTextColor="#6b716e"
            style={styles.input}
            value={inviteCode}
          />

          <Pressable
            accessibilityRole="button"
            disabled={!canContinue}
            style={({ pressed }) => [
              styles.primaryButton,
              !canContinue && styles.disabledButton,
              pressed && canContinue && styles.pressedButton,
            ]}
            onPress={() => onJoined({ eventId: inviteCode.trim().toUpperCase() })}
          >
            <Text style={styles.primaryButtonText}>Continue securely</Text>
          </Pressable>

          <Text style={styles.language}>Language: English  |  Hindi</Text>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: "#f7f8f4" },
  keyboard: { flex: 1 },
  content: { flex: 1, justifyContent: "center", padding: 24 },
  kicker: {
    color: "#66736d",
    fontSize: 14,
    fontWeight: "800",
    letterSpacing: 1,
    marginBottom: 12,
    textTransform: "uppercase",
  },
  title: { color: "#18211f", fontSize: 32, fontWeight: "900" },
  description: {
    color: "#3b4742",
    fontSize: 17,
    lineHeight: 25,
    marginTop: 12,
  },
  qrBox: {
    alignItems: "center",
    backgroundColor: "#ffffff",
    borderColor: "#b6beb9",
    borderRadius: 8,
    borderWidth: 2,
    marginTop: 30,
    padding: 24,
  },
  qrPattern: {
    alignItems: "center",
    borderColor: "#242b28",
    borderWidth: 2,
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 12,
    height: 126,
    justifyContent: "center",
    padding: 12,
    width: 126,
  },
  qrCorner: { borderColor: "#242b28", borderWidth: 5, height: 32, width: 32 },
  qrDot: { backgroundColor: "#242b28", height: 18, width: 18 },
  qrTitle: { color: "#18211f", fontSize: 18, fontWeight: "800", marginTop: 16 },
  input: {
    backgroundColor: "#ffffff",
    borderColor: "#b6beb9",
    borderRadius: 8,
    borderWidth: 1,
    color: "#18211f",
    fontSize: 16,
    marginTop: 16,
    minHeight: 54,
    paddingHorizontal: 16,
  },
  primaryButton: {
    alignItems: "center",
    backgroundColor: "#18211f",
    borderRadius: 8,
    justifyContent: "center",
    marginTop: 14,
    minHeight: 58,
  },
  primaryButtonText: { color: "#ffffff", fontSize: 17, fontWeight: "900" },
  disabledButton: { opacity: 0.4 },
  pressedButton: { opacity: 0.8 },
  language: { color: "#66736d", fontSize: 13, marginTop: 18, textAlign: "center" },
});
