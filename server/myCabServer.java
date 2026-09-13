package server;

import common.myCabInterface;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Map;

public class myCabServer extends UnicastRemoteObject implements myCabInterface {

    private final DatabaseManager db;

    // Server-side Lamport logical clock
    private long lamportClock = 0;

    public myCabServer() throws RemoteException {
        super();
        this.db = new DatabaseManager();
    }

    // Receive-event rule: clock = max(local, received) + 1
    private synchronized long updateLamportClock(long receivedClock) {
        lamportClock = Math.max(lamportClock, receivedClock) + 1;
        return lamportClock;
    }

    // Internal-event rule: clock = clock + 1
    private synchronized long tickLamportClock() {
        lamportClock++;
        return lamportClock;
    }

    @Override
    public synchronized int requestCab(String customer, String pickup, String destination, long lamportClock)
            throws RemoteException {
        long newClock = updateLamportClock(lamportClock);
        long requestTime = System.currentTimeMillis();
        int rideId = db.insertRide(customer, pickup, destination, requestTime);

        System.out.println("========== CAB REQUEST ==========");
        System.out.println("Ride ID: " + rideId);
        System.out.println("Customer: " + customer);
        System.out.println("Pickup: " + pickup);
        System.out.println("Destination: " + destination);
        System.out.println("Request timestamp (physical): " + requestTime);
        System.out.println("Server Lamport clock: " + newClock);
        return rideId;
    }

    @Override
    public long getServerTime() throws RemoteException {
        return System.currentTimeMillis();
    }

    @Override
    public synchronized long getServerLamportClock() throws RemoteException {
        return lamportClock;
    }

    @Override
    public synchronized boolean acceptRide(int rideId, String driverId, long synchronizedTimestamp, long lamportClock)
            throws RemoteException {
        long newClock = updateLamportClock(lamportClock);

        String status = db.getStatus(rideId);
        if (status == null) {
            return false;
        }
        if (!status.equals("WAITING")) {
            System.out.println("Ride " + rideId + " is no longer available");
            return false;
        }

        db.addAcceptance(rideId, driverId, synchronizedTimestamp, newClock);

        System.out.println("[ACCEPTANCE] Driver " + driverId + " accepted Ride " + rideId
                + " | physical time=" + synchronizedTimestamp
                + " | server Lamport clock=" + newClock);
        return true;
    }

    @Override
    public synchronized String finalizeRide(int rideId) throws RemoteException {
        long newClock = tickLamportClock();

        String status = db.getStatus(rideId);
        if (status == null) {
            return "Error: Ride " + rideId + " not found.";
        }

        Map<String, Long> acceptances = db.getAcceptances(rideId);
        Map<String, Long> acceptanceLamportClocks = db.getAcceptanceLamportClocks(rideId);

        if (acceptances.isEmpty()) {
            return "No driver accepted Ride " + rideId;
        }

        // Physical clock decides the actual winner (authoritative)
        String physicalWinner = null;
        long earliestTimestamp = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : acceptances.entrySet()) {
            if (entry.getValue() < earliestTimestamp) {
                earliestTimestamp = entry.getValue();
                physicalWinner = entry.getKey();
            }
        }

        // Lamport clock ordering, computed alongside for comparison
        String lamportWinner = null;
        long earliestLamport = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : acceptanceLamportClocks.entrySet()) {
            if (entry.getValue() < earliestLamport) {
                earliestLamport = entry.getValue();
                lamportWinner = entry.getKey();
            }
        }

        long assignmentTime = System.currentTimeMillis();
        long deadline = assignmentTime + (10 * 60 * 1000);
        db.assignRide(rideId, physicalWinner, assignmentTime, newClock, deadline);

        System.out.println();
        System.out.println("========== DRIVER ACCEPTANCE COMPARISON ==========");
        for (Map.Entry<String, Long> entry : acceptances.entrySet()) {
            String d = entry.getKey();
            System.out.println("Driver " + d
                    + " | physical=" + entry.getValue()
                    + " | lamport=" + acceptanceLamportClocks.get(d));
        }
        System.out.println();
        System.out.println("WINNER BY PHYSICAL CLOCK (authoritative): " + physicalWinner
                + " (t=" + earliestTimestamp + ")");
        System.out.println("WINNER BY LAMPORT CLOCK (causal order):   " + lamportWinner
                + " (L=" + earliestLamport + ")");
        if (!physicalWinner.equals(lamportWinner)) {
            System.out.println("NOTE: Physical and Lamport orderings DISAGREE — likely clock skew between driver clients.");
        } else {
            System.out.println("Physical and Lamport orderings AGREE.");
        }
        System.out.println("Assignment timestamp (physical): " + assignmentTime);
        System.out.println("Assignment Lamport clock: " + newClock);
        System.out.println("10-minute deadline (physical): " + deadline);
        return "Ride " + rideId + " assigned to Driver " + physicalWinner;
    }

    @Override
    public synchronized boolean arriveAtPickup(int rideId, String driverId, long synchronizedTimestamp, long lamportClock)
            throws RemoteException {
        long newClock = updateLamportClock(lamportClock);

        String status = db.getStatus(rideId);
        if (status == null) {
            return false;
        }

        System.out.println();
        System.out.println("========== ARRIVAL DEADLINE CHECK ==========");
        System.out.println("Driver: " + driverId);
        System.out.println("Arrival timestamp (physical): " + synchronizedTimestamp);
        System.out.println("Arrival Lamport clock: " + newClock);

        String assignedDriver = db.getAssignedDriver(rideId);
        long deadline = db.getDeadline(rideId);
        System.out.println("Deadline (physical): " + deadline);

        if (!driverId.equals(assignedDriver)) {
            System.out.println("Driver is not assigned to this ride.");
            return false;
        }

        db.addArrivalLamport(rideId, driverId, newClock);

        // Physical clock remains the authoritative decision for the deadline
        if (synchronizedTimestamp <= deadline) {
            db.setStatus(rideId, "DRIVER_ARRIVED");
            System.out.println("RESULT: DRIVER ON TIME (physical clock decision)");
            System.out.println("Continue ride.");
            return true;
        } else {
            System.out.println("RESULT: DRIVER LATE (physical clock decision)");
            System.out.println("REASSIGNING CAB...");
            db.clearAssignment(rideId);
            return false;
        }
    }

    @Override
    public synchronized String getRideInstance(int rideId) throws RemoteException {
        return db.getRideAsString(rideId);
    }

    public static void main(String[] args) {
        int port = 1099;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }

        String hostname = System.getenv().getOrDefault("SERVER_HOSTNAME", "localhost");

        try {
            System.out.println("================================");
            System.out.println("Starting MyCab Server on port " + port + "...");
            System.out.println("Advertising RMI hostname: " + hostname);
            System.setProperty("java.rmi.server.hostname", hostname);
            LocateRegistry.createRegistry(port);
            myCabServer server = new myCabServer();
            Naming.rebind("rmi://localhost:" + port + "/MyCabService", server);
            System.out.println("RMI Registry started on port " + port + ".");
            System.out.println("MyCabService registered.");
            System.out.println("MyCab Server is ready.");
            System.out.println("================================");
        } catch (Exception e) {
            System.out.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
