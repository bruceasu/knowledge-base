package me.asu.kb.adapter.gitevidence;

/** An ancestor-validated range whose oldest and newest commit endpoints are both included. */
public record InclusiveCommitRange(String oldestInclusive, String newestInclusive)
    implements GitCommitSelection {}
