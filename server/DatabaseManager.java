package server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connects to a shared PRIMARY MySQL instance for all reads/writes.
 * Every write is also best-effort mirrored to a BACKUP instance so it stays
 * caught up. If the primary becomes unreachable, this manager automatically
 * fails over to the backup for both reads and writes for the rest of the
 * server's lifetime (does not fail back automatically).
 */
public class DatabaseManager {

    private Connection primaryConnection;
    private Connection backupConnection;
    private final String primaryUrl;
    private final String backupUrl;
    private final String user = "mycab_user";
    private final String password = "mycab_password";

    private boolean usingBackup = false;
    private long lastFailbackAttemptMs = 0;
    private static final long FAILBACK_RETRY_INTERVAL_MS = 5000;

    public DatabaseManager() {
        String primaryHost = System.getenv().getOrDefault("DB_PRIMARY_HOST", "mysql-primary");
        String primaryPort = System.getenv().getOrDefault("DB_PRIMARY_PORT", "3306");
        String backupHost = System.getenv().getOrDefault("DB_BACKUP_HOST", "mysql-backup");
        String backupPort = System.getenv().getOrDefault("DB_BACKUP_PORT", "3306");

        this.primaryUrl = "jdbc:mysql://" + primaryHost + ":" + primaryPort
                + "/mycab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        this.backupUrl = "jdbc:mysql://" + backupHost + ":" + backupPort
                + "/mycab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        connectPrimary();
        connectBackup();
    }

    private void connectPrimary() {
        try {
            primaryConnection = DriverManager.getConnection(primaryUrl, user, password);
            System.out.println("[DB] Connected to PRIMARY: " + primaryUrl);
        } catch (SQLException e) {
            System.out.println("[DB] WARNING: could not connect to primary: " + e.getMessage());
            primaryConnection = null;
        }
    }

    private void connectBackup() {
        try {
            backupConnection = DriverManager.getConnection(backupUrl, user, password);
            System.out.println("[DB] Connected to BACKUP: " + backupUrl);
        } catch (SQLException e) {
            System.out.println("[DB] WARNING: could not connect to backup: " + e.getMessage());
            backupConnection = null;
        }
    }

    private boolean isAlive(Connection c) {
        try {
            return c != null && !c.isClosed() && c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    // Returns whichever database should serve this operation right now.
    // While on backup, periodically (throttled) attempts to reconnect to
    // primary and fails back automatically once it responds again.
    private synchronized Connection getActiveConnection() {
        if (usingBackup) {
            long now = System.currentTimeMillis();
            if (now - lastFailbackAttemptMs > FAILBACK_RETRY_INTERVAL_MS) {
                lastFailbackAttemptMs = now;
                connectPrimary();
                if (isAlive(primaryConnection)) {
                    System.out.println("[DB] FAILBACK: primary is back online, switching back to PRIMARY.");
                    usingBackup = false;
                }
            }
        }

        if (!usingBackup) {
            if (isAlive(primaryConnection)) {
                return primaryConnection;
            }
            System.out.println("[DB] PRIMARY unreachable — attempting reconnect...");
            connectPrimary();
            if (isAlive(primaryConnection)) {
                return primaryConnection;
            }
            System.out.println("[DB] FAILOVER: primary is down, switching to BACKUP.");
            usingBackup = true;
        }

        if (!isAlive(backupConnection)) {
            connectBackup();
        }
        if (!isAlive(backupConnection)) {
            throw new RuntimeException("Both primary and backup databases are unreachable.");
        }
        return backupConnection;
    }

    // Best-effort mirror of a write onto whichever database ISN'T currently
    // active, so it's caught up if failover happens later. Never throws —
    // a failed mirror must never fail the actual operation.
    private void mirrorWrite(String sql, Object... params) {
        Connection target = usingBackup ? primaryConnection : backupConnection;
        if (!isAlive(target)) {
            return;
        }
        try (PreparedStatement ps = target.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[DB] Mirror write failed (non-fatal): " + e.getMessage());
        }
    }

    public synchronized int insertRide(String customer, String pickup, String destination, long requestTime) {
        String sql = "INSERT INTO rides (customer, pickup, destination, status, request_time) VALUES (?, ?, ?, 'WAITING', ?)";
        int rideId;
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, customer);
            ps.setString(2, pickup);
            ps.setString(3, destination);
            ps.setLong(4, requestTime);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new RuntimeException("Failed to obtain generated ride ID");
                }
                rideId = keys.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert ride", e);
        }

