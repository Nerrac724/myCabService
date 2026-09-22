# MyCabService 🚕

A distributed cab booking and management system built with **Java RMI**, backed by a **MySQL primary/backup pair**, and containerized with **Docker Compose**. The project demonstrates three distinct distributed-systems experiments on top of the same ride-booking domain: a **timing and consistency model** (physical + logical clocks), a **database fault tolerance model** (automatic primary/backup failover, failback, and reconciliation), and a **client-side fault tolerance model** (automatic failover and failback between two RMI server instances).

---

## 🏗 System Architecture

```text
MyCabProject/
├── common/
│   ├── myCabInterface.java   # Remote RMI interface
│   ├── Ride.java             # Shared ride data model (legacy; DB now holds ride state)
│   └── TimeUtil.java         # Shared human-readable timestamp formatting
├── server/
│   ├── myCabServer.java      # RMI server implementation
│   └── DatabaseManager.java  # Primary/backup DB connection + failover/failback/reconciliation logic
├── client/
│   └── myCabClient.java      # Interactive CLI client with server failover/failback
├── db/
│   └── init.sql              # Schema: rides, acceptances, arrivals
├── lib/
│   └── mysql-connector-j.jar # JDBC driver (downloaded separately, see below)
├── Dockerfile.server
├── Dockerfile.client
├── docker-compose.yml
└── README.md
```

**Services in `docker-compose.yml`:**
- `mysql-primary`, `mysql-backup` — two independent MySQL instances sharing the same schema
- `server1`, `server2` — two RMI server instances, both connected to the *same* primary/backup database pair
- `client` — interactive CLI, connects to `server1` by default, with automatic failover to `server2` if `server1` becomes unreachable

Both server instances read/write through the same database pair rather than owning separate databases — data consistency here is about keeping one logical dataset correct and available, not about reconciling divergent copies.

---

## ⚡ Experiment 1: Timing and Consistency

**Where it lives:** `myCabClient.java` (client-side clock logic), `myCabServer.java` (server-side clock logic + `finalizeRide`), `common/TimeUtil.java` (shared formatting).

**Physical clock — Cristian's Algorithm** (`synchronizeClock` in the client): runs automatically the moment a client connects, before the menu appears. It takes five round-trip samples against `getServerTime()`, computes an offset for each, and adopts the offset from whichever sample had the lowest round-trip time (least network uncertainty). This offset is applied to every subsequent client-side timestamp sent to the server (`System.currentTimeMillis() + clockOffset`), so acceptance and arrival times are comparable across clients even if their local clocks differ slightly.

**Lamport logical clock** (`tick()` / `syncLamportWithServer()` on the client, `updateLamportClock()` / `tickLamportClock()` on the server): both sides maintain an independent counter. Every RMI call increments the sender's clock before sending (`tick()`), and the receiver applies `max(local, received) + 1` on arrival. After the call returns, the client also pulls the server's current clock (`getServerLamportClock()`) and applies the same receive-rule to itself, since a normal RMI return value has no room to carry a clock value back.

**Where the two are compared:** `finalizeRide` on the server computes the ride winner twice — once by earliest physical timestamp (the actual, authoritative decision) and once by earliest Lamport clock (logged only). It explicitly prints whether the two agree or disagree; a disagreement indicates clock skew between the competing drivers' clients.

**Human-readable timestamps:** all physical timestamps — Cristian's sample times, acceptance/arrival timestamps, assignment time, deadline — are formatted through `TimeUtil.formatTime()` as `yyyy-MM-dd HH:mm:ss.SSS` in the local system time zone, rather than printed as raw epoch milliseconds.

### Demonstration steps

1. Bring up the stack (see **Running the Project** below) and open a client:
   ```
   docker compose run --rm client
   ```
2. Observe the automatic physical clock sync on startup — five samples with readable server times, adopted offset, and a synchronized local time, all printed before the menu appears.
3. Option 1 — request a cab, note the Ride ID. Observe the Lamport clock printed before and after the call.
4. Option 3 — accept the ride as one driver (e.g. driver `A`). Note the readable physical timestamp and Lamport clock recorded.
5. Open a second client session and accept the same ride as a different driver (e.g. driver `B`), ideally with a short delay so the two acceptances land close together in time.
6. Option 4 — finalize the ride. The server output shows both drivers' physical and Lamport values side by side, states the winner by each method, and explicitly reports whether they **AGREE** or **DISAGREE**.
7. To intentionally produce a disagreement for the report: run one client under `faketime` (or otherwise skew its clock) so its physical timestamp lags or leads while its Lamport clock still reflects normal message ordering — this reliably produces the `DISAGREE` case.

