package com.reddis.storage;

/** A sorted-set member together with its score. */
public record ScoredMember(byte[] member, double score) { }