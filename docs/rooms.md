# Rooms with a friend

The **Pads** tab plays on its own or inside a room. In a room, one person creates it and shares a six-character
code, a friend joins with the code, and every touch on a pad is heard by both of you as it happens. Voice and a
chat open from the room bar above the pads.

Rooms need the small server in [`services/rooms`](../services/rooms/README.md). It is separate from the account
and payment server, keeps nothing on disk and does not need a database. A build without a server address shows
the pads on their own and hides the room buttons.

## Point the app at a server

The address comes from the `mural.roomsOrigin` Gradle property, set either on the command line or in
`apps/android/local.properties` (which Git ignores):

```properties
mural.roomsOrigin=wss://rooms.example.com
```

Release builds accept only `wss://` with a bare host, so friends' touches and chat are always encrypted. Debug
builds also accept plain `ws://` for `10.0.2.2`, `localhost` and `127.0.0.1`, so you can try rooms without a
certificate.

## Try it on your own computer

1. Start the server: `cd services/rooms && npm install && npm run dev`.
2. Build and install the debug app pointing at it:

   ```sh
   cd apps/android
   ./gradlew :app:installDebug -Pmural.roomsOrigin=ws://127.0.0.1:8787
   ```

3. Let each device reach the server on your computer. Run this once per emulator or USB-connected phone:

   ```sh
   adb reverse tcp:8787 tcp:8787
   ```

4. On one device tap **Create room**, on **Home** or above the pads. On the other, tap **Join with code** and type
   the code. Creating or joining takes you to the pads. Use headphones on both.

## Try it with a friend somewhere else

Your friend needs a build that points at an address they can reach over TLS. Expose the local server with a
tunnel that provides HTTPS, for example `cloudflared tunnel --url http://localhost:8787`, then build with the
`wss://` form of the address it prints:

```sh
./gradlew :app:assembleDebug -Pmural.roomsOrigin=wss://your-tunnel-name.trycloudflare.com
```

Send the APK from `apps/android/app/build/outputs/apk/debug/`. Anyone with the address and a code can join a
room that has space, so stop the tunnel when you are done. Nothing here deploys a server for you.

## What to expect

- Sliding, tapping and holding pads sound the same for your friend as for you, including left-to-right
  position. Movement is sent about 30 times a second, so a slow connection loses some detail, not time.
- If a phone leaves the app or the Pads tab while a finger is down, its sounds stop for both people.
- If a connection drops, the bar says so and **Rejoin** returns to the same room while it is still open. A room
  stays open for two minutes after everyone leaves and for at most twelve hours.
- Friends' sounds play only while the Pads tab is open. Chat, voice and presence keep working on Home.
- Rooms hold two people for now. The server can allow up to eight (`ROOM_MAX_PEERS`), but the app shows the
  friends' names in one line and has not been designed for more.