---

## 🛡 Experiment 2: Database Fault Tolerance (Primary/Backup Failover, Failback, and Reconciliation)

**Where it lives:** `DatabaseManager.java`.

Each server holds two live JDBC connections — one to `mysql-primary`, one to `mysql-backup`. All reads and writes go through whichever database is currently marked active (primary, by default). Every write is also best-effort mirrored to the *inactive* side, so the backup stays caught up in near real time without relying on MySQL's own replication features.

**Failover:** if the active connection is found to be dead (`isAlive()` fails), the manager attempts one reconnect; if that also fails, it logs `FAILOVER` and switches to the backup for all subsequent operations.

**Failback with reconciliation:** while running on backup, the manager retries the primary on a throttled interval (every 5 seconds). Once the primary responds again, it runs `resyncFromBackup()` — which copies every row from `rides`, `acceptances`, and `arrivals` on the backup into the primary (via `INSERT ... ON DUPLICATE KEY UPDATE` for `rides`/`acceptances`, and an existence-check-then-insert for `arrivals`) — **before** switching the active side back to primary. This closes the gap left during the outage: mirroring alone only covers writes made *while* primary is reachable, so anything written on backup during the outage needs an explicit catch-up pass rather than relying on the next mirrored write to carry it over.

**Visible proof point:** `getRideInstance` (client option 6) always reports `Currently serving from: PRIMARY` or `BACKUP`, so the active database is visible in every status check without needing to read server logs. Server logs also show `[DB] Reconciling PRIMARY with BACKUP after outage...` and `[DB] Reconciliation complete...` around every failback.

### Demonstration steps

1. Bring up the stack and confirm both databases are healthy:
   ```
   docker compose up -d --build
   docker compose ps
   ```
2. Create a ride:
   ```
   docker compose run --rm client
   ```
   Option 1, note the Ride ID.
3. Confirm the write was mirrored to both databases:
   ```
   docker compose exec mysql-primary mysql -u root -prootpassword -e "USE mycab; SELECT * FROM rides;"
   docker compose exec mysql-backup mysql -u root -prootpassword -e "USE mycab; SELECT * FROM rides;"
   ```
   Both should show the identical row — this is the baseline proof that replication is live *before* any failure is introduced.
4. Simulate a primary outage:
   ```
   docker compose stop mysql-primary
   ```
5. Tail the server logs in a separate terminal:
   ```
   docker compose logs -f server1
   ```
6. Perform another action (e.g. option 6, Check Ride Status, same Ride ID). The logs show `FAILOVER: primary is down, switching to BACKUP.` and the client output ends with `Currently serving from: BACKUP`.
7. Continue exercising writes while still on backup — option 3 (accept) and option 5 (driver arrival) with the same Ride ID — to show the system is fully operational, not just serving stale reads. This is the data that must survive the failback reconciliation.
8. Restore the primary:
   ```
   docker compose start mysql-primary
   ```
9. Wait at least 5 seconds (the failback retry interval), then perform another action. The logs show:
   ```
   [DB] FAILBACK: primary is back online, switching back to PRIMARY.
   [DB] Reconciling PRIMARY with BACKUP after outage...
   [DB] Reconciliation complete — PRIMARY caught up with BACKUP.
   ```
   and the client output returns to `Currently serving from: PRIMARY`.
10. Confirm the primary is caught up on everything written during the outage:
    ```
    docker compose exec mysql-primary mysql -u root -prootpassword -e "USE mycab; SELECT * FROM rides; SELECT * FROM acceptances; SELECT * FROM arrivals;"
    ```
    The data written in step 7 should now be present on primary — this is the proof that recovery restored both connectivity *and* data consistency, not just one or the other.

---

## 🔁 Experiment 3: Client-Side Fault Tolerance (Server Failover/Failback)

**Where it lives:** `myCabClient.java` — `KNOWN_SERVERS`, `connectToAnyServer()`, `attemptFailback()`, `callWithFailover()`.

Independent of the database-level fault tolerance above, the client itself is resilient to a full RMI server going down. The client holds a list of known servers (`server1:1099`, `server2:1099`) and every RMI call is routed through `callWithFailover(...)`, which:

1. First checks (throttled to once per 5 seconds) whether the preferred server (`server1`, index 0) has come back online, switching back to it if so — this is the client-side **failback**.
2. Attempts the call against the currently active server.
3. If the call fails with a `RemoteException`, logs the failure, tries the next known server in the list, and retries — this is the client-side **failover**.

