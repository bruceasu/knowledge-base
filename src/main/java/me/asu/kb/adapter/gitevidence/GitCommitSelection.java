package me.asu.kb.adapter.gitevidence;

/** Closed caller grammar for selecting local commit objects without raw revision expressions. */
public sealed interface GitCommitSelection permits ExplicitCommits, InclusiveCommitRange {}
