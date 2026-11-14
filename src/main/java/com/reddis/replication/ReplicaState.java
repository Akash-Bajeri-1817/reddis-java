package com.reddis.replication;

public interface ReplicaState {
    boolean isReplica();
    boolean isApplying();
}