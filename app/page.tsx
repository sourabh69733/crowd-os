"use client";

import { useMemo, useState } from "react";

type Incident = {
  id: string;
  type: string;
  zone: string;
  priority: "Critical" | "High" | "Watch";
  age: string;
  status: "New" | "Dispatching" | "Stabilized";
  detail: string;
};

const incidents: Incident[] = [
  {
    id: "SOS-1048",
    type: "Medical",
    zone: "Gate 3",
    priority: "Critical",
    age: "2m",
    status: "New",
    detail: "Three people down near the west barricade. First-aid tent is 180m away.",
  },
  {
    id: "RPT-8821",
    type: "Crowding",
    zone: "North Spine",
    priority: "High",
    age: "6m",
    status: "Dispatching",
    detail: "Density rising above comfort threshold; one exit corridor is blocked.",
  },
  {
    id: "LEG-0314",
    type: "Legal Help",
    zone: "Media Row",
    priority: "Watch",
    age: "14m",
    status: "Stabilized",
    detail: "Volunteer legal desk has acknowledged and shared meetup pin.",
  },
];

const zones = [
  { name: "Gate 1", density: 48, volunteers: 14, risk: "Steady" },
  { name: "Gate 3", density: 91, volunteers: 8, risk: "Critical" },
  { name: "North Spine", density: 84, volunteers: 11, risk: "High" },
  { name: "Medical Camp", density: 36, volunteers: 19, risk: "Clear" },
  { name: "Water Point", density: 63, volunteers: 7, risk: "Rising" },
  { name: "South Exit", density: 42, volunteers: 16, risk: "Clear" },
];

const lostPeople = [
  { name: "Aarav M.", age: "8", checkpoint: "Blue Flag", lastSeen: "Gate 2" },
  { name: "Leela R.", age: "72", checkpoint: "Medical Camp", lastSeen: "North Spine" },
  { name: "Imran K.", age: "11", checkpoint: "Water Point", lastSeen: "Food Lane" },
];

const responseTeams = [
  { role: "Medical", assigned: 18, available: 6, zone: "Gate 3" },
  { role: "Route marshals", assigned: 24, available: 9, zone: "North Spine" },
  { role: "Water", assigned: 12, available: 4, zone: "Water Point" },
  { role: "Legal", assigned: 8, available: 3, zone: "Media Row" },
];

