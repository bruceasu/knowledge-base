package me.asu.kb.adapter.gitevidence;

/** Result of exact local object-type validation for one complete object ID. */
enum GitObjectStatus {
  COMMIT,
  MISSING,
  NON_COMMIT
}
