# How to Run MyCabService — Complete Guide 🚕

This guide covers **two ways** to run your project. Pick whichever your lab setup supports.

---

## Option A: Using Docker (The Designed Way)

This is how the project is meant to run. Docker creates everything automatically — databases, servers, client — inside containers.

### Prerequisites
1. **Install Docker Desktop** → [Download here](https://docs.docker.com/desktop/install/windows-install/)
2. **Start Docker Desktop** → Open the app, wait until it says **"Engine running"** (green icon in system tray)
3. Make sure `lib/mysql-connector-j.jar` exists in the project. If not:
   ```powershell
   mkdir lib
   curl -L -o lib/mysql-connector-j.jar https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar
   ```

### Step-by-Step

**Step 1 — Build and start everything:**
```powershell
cd C:\Users\USER\Desktop\DC_lab\myCabService
docker compose up -d --build
```
This creates 5 containers:
- `mycab-mysql-primary` (primary database)
- `mycab-mysql-backup` (backup database)
- `mycab-server1` (RMI server 1)
- `mycab-server2` (RMI server 2)
- `mycab-client` (interactive client)

**Step 2 — Check all services are running:**
```powershell
docker compose ps
```
You should see all 5 services listed. Both MySQL containers should say `healthy`.

**Step 3 — Open a client:**
```powershell
docker compose run --rm client
```
This drops you into the interactive menu.

**Step 4 — Watch server logs (open a second terminal):**
```powershell
docker compose logs -f server1
```

**Step 5 — Stop everything when done:**
```powershell
docker compose down        # keeps database data
docker compose down -v     # also deletes database data
```

---

## Option B: Using MySQL Workbench (Manual / Without Docker)

If Docker is not available in your lab, you can run MySQL locally via **MySQL Workbench** and run the Java code directly.

### Prerequisites
1. **MySQL Server** installed and running (comes with MySQL Installer)
2. **MySQL Workbench** installed (for GUI access to the database)
3. **JDK 17+** installed (`java -version` should work in terminal)
4. `lib/mysql-connector-j.jar` in the project folder

### Step-by-Step

#### Part 1: Set Up the Database

**Step 1 — Open MySQL Workbench** and connect to your local MySQL instance (usually `localhost:3306`, user `root`, with the password you set during MySQL installation).

**Step 2 — Create the database and user.** In a new Query tab, run:

```sql
-- Create the database
CREATE DATABASE IF NOT EXISTS mycab;

-- Create the application user
CREATE USER IF NOT EXISTS 'mycab_user'@'localhost' IDENTIFIED BY 'mycab_password';
GRANT ALL PRIVILEGES ON mycab.* TO 'mycab_user'@'localhost';
FLUSH PRIVILEGES;

-- Switch to the database
USE mycab;
```

**Step 3 — Create the tables.** Run the contents of `db/init.sql`:

```sql
CREATE TABLE IF NOT EXISTS rides (
    ride_id           INT AUTO_INCREMENT PRIMARY KEY,
    customer          VARCHAR(255) NOT NULL,
    pickup            VARCHAR(255) NOT NULL,
    destination       VARCHAR(255) NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'WAITING',
    assigned_driver   VARCHAR(255) DEFAULT NULL,
    request_time      BIGINT NOT NULL,
    assignment_time   BIGINT DEFAULT NULL,
    assignment_lamport_clock BIGINT DEFAULT NULL,
    deadline          BIGINT DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS acceptances (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    ride_id           INT NOT NULL,
    driver_id         VARCHAR(255) NOT NULL,
    physical_timestamp BIGINT NOT NULL,
    lamport_clock     BIGINT NOT NULL,
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id) ON DELETE CASCADE,
    UNIQUE KEY unique_driver_per_ride (ride_id, driver_id)
);

CREATE TABLE IF NOT EXISTS arrivals (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    ride_id           INT NOT NULL,
    driver_id         VARCHAR(255) NOT NULL,
    lamport_clock     BIGINT NOT NULL,
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id) ON DELETE CASCADE
);
```

**Step 4 — Verify.** Run:
```sql
USE mycab;
SHOW TABLES;
```
You should see: `rides`, `acceptances`, `arrivals`.

#### Part 2: Compile the Java Code

Open PowerShell/CMD in the project folder:

```powershell
cd C:\Users\USER\Desktop\DC_lab\myCabService
```

**Step 5 — Compile everything:**
```powershell
# Create output directory
mkdir -Force out

# Compile server + common classes
javac -cp "lib/mysql-connector-j.jar" -d out common/myCabInterface.java common/Ride.java server/myCabServer.java server/DatabaseManager.java

# Compile client + common classes
javac -d out common/myCabInterface.java common/Ride.java client/myCabClient.java
```

#### Part 3: Configure Database Connection for Local MySQL

> [!IMPORTANT]
> The code uses environment variables to find the database. For local MySQL, you need to set them before running the server.

**Step 6 — Set environment variables (PowerShell):**
```powershell
$env:DB_PRIMARY_HOST = "localhost"
$env:DB_PRIMARY_PORT = "3306"
$env:DB_BACKUP_HOST = "localhost"
$env:DB_BACKUP_PORT = "3306"
```

> [!NOTE]
> When running locally, both primary and backup point to the same MySQL instance. The failover demo won't fully work locally (you can't stop one without stopping the other), but all other features work perfectly.

