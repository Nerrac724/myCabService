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

    public myCabServer() throws RemoteException {
        super();
    }

    @Override
    public int requestCab(String customer, String pickup, String destination) throws RemoteException {
        int rideId = nextRideId++;
        Ride ride = new Ride(rideId, customer, pickup, destination);
        rides.put(rideId, ride);
        System.out.println("========== CAB REQUEST ==========");
        System.out.println("Ride ID: " + rideId);
        System.out.println("Customer: " + customer);
        System.out.println("Pickup: " + pickup);
        System.out.println("Destination: " + destination);
        System.out.println("Request timestamp: " + ride.getRequestTime());
        return rideId;
    }

    @Override
    public long getServerTime() throws RemoteException {
        return System.currentTimeMillis();
    }

    @Override
    public synchronized boolean acceptRide(int rideId, String driverId, long synchronizedTimestamp)
            throws RemoteException {
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return false;
        }

        if (!ride.getStatus().equals("WAITING")) {
            System.out.println("Ride " + rideId + " is no longer available");
            return false;
        }
        ride.addAcceptance(driverId, synchronizedTimestamp);

        System.out.println("[ACCEPTANCE] Driver " + driverId + " accepted Ride " + rideId + " at synchronized time "
                + synchronizedTimestamp);
        return true;
    }

    @Override
    public synchronized String finalizeRide(int rideId) throws RemoteException {
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return "Error: Ride " + rideId + " not found.";
        }
        if (ride.getAcceptances().isEmpty()) {
            return "No driver accepted Ride " + rideId;
        }
        String winner = null;
        long earliestTimestamp = Long.MAX_VALUE;

        for (Map.Entry<String, Long> entry : ride.getAcceptances().entrySet()) {
            if (entry.getValue() < earliestTimestamp) {
                earliestTimestamp = entry.getValue();
                winner = entry.getKey();
            }
        }
        ride.setAssignedDriver(winner);
        ride.setAssignmentTime(System.currentTimeMillis());
        ride.setDeadline(ride.getAssignmentTime() + (10 * 60 * 1000));
        ride.setStatus("ASSIGNED");
        System.out.println();
        System.out.println("========== DRIVER ACCEPTANCE ==========");
        for (Map.Entry<String, Long> entry : ride.getAcceptances().entrySet()) {
            System.out.println("Driver " + entry.getKey() + " timestamp: " + entry.getValue());
        }
        System.out.println();
        System.out.println("FIRST VALID ACCEPTANCE: Driver " + winner);
        System.out.println("Earliest timestamp: " + earliestTimestamp);
        System.out.println("Assignment timestamp: " + ride.getAssignmentTime());
        System.out.println("10-minute deadline: " + ride.getDeadline());
        return "Ride " + rideId + " assigned to Driver " + winner;
    }

    @Override
    public synchronized boolean arriveAtPickup(int rideId, String driverId, long synchronizedTimestamp)
            throws RemoteException {
        Ride ride = rides.get(rideId);
        if (ride == null) {
            return false;
        }
        System.out.println();
        System.out.println("========== ARRIVAL DEADLINE CHECK ==========");

        System.out.println("Driver: " + driverId);

        System.out.println("Arrival timestamp: " + synchronizedTimestamp);

        System.out.println("Deadline: " + ride.getDeadline());

        if (!driverId.equals(ride.getAssignedDriver())) {
            System.out.println("Driver is not assigned to this ride.");
            return false;
        }

        if (synchronizedTimestamp <= ride.getDeadline()) {
            ride.setStatus("DRIVER_ARRIVED");
            System.out.println("RESULT: DRIVER ON TIME");
            System.out.println("Continue ride.");
            return true;
        } else {
            System.out.println("RESULT: DRIVER LATE");
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
        try {
            System.out.println("================================");
            System.out.println("Starting MyCab Server...");
            System.setProperty("java.rmi.server.hostname", "server");
            LocateRegistry.createRegistry(1099);
            myCabServer server = new myCabServer();
            Naming.rebind("rmi://localhost:1099/MyCabService", server);
            System.out.println("RMI Registry started on port 1099.");
            System.out.println("MyCabService registered.");
            System.out.println("MyCab Server is ready.");
            System.out.println("================================");
        } catch (Exception e) {
            System.out.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}