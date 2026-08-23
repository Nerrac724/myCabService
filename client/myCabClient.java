package client;

import common.myCabInterface;

import java.rmi.Naming;
import java.util.Scanner;

public class myCabClient {

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        try {
            myCabInterface server = (myCabInterface) Naming.lookup("rmi://server:1099/MyCabService");

            System.out.println("================================");
            System.out.println(" Connected to MyCab Server");
            System.out.println("================================");

            int rideId = -1;
            long clockOffset = 0;

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
                        rideId = server.requestCab(customer, pickup, destination);
                        System.out.println();
                        System.out.println("Cab requested successfully.");
                        System.out.println("Ride ID: " + rideId);
                        break;
                    case 2:
                        System.out.println();
                        System.out.println("====== CLOCK SYNCHRONIZATION ======");
                        long localTime = System.currentTimeMillis();
                        long serverTime = server.getServerTime();
                        clockOffset = serverTime - localTime;
                        System.out.println("Client local time: " + localTime);
                        System.out.println("Server time: " + serverTime);
                        System.out.println("Clock offset: " + clockOffset + " ms");
                        System.out.println("Clock synchronized.");
                        break;
                    case 3:
                        if (rideId == -1) {
                            System.out.println("Please request a cab first.");
                            break;
                        }
                        System.out.println();
                        System.out.println("====== DRIVER ACCEPTANCE ======");
                        System.out.print("Enter Driver ID: ");
                        String driverId = scanner.nextLine();
                        System.out.print("Enter maximum acceptance delay in milliseconds: ");
                        long maxDelay = scanner.nextLong();
                        scanner.nextLine();
                        long delay = (long) (Math.random() * maxDelay);
                        System.out.println("Random acceptance delay generated: " + delay + " ms");
                        if (delay > 0) {
                            System.out.println("Driver " + driverId + " waiting " + delay + " ms...");
                            Thread.sleep(delay);
                        }
                        long synchronizedTimestamp = System.currentTimeMillis() + clockOffset;
                        System.out.println("Synchronized acceptance timestamp: " + synchronizedTimestamp);
                        boolean accepted = server.acceptRide(rideId, driverId, synchronizedTimestamp);
                        if (accepted) {
                            System.out.println("Driver " + driverId + " accepted Ride " + rideId);
                        } else {
                            System.out.println("Acceptance rejected.");
                        }
                        break;
                    case 4:
                        if (rideId == -1) {
                            System.out.println("Please request a cab first.");
                            break;
                        }
                        System.out.println();
                        System.out.println("====== FINALIZE RIDE ======");
                        String result = server.finalizeRide(rideId);
                        System.out.println(result);
                        break;
                    case 5:
                        if (rideId == -1) {
                            System.out.println("Please request a cab first.");
                            break;
                        }

                        System.out.println();
                        System.out.println("====== DRIVER ARRIVAL ======");
                        System.out.print("Enter Driver ID: ");
                        String arrivalDriver = scanner.nextLine();
                        System.out.print("Enter maximum arrival delay in milliseconds: ");
                        long maxArrivalDelay = scanner.nextLong();
                        scanner.nextLine();
                        long arrivalDelay = (long) (Math.random() * maxArrivalDelay);
                        System.out.println("Random arrival delay generated: " + arrivalDelay + " ms");
                        if (arrivalDelay > 0) {
                            System.out.println("Simulating driver travel for " + arrivalDelay + " ms...");
                            Thread.sleep(arrivalDelay);
                        }
                        long arrivalTimestamp = System.currentTimeMillis() + clockOffset;
                        System.out.println("Arrival timestamp: " + arrivalTimestamp);
                        boolean onTime = server.arriveAtPickup(rideId, arrivalDriver, arrivalTimestamp);
                        if (onTime) {
                            System.out.println("Driver arrived on time.");
                        } else {
                            System.out.println("Driver was late or arrival was rejected.");
                        }
                        break;
                    case 6:
                        if (rideId == -1) {
                            System.out.println("No ride has been created yet.");
                            break;
                        }
                        System.out.println();
                        System.out.println("====== RIDE STATUS ======");
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
}