This works safely because both `server1` and `server2` share the same primary/backup database pair (Experiment 2) — a ride created via one server is immediately visible via the other, so switching which server a client talks to mid-session never loses or hides data.

**Design note for later comparison:** the failback policy here is a fixed preference (always prefer `server1` if reachable), not a load-based decision — intentionally a naive baseline to contrast against a real load balancer in a later experiment.

### Demonstration steps

1. Bring up the stack and connect a client:
   ```
   docker compose up -d --build
   docker compose run --rm client
   ```
   Confirms connection to `server1:1099` by default.
2. Create a ride (option 1), note the Ride ID.
3. Kill the server the client is connected to:
   ```
   docker compose stop server1
   ```
4. In the same client session, perform another action (option 6, same Ride ID). Expect:
   ```
   Call failed (...) — failing over to next server...
   Connected to MyCab Server at server2:1099
   ```
   followed by the ride status printing successfully.
5. Restore the first server:
   ```
   docker compose start server1
   ```
6. Wait at least 5 seconds, then perform another action. Expect:
   ```
   Failback: server1:1099 is back online, switching back.
   ```
7. Confirm the call actually landed on `server1` by checking its logs immediately after:
   ```
   docker compose logs server1
   ```

---

## 🚀 Running the Project

### Prerequisites
- Docker Desktop
- `lib/mysql-connector-j.jar` present in the project root (if missing):
  ```
  mkdir lib
  curl -L -o lib/mysql-connector-j.jar https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar
  ```

### Start everything
```
docker compose up -d --build
docker compose ps
```
All five services (`mysql-primary`, `mysql-backup`, `server1`, `server2`, `client`) should be listed; both databases should reach `healthy` before the servers start (enforced via `depends_on`). `client` is not started by `up` since it's interactive — see below.

### Connect a client
```
docker compose run --rm client
```
Connects to `server1` by default (baked into the compose file's `command`, and also the Dockerfile's own default). To target `server2` directly instead:
```
docker compose run --rm client java -cp out client.myCabClient server2:1099
```

### Inspect a database directly

Via CLI:
```
docker compose exec mysql-primary mysql -u root -prootpassword -e "USE mycab; SHOW TABLES;"
docker compose exec mysql-backup mysql -u root -prootpassword -e "USE mycab; SHOW TABLES;"
```

Via MySQL Workbench: connect to `127.0.0.1:3307` for primary and `127.0.0.1:3308` for backup, user `mycab_user` / password `mycab_password`.

### Stop everything
```
docker compose down
```
Database contents persist in named volumes (`mycab_mysql_primary_data`, `mycab_mysql_backup_data`) across restarts; add `-v` to also wipe them.

---

## 📋 Full Project Demonstration — How Every Topic Is Implemented

This section explains, topic by topic, exactly *how* each distributed-systems concept is realized in the code, which files and methods are responsible, and what output to expect when you exercise them.

---

### 1. Java RMI (Remote Method Invocation)

**Concept:** RMI lets a Java client invoke methods on a server object running in a different JVM — possibly on a different machine — as if it were a local method call.

**How we implement it:**

| Layer | File | Role |
|-------|------|------|
| Interface | `common/myCabInterface.java` | Declares all remotely callable methods (`requestCab`, `acceptRide`, `finalizeRide`, `arriveAtPickup`, `getRideInstance`, `getServerTime`, `getServerLamportClock`). Extends `java.rmi.Remote`; every method throws `RemoteException`. |
| Server | `server/myCabServer.java` | Extends `UnicastRemoteObject` and implements `myCabInterface`. In `main()`, creates an RMI registry on port 1099 (`LocateRegistry.createRegistry(port)`) and binds the server instance under the name `MyCabService` (`Naming.rebind`). |
| Client | `client/myCabClient.java` | Looks up remote objects with `Naming.lookup("rmi://<host>:1099/MyCabService")` and casts to `myCabInterface`. Every RMI call is routed through `callWithFailover()` (see Experiment 3), which internally selects the live server before invoking. |

**Key detail — hostname advertisement:** Inside Docker, each server container sets `java.rmi.server.hostname` to its compose service name (e.g., `server1`) via the `SERVER_HOSTNAME` environment variable. Without this, RMI would advertise the container's internal IP or `localhost`, which is unreachable from other containers by name.

```java
// myCabServer.java — main()
System.setProperty("java.rmi.server.hostname", hostname);
LocateRegistry.createRegistry(port);
Naming.rebind("rmi://localhost:" + port + "/MyCabService", server);
```