export default function Home() {
  const [selectedZone, setSelectedZone] = useState(zones[1]);
  const [selectedIncident, setSelectedIncident] = useState(incidents[0]);
  const [announcement, setAnnouncement] = useState(
    "Medical volunteers move to Gate 3. Route marshals open South Exit overflow."
  );
  const [meshEnabled, setMeshEnabled] = useState(true);

  const activePriority = useMemo(
    () => incidents.filter((incident) => incident.priority !== "Watch").length,
    []
  );

  return (
    <main className="min-h-screen bg-[#f7f8f4] text-[#18211f]">
      <section className="shell">
        <header className="topbar" aria-label="CrowdOS command status">
          <div>
            <p className="eyebrow">Emergency communication and crowd management</p>
            <h1>CrowdOS</h1>
          </div>
          <div className="status-strip" aria-label="Live network health">
            <span className="signal-dot" />
            <span>{meshEnabled ? "Mesh relay active" : "Mesh relay paused"}</span>
            <button
              className="icon-button"
              type="button"
              aria-label={meshEnabled ? "Pause mesh relay" : "Resume mesh relay"}
              title={meshEnabled ? "Pause mesh relay" : "Resume mesh relay"}
              onClick={() => setMeshEnabled((enabled) => !enabled)}
            >
              {meshEnabled ? "||" : ">"}
            </button>
          </div>
        </header>

        <section className="hero-grid">
          <div className="command-panel">
            <div className="panel-heading">
              <div>
                <p className="eyebrow">Live command center</p>
                <h2>Keep people moving, informed, and reachable.</h2>
              </div>
              <div className="incident-counter">
                <strong>{activePriority}</strong>
                <span>urgent lanes</span>
              </div>
            </div>

            <div className="map-surface" aria-label="Crowd density map">
              {zones.map((zone, index) => (
                <button
                  key={zone.name}
                  className={`zone zone-${index + 1} ${selectedZone.name === zone.name ? "selected" : ""}`}
                  type="button"
                  style={{ "--density": `${zone.density}%` } as React.CSSProperties}
                  onClick={() => setSelectedZone(zone)}
                >
                  <span>{zone.name}</span>
                  <strong>{zone.density}%</strong>
                </button>
              ))}
              <div className="route route-main" />
              <div className="route route-alt" />
            </div>
          </div>

          <aside className="zone-detail" aria-label="Selected zone">
            <p className="eyebrow">Selected zone</p>
            <h2>{selectedZone.name}</h2>
            <div className="density-meter">
              <span style={{ width: `${selectedZone.density}%` }} />
            </div>
            <dl>
              <div>
                <dt>Density</dt>
                <dd>{selectedZone.density}%</dd>
              </div>
              <div>
                <dt>Volunteers</dt>
                <dd>{selectedZone.volunteers}</dd>
              </div>
              <div>
                <dt>Risk</dt>
                <dd>{selectedZone.risk}</dd>
              </div>
            </dl>
            <button className="primary-action" type="button">
              Dispatch route team
            </button>
          </aside>
        </section>

        <section className="workspace-grid">
          <div className="incident-list" aria-label="Incident triage">
            <div className="section-title">
              <p className="eyebrow">SOS and reports</p>
              <h2>Incident triage</h2>
            </div>
            {incidents.map((incident) => (
              <button
                key={incident.id}
                className={`incident-card ${selectedIncident.id === incident.id ? "active" : ""}`}
                type="button"
                onClick={() => setSelectedIncident(incident)}
              >
                <span className={`priority ${incident.priority.toLowerCase()}`}>
                  {incident.priority}
                </span>
                <strong>{incident.type}</strong>
                <span>{incident.zone} · {incident.age}</span>
              </button>
            ))}
          </div>

          <article className="incident-detail">
            <div className="section-title">
              <p className="eyebrow">{selectedIncident.id}</p>
              <h2>{selectedIncident.type} response</h2>
            </div>
            <p>{selectedIncident.detail}</p>
            <div className="response-row">
              <button className="primary-action" type="button">Assign volunteers</button>
              <button className="secondary-action" type="button">Broadcast update</button>
            </div>
            <div className="checklist">
              <span>Confirm location</span>
              <span>Send nearest trained volunteer</span>
              <span>Mark route as safe or blocked</span>
            </div>
          </article>

          <div className="mesh-composer">
            <div className="section-title">
              <p className="eyebrow">Offline mesh</p>
              <h2>Priority broadcast</h2>
            </div>
            <textarea
              aria-label="Priority broadcast message"
              value={announcement}
              onChange={(event) => setAnnouncement(event.target.value)}
            />
            <div className="broadcast-meta">
              <span>Relays: Bluetooth, Wi-Fi Direct, SMS fallback</span>
              <button className="primary-action" type="button">Queue alert</button>
            </div>
          </div>

          <div className="teams">
            <div className="section-title">
              <p className="eyebrow">Volunteer coordination</p>
              <h2>Team load</h2>
            </div>
            {responseTeams.map((team) => (
              <div className="team-row" key={team.role}>
                <div>
                  <strong>{team.role}</strong>
                  <span>{team.zone}</span>
                </div>
                <div className="team-count">
                  <strong>{team.available}</strong>
                  <span>/ {team.assigned}</span>
                </div>
              </div>
            ))}
          </div>

          <div className="lost-board">
            <div className="section-title">
              <p className="eyebrow">Lost person system</p>
              <h2>Meetup board</h2>
            </div>
            {lostPeople.map((person) => (
              <div className="person-row" key={person.name}>
                <div className="avatar" aria-hidden="true">{person.name.slice(0, 1)}</div>
                <div>
                  <strong>{person.name}, {person.age}</strong>
                  <span>Last seen: {person.lastSeen}</span>
                </div>
                <b>{person.checkpoint}</b>
              </div>
            ))}
          </div>

          <div className="playbook">
            <div className="section-title">
              <p className="eyebrow">Preplanned fallback</p>
              <h2>Emergency playbook</h2>
            </div>
            <ol>
              <li>Open South Exit when North Spine exceeds 80% density.</li>
              <li>Move medical team to Gate 3 and keep one stretcher lane clear.</li>
              <li>Sync incident media locally; upload encrypted backups when online.</li>
            </ol>
          </div>
        </section>
      </section>
    </main>
  );
}
