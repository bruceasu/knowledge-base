package me.asu.kb.session;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import me.asu.kb.service.KnowledgeRepository;

public final class SessionService {

  private static final int DEFAULT_CONTEXT_WINDOW = 8;

  private final KnowledgeRepository repository;

  public SessionService(KnowledgeRepository repository) {
    this.repository = repository;
  }

  public long create(String title) throws SQLException {
    return repository.insertSession(title);
  }

  public List<KnowledgeRepository.SessionRow> list() throws SQLException {
    return repository.findAllSessions();
  }

  public Optional<KnowledgeRepository.SessionRow> findById(long sessionId) throws SQLException {
    return repository.findSessionById(sessionId);
  }

  public void rename(long sessionId, String title) throws SQLException {
    repository.updateSessionTitle(sessionId, title);
  }

  public void archive(long sessionId) throws SQLException {
    repository.archiveSession(sessionId);
  }

  public void delete(long sessionId) throws SQLException {
    repository.deleteSession(sessionId);
  }

  public void appendUserMessage(long sessionId, String content) throws SQLException {
    insertMessage(sessionId, "user", content);
  }

  public void appendAssistantMessage(long sessionId, String content) throws SQLException {
    insertMessage(sessionId, "assistant", content);
  }

  public void upsertSummary(long sessionId, String summaryText) throws SQLException {
    repository.upsertSessionSummary(sessionId, nextSummaryNo(sessionId), summaryText);
  }

  public void upsertSummary(long sessionId, int summaryNo, String summaryText) throws SQLException {
    repository.upsertSessionSummary(sessionId, summaryNo, summaryText);
  }

  public Optional<String> latestSummary(long sessionId) throws SQLException {
    List<KnowledgeRepository.SessionSummaryRow> summaries = repository.findSummariesBySessionId(sessionId);
    if (summaries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(summaries.get(summaries.size() - 1).summaryText());
  }

  public List<KnowledgeRepository.SessionMessageRow> recentMessages(long sessionId) throws SQLException {
    return recentMessages(sessionId, DEFAULT_CONTEXT_WINDOW);
  }

  public List<KnowledgeRepository.SessionMessageRow> messages(long sessionId) throws SQLException {
    return repository.findMessagesBySessionId(sessionId);
  }

  public List<KnowledgeRepository.SessionSummaryRow> summaries(long sessionId) throws SQLException {
    return repository.findSummariesBySessionId(sessionId);
  }

  public List<KnowledgeRepository.SessionMessageRow> recentMessages(long sessionId, int limit)
      throws SQLException {
    List<KnowledgeRepository.SessionMessageRow> messages = repository.findMessagesBySessionId(sessionId);
    if (limit <= 0 || messages.size() <= limit) {
      return messages;
    }
    return messages.subList(messages.size() - limit, messages.size());
  }

  public int nextMessageNo(long sessionId) throws SQLException {
    return repository.findMessagesBySessionId(sessionId).size() + 1;
  }

  public void addMessage(long sessionId, String role, String content) throws SQLException {
    insertMessage(sessionId, role, content);
  }

  public Optional<KnowledgeRepository.SessionSummaryRow> latestSummaryRow(long sessionId)
      throws SQLException {
    List<KnowledgeRepository.SessionSummaryRow> summaries = repository.findSummariesBySessionId(sessionId);
    if (summaries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(summaries.get(summaries.size() - 1));
  }

  private void insertMessage(long sessionId, String role, String content) throws SQLException {
    int messageNo = nextMessageNo(sessionId);
    repository.insertSessionMessage(sessionId, messageNo, role, content);
  }

  private int nextSummaryNo(long sessionId) throws SQLException {
    return repository.findSummariesBySessionId(sessionId).size() + 1;
  }
}