**What you see:**
```
================================
Starting MyCab Server on port 1099...
Advertising RMI hostname: server1
RMI Registry started on port 1099.
MyCabService registered.
MyCab Server is ready.
================================
```

---

### 2. Physical Clock Synchronization — Cristian's Algorithm

**Concept:** Cristian's algorithm estimates the offset between a client's local clock and an authoritative server clock by measuring the round-trip time (RTT) of a time request. The server's reported time is assumed to correspond to the midpoint of the round trip: `offset = serverTime − (t₀ + RTT/2)`.

**How we implement it:**

| Component | Location | What it does |
|-----------|----------|--------------|
| Server endpoint | `myCabServer.getServerTime()` | Returns `System.currentTimeMillis()` — acts as the authoritative time source. |
| Client sync | `myCabClient.synchronizeClock()` | Takes **5 round-trip samples**, calculates `RTT` and `offset` for each, and **adopts the offset from the sample with the lowest RTT** (least network jitter). |
| Offset application | Every client timestamp | `System.currentTimeMillis() + clockOffset` — used in `acceptRide` and `arriveAtPickup`. |
| Readable output | `common/TimeUtil.formatTime()` | Converts every physical millisecond timestamp into `yyyy-MM-dd HH:mm:ss.SSS` local time before printing. |

```java
// myCabClient.java — synchronizeClock()
for (int i = 1; i <= samples; i++) {
    long t0 = System.currentTimeMillis();
    long serverTime = server.getServerTime();   // RMI call
    long t1 = System.currentTimeMillis();

    long rtt = t1 - t0;
    long offset = serverTime - (t0 + rtt / 2);  // Cristian's formula

    if (rtt < bestRtt) {                         // keep best sample
        bestRtt = rtt;
        bestOffset = offset;
    }
}
clockOffset = bestOffset;
```

**Why 5 samples instead of 1:** Network latency is noisy. A single sample might have an unusually high RTT (packet retransmission, GC pause, etc.), producing a large error. By taking multiple samples and picking the one with the smallest RTT, we minimize the uncertainty window.

**What you see on startup:**
```
====== PHYSICAL CLOCK SYNCHRONIZATION (Cristian's Algorithm) ======
Sample 1 | Server time: 2026-09-21 14:32:07.481 | RTT: 15ms | Offset: 2ms
Sample 2 | Server time: 2026-09-21 14:32:07.489 | RTT: 8ms | Offset: 1ms
Sample 3 | Server time: 2026-09-21 14:32:07.498 | RTT: 12ms | Offset: 2ms
Sample 4 | Server time: 2026-09-21 14:32:07.505 | RTT: 6ms | Offset: 1ms
Sample 5 | Server time: 2026-09-21 14:32:07.512 | RTT: 9ms | Offset: 1ms
Best sample RTT: 6ms
Adopted clock offset: 1ms
Synchronized local time: 2026-09-21 14:32:07.506
Clock synchronized.
=====================================================
```

---

### 3. Lamport Logical Clocks

**Concept:** Lamport clocks assign a monotonically increasing counter to events such that if event A *happens before* event B, then `L(A) < L(B)`. The rules are:
- **Send/local event:** `clock = clock + 1`
- **Receive event:** `clock = max(local, received) + 1`

**How we implement it:**

| Side | Method | Rule |
|------|--------|------|
| Client | `tick()` | Increments `lamportClock++` before every RMI call (send event). |
| Client | `syncLamportWithServer()` | After the RMI call returns, fetches `server.getServerLamportClock()` and applies `max(local, server) + 1` (receive event). |
| Server | `updateLamportClock(received)` | On every incoming call, applies `max(local, received) + 1`. |
| Server | `tickLamportClock()` | Internal events (e.g., `finalizeRide` decision) increment `clock + 1`. |

**Data flow for a single `acceptRide` call:**

```
Client                                    Server
  │                                          │
  ├── tick()  →  lamport = 5                 │
  │              (send event)                │
  │                                          │
  ├── RMI: acceptRide(..., lamport=5) ──────►│
  │                                          ├── updateLamportClock(5)
  │                                          │   max(serverClock=3, 5) + 1 = 6
  │                                          │   (receive event)
  │                                          │
  │◄──── RMI returns ────────────────────────┤
  │                                          │
  ├── syncLamportWithServer()                │
  │   getServerLamportClock() = 6            │
  │   max(localClock=5, 6) + 1 = 7           │
  │   (receive event on return)              │
```

**Where the clock is stored in the database:**
- `acceptances.lamport_clock` — the server-side Lamport value at the moment the acceptance was recorded.
- `rides.assignment_lamport_clock` — the server-side Lamport value at finalization time.
- `arrivals.lamport_clock` — the server-side Lamport value when the driver's arrival was logged.