#### Part 4: Run the Server

**Step 7 — Start the RMI server:**
```powershell
java -cp "out;lib/mysql-connector-j.jar" server.myCabServer
```

You should see:
```
================================
Starting MyCab Server on port 1099...
[DB] Connected to PRIMARY: jdbc:mysql://localhost:3306/mycab?...
[DB] Connected to BACKUP: jdbc:mysql://localhost:3306/mycab?...
RMI Registry started on port 1099.
MyCabService registered.
MyCab Server is ready.
================================
```

> [!WARNING]
> Keep this terminal open! The server must stay running.

#### Part 5: Run the Client

**Step 8 — Open a NEW terminal** and run the client:
```powershell
cd C:\Users\USER\Desktop\DC_lab\myCabService
java -cp out client.myCabClient localhost:1099
```

You should see clock synchronization happen, then the menu:
```
====== PHYSICAL CLOCK SYNCHRONIZATION (Cristian's Algorithm) ======
Sample 1: RTT=2ms, estimated offset=0ms
...
Clock synchronized.

========== MY CAB MENU ==========
1. Request Cab
2. Synchronize Clock
3. Driver Accept Ride
4. Finalize Ride / Assign Driver
5. Driver Arrives at Pickup
6. Check Ride Status
7. Exit
=================================
Enter your choice:
```

#### Part 6: Use the Application

**Step 9 — Request a cab (option 1):**
```
Enter your choice: 1
Enter customer name: Alice
Enter pickup location: Central Station
Enter destination: Airport
```
→ Note the **Ride ID** it gives you (e.g., `1`)

**Step 10 — Accept the ride as a driver (option 3):**
```
Enter your choice: 3
Enter Ride ID: 1
Enter Driver ID: DriverA
```

**Step 11 — Open another client** in a third terminal and accept the same ride as a different driver:
```powershell
java -cp out client.myCabClient localhost:1099
```
```
Enter your choice: 3
Enter Ride ID: 1
Enter Driver ID: DriverB
```

**Step 12 — Finalize the ride (option 4)** — back in the first client:
```
Enter your choice: 4
Enter Ride ID: 1
```
→ Check the **server terminal** — it shows the physical vs. Lamport clock comparison!

**Step 13 — Driver arrives (option 5):**
```
Enter your choice: 5
Enter Ride ID: 1
Enter Driver ID: DriverA
```

**Step 14 — Check ride status (option 6):**
```
Enter your choice: 6
Enter Ride ID: 1
```

#### Part 7: Verify Data in MySQL Workbench

