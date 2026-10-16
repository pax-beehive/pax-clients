# Native client preparation

## Objective

Let a user leave their computer, inspect an agent session on a phone, send the
next instruction, and resolve a pending approval. Agent execution remains on
existing paxd devices. A phone is a controller, not an agent execution host.

This is an initial proposal, not a confirmed complete product specification.
Default scope: native iPhone/iPad and Android applications, English UI matching
the existing workbench, with localization-ready resources. macOS follows later.

## First useful release

1. Sign in using the system browser; restore and revoke native access.
   Preserve existing Pax identity and assigned region.
2. Browse existing projects, devices, and sessions. Display connection state and
   explicit loading, failure, empty, and offline states.
3. Open an existing session, recover its history, and follow live output. Keep
   scroll position when earlier history loads.
4. Send an instruction, cancel a running turn, and decide an approval. Show
   pending versus applied actions; recover status after a lost connection.
5. Access encrypted sessions after device authorization. A signed-in user without
   keys sees a locked state. Verify interoperability with the existing web client
   and paxd before claiming messaging support.

New session creation is implemented in the Android local build, with project
targets, device and agent selection, and an explicit working directory. See
[mobile session creation](android-new-session.md) for validation and deployment status.

## Delivery sequence and completion gates

| Stage | Deliverable | Completion gate |
| --- | --- | --- |
| 0 | Native project shells and integration map | iOS simulator build; Android APK and lint; scope review |
| 1 | Native authentication and account bootstrap | Real sign-in, cancellation, restoration, logout, revoked credentials, correct region; Android first |
| 2 | Read-only projects, devices, sessions | Real account data; pagination; failure and offline handling |
| 3 | E2EE key access and history | Web/native/paxd interoperability vectors; wrong-key and tamper rejection; authorized key transfer |
| 4 | Live session and approval loop | Resume after backgrounding without duplicate text or duplicate commands; correct approval identity |
| 5 | Internal distribution | Physical-device checks, signing, privacy disclosures, release artifacts and distribution verification |

Android implementation and synthetic protocol validation are recorded in
`android-status.md`. Production checks in these gates remain open; passing local
tests does not complete the live integration gates. iOS development is now active;
see [iOS status](ios-status.md) for the implementation and remaining acceptance gates.

## Deferred

- Running local agents on mobile, installing or upgrading harnesses.
- Web feature parity, administration, billing, collaboration management.
- Browser remote control, voice, file upload, and offline command queues.
- Push notifications: add APNs/FCM registration and server delivery first;
  foreground streaming cannot substitute for background push. Push payloads
  should avoid plaintext encrypted-session content.
- macOS app distribution. Reuse Swift feature packages but design desktop
  windows, menus, keyboard commands, and sidebar navigation for macOS.

## Product decisions still open

Confirm minimum OS support, final application identifiers, distribution accounts,
first-release localization, and whether notification delivery is required for
launch. These do not block the initial shells.
