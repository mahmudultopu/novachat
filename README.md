# NovaChat

A fast, private messenger for Android. This repository holds the native
Android app and the Go backend.

This is the **first working slice**: sign up with a phone number and send
text messages between two phones in real time, with sent, delivered and read
ticks and a typing indicator. End-to-end encryption, media, groups, calls and
push notifications come next (see [Roadmap](#roadmap)).

```
novachat/
  android/   Kotlin + Jetpack Compose app
  server/    Go API + WebSocket gateway
  infra/     docker-compose for local development
```

## What you need on your laptop

| Tool | Why | Get it |
| --- | --- | --- |
| Docker Desktop | Runs Postgres, Redis and the server | https://www.docker.com/products/docker-desktop |
| Android Studio (latest stable) | Builds the app and runs emulators | https://developer.android.com/studio |
| Go 1.23+ (optional) | Only if you want to run the server without Docker | https://go.dev/dl |

8 GB of RAM works; 16 GB is comfortable when running two emulators.

## 1. Start the backend

```bash
docker compose -f infra/docker-compose.yml up --build
```

This starts Postgres, Redis and the NovaChat server on
<http://localhost:8080>. The server creates its tables on first start. Check it
with:

```bash
curl http://localhost:8080/healthz      # prints "ok"
```

The local server runs in **dev mode**: no SMS is sent, and every phone number
accepts the code **123456** (the app also shows the code on screen). Change
it with `NOVACHAT_DEV_OTP` in `infra/docker-compose.yml`.

Stop everything with `Ctrl+C`, or `docker compose -f infra/docker-compose.yml down`
(add `-v` to wipe the database).

<details>
<summary>Running the server without Docker</summary>

Start only the databases with Docker, then run the server with Go:

```bash
docker compose -f infra/docker-compose.yml up -d postgres redis
cd server
go run ./cmd/novachat
```

Settings come from environment variables:

| Variable | Default |
| --- | --- |
| `NOVACHAT_ENV` | `dev` (`prod` turns off the fixed code; an SMS provider is then required) |
| `NOVACHAT_HTTP_ADDR` | `:8080` (or `:$PORT` when `PORT` is set) |
| `NOVACHAT_DATABASE_URL` | `postgres://novachat:novachat@localhost:5432/novachat?sslmode=disable` |
| `NOVACHAT_REDIS_URL` | `redis://localhost:6379/0` |
| `NOVACHAT_DEV_OTP` | `123456` |

</details>

## 2. Run the app on two emulators

1. Open the `android/` folder in Android Studio and let Gradle sync.
2. Open **Device Manager** and create two virtual devices (for example a
   Pixel 8 on API 35 and a Pixel 7 on API 34).
3. Start the first emulator, pick it in the device dropdown and press **Run**.
4. Start the second emulator, pick it and press **Run** again. Both now have
   NovaChat installed.

The emulator reaches your laptop at `10.0.2.2`, so the app talks to
`http://10.0.2.2:8080` out of the box.

### Send your first message

1. On emulator A, sign up with `+880 1711111111`, enter code `123456`, and
   set the name "Alice".
2. On emulator B, sign up with `+880 1722222222`, code `123456`, name "Bob".
3. On A, tap **+**, enter `+8801722222222` and tap **Start chat**.
4. Send a message. It appears on B instantly. When B opens the chat, A's
   ticks turn aqua (read). Typing on one phone shows "typing…" on the other.

Try switching one emulator to airplane mode, sending a few messages, and
switching it back: the messages wait with a clock icon and go out when the
connection returns.

## 3. Test on a real phone

1. Put the phone and laptop on the same Wi-Fi network.
2. Find your laptop's IP address (`ipconfig` on Windows, `ipconfig getifaddr en0`
   on Mac, `hostname -I` on Linux), e.g. `192.168.1.20`.
3. Enable **Developer options → USB debugging** on the phone, plug it in and
   press **Run** in Android Studio.
4. On the sign-in screen tap the gear icon and set the server address to
   `http://192.168.1.20:8080`.

You can also set the default for every build in `android/gradle.properties`
(`novachat.serverUrl`). Allow port 8080 through your laptop's firewall if the
phone can't connect.

### Just want to try it?

Every change merged to `main` publishes a ready-to-install test build that
talks to the online test server:

**<https://github.com/mahmudultopu/novachat/releases/download/latest/novachat.apk>**

Open that link on an Android phone, allow installing from your browser when
asked, and tap **Install**. New builds install over the old one and keep your
chats. (If you installed a build from before version 0.2.0, uninstall it once
first, because older builds were signed with a different key.)

Each pull request's CI run also builds a debug APK you can download from the
run's **Artifacts** section.

## 4. Put the server online

`render.yaml` deploys the server, Postgres and Redis to
[Render](https://render.com) on the free plan, in the Singapore region, so a
phone can chat from anywhere.

1. Sign up at <https://dashboard.render.com/register> with **GitHub**.
2. In the dashboard click **New > Blueprint**, connect GitHub, and pick the
   `novachat` repository.
3. Click **Deploy Blueprint**. The first build takes about five minutes.
4. Open the **novachat** web service and copy its address, such as
   `https://novachat-abcd.onrender.com`. Opening
   `https://<address>/healthz` in a browser should show `ok`.
5. In the app, tap the gear icon on the sign-in screen and enter that address,
   or set `novachat.serverUrl` in `android/gradle.properties` so new builds
   use it by default.

The online server runs in **dev mode** like the local one: no SMS is sent and
every number accepts `123456`, so treat it as a test server. Free-plan limits
to know about: the server sleeps after 15 minutes without traffic and takes
about a minute to wake up, the free database expires after 30 days, and the
free Redis keeps nothing on restart (only sign-in codes live there). Upgrade
the plans in Render when you need it always on.

## How it works

```
 Android app ──HTTPS──▶ /v1/auth, /v1/users, /v1/sync  ──▶ Postgres
      │
      └────WebSocket──▶ /v1/ws  ◀──▶ Redis pub/sub (routes events between server instances)
```

* **Sign-up**: the app asks for a code (`POST /v1/auth/request-code`), then
  verifies it (`POST /v1/auth/verify`) and gets a bearer token for this
  device. Codes live in Redis for 5 minutes, with limits on requests and wrong
  attempts. Only a hash of each token is stored in Postgres.
* **On the phone**: chats are stored in a Room database (`data/local`). The
  screens read from it directly, and the sync code writes server data into
  it, so the app works the same online and offline.
* **Messaging**: the app keeps one WebSocket open and reconnects with backoff
  when it drops. After every connect it calls `GET /v1/sync?after=<last id>`
  to catch up, then resends anything still pending. Each message carries a
  `client_id`, so a resend never creates a duplicate.
* **Scaling**: every server instance subscribes in Redis to the users
  connected to it, so you can run several behind a load balancer. The guide's
  next step replaces Redis pub/sub with NATS JetStream and moves message
  storage to ScyllaDB.

### End-to-end encryption

Every message is encrypted on the sender's phone and decrypted on the
recipient's phone, using the same design as Signal (and WhatsApp):

* **Keys** (`android/.../crypto`): each install creates an identity key pair
  (X25519 for key agreement, Ed25519 for signing), a signed prekey that is
  replaced weekly, and batches of 100 one-time prekeys. Private keys never
  leave the phone; they are stored in the chat database sealed with an AES key
  held in the Android Keystore (`data/Vault.kt`).
* **Key directory** (`server/internal/keys`): phones publish public keys with
  `PUT /v1/keys`; `GET /v1/keys/{user}` hands out one bundle and deletes the
  one-time prekey it used. The server checks the prekey signature.
* **Sessions**: the first message runs X3DH to agree on a secret, then a Double
  Ratchet session gives every message its own key (forward secrecy) and heals
  after a key leak (post-compromise security). Lost, delayed and reordered
  messages still decrypt; tampered ones are rejected.
* **What the server sees**: who talks to whom and when, plus an opaque
  `envelope`. It cannot read message text.
* **Verify**: tap the lock notice at the top of a chat to see the 60-digit
  safety number. If it matches on both phones, nobody is intercepting.
* **One phone per account**: like WhatsApp, signing in on a new phone
  publishes new keys and signs the old phone out. Messages sent to the old
  keys cannot be read on the new phone. The sender's app notices the key change
  (`stale_identity`), sets up a new session and resends automatically.

The crypto core has unit tests (`android/app/src/test`): two simulated phones,
lost and shuffled messages, tampering, replay, both people starting at once,
and a forged prekey.

### Photos, videos, voice notes and documents

Tap the paperclip in a chat for **Photos & videos**, **Camera** or
**Document**; tap the microphone to record a voice message, then the send
button to send it (or the bin to discard it).

* Photos are resized to at most 1600 px and re-encoded as JPEG on the phone,
  which keeps them around 200 to 400 KB and strips location data. Videos and
  documents are sent as they are, up to 16 MB. Voice notes are mono AAC at
  32 kbps (about 240 KB a minute).
* Every file is encrypted on the phone with its own AES-256-GCM key
  (`crypto/AttachmentCipher.kt`) before upload. The key and the SHA-256 of the
  encrypted file travel only inside the end-to-end encrypted message, so the
  server stores bytes it can neither read nor swap.
* The server (`server/internal/media`) keeps the encrypted blobs in Postgres
  for 30 days (`POST /v1/attachments`, `GET /v1/attachments/{id}`). That is
  fine for testing; move them to S3-compatible storage (Cloudflare R2,
  Backblaze B2, MinIO) before real traffic.
* Photos and voice notes download automatically when you open the chat;
  videos and documents download when tapped. Decrypted files live in the
  app's private storage and are deleted on log out.

### Profiles, last seen and online

Settings → tap your card to set a **profile photo**, your **name** and an
**about** line (up to 140 characters). Tap the name at the top of a chat to
see the other person's photo, about and the encryption check.

* Photos are cropped square and resized to 640 px on the phone (about 60 to
  120 KB), uploaded with `PUT /v1/me/avatar` and cached on other phones.
  Every new photo gets a new id, so a cached copy never goes stale.
* The chat header shows **typing…**, then **online**, then **last seen today
  at 14:05**. A phone counts as online while its WebSocket is connected; the
  server keeps a short-lived key per user in Redis (`nc:online:<id>`, 75 s,
  refreshed every 30 s) so it works across several server instances.
* When someone comes online or goes offline, the people they have chatted with
  get a `presence` frame. An app opening a chat asks with `presence_query`.
  Profile photos, unlike messages, are not end-to-end encrypted, the same as
  on most messengers.

### Reply, react, edit, delete, forward, star

Long-press any message for the quick reactions and the actions: **Reply**,
**Copy text**, **Forward** (to one or more chats), **Star**, **Edit** (your
own text messages, for 15 minutes) and **Delete** (for you, or for everyone
within 48 hours). Starred messages from every chat are in Settings →
Starred messages.

* Reactions, edits and deletes are small hidden messages that travel end to
  end encrypted like any other (`MessageContent.reaction`, `.edit`,
  `.delete`), so the server never learns who reacted with what or what a
  message was changed to. The receiving phone checks that only the author
  edits or deletes a message, and only within the time limits.
* A reply carries a pointer to the original (`reply_to`), never a copy of it;
  tap the quote to jump to the original message.
* Forwarding a photo, video or document does not upload it again: the copy
  points at the same encrypted file and carries the same key.
* Stars and "delete for me" stay on your phone.

### Groups

Tap **+** → **New group**, pick people (or add them by phone number) and
name the group. In the group, tap its name for the group info: photo, name,
description, members, admins, settings and the invite link.

* **End to end encrypted.** The phone encrypts a separate copy of every group
  message for each member over their 1:1 session and sends the copies as
  ordinary messages (`MessageContent.group_id`). A file is uploaded once and
  every copy carries its key. The server knows who is in a group but never
  what is said in it.
* **Admins** add and remove people, make others admin, reset or turn off the
  invite link, and can limit sending messages or editing the group info to
  admins. When the last admin leaves, the longest-standing member takes over.
  Each phone also checks that a message comes from a current member (and from
  an admin, when only admins may send).
* **Invite links** look like `https://<server>/join/<code>`. In a browser the
  link opens a small page with an "Open in NovaChat" button
  (`novachat://join/<code>`); in the app, **+** → **Join a group** accepts a
  pasted link. Resetting the link makes the old one stop working.
* Every change ("Ana added Ben") is written by the server as a message to each
  member, so phones that were offline see it in order with the chat.
* Ticks on your group messages turn grey once every member's phone got the
  message, and coloured once everyone has read it.

### Status updates

The **Status** tab shows photo, video and text updates that disappear after
24 hours. Tap the camera button to post a photo or video (with a caption),
or the pencil for a text status on a coloured background. Open an update to
watch it: tap right for the next one, left for the previous one, hold to
pause. On your own updates, tap 👁 to see who viewed them, or delete them.

* Status updates go to everyone you have a one-to-one chat with, encrypted to
  each of them like a message (`MessageContent.status`). A photo or video is
  uploaded once, encrypted, and every copy carries its key.
* "Seen" and "deleted" travel the same way, so the server never learns who
  posts what or who watched it. Each phone deletes updates after 24 hours.

### Notifications

New messages show a notification with the sender's photo, the last few
messages of that chat, and **Reply** and **Mark as read** buttons, so you can
answer without opening the app. Group messages are grouped per chat with each
sender's name.

* To get messages while the app is closed, NovaChat keeps its encrypted
  connection open in a small background service (a silent "Connected" note
  that Android requires). Turn it off under **Settings → Instant
  notifications**; messages then arrive the next time you open the app. No
  Google services are needed, and message text never leaves your phone
  unencrypted.
* The app tells the server when it is on screen (`{"type": "active"}`), so
  you only show as "online" while you actually have NovaChat open, not while
  it is connected in the background.
* The service starts again after the phone restarts.

### Voice and video calls

Open a one-to-one chat and tap the phone (voice) or camera (video) button at
the top. The **Calls** tab lists recent calls; tap the button on a row to call
back. During a call you can mute, switch to the speaker, turn your camera on
or off, and flip between cameras. A finished call shows up in the chat as
"Voice call · 2:31" or "Missed voice call".

* Calls go straight between the two phones with WebRTC. Audio and video are
  encrypted by WebRTC (DTLS-SRTP), and the offer and answer that set up those
  keys travel as end-to-end encrypted messages (`MessageContent.call`), so the
  server can't listen in or swap the keys.
* Incoming calls ring with the phone's ringtone and show over the lock screen,
  as long as **Settings → Instant notifications** is on.
* The app asks the server for STUN/TURN servers (`GET /v1/calls/ice`). STUN
  (Google's, by default) is enough when the phones can reach each other, such
  as on most Wi-Fi. On some mobile networks a **TURN relay** is needed. The
  relay only passes on packets that are already encrypted. To add one, set
  these on the Render service (**Environment**):
  * Cloudflare's TURN service: `NOVACHAT_CLOUDFLARE_TURN_KEY_ID` and
    `NOVACHAT_CLOUDFLARE_TURN_TOKEN`, from the Cloudflare dashboard under
    **Realtime → TURN Server → Create**. It's free for the first 1,000 GB a
    month.
  * Or your own coturn server: `NOVACHAT_TURN_URLS` (such as
    `turn:turn.example.com:3478`) and `NOVACHAT_TURN_SECRET`, its
    `static-auth-secret`.
* Calls need the native WebRTC library, built only for ARM phones to keep the
  app small. PC emulators with ARM translation still run it.

### Pin, mute and archive

Long-press a chat in the list:

* **Pin chat** keeps it at the top (up to 3 chats).
* **Mute notifications** for 8 hours, a week or always. Messages still arrive
  and count as unread, with a grey badge, but make no notification.
* **Archive chat** moves it under **Archived** at the top of the list. It
  stays there when new messages arrive; long-press it there to unarchive.

These choices live only on this phone (the `chat_prefs` table), so the server
never learns who you pinned or muted.

### Disappearing messages

Open a contact's or group's info and tap **Disappearing messages** to pick
24 hours, 7 days or 90 days (or Off). A line in the chat tells everyone who
changed it. In a group, only people who may edit the group info can change it.

* The timer travels inside the encrypted message (`timer_s`), and each new
  message carries its own time (`expire_s`), so the server can't tell which
  chats use it.
* Each phone wipes a message, and its file, when its time is up, counted from
  when it was sent. A hidden placeholder row stays so catching up with the
  server never fetches it again.
* Messages sent before the timer was turned on stay.

### API

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/auth/request-code` `{phone}` | – | Send a code (dev mode returns `dev_code`) |
| `POST /v1/auth/verify` `{phone, code, device_name}` | – | Returns `{token, user, is_new}` |
| `POST /v1/auth/logout` | Bearer | Ends this device's session |
| `GET /v1/me`, `PATCH /v1/me` `{name, about}` | Bearer | Your profile |
| `PUT /v1/me/avatar` (JPEG), `DELETE /v1/me/avatar` | Bearer | Set or remove your profile photo |
| `GET /v1/avatars/{id}` | Bearer | Download a profile photo |
| `GET /v1/users/by-phone?phone=` | Bearer | Find someone to chat with |
| `GET /v1/users/{id}` | Bearer | Someone's public profile |
| `GET /v1/conversations` | Bearer | Chats with last message and unread count |
| `GET /v1/messages?peer=&before=&limit=` | Bearer | Older messages in one chat |
| `GET /v1/sync?after=&limit=` | Bearer | Everything since a message id |
| `PUT /v1/keys` | Bearer | Publish identity, signed prekey and one-time prekeys |
| `GET /v1/keys/{id}` | Bearer | Claim someone's prekey bundle to start encryption |
| `POST /v1/attachments` (raw bytes) | Bearer | Upload an encrypted file, returns `{id}` |
| `GET /v1/attachments/{id}` | Bearer | Download an encrypted file |
| `POST /v1/groups` `{name, members}`, `GET /v1/groups` | Bearer | Create a group, list yours |
| `GET`, `PATCH /v1/groups/{id}` `{name, description, only_admins_send, only_admins_edit}` | Bearer | Read or change a group |
| `PUT`, `DELETE /v1/groups/{id}/avatar` | Bearer | Group photo |
| `POST /v1/groups/{id}/members` `{user_ids}` | Admin | Add people |
| `DELETE /v1/groups/{id}/members/{user}` | Admin, or yourself | Remove someone, or leave |
| `PUT /v1/groups/{id}/members/{user}` `{role}` | Admin | Make or dismiss an admin |
| `POST`, `DELETE /v1/groups/{id}/invite` | Admin | Reset or turn off the invite link |
| `GET /v1/invites/{code}`, `POST /v1/invites/{code}/join` | Bearer | Preview and join from a link |
| `GET /join/{code}` | – | Invite page that opens the app |
| `GET /v1/calls/ice` | Bearer | STUN/TURN servers for calls |
| `GET /v1/ws` | Bearer | Realtime WebSocket |

### WebSocket frames (JSON)

App to server:

```json
{"type": "send", "client_id": "<uuid>", "to": "<user id>", "envelope": "<encrypted>", "identity_key": "<their identity key, base64>"}
{"type": "receipt", "status": "delivered", "message_ids": [42, 43]}
{"type": "receipt", "status": "read", "message_ids": [42, 43]}
{"type": "typing", "to": "<user id>"}
{"type": "presence_query", "to": "<user id>"}
{"type": "active", "active": false}
```

Server to app:

```json
{"type": "ready", "user_id": "<your id>"}
{"type": "message", "message": {"id": 42, "client_id": "...", "sender_id": "...", "recipient_id": "...", "body": "Hello", "created_at": "...", "delivered_at": null, "read_at": null}}
{"type": "receipt", "status": "read", "message_ids": [42], "at": "...", "from": "<reader id>"}
{"type": "typing", "from": "<user id>"}
{"type": "presence", "user_id": "<user id>", "online": false, "last_seen_at": "..."}
{"type": "error", "code": "bad_recipient", "error": "...", "client_id": "..."}
```

The sender also receives the `message` frame for its own message; that is the
"sent" tick.

## Running the tests

```bash
cd server
go test ./...                       # unit tests

# end-to-end test (two users chatting over real WebSockets):
docker compose -f ../infra/docker-compose.yml up -d postgres redis
docker compose -f ../infra/docker-compose.yml exec postgres createdb -U novachat novachat_test
NOVACHAT_TEST_DATABASE_URL="postgres://novachat:novachat@localhost:5432/novachat_test?sslmode=disable" \
NOVACHAT_TEST_REDIS_URL="redis://localhost:6379/15" go test ./...
```

GitHub Actions runs the server tests and builds the Android app on every pull
request.

## Roadmap

Following the builder guide, in order:

1. ~~**Local storage**~~ (done in 0.2.0): chats live in a Room database on
   the phone, so they open instantly, work offline and survive restarts. Only
   new messages are fetched after a reconnect. SQLCipher encryption of that
   database and a Keystore-protected session token come with step 2.
2. ~~**End-to-end encryption**~~ (done in 0.3.0): see
   [End-to-end encryption](#end-to-end-encryption) below.
3. ~~**Media**~~ (done in 0.4.0): photos, videos, voice notes and documents,
   see [Photos, videos, voice notes and documents](#photos-videos-voice-notes-and-documents).
4. ~~**Profiles and presence**~~ (done in 0.5.0): profile photo, about, last
   seen and online, see [Profiles, last seen and online](#profiles-last-seen-and-online).
5. ~~**Message actions**~~ (done in 0.6.0): reply, react, edit, delete for
   everyone, forward, star and copy, see
   [Reply, react, edit, delete, forward, star](#reply-react-edit-delete-forward-star).
6. ~~**Groups**~~ (done in 0.7.0): admins, settings and invite links, see
   [Groups](#groups).
7. ~~**Status updates**~~ (done in 0.8.0), see [Status updates](#status-updates).
8. ~~**Notifications**~~ (done in 0.9.0): quick reply and mark as read, see
   [Notifications](#notifications).
9. ~~**Voice and video calls**~~ (done in 0.10.0): one-to-one, see
   [Voice and video calls](#voice-and-video-calls). Group calls come next.
10. **Chat controls**: ~~pin, mute and archive~~ (done in 0.11.0, see
    [Pin, mute and archive](#pin-mute-and-archive)), ~~disappearing
    messages~~ (done in 0.12.0, see [Disappearing messages](#disappearing-messages));
    block, report and privacy settings next.
11. A real **SMS provider** for production sign-up, and deployment.
