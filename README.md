# MyCabService 🚕

A distributed cab booking and management system built with **Java RMI**, backed by a **MySQL primary/backup pair**, and containerized with **Docker Compose**. The project demonstrates two distinct distributed-systems experiments on top of the same ride-booking domain: a **timing and consistency model** (physical + logical clocks) and a **fault tolerance model** (automatic database failover and failback).

---

## 🏗 System Architecture

```text
MyCabProject/
├── common/
│   ├── myCabInterface.java   # Remote RMI interface
│   └── Ride.java             # Shared ride data model
├── server/
│   ├── myCabServer.java      # RMI server implementation
│   └── DatabaseManager.java  # Primary/backup DB connection + failover logic
├── client/
│   └── myCabClient.java      # Interactive CLI client
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
- `client` — interactive CLI, connects to `server1` by default

Both server instances read/write through the same database pair rather than owning separate databases — data consistency here is about keeping one logical dataset correct and available, not about reconciling divergent copies.

---

## ⚡ Experiment 1: Timing and Consistency

**Where it lives:** `myCabClient.java` (client-side clock logic) and `myCabServer.java` (server-side clock logic + `finalizeRide`).

**Physical clock — Cristian's Algorithm** (`synchronizeClock` in the client): runs automatically the moment a client connects, before the menu appears. It takes five round-trip samples against `getServerTime()`, computes an offset for each, and adopts the offset from whichever sample had the lowest round-trip time (least network uncertainty). This offset is applied to every subsequent client-side timestamp sent to the server (`System.currentTimeMillis() + clockOffset`), so acceptance and arrival times are comparable across clients even if their local clocks differ slightly.

**Lamport logical clock** (`tick()` / `syncLamportWithServer()` on the client, `updateLamportClock()` / `tickLamportClock()` on the server): both sides maintain an independent counter. Every RMI call increments the sender's clock before sending (`tick()`), and the receiver applies `max(local, received) + 1` on arrival. After the call returns, the client also pulls the server's current clock (`getServerLamportClock()`) and applies the same receive-rule to itself, since a normal RMI return value has no room to carry a clock value back.

**Where the two are compared:** `finalizeRide` on the server computes the ride winner twice — once by earliest physical timestamp (the actual, authoritative decision) and once by earliest Lamport clock (logged only). It explicitly prints whether the two agree or disagree; a disagreement indicates clock skew between the competing drivers' clients.

### Demonstration steps

1. Bring up the stack (see **Running the Project** below) and open a client:
   ```
   docker compose run --rm client
   ```
2. Observe the automatic physical clock sync on startup — five samples, adopted offset, printed before the menu appears.
3. Option 1 — request a cab, note the Ride ID. Observe the Lamport clock printed before and after the call.
4. Option 3 — accept the ride as one driver (e.g. driver `A`). Note the physical timestamp and Lamport clock recorded.
5. Open a second client session and accept the same ride as a different driver (e.g. driver `B`), ideally with a short delay so the two acceptances land close together in time.
6. Option 4 — finalize the ride. The server output shows both drivers' physical and Lamport values side by side, states the winner by each method, and explicitly reports whether they **AGREE** or **DISAGREE**.
7. To intentionally produce a disagreement for the report: run one client under `faketime` (or otherwise skew its clock) so its physical timestamp lags or leads while its Lamport clock still reflects normal message ordering — this reliably produces the `DISAGREE` case.

---

## 🛡 Experiment 2: Fault Tolerance (Primary/Backup Failover)

**Where it lives:** `DatabaseManager.java`.

Each server holds two live JDBC connections — one to `mysql-primary`, one to `mysql-backup`. All reads and writes go through whichever database is currently marked active (primary, by default). Every write is also best-effort mirrored to the *inactive* side, so the backup stays caught up in near real time without relying on MySQL's own replication features.

**Failover:** if the active connection is found to be dead (`isAlive()` fails), the manager attempts one reconnect; if that also fails, it logs `FAILOVER` and switches to the backup for all subsequent operations.

**Failback:** while running on backup, the manager retries the primary on a throttled interval (every 5 seconds). Once the primary responds again, it logs `FAILBACK` and switches back — and because mirroring always targets whichever side is currently inactive, the primary is automatically caught up on everything written during the outage before or as failback occurs.

**Visible proof point:** `getRideInstance` (client option 6) always reports `Currently serving from: PRIMARY` or `BACKUP`, so the active database is visible in every status check without needing to read server logs.

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
7. Continue exercising writes while still on backup — option 3 (accept) and option 4 (finalize) with the same Ride ID — to show the system is fully operational, not just serving stale reads.
8. Restore the primary:
   ```
   docker compose start mysql-primary
   ```
9. Wait at least 5 seconds (the failback retry interval), then perform another action. The logs show `FAILBACK: primary is back online, switching back to PRIMARY.` and the client output returns to `Currently serving from: PRIMARY`.
10. Confirm the primary is caught up on everything written during the outage:
    ```
    docker compose exec mysql-primary mysql -u root -prootpassword -e "USE mycab; SELECT * FROM rides; SELECT * FROM acceptances;"
    ```
    The acceptance and assignment data from step 7 should be present — this is the proof that recovery didn't just restore connectivity, it restored data consistency.

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
All five services (`mysql-primary`, `mysql-backup`, `server1`, `server2`, `client`) should be listed; both databases should reach `healthy` before the servers start (enforced via `depends_on`).

### Connect a client
```
docker compose run --rm client
```
Connects to `server1` by default (baked into the compose file's `command`). To target `server2` instead:
```
docker compose run --rm client java -cp out client.myCabClient server2:1099
```

### Inspect a database directly
```
docker compose exec mysql-primary mysql -u root -prootpassword -e "USE mycab; SHOW TABLES;"
docker compose exec mysql-backup mysql -u root -prootpassword -e "USE mycab; SHOW TABLES;"
```

### Stop everything
```
docker compose down
```
Database contents persist in named volumes (`mycab_mysql_primary_data`, `mycab_mysql_backup_data`) across restarts; add `-v` to also wipe them.

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
