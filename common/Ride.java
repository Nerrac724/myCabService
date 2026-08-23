package common;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Ride implements Serializable {

    private static final long serialVersionUID = 1L;

    private int rideId;
    private String customer;
    private String pickup;
    private String destination;

    private String status;
    private String assignedDriver;

    private long requestTime;
    private long assignmentTime;
    private long deadline;

    private Map<String, Long> acceptances = new HashMap<>();

    public Ride(int rideId, String customer, String pickup, String destination) {
        this.rideId = rideId;
        this.customer = customer;
        this.pickup = pickup;
        this.destination = destination;

        this.status = "WAITING";

        this.requestTime = System.currentTimeMillis();
    }

    // Getters

    public int getRideId() {
        return rideId;
    }

    public String getCustomer() {
        return customer;
    }

    public String getPickup() {
        return pickup;
    }

    public String getDestination() {
        return destination;
    }

    public String getStatus() {
        return status;
    }

    public String getAssignedDriver() {
        return assignedDriver;
    }

    public long getRequestTime() {
        return requestTime;
    }

    public long getAssignmentTime() {
        return assignmentTime;
    }

    public long getDeadline() {
        return deadline;
    }

    public Map<String, Long> getAcceptances() {
        return acceptances;
    }

    // Setters

    public void setStatus(String status) {
        this.status = status;
    }

    public void setAssignedDriver(String assignedDriver) {
        this.assignedDriver = assignedDriver;
    }

    public void setAssignmentTime(long assignmentTime) {
        this.assignmentTime = assignmentTime;
    }

    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    public void addAcceptance(String driverId, long timestamp) {
        acceptances.put(driverId, timestamp);
    }

    @Override
    public String toString() {
        return "Ride ID: " + rideId
                + "\nCustomer: " + customer
                + "\nPickup: " + pickup
                + "\nDestination: " + destination
                + "\nStatus: " + status
                + "\nAssigned Driver: "
                + assignedDriver
                + "\nRequest Time: "
                + requestTime
                + "\nAssignment Time: "
                + assignmentTime
                + "\nDeadline: "
                + deadline;
    }
}