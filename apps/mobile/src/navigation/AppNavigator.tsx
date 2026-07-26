import { useState } from "react";

import type { JoinedEvent, RootScreen } from "./types";
import { HomeScreen } from "../screens/HomeScreen";
import { JoinEventScreen } from "../screens/JoinEventScreen";

export function AppNavigator() {
  const [screen, setScreen] = useState<RootScreen>("join-event");
  const [event, setEvent] = useState<JoinedEvent | null>(null);

  if (screen === "join-event" || !event) {
    return (
      <JoinEventScreen
        onJoined={(joinedEvent) => {
          setEvent(joinedEvent);
          setScreen("home");
        }}
      />
    );
  }

  return <HomeScreen eventId={event.eventId} />;
}
