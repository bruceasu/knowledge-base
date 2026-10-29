package me.asu.kb.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Preflight for uploaded ZIP and DOCX; counts actual bytes, not only ZIP headers. */
public final class ArchiveBudget {
  public static final long MAX_UPLOAD_BYTES = 100_000_000;
  public static final long MAX_EXPANDED_BYTES = 500_000_000;
  public static final long MAX_ENTRY_BYTES = 100_000_000;
  public static final int MAX_ENTRIES = 2_000;
  public static final int MAX_RATIO = 100;

  private ArchiveBudget() {}

  public static void validate(Path archive) throws IOException {
    long compressed = Files.size(archive);
    if (compressed < 1 || compressed > MAX_UPLOAD_BYTES) throw new IOException("archive size exceeds budget");
    long total = 0;
    int count = 0;
    Set<String> names = new HashSet<>();
    try (var metadata = new org.apache.commons.compress.archivers.zip.ZipFile(archive.toFile())) {
      var entries = metadata.getEntries();
      int inspected = 0;
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (++inspected > MAX_ENTRIES || entry.isUnixSymlink()) throw new IOException("unsafe archive entry");
      }
    }
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      var entries = zip.entries();
      byte[] buffer = new byte[8192];
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (++count > MAX_ENTRIES) throw new IOException("archive entry count exceeds budget");
        String name = safeEntryName(entry.getName());
        String identity = (name.endsWith("/") ? name.substring(0, name.length() - 1) : name).toLowerCase(Locale.ROOT);
        if (!names.add(identity)) throw new IOException("duplicate archive path");
        long size = 0;
        try (var input = zip.getInputStream(entry)) {
          for (int read; (read = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("archive validation interrupted");
            size += read;
            total += read;
            if (size > MAX_ENTRY_BYTES || total > MAX_EXPANDED_BYTES || total > compressed * MAX_RATIO) {
              throw new IOException("archive expansion exceeds budget");
            }
            long compressedEntry = entry.getCompressedSize();
            if (compressedEntry >= 0 && size > Math.max(1, compressedEntry) * MAX_RATIO) {
              throw new IOException("archive compression ratio exceeds budget");
            }
          }
        }
        if (entry.isDirectory() && size != 0) throw new IOException("archive directory contains data");
      }
    }
  }

  public static String safeEntryName(String name) throws IOException {
    if (name == null || name.isEmpty() || name.length() > 1024 || name.startsWith("/")
        || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) {
      throw new IOException("unsafe archive path");
    }
    String canonical = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    for (String part : canonical.split("/", -1)) {
      if (part.isEmpty()) throw new IOException("empty archive component");
      if (part.equals("..") || part.equals(".") || part.endsWith(".") || part.endsWith(" ")
          || part.chars().anyMatch(c -> c < 32)) throw new IOException("unsafe archive component");
      String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
      if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("reserved archive name");
    }
    return name;
  }
}