**Step 15 — Go back to MySQL Workbench** and run:
```sql
USE mycab;
SELECT * FROM rides;
SELECT * FROM acceptances;
SELECT * FROM arrivals;
```

You'll see all the ride data, driver acceptances with physical timestamps and Lamport clocks, and arrival records.

---

## What to Say If Sir Asks (Viva Q&A)

### "How do you run this project?"

> "We use Docker Compose to spin up 5 containers — two MySQL databases (primary and backup), two RMI servers, and one interactive client. A single `docker compose up -d --build` starts everything. Alternatively, we can run it locally with MySQL Workbench for the database and run the Java RMI server and client from the command line."

### "What is Java RMI and how does it work here?"

> "RMI stands for Remote Method Invocation. It allows the client to call methods on the server as if they were local method calls. We define a `myCabInterface` that extends `java.rmi.Remote`, the server implements it and registers with an RMI registry on port 1099, and the client looks it up by name using `Naming.lookup`."

### "How does Cristian's Algorithm work in your project?"

> "When the client starts, it automatically synchronizes its clock with the server. It takes 5 round-trip samples — for each, it records the time before and after calling `getServerTime()`, calculates the RTT, and estimates the offset as `serverTime − (t0 + RTT/2)`. It picks the sample with the lowest RTT for the best accuracy. This offset is then added to all future timestamps."

### "What are Lamport Clocks and how do you use them?"

> "Lamport clocks maintain causal ordering of events. Every time the client sends a message, it increments its clock (`tick`). When the server receives a message, it does `max(local, received) + 1`. After the RMI call returns, the client also syncs with the server's clock. We compare physical and Lamport orderings at finalization to show they can disagree due to clock skew."

### "How does failover work?"

> "The `DatabaseManager` maintains connections to both primary and backup MySQL. Every operation goes through `getActiveConnection()`. If the primary is unreachable (checked via `isValid(2)`), it tries one reconnect. If that fails too, it switches to backup and logs `FAILOVER`. While on backup, it retries the primary every 5 seconds. Once primary responds, it logs `FAILBACK` and switches back."

### "How does data stay consistent across primary and backup?"

> "Every write operation is mirrored to the inactive database using `mirrorWrite()`. For inserts, we explicitly include the generated primary key so both databases have matching IDs. The mirror is best-effort — if it fails, the main operation still succeeds. This way, when failover happens, the backup already has all the data."

### "What is the role of MySQL Workbench?"

> "MySQL Workbench is a GUI tool to manage MySQL databases. We use it to create the `mycab` database, run the `init.sql` schema to create the tables (`rides`, `acceptances`, `arrivals`), and to inspect the data after running experiments — verifying that rides, acceptances, and arrival records are correctly stored."

### "What happens when a driver is late?"

> "At finalization, the server sets a 10-minute deadline (`assignmentTime + 10 minutes`). When the driver calls `arriveAtPickup`, the server compares the Cristian-synchronized arrival timestamp against this deadline. If late, the ride status is reset to `WAITING` and the driver assignment is cleared — the ride can be accepted by other drivers again."

---

## Quick Reference: All Commands

| Action | Docker Way | Manual Way |
|--------|-----------|------------|
| Start databases | `docker compose up -d --build` | Start MySQL service + create DB in Workbench |
| Start server | (automatic in docker) | `java -cp "out;lib/mysql-connector-j.jar" server.myCabServer` |
| Start client | `docker compose run --rm client` | `java -cp out client.myCabClient localhost:1099` |
| View server logs | `docker compose logs -f server1` | (visible in server terminal directly) |
| Inspect database | `docker compose exec mysql-primary mysql -u root -prootpassword -e "USE mycab; SELECT * FROM rides;"` | Run `SELECT * FROM rides;` in MySQL Workbench |
| Simulate failover | `docker compose stop mysql-primary` | Stop MySQL service from Windows Services |
| Stop everything | `docker compose down` | Close terminals + stop MySQL service |
