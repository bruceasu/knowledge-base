package me.asu.kb.context;

/** Fixed priority category explaining why an item was selected. */
public enum ContextSelectionReason {
  TARGET,
  DIRECT_REQUIREMENT_DESIGN_DECISION,
  DIRECT_CODE_SYMBOL,
  CORRESPONDING_TEST,
  MATCHING_GIT_EVIDENCE,
  OTHER_DIRECT_RELATION
}
