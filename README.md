# MyCabService 🚕

A distributed Cab Booking and Management System built with **Java RMI (Remote Method Invocation)** and containerized using **Docker** and **Docker Compose**.

The system enables customers to request rides, synchronized timestamping for driver acceptances (handling distributed clock offsets), first-valid acceptance finalization, driver pickup tracking, and real-time status monitoring.

---

## 🏗 System Architecture

The project consists of three main Java components:
- **`common/`**: Contains shared interfaces (`myCabInterface.java`) and data models (`Ride.java`).
- **`server/`**: Contains the RMI server implementation (`myCabServer.java`) that manages rides, acceptances, clock sync, and driver assignments.
- **`client/`**: Interactive command-line client (`myCabClient.java`) that connects to the RMI server.

---

## ⚡ Features

1. **Cab Request**: Customers can create ride requests specifying pickup and destination points.
2. **Clock Synchronization**: Supports timestamp synchronization between client/drivers and the server.
3. **Driver Acceptance**: Multiple drivers can accept a ride request with synchronized timestamps.
4. **Ride Finalization & Winner Determination**: Assigns the ride to the driver with the earliest valid acceptance timestamp.
5. **Pickup Arrival & Status Tracking**: Tracks driver arrival at pickup locations and monitors ride states (`REQUESTED`, `ASSIGNED`, `ARRIVED`, etc.).

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
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running on your system.

---

### Step 1: Clone the Repository
```bash
git clone https://github.com/Nerrac724/myCabService.git
cd myCabService
```

### Step 2: Build and Start the RMI Server
Build and start the `mycab-server` container in detached mode:

```bash
docker compose up --build -d server
```

To view the server logs in real-time:
```bash
docker compose logs -f server
```

### Step 3: Run the Interactive Client CLI
Launch an interactive terminal session with the client container:

```bash
docker compose run --rm client
```

*Note: The client will automatically locate and connect to `rmi://server:1099/MyCabService` on the bridge network.*

---

### Step 4: Interactive Menu Options

When the client starts, you will see the interactive menu:

```text
================================
 Connected to MyCab Server
================================

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

---

### Step 5: Stopping the Containers
To stop and clean up containers and networks:

```bash
docker compose down
```

---

## 🛠 Running Locally (Without Docker)

### Prerequisites
- Java Development Kit (JDK 17 or higher) installed.

### 1. Compile the Source Files
From the project root directory (`MyCabProject/`):

```bash
javac -d out common/*.java server/*.java client/*.java
```

### 2. Start the RMI Server
```bash
java -cp out server.myCabServer
```

### 3. Run the Client CLI
Open a new terminal window/tab:
```bash
java -cp out client.myCabClient
```

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
