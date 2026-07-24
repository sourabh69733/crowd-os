# CrowdOS Build Order

## Now

1. Mobile field app
   - Offline event plan
   - SOS queue
   - Verified organizer broadcasts
   - Store-and-forward sync

2. Secure message core
   - Signed messages
   - Trusted organizer keys
   - Encrypted sensitive payloads
   - Replay/expiry checks

3. Organizer dashboard
   - Incident triage
   - Broadcast creation
   - Volunteer dispatch

## Next Native Transport

1. Android Nearby Connections
2. iOS Multipeer Connectivity
3. Gateway relay when any device regains internet

Full 1.5 km coverage needs enough phones distributed through the area. Without an internet gateway, CrowdOS can move app messages locally, not provide general internet.