**What you see (client side):**
```
Lamport clock (send): 5
Lamport clock (after receive): 7
```

---

### 4. Physical vs. Logical Clock Comparison at Finalization

**Concept:** When multiple drivers accept the same ride, the system must pick a winner. We use *physical timestamps* (Cristian-synchronized) as the authoritative ordering, but also compute the ordering by *Lamport clocks* and explicitly report whether the two agree.

**How we implement it — `myCabServer.finalizeRide()`:**

```java
// Winner by physical clock (authoritative)
for (Map.Entry<String, Long> entry : acceptances.entrySet()) {
    if (entry.getValue() < earliestTimestamp) {
        earliestTimestamp = entry.getValue();
        physicalWinner = entry.getKey();
    }
}

// Winner by Lamport clock (for comparison only)
for (Map.Entry<String, Long> entry : acceptanceLamportClocks.entrySet()) {
    if (entry.getValue() < earliestLamport) {
        earliestLamport = entry.getValue();
        lamportWinner = entry.getKey();
    }
}

// Compare and report
if (!physicalWinner.equals(lamportWinner)) {
    System.out.println("NOTE: Physical and Lamport orderings DISAGREE — likely clock skew.");
} else {
    System.out.println("Physical and Lamport orderings AGREE.");
}
```

**What you see (server log):**
```
========== DRIVER ACCEPTANCE COMPARISON ==========
Driver A | physical=2026-09-21 14:33:21.234 | lamport=6
Driver B | physical=2026-09-21 14:33:21.567 | lamport=4

WINNER BY PHYSICAL CLOCK (authoritative): A (t=2026-09-21 14:33:21.234)
WINNER BY LAMPORT CLOCK (causal order):   B (L=4)
NOTE: Physical and Lamport orderings DISAGREE — likely clock skew between driver clients.
Assignment timestamp (physical): 2026-09-21 14:33:22.000
Assignment Lamport clock: 9
10-minute deadline (physical): 2026-09-21 14:43:22.000
```

**Why they can disagree:** Lamport clocks only capture *causal* ordering (message dependencies), not real-time ordering. Driver B might have a lower Lamport value because it sent fewer messages overall, even though it accepted later in wall-clock time.

---

### 5. Primary/Backup Database Replication

**Concept:** The system maintains two independent MySQL databases (primary and backup). All writes go to whichever database is currently active, and are **best-effort mirrored** to the other side so it stays caught up.

**How we implement it — `DatabaseManager.java`:**

| Method | Purpose |
|--------|---------|
| `getActiveConnection()` | Returns whichever MySQL connection is currently active (primary by default, backup after failover). |
| `mirrorWrite(sql, params...)` | After every write on the active side, replays the same SQL on the *inactive* side. Failures are logged but never thrown — mirroring must never break the main operation. |

```java
// Every write method follows this pattern:
public synchronized int insertRide(...) {
    // 1. Write to active database
    try (PreparedStatement ps = getActiveConnection().prepareStatement(sql, ...)) {
        ...
        ps.executeUpdate();
        rideId = keys.getInt(1);
    }
    // 2. Mirror to inactive database (best-effort)
    mirrorWrite("INSERT INTO rides (ride_id, ...) VALUES (?, ...)", rideId, ...);
    return rideId;
}
```

**Important detail — identity preservation:** When mirroring an `INSERT`, the ride ID generated on the active side is explicitly included in the mirrored `INSERT` so both databases agree on the same primary key. Without this, auto-increment values could diverge.

**Methods that mirror writes:** `insertRide`, `addAcceptance`, `assignRide`, `clearAssignment`, `setStatus`, `addArrivalLamport`.

