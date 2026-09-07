-- Schema for shared ride state between MyCab server instances.
-- Mirrors the fields currently held in-memory in common.Ride,
-- split into a rides table and a normalized acceptances table
-- (one ride has many driver acceptances).
 
CREATE TABLE IF NOT EXISTS rides (
    ride_id           INT AUTO_INCREMENT PRIMARY KEY,
    customer          VARCHAR(255) NOT NULL,
    pickup            VARCHAR(255) NOT NULL,
    destination       VARCHAR(255) NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'WAITING',
    assigned_driver   VARCHAR(255) DEFAULT NULL,
    request_time      BIGINT NOT NULL,
    assignment_time   BIGINT DEFAULT NULL,
    assignment_lamport_clock BIGINT DEFAULT NULL,
    deadline          BIGINT DEFAULT NULL
);
 
CREATE TABLE IF NOT EXISTS acceptances (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    ride_id           INT NOT NULL,
    driver_id         VARCHAR(255) NOT NULL,
    physical_timestamp BIGINT NOT NULL,
    lamport_clock     BIGINT NOT NULL,
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id) ON DELETE CASCADE,
    UNIQUE KEY unique_driver_per_ride (ride_id, driver_id)
);
 
CREATE TABLE IF NOT EXISTS arrivals (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    ride_id           INT NOT NULL,
    driver_id         VARCHAR(255) NOT NULL,
    lamport_clock     BIGINT NOT NULL,
    FOREIGN KEY (ride_id) REFERENCES rides(ride_id) ON DELETE CASCADE
);