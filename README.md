# Sound rooms

Make sounds for a friend, live. One person creates a room and shares a six-character code, the other joins, and every touch on a pad is heard by both of you as it happens: brushing, tapping, rain, a fire. Talk over voice or chat while you play. Best with headphones.

<p align="center"><em>Android app in <code>apps/android</code>, room server in <code>services/rooms</code>.</em></p>

## What is here

| Path | What it is |
|---|---|
| [`apps/android`](apps/android/README.md) | The Android app: lobby, room, pads, voice and chat |
| [`services/rooms`](services/rooms/README.md) | The small WebSocket server that puts friends in a room. In memory, no accounts, no database |
| [`docs/rooms.md`](docs/rooms.md) | How to run the server and try the app with a friend |
| [`docs/sounds.md`](docs/sounds.md) | Where every sound comes from (all CC0) |
| [`scripts/build-sounds.py`](scripts/build-sounds.py) | Downloads and prepares the sounds |

## Try it

```sh
# 1. Start the room server
cd services/rooms && npm install && npm run dev

# 2. Build and install the app, pointing it at that server
cd apps/android && ./gradlew :app:installDebug -Pmural.roomsOrigin=ws://127.0.0.1:8787

# 3. Let each device reach the server on your computer
adb reverse tcp:8787 tcp:8787
```

Then open the app on two devices: **Create room** on one, **Join with code** on the other. [docs/rooms.md](docs/rooms.md) has the details, including trying it with a friend on another network.

## Where this came from

This started as [Chuloo/mural](https://github.com/Chuloo/mural), a language-learning app, and keeps its visual design, Google sign-in and purchase code under its MIT [license](LICENSE). The language-learning features were removed from the Android app. `apps/ios` and `services/api` are the original iPhone app and account/payments server; they are not part of the sound rooms and are left as they were.

Before publishing anything, replace the name and identifiers (the name "Mural" belongs to an unrelated company), the privacy-policy and terms links in the account sheet, which still point to the original project's site, and the app icon.
