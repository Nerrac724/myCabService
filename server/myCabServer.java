package server;

import common.myCabInterface;
import common.Ride;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;

public class myCabServer extends UnicastRemoteObject implements myCabInterface {
    private Map<Integer, Ride> rides = new HashMap<>();
    private int nextRideId = 1;

    private long lamportClock = 0;

    public myCabServer() throws RemoteException {
        super();
    }

    private synchronized long updateLamportClock(long receivedClock) {
        lamportClock = Math.max(lamportClock, receivedClock) + 1;
        return lamportClock;
    }
    private synchronized long tickLamportClock() {
        lamportClock++;
        return lamportClock;
    }

    @Override
    public synchronized int requestCab(String customer, String pickup, String destination, long lamportClock)
            throws RemoteException {
        long newClock = updateLamportClock(lamportClock);
        int rideId = nextRideId++;
        Ride ride = new Ride(rideId, customer, pickup, destination);
        rides.put(rideId, ride);
        System.out.println("========== CAB REQUEST ==========");
        System.out.println("Ride ID: " + rideId);
        System.out.println("Customer: " + customer);
        System.out.println("Pickup: " + pickup);
        System.out.println("Destination: " + destination);
        System.out.println("Request timestamp (physical): " + ride.getRequestTime());
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
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return false;
        }

        if (!ride.getStatus().equals("WAITING")) {
            System.out.println("Ride " + rideId + " is no longer available");
            return false;
        }
        ride.addAcceptance(driverId, synchronizedTimestamp, newClock);

        System.out.println("[ACCEPTANCE] Driver " + driverId + " accepted Ride " + rideId
                + " | physical time=" + synchronizedTimestamp
                + " | server Lamport clock=" + newClock);
        return true;
    }

    @Override
    public synchronized String finalizeRide(int rideId) throws RemoteException {
        long newClock = tickLamportClock();

        Ride ride = rides.get(rideId);
        if (ride == null) {
            return "Error: Ride " + rideId + " not found.";
        }
        if (ride.getAcceptances().isEmpty()) {
            return "No driver accepted Ride " + rideId;
        }

        String physicalWinner = null;
        long earliestTimestamp = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : ride.getAcceptances().entrySet()) {
            if (entry.getValue() < earliestTimestamp) {
                earliestTimestamp = entry.getValue();
                physicalWinner = entry.getKey();
            }
        }

        String lamportWinner = null;
        long earliestLamport = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : ride.getAcceptanceLamportClocks().entrySet()) {
            if (entry.getValue() < earliestLamport) {
                earliestLamport = entry.getValue();
                lamportWinner = entry.getKey();
            }
        }

        ride.setAssignedDriver(physicalWinner);
        ride.setAssignmentTime(System.currentTimeMillis());
        ride.setAssignmentLamportClock(newClock);
        ride.setDeadline(ride.getAssignmentTime() + (10 * 60 * 1000));
        ride.setStatus("ASSIGNED");

        System.out.println();
        System.out.println("========== DRIVER ACCEPTANCE COMPARISON ==========");
        for (Map.Entry<String, Long> entry : ride.getAcceptances().entrySet()) {
            String d = entry.getKey();
            System.out.println("Driver " + d
                    + " | physical=" + entry.getValue()
                    + " | lamport=" + ride.getAcceptanceLamportClocks().get(d));
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
        System.out.println("Assignment timestamp (physical): " + ride.getAssignmentTime());
        System.out.println("Assignment Lamport clock: " + newClock);
        System.out.println("10-minute deadline (physical): " + ride.getDeadline());
        return "Ride " + rideId + " assigned to Driver " + physicalWinner;
    }

    @Override
    public synchronized boolean arriveAtPickup(int rideId, String driverId, long synchronizedTimestamp, long lamportClock)
            throws RemoteException {
        long newClock = updateLamportClock(lamportClock);
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return false;
        }
        System.out.println();
        System.out.println("========== ARRIVAL DEADLINE CHECK ==========");
        System.out.println("Driver: " + driverId);
        System.out.println("Arrival timestamp (physical): " + synchronizedTimestamp);
        System.out.println("Arrival Lamport clock: " + newClock);
        System.out.println("Deadline (physical): " + ride.getDeadline());

        if (!driverId.equals(ride.getAssignedDriver())) {
            System.out.println("Driver is not assigned to this ride.");
            return false;
        }

        ride.addArrivalLamportClock(driverId, newClock);

        if (synchronizedTimestamp <= ride.getDeadline()) {
            ride.setStatus("DRIVER_ARRIVED");
            System.out.println("RESULT: DRIVER ON TIME (physical clock decision)");
            System.out.println("Continue ride.");
            return true;
        } else {
            System.out.println("RESULT: DRIVER LATE (physical clock decision)");
            System.out.println("REASSIGNING CAB...");
            ride.setStatus("WAITING");
            ride.setAssignedDriver(null);
            return false;
        }
    }

    @Override
    public synchronized String getRideInstance(int rideId) throws RemoteException {
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return "Ride not found";
        }
        return ride.toString();
    }

    public static void main(String[] args) {
        int port = 1099;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        try {
            System.out.println("================================");
            System.out.println("Starting MyCab Server on port " + port + "...");
            System.setProperty("java.rmi.server.hostname", "localhost");
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
