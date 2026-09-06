package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface myCabInterface extends Remote {
    int requestCab(String customer, String pickup, String destination, long lamportClock) throws RemoteException;

    long getServerTime() throws RemoteException;

    long getServerLamportClock() throws RemoteException;

    boolean acceptRide(int rideId, String driverId, long synchronizedTimestamp, long lamportClock) throws RemoteException;

    String finalizeRide(int rideId) throws RemoteException;

    boolean arriveAtPickup(int rideId, String driverId, long synchronizedTimestamp, long lamportClock) throws RemoteException;

    String getRideInstance(int rideId) throws RemoteException;
}
