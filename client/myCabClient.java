package client;

import common.myCabInterface;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.Scanner;

public class myCabClient {

    // Physical clock offset (this client's local time -> server time)
    private static long clockOffset = 0;

    // Lamport logical clock for this client
    private static long lamportClock = 0;

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        // Optional first arg: "host:port" of the server to connect to.
        // Defaults to "server:1099" for the existing Docker setup.
        String target = (args.length >= 1) ? args[0] : "server:1099";

        try {
            myCabInterface server = (myCabInterface) Naming.lookup("rmi://" + target + "/MyCabService");

            System.out.println("================================");
            System.out.println(" Connected to MyCab Server at " + target);
            System.out.println("================================");

            // Physical clock system initializes automatically on startup
            synchronizeClock(server, true);

            int rideId = -1;

            boolean running = true;

            while (running) {
                System.out.println();
                System.out.println("========== MY CAB MENU ==========");
                System.out.println("1. Request Cab");
                System.out.println("2. Synchronize Clock");
                System.out.println("3. Driver Accept Ride");
                System.out.println("4. Finalize Ride / Assign Driver");
                System.out.println("5. Driver Arrives at Pickup");
                System.out.println("6. Check Ride Status");
                System.out.println("7. Exit");
                System.out.println("=================================");
                System.out.print("Enter your choice: ");

                int choice = scanner.nextInt();
                scanner.nextLine();

                switch (choice) {
                    case 1:
                        System.out.println();
                        System.out.println("========== REQUEST CAB ==========");
                        System.out.print("Enter customer name: ");
                        String customer = scanner.nextLine();
                        System.out.print("Enter pickup location: ");
                        String pickup = scanner.nextLine();
                        System.out.print("Enter destination: ");
                        String destination = scanner.nextLine();

                        tick();
                        System.out.println("Lamport clock (send): " + lamportClock);
                        rideId = server.requestCab(customer, pickup, destination, lamportClock);
                        syncLamportWithServer(server);
                        System.out.println("Lamport clock (after receive): " + lamportClock);

                        System.out.println();
                        System.out.println("Cab requested successfully.");
                        System.out.println("Ride ID: " + rideId);
                        break;
                    case 2:
                        synchronizeClock(server, true);
                        break;
                    case 3:
                        System.out.println();
                        System.out.println("====== DRIVER ACCEPTANCE ======");
                        System.out.print("Enter Ride ID: ");
                        rideId = scanner.nextInt();
                        scanner.nextLine();
                        System.out.print("Enter Driver ID: ");
                        String driverId = scanner.nextLine();
                        long synchronizedTimestamp = System.currentTimeMillis() + clockOffset;
                        System.out.println("Synchronized acceptance timestamp (physical): " + synchronizedTimestamp);

                        tick();
                        System.out.println("Lamport clock (send): " + lamportClock);
                        boolean accepted = server.acceptRide(rideId, driverId, synchronizedTimestamp, lamportClock);
                        syncLamportWithServer(server);
                        System.out.println("Lamport clock (after receive): " + lamportClock);

                        if (accepted) {
                            System.out.println("Driver " + driverId + " accepted Ride " + rideId);
                        } else {
                            System.out.println("Acceptance rejected.");
                        }
                        break;
                    case 4:
                        System.out.println();
                        System.out.println("====== FINALIZE RIDE ======");
                        System.out.print("Enter Ride ID: ");
                        rideId = scanner.nextInt();
                        scanner.nextLine();
                        String result = server.finalizeRide(rideId);
                        syncLamportWithServer(server);
                        System.out.println(result);
                        System.out.println("Lamport clock (after receive): " + lamportClock);
                        break;
                    case 5:
                        System.out.println();
                        System.out.println("====== DRIVER ARRIVAL ======");
                        System.out.print("Enter Ride ID: ");
                        rideId = scanner.nextInt();
                        scanner.nextLine();
                        System.out.print("Enter Driver ID: ");
                        String arrivalDriver = scanner.nextLine();
                        long arrivalDelay = (long) (Math.random() * 100);
                        System.out.println("Random arrival delay generated: " + arrivalDelay + " ms");
                        if (arrivalDelay > 0) {
                            System.out.println("Simulating driver travel for " + arrivalDelay + " ms...");
                            Thread.sleep(arrivalDelay);
                        }
                        long arrivalTimestamp = System.currentTimeMillis() + clockOffset;
                        System.out.println("Arrival timestamp (physical): " + arrivalTimestamp);

                        tick();
                        System.out.println("Lamport clock (send): " + lamportClock);
                        boolean onTime = server.arriveAtPickup(rideId, arrivalDriver, arrivalTimestamp, lamportClock);
                        syncLamportWithServer(server);
                        System.out.println("Lamport clock (after receive): " + lamportClock);

                        if (onTime) {
                            System.out.println("Driver arrived on time.");
                        } else {
                            System.out.println("Driver was late or arrival was rejected.");
                        }
                        break;
                    case 6:
                        System.out.println();
                        System.out.println("====== RIDE STATUS ======");
                        System.out.print("Enter Ride ID: ");
                        rideId = scanner.nextInt();
                        scanner.nextLine();
                        System.out.println(server.getRideInstance(rideId));
                        break;
                    case 7:
                        System.out.println();
                        System.out.println("Exiting MyCab Client...");
                        running = false;
                        break;
                    default:
                        System.out.println("Invalid choice.");
                        System.out.println("Please enter a number between 1 and 7.");
                }
            }
        } catch (Exception e) {
            System.out.println("Client error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }

    /**
     * Physical clock synchronization using Cristian's algorithm. Takes several
     * round-trip samples and adopts the offset from the sample with the
     * smallest round-trip time (least uncertainty), rather than trusting a
     * single noisy measurement.
     */
    private static void synchronizeClock(myCabInterface server, boolean verbose) throws RemoteException {
        int samples = 5;
        long bestRtt = Long.MAX_VALUE;
        long bestOffset = 0;

        if (verbose) {
            System.out.println();
            System.out.println("====== PHYSICAL CLOCK SYNCHRONIZATION (Cristian's Algorithm) ======");
        }

        for (int i = 1; i <= samples; i++) {
            long t0 = System.currentTimeMillis();
            long serverTime = server.getServerTime();
            long t1 = System.currentTimeMillis();

            long rtt = t1 - t0;
            long offset = serverTime - (t0 + rtt / 2);

            if (verbose) {
                System.out.println("Sample " + i + ": RTT=" + rtt + "ms, estimated offset=" + offset + "ms");
            }

            if (rtt < bestRtt) {
                bestRtt = rtt;
                bestOffset = offset;
            }
        }

        clockOffset = bestOffset;

        if (verbose) {
            System.out.println("Best sample RTT: " + bestRtt + "ms");
            System.out.println("Adopted clock offset: " + clockOffset + "ms");
            System.out.println("Clock synchronized.");
            System.out.println("=====================================================");
        }
    }

    // Local-event / send-event rule: clock = clock + 1
    private static long tick() {
        return ++lamportClock;
    }

    // Receive-event rule, applied after an RMI call returns:
    // clock = max(local, server's clock) + 1
    private static void syncLamportWithServer(myCabInterface server) throws RemoteException {
        long serverClock = server.getServerLamportClock();
        lamportClock = Math.max(lamportClock, serverClock) + 1;
    }
}
