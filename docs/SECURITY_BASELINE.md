# Security Baseline

## Non-Negotiable

1. Organizer broadcasts must be signed.
2. Clients must reject unsigned or expired alerts.
3. Sensitive SOS details must be encrypted before storage or relay.
4. Devices should store minimal personal data.
5. Every dashboard action needs an audit log.
6. Event join should use signed QR invites.
7. Rate limits must exist for SOS, reports, and broadcast relay.

## Threats

1. Fake alerts causing panic.
2. Malicious relay nodes modifying messages.
3. Phone seizure or loss.
4. Spam flooding the mesh.
5. Sensitive identity leakage.

## Design Rule

Mesh nodes can relay messages, but they should not be able to read or alter protected content.