**Known limitation this mechanism alone does not solve:** `mirrorWrite` only covers the single write happening *right now*, and only reaches the currently inactive side. If that side is unreachable at write time (e.g. it's the failed primary), the mirror is silently skipped — by design, since a dead mirror target must never fail the real operation. This means writes made *while a database is down* never get mirrored to it. See Section 6 for how the gap is closed on recovery.

---

### 6. Automatic Failover, Failback, and Reconciliation

**Concept:** If the active (primary) database becomes unreachable, the system automatically fails over to the backup. While on backup, it periodically retries the primary; once it recovers, the system doesn't just resume using it — it first **reconciles** the primary with everything the backup accumulated during the outage, closing the gap that best-effort mirroring alone cannot cover (see Section 5).

**How we implement it — `getActiveConnection()`:**

```java
private synchronized Connection getActiveConnection() {
    // --- FAILBACK CHECK (while running on backup) ---
    if (usingBackup) {
        long now = System.currentTimeMillis();
        if (now - lastFailbackAttemptMs > FAILBACK_RETRY_INTERVAL_MS) {  // 5 seconds
            lastFailbackAttemptMs = now;
            connectPrimary();
            if (isAlive(primaryConnection)) {
                System.out.println("[DB] FAILBACK: primary is back online, switching back to PRIMARY.");
                resyncFromBackup();          // reconcile BEFORE switching the active side
                usingBackup = false;
            }
        }
    }

    // --- NORMAL PATH (primary) ---
    if (!usingBackup) {
        if (isAlive(primaryConnection)) return primaryConnection;
        connectPrimary();                           // one reconnect attempt
        if (isAlive(primaryConnection)) return primaryConnection;
        System.out.println("[DB] FAILOVER: primary is down, switching to BACKUP.");
        usingBackup = true;                         // give up on primary
    }

    // --- BACKUP PATH ---
    if (!isAlive(backupConnection)) connectBackup();
    if (!isAlive(backupConnection))
        throw new RuntimeException("Both primary and backup databases are unreachable.");
    return backupConnection;
}
```

**Reconciliation — `resyncFromBackup()`:** Reads every row currently in the backup's `rides`, `acceptances`, and `arrivals` tables and upserts them into the primary (`INSERT ... ON DUPLICATE KEY UPDATE` for `rides`/`acceptances`; an explicit existence check followed by insert for `arrivals`, since that table has no natural unique key to upsert against). This runs synchronously as part of the failback path, so the active side is only flipped back to primary once it's confirmed caught up — a request arriving mid-reconciliation still sees the backup as active until the copy finishes.

**Health check — `isAlive()`:**
```java
private boolean isAlive(Connection c) {
    return c != null && !c.isClosed() && c.isValid(2);  // 2-second timeout
}
```

**State machine:**
```
                       primary healthy
                    ┌──────────────────┐
                    │                  │
                    ▼                  │
              ┌──────────┐      ┌──────────┐
              │ PRIMARY  │─────►│ BACKUP   │
              │ (active) │      │ (active) │
              └──────────┘      └──────────┘
                    ▲   primary       │
                    │   unreachable   │
                    │                 │
                    └─────────────────┘
                 primary recovered + reconciled
                     (retried every 5s)
```

**Visible proof — `getRideAsString()`:** Every status check appends `Currently serving from: PRIMARY` or `Currently serving from: BACKUP`, so you can confirm the active database without reading server logs. Server logs additionally show the reconciliation happening around every failback.

---

### 7. Deadline Enforcement with Arrival Check

**Concept:** After a ride is assigned, the driver has a 10-minute deadline to arrive at the pickup location. If the driver is late, the ride is unassigned and returned to `WAITING` status for other drivers.

**How we implement it — `myCabServer.arriveAtPickup()`:**

```java
// Deadline is set at finalization: assignmentTime + 10 minutes
long deadline = assignmentTime + (10 * 60 * 1000);  // in finalizeRide()

// At arrival, the server checks:
if (synchronizedTimestamp <= deadline) {
    db.setStatus(rideId, "DRIVER_ARRIVED");
    System.out.println("RESULT: DRIVER ON TIME (physical clock decision)");
    return true;
} else {
    System.out.println("RESULT: DRIVER LATE (physical clock decision)");
    db.clearAssignment(rideId);  // sets status back to WAITING
    return false;
}
```

**Note:** The arrival timestamp comes from the client's Cristian-synchronized clock (`System.currentTimeMillis() + clockOffset`), so even if the client's raw clock drifts, the comparison against the server-set deadline remains fair.

---

### 8. Docker Containerization and Networking

**Concept:** The entire system (2 databases, 2 servers, 1 client) runs in Docker containers on a shared bridge network, with health checks ensuring proper startup order.

**How we implement it:**

| Service | Image / Dockerfile | Key Configuration |
|---------|-------------------|-------------------|
| `mysql-primary` | `mysql:8.0` | Port 3307→3306, named volume, health check via `mysqladmin ping` |
| `mysql-backup` | `mysql:8.0` | Port 3308→3306, named volume, health check via `mysqladmin ping` |
| `server1` | `Dockerfile.server` | `depends_on: mysql-primary (healthy), mysql-backup (healthy)`, env vars for DB hosts, `SERVER_HOSTNAME=server1` |
| `server2` | `Dockerfile.server` | Same as server1, different `SERVER_HOSTNAME` |
| `client` | `Dockerfile.client` | `depends_on: server1, server2`, `stdin_open: true`, `tty: true` for interactive CLI, `command` baked in to target `server1:1099` by default |

**Build process (Dockerfile.server):**
```dockerfile
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY common ./common
COPY server ./server
COPY lib ./lib
RUN javac -cp "lib/mysql-connector-j.jar" -d out common/*.java server/*.java
EXPOSE 1099
CMD ["java", "-cp", "out:lib/mysql-connector-j.jar", "server.myCabServer"]
```
Both Dockerfiles compile with a wildcard (`common/*.java server/*.java` / `common/*.java client/*.java`) rather than listing files individually, so adding a new shared class (like `TimeUtil.java`) never requires touching the Dockerfile.

**Startup order guarantee:** Servers use `depends_on` with `condition: service_healthy`. The MySQL health checks retry 10 times at 5-second intervals, so the server containers don't start until both databases are accepting connections and the `init.sql` schema has been loaded.

**Network isolation:** All services share the `mycab-network` bridge network. Containers reference each other by service name (e.g., `server1:1099`, `mysql-primary:3306`). Host-side ports (3307, 3308) are exposed only for manual database inspection from the host.

---

### 9. Database Schema Design

**Concept:** The schema is normalized into three tables that mirror the lifecycle of a ride: creation → acceptance(s) → assignment → arrival.

```sql
-- rides: one row per cab request
CREATE TABLE rides (
    ride_id                 INT AUTO_INCREMENT PRIMARY KEY,
    customer                VARCHAR(255) NOT NULL,
    pickup                  VARCHAR(255) NOT NULL,
    destination              VARCHAR(255) NOT NULL,
    status                  VARCHAR(50) DEFAULT 'WAITING',   -- WAITING → ASSIGNED → DRIVER_ARRIVED
    assigned_driver         VARCHAR(255) DEFAULT NULL,
    request_time            BIGINT NOT NULL,                  -- physical clock
    assignment_time         BIGINT DEFAULT NULL,              -- physical clock
    assignment_lamport_clock BIGINT DEFAULT NULL,             -- logical clock
    deadline                BIGINT DEFAULT NULL               -- physical clock
);

-- acceptances: many drivers can accept the same ride before finalization
CREATE TABLE acceptances (
    ride_id            INT NOT NULL,
    driver_id          VARCHAR(255) NOT NULL,
    physical_timestamp BIGINT NOT NULL,        -- Cristian-synchronized
    lamport_clock      BIGINT NOT NULL,        -- server-side Lamport at acceptance time
    UNIQUE KEY (ride_id, driver_id),           -- one acceptance per driver per ride
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id) ON DELETE CASCADE
);

-- arrivals: logged when the assigned driver arrives at pickup
CREATE TABLE arrivals (
    ride_id       INT NOT NULL,
    driver_id     VARCHAR(255) NOT NULL,
    lamport_clock BIGINT NOT NULL,             -- server-side Lamport at arrival time
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id) ON DELETE CASCADE
);
```

**Ride status lifecycle:**
```
WAITING  ──(finalizeRide)──►  ASSIGNED  ──(arriveAtPickup, on time)──►  DRIVER_ARRIVED
                                  │
                                  └──(arriveAtPickup, late)──► WAITING  (reassign)
```

---

### 10. Complete End-to-End Walkthrough

This walkthrough exercises **every feature** in a single session, including the failback reconciliation fix. Open **three terminal windows**.

#### Terminal 1 — Bring up the stack and monitor server logs

```bash
docker compose up -d --build
docker compose ps                  # confirm all databases/servers are healthy/running
docker compose logs -f server1     # keep this running to observe server output
```

#### Terminal 2 — Client session (Driver A)

```bash
docker compose run --rm client
```

**Step 1 — Observe physical clock sync (Cristian's Algorithm), now human-readable:**
```
====== PHYSICAL CLOCK SYNCHRONIZATION (Cristian's Algorithm) ======
Sample 1 | Server time: 2026-09-21 14:32:07.481 | RTT: 12ms | Offset: 1ms
...
Adopted clock offset: 1ms
Synchronized local time: 2026-09-21 14:32:07.500
Clock synchronized.
```

**Step 2 — Request a cab (option 1):**
```
Enter customer name: Alice
Enter pickup location: Central Station
Enter destination: Airport
Lamport clock (send): 1
Lamport clock (after receive): 3
Cab requested successfully.
Ride ID: 1
```

**Step 3 — Accept the ride as Driver A (option 3):**
```
Enter Ride ID: 1
Enter Driver ID: DriverA
Synchronized acceptance timestamp (physical): 2026-09-21 14:33:21.234
Lamport clock (send): 4
Lamport clock (after receive): 6
Driver DriverA accepted Ride 1
```

#### Terminal 3 — Client session (Driver B)

```bash
docker compose run --rm client
```

**Step 4 — Accept the same ride as Driver B (option 3):**
```
Enter Ride ID: 1
Enter Driver ID: DriverB
Synchronized acceptance timestamp (physical): 2026-09-21 14:33:21.567
Lamport clock (send): 2
Lamport clock (after receive): 8
Driver DriverB accepted Ride 1
```

#### Back to Terminal 2

**Step 5 — Finalize the ride (option 4):**
```
Enter Ride ID: 1
Ride 1 assigned to Driver DriverA
```

Check Terminal 1 (server logs) for the physical vs. Lamport comparison output.

**Step 6 — Verify data mirrored to both databases:**
```bash
docker compose exec mysql-primary mysql -u root -prootpassword \
  -e "USE mycab; SELECT * FROM rides; SELECT * FROM acceptances;"

docker compose exec mysql-backup mysql -u root -prootpassword \
  -e "USE mycab; SELECT * FROM rides; SELECT * FROM acceptances;"
```
Both should show identical data.

**Step 7 — Simulate primary failure (Failover):**
```bash
docker compose stop mysql-primary
```

**Step 8 — Check ride status (option 6 in Terminal 2):**
```
Enter Ride ID: 1
...
Currently serving from: BACKUP
```
Terminal 1 server logs show: `[DB] FAILOVER: primary is down, switching to BACKUP.`

**Step 9 — Driver arrives while on backup (option 5 in Terminal 2):**
```
Enter Ride ID: 1
Enter Driver ID: DriverA
Arrival timestamp (physical): 2026-09-21 14:35:10.100
Lamport clock (send): 8
Driver arrived on time.
```
This proves the system handles writes on backup, not just reads — and gives the reconciliation step (Step 11) something real to catch up on.

**Step 10 — Restore primary (Failback):**
```bash
docker compose start mysql-primary
```

Wait ~5 seconds, then check ride status again (option 6):
```
Enter Ride ID: 1
...
Currently serving from: PRIMARY
```
Terminal 1 server logs show:
```
[DB] FAILBACK: primary is back online, switching back to PRIMARY.
[DB] Reconciling PRIMARY with BACKUP after outage...
[DB] Reconciliation complete — PRIMARY caught up with BACKUP.
```

**Step 11 — Confirm primary caught up on outage writes:**
```bash
docker compose exec mysql-primary mysql -u root -prootpassword \
  -e "USE mycab; SELECT * FROM rides; SELECT * FROM arrivals;"
```
The arrival record created during the outage (Step 9) should be present — proving data consistency was restored alongside connectivity, not left stranded on the backup.

**Step 12 — Exit (option 7):**
```
Exiting MyCab Client...
```

**Step 13 — Tear down:**
```bash
docker compose down       # keeps data volumes
docker compose down -v    # also wipes database volumes
```

---

### Summary of Distributed Systems Topics Covered

| # | Topic | Where Implemented | Key Method / File |
|---|-------|-------------------|-------------------|
| 1 | Remote Method Invocation (RMI) | Client-server communication | `myCabInterface.java`, `myCabServer.java`, `myCabClient.java` |
| 2 | Physical Clock Sync (Cristian's) | Client startup + on-demand | `myCabClient.synchronizeClock()`, `TimeUtil.formatTime()` |
| 3 | Lamport Logical Clocks | Every RMI call | `tick()`, `syncLamportWithServer()`, `updateLamportClock()`, `tickLamportClock()` |
| 4 | Clock Ordering Comparison | Ride finalization | `myCabServer.finalizeRide()` |
| 5 | Primary/Backup Replication | Every database write | `DatabaseManager.mirrorWrite()` |
| 6 | Automatic Failover, Failback & Reconciliation | On primary failure / recovery | `DatabaseManager.getActiveConnection()`, `resyncFromBackup()` |
| 7 | Client-Side Server Failover/Failback | Every RMI call | `myCabClient.callWithFailover()`, `attemptFailback()` |
| 8 | Deadline Enforcement | Driver arrival | `myCabServer.arriveAtPickup()` |
| 9 | Containerization (Docker) | Deployment | `Dockerfile.server`, `Dockerfile.client`, `docker-compose.yml` |
| 10 | Shared-nothing Networking | Docker bridge network | `docker-compose.yml` — `mycab-network` |

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).