        // Mirror with the SAME ride_id so both databases agree on identity
        mirrorWrite("INSERT INTO rides (ride_id, customer, pickup, destination, status, request_time) "
                        + "VALUES (?, ?, ?, ?, 'WAITING', ?)",
                rideId, customer, pickup, destination, requestTime);

        return rideId;
    }

    public synchronized String getStatus(int rideId) {
        String sql = "SELECT status FROM rides WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch ride status", e);
        }
        return null;
    }

    public synchronized void addAcceptance(int rideId, String driverId, long timestamp, long lamportClock) {
        String sql = "INSERT INTO acceptances (ride_id, driver_id, physical_timestamp, lamport_clock) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE physical_timestamp = VALUES(physical_timestamp), lamport_clock = VALUES(lamport_clock)";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            ps.setString(2, driverId);
            ps.setLong(3, timestamp);
            ps.setLong(4, lamportClock);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert acceptance", e);
        }
        mirrorWrite(sql, rideId, driverId, timestamp, lamportClock);
    }

    public synchronized Map<String, Long> getAcceptances(int rideId) {
        Map<String, Long> result = new LinkedHashMap<>();
        String sql = "SELECT driver_id, physical_timestamp FROM acceptances WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("driver_id"), rs.getLong("physical_timestamp"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch acceptances", e);
        }
        return result;
    }

    public synchronized Map<String, Long> getAcceptanceLamportClocks(int rideId) {
        Map<String, Long> result = new LinkedHashMap<>();
        String sql = "SELECT driver_id, lamport_clock FROM acceptances WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("driver_id"), rs.getLong("lamport_clock"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch acceptance lamport clocks", e);
        }
        return result;
    }

    public synchronized void assignRide(int rideId, String driverId, long assignmentTime,
                                         long assignmentLamportClock, long deadline) {
        String sql = "UPDATE rides SET assigned_driver = ?, assignment_time = ?, assignment_lamport_clock = ?, "
                + "deadline = ?, status = 'ASSIGNED' WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setString(1, driverId);
            ps.setLong(2, assignmentTime);
            ps.setLong(3, assignmentLamportClock);
            ps.setLong(4, deadline);
            ps.setInt(5, rideId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to assign ride", e);
        }
        mirrorWrite(sql, driverId, assignmentTime, assignmentLamportClock, deadline, rideId);
    }

    public synchronized String getAssignedDriver(int rideId) {
        String sql = "SELECT assigned_driver FROM rides WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("assigned_driver");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch assigned driver", e);
        }
        return null;
    }

    public synchronized long getDeadline(int rideId) {
        String sql = "SELECT deadline FROM rides WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("deadline");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch deadline", e);
        }
        return 0;
    }

    public synchronized void clearAssignment(int rideId) {
        String sql = "UPDATE rides SET assigned_driver = NULL, status = 'WAITING' WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear assignment", e);
        }
        mirrorWrite(sql, rideId);
    }

    public synchronized void setStatus(int rideId, String status) {
        String sql = "UPDATE rides SET status = ? WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, rideId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update ride status", e);
        }
        mirrorWrite(sql, status, rideId);
    }

    public synchronized void addArrivalLamport(int rideId, String driverId, long lamportClock) {
        String sql = "INSERT INTO arrivals (ride_id, driver_id, lamport_clock) VALUES (?, ?, ?)";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            ps.setString(2, driverId);
            ps.setLong(3, lamportClock);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert arrival record", e);
        }
        mirrorWrite(sql, rideId, driverId, lamportClock);
    }

    public synchronized String getRideAsString(int rideId) {
        String sql = "SELECT * FROM rides WHERE ride_id = ?";
        try (PreparedStatement ps = getActiveConnection().prepareStatement(sql)) {
            ps.setInt(1, rideId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "Ride not found";
                }
                return "Ride ID: " + rs.getInt("ride_id")
                        + "\nCustomer: " + rs.getString("customer")
                        + "\nPickup: " + rs.getString("pickup")
                        + "\nDestination: " + rs.getString("destination")
                        + "\nStatus: " + rs.getString("status")
                        + "\nAssigned Driver: " + rs.getString("assigned_driver")
                        + "\nRequest Time (physical): " + rs.getLong("request_time")
                        + "\nAssignment Time (physical): " + rs.getLong("assignment_time")
                        + "\nAssignment Lamport Clock: " + rs.getLong("assignment_lamport_clock")
                        + "\nDeadline (physical): " + rs.getLong("deadline")
                        + "\nCurrently serving from: " + (usingBackup ? "BACKUP" : "PRIMARY");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch ride", e);
        }
    }
}
