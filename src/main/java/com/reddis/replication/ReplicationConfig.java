package com.reddis.replication;

/**
 * @param masterHost   the master to follow at startup, or null to start as a master
 * @param masterPort   its port
 * @param backlogBytes how much of the recent write stream a master keeps for partial resyncs
 */
public record ReplicationConfig(String masterHost, int masterPort, long backlogBytes) {

    public static final long DEFAULT_BACKLOG_BYTES = 1024 * 1024;

    public ReplicationConfig {
        if (backlogBytes < 1024) {
            throw new IllegalArgumentException("repl-backlog-size must be at least 1kb");
        }
        if (masterHost != null && (masterPort < 1 || masterPort > 65_535)) {
            throw new IllegalArgumentException("invalid master port: " + masterPort);
        }
    }

    public static ReplicationConfig defaults() {
        return new ReplicationConfig(null, 0, DEFAULT_BACKLOG_BYTES);
    }

    public boolean isReplica() {
        return masterHost != null;
    }
}