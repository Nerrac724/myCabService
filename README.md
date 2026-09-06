# MyCabService 🚕

A distributed Cab Booking and Management System built with **Java RMI (Remote Method Invocation)** and containerized using **Docker** and **Docker Compose**.

The system enables ride requests, dual-layer timestamping for driver acceptances — a **physical clock** (Cristian's algorithm, synchronized automatically on startup) and a **Lamport logical clock** (causal ordering) — first-valid acceptance finalization, driver pickup tracking, and real-time status monitoring.

---

## 🏗 System Architecture

The project consists of three main Java components:
- **`common/`**: Shared interfaces (`myCabInterface.java`) and data models (`Ride.java`).
- **`server/`**: RMI server implementation (`myCabServer.java`) managing rides, acceptances, physical/logical clock state, and driver assignments.
- **`client/`**: Interactive command-line client (`myCabClient.java`) that connects to a specified RMI server.

---

## ⚡ Features

1. **Cab Request**: Ride requests can be created specifying customer, pickup, and destination.
2. **Physical Clock Synchronization**: Runs automatically on client startup using Cristian's algorithm — five round-trip samples are taken and the offset from the lowest-RTT sample is adopted. Can also be re-triggered manually from the menu.
3. **Lamport Logical Clock**: Both client and server maintain an independent logical clock, incremented on every send and updated via the max-plus-one rule on every receive. Printed alongside each action for traceability.
4. **Driver Acceptance**: Multiple drivers can accept a ride request, each acceptance recorded with both a physical timestamp and a Lamport clock value.
5. **Ride Finalization & Winner Determination**: The ride is assigned to the driver with the earliest physical timestamp (authoritative). The Lamport-order winner is computed and logged alongside it for comparison — a mismatch between the two indicates clock skew between drivers.
6. **Pickup Arrival & Deadline Tracking**: Tracks driver arrival against a 10-minute physical-clock deadline and reassigns the ride if the driver is late. Arrival Lamport values are recorded for reference.
7. **Multi-Instance Support**: Server and client each accept an optional startup argument, allowing multiple independent server/client pairs to run side by side (e.g. for local or Dockerized multi-instance testing).

---

## 📁 Repository Structure

```text
MyCabProject/
├── client/
│   └── myCabClient.java      # CLI Client application
├── common/
│   ├── myCabInterface.java   # Remote RMI Interface
│   └── Ride.java             # Ride data structure
├── server/
│   └── myCabServer.java      # RMI Server implementation
├── Dockerfile.client         # Dockerfile for Client
├── Dockerfile.server         # Dockerfile for Server
├── docker-compose.yml        # Docker Compose configuration
└── README.md                 # Project documentation
```

---

## 🚀 Getting Started with Docker (Recommended)

### Prerequisites
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running.

---

### Step 1: Clone the Repository
```bash
git clone https://github.com/Nerrac724/myCabService.git
cd myCabService
```

### Step 2: Build and Start the RMI Server
```bash
docker compose up --build -d server
```

To view server logs in real time (including physical/Lamport clock activity):
```bash
docker compose logs -f server
```

### Step 3: Run the Interactive Client CLI
```bash
docker compose run --rm client
```

*The client connects to `rmi://server:1099/MyCabService` on the bridge network by default, and performs an automatic physical clock sync against it on startup before showing the menu.*

---

### Step 4: Interactive Menu Options

```text
================================
 Connected to MyCab Server at server:1099
================================

====== PHYSICAL CLOCK SYNCHRONIZATION (Cristian's Algorithm) ======
Sample 1: RTT=...ms, estimated offset=...ms
...
Adopted clock offset: ...ms
Clock synchronized.
=====================================================

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

Each menu action that involves an RMI call (1, 3, 5) prints the Lamport clock value before sending and after the corresponding receive-event update, alongside the physical timestamp where relevant.

---

### Step 5: Stopping the Containers
```bash
docker compose down
```

---

## 🛠 Running Locally (Without Docker)

### Prerequisites
- Java Development Kit (JDK 17 or higher).

### 1. Compile the Source Files
From the project root directory (`MyCabProject/`):
```bash
javac -d out common/*.java server/*.java client/*.java
```

### 2. Start the RMI Server
Optional first argument sets the registry port (defaults to `1099`):
```bash
java -cp out server.myCabServer 1099
```

### 3. Run the Client CLI
Optional first argument sets the target `host:port` (defaults to `server:1099`):
```bash
java -cp out client.myCabClient localhost:1099
```

---

## 🔀 Running Multiple Server/Client Pairs

The port and host arguments above allow independent server/client pairs to run side by side without interfering with one another — useful for demonstrating physical/Lamport clock behavior across genuinely separate processes.

```bash
# Terminal 1
java -cp out server.myCabServer 1099

# Terminal 2
java -cp out server.myCabServer 1100

# Terminal 3
java -cp out client.myCabClient localhost:1099

# Terminal 4
java -cp out client.myCabClient localhost:1100
```

Each server maintains its own independent ride list and Lamport clock; each client independently syncs its own physical clock offset against whichever server it targets. The two pairs do not share state or communicate — this setup is for isolated timing demonstrations, not load balancing or failover, which are out of scope for this experiment.

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
