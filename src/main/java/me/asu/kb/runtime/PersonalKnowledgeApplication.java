package me.asu.kb.runtime;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/** Transport-neutral personal knowledge boundary. */
public interface PersonalKnowledgeApplication extends AutoCloseable {
  record Caller(String subject, Set<String> scopes, Set<String> collections) {
    public Caller { scopes = Set.copyOf(scopes); collections = Set.copyOf(collections); }
    public boolean allows(String collection) { return collections.contains("*") || collections.contains(collection); }
  }
  record Result(int status, Map<String, Object> data) {}
  enum Operation { CAPABILITIES, SEARCH, READ, BROWSER_READ, RECENT, CREATE_NOTE, CREATION_STATUS, LIST_IMPORTS, RETRY_IMPORT }
  Result execute(Operation operation, Caller caller, Map<String, Object> request) throws Exception;
  Result upload(Caller caller, String filename, InputStream input) throws Exception;
  InputStream original(Caller caller, String documentId) throws Exception;

  final class Failure extends RuntimeException {
    private final int status;
    private final String code;
    public Failure(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
  }
}
