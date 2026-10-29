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

Each pull request's CI run also builds a debug APK you can download from the
run's **Artifacts** section and install on any Android phone.

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
* **Messaging**: the app keeps one WebSocket open and reconnects with backoff
  when it drops. After every connect it calls `GET /v1/sync?after=<last id>`
  to catch up, then resends anything still pending. Each message carries a
  `client_id`, so a resend never creates a duplicate.
* **Scaling**: every server instance subscribes in Redis to the users
  connected to it, so you can run several behind a load balancer. The guide's
  next step replaces Redis pub/sub with NATS JetStream and moves message
  storage to ScyllaDB.

### API

| Method and path | Auth | Purpose |
| --- | --- | --- |
| `POST /v1/auth/request-code` `{phone}` | – | Send a code (dev mode returns `dev_code`) |
| `POST /v1/auth/verify` `{phone, code, device_name}` | – | Returns `{token, user, is_new}` |
| `POST /v1/auth/logout` | Bearer | Ends this device's session |
| `GET /v1/me`, `PATCH /v1/me` `{name, about}` | Bearer | Your profile |
| `GET /v1/users/by-phone?phone=` | Bearer | Find someone to chat with |
| `GET /v1/users/{id}` | Bearer | Someone's public profile |
| `GET /v1/conversations` | Bearer | Chats with last message and unread count |
| `GET /v1/messages?peer=&before=&limit=` | Bearer | Older messages in one chat |
| `GET /v1/sync?after=&limit=` | Bearer | Everything since a message id |
| `GET /v1/ws` | Bearer | Realtime WebSocket |

### WebSocket frames (JSON)

App to server:

```json
{"type": "send", "client_id": "<uuid>", "to": "<user id>", "body": "Hello"}
{"type": "receipt", "status": "delivered", "message_ids": [42, 43]}
{"type": "receipt", "status": "read", "message_ids": [42, 43]}
{"type": "typing", "to": "<user id>"}
```

Server to app:

```json
{"type": "ready", "user_id": "<your id>"}
{"type": "message", "message": {"id": 42, "client_id": "...", "sender_id": "...", "recipient_id": "...", "body": "Hello", "created_at": "...", "delivered_at": null, "read_at": null}}
{"type": "receipt", "status": "read", "message_ids": [42], "at": "...", "from": "<reader id>"}
{"type": "typing", "from": "<user id>"}
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

1. **Local storage**: Room + SQLCipher on the phone so chats open instantly
   and work offline; keep the session token under an Android Keystore key.
2. **End-to-end encryption**: per-device keys, a key directory on the server,
   and Double Ratchet sessions (vodozemac via a small Rust crate, or
   libsignal). Message bodies on the server become opaque ciphertext.
3. **Push notifications** with Firebase Cloud Messaging for offline phones.
4. **Media**: photos, video, voice notes and documents, encrypted on the
   phone and uploaded to S3-compatible storage (MinIO locally).
5. **Groups**, then **voice and video calls** (WebRTC + LiveKit + coturn).
6. A real **SMS provider** for production sign-up, and deployment.
