package me.asu.kb.ingest;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Pure local extraction. ZIP resources remain in the untouched original bundle. */
public final class PersonalTextExtractor {
  public java.util.List<String> diagnostics(Path original, String filename) throws IOException {
    if (!extension(filename).equals("zip")) return java.util.List.of();
    var warnings = new java.util.LinkedHashSet<String>();
    try (ZipFile zip = new ZipFile(original.toFile())) {
      var names = zip.stream().map(java.util.zip.ZipEntry::getName).collect(java.util.stream.Collectors.toSet());
      var htmlFiles = zip.stream().filter(e -> java.util.Set.of("html", "htm").contains(extension(e.getName()))).toList();
      for (var entry : htmlFiles) {
        try (var input = zip.getInputStream(entry)) {
          String source = decode(input.readNBytes(OfficeTextExtractor.MAX_TEXT_CHARS * 4));
          var matcher = java.util.regex.Pattern.compile("(?i)(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(source);
          while (matcher.find() && warnings.size() < 20) {
            String target = matcher.group(1);
            if (target.startsWith("#") || target.startsWith("data:")) continue;
            try {
              var uri = java.net.URI.create(target);
              if (uri.isAbsolute() || target.startsWith("//")) { warnings.add("外部资源未加载"); continue; }
              Path parent = Path.of(entry.getName()).getParent();
              String resource = (parent == null ? Path.of(uri.getPath()) : parent.resolve(uri.getPath())).normalize().toString().replace('\\', '/');
              if (!names.contains(resource)) warnings.add("缺失资源：" + target.substring(0, Math.min(160, target.length())));
            } catch (RuntimeException invalid) { warnings.add("部分资源链接无法解析"); }
          }
        }
      }
      if (names.stream().anyMatch(name -> extension(name).equals("zip"))) warnings.add("嵌套 ZIP 已保留但未展开");
    }
    return java.util.List.copyOf(warnings);
  }
  public String extract(Path original, String filename) throws IOException {
    String extension = extension(filename);
    String text = switch (extension) {
      case "pdf" -> new OfficeTextExtractor().pdf(original);
      case "docx" -> new OfficeTextExtractor().docx(original);
      case "zip" -> bundle(original);
      case "html", "htm" -> html(decode(Files.readAllBytes(original)));
      case "md", "markdown", "txt" -> decode(Files.readAllBytes(original));
      default -> throw new UnsupportedFormat();
    };
    if (text.length() > OfficeTextExtractor.MAX_TEXT_CHARS) throw new IOException("extracted text exceeds budget");
    return text;
  }
  private String bundle(Path original) throws IOException {
    ArchiveBudget.validate(original);
    StringBuilder text = new StringBuilder();
    try (ZipFile zip = new ZipFile(original.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        String extension = extension(entry.getName());
        if (entry.isDirectory() || !java.util.Set.of("html", "htm", "txt", "md", "markdown").contains(extension)) continue;
        try (var input = zip.getInputStream(entry)) {
          byte[] bytes = input.readNBytes(OfficeTextExtractor.MAX_TEXT_CHARS * 4 + 1);
          if (bytes.length > OfficeTextExtractor.MAX_TEXT_CHARS * 4) throw new IOException("bundle text exceeds budget");
          String part = decode(bytes);
          if (extension.equals("html") || extension.equals("htm")) part = html(part);
          if ((long) text.length() + part.length() + entry.getName().length() + 8 > OfficeTextExtractor.MAX_TEXT_CHARS) throw new IOException("bundle text exceeds budget");
          text.append("\n\n# ").append(entry.getName()).append("\n\n").append(part);
        }
      }
    }
    if (text.isEmpty()) throw new UnsupportedFormat();
    return text.toString();
  }
  private String html(String source) throws IOException {
    StringBuilder text = new StringBuilder();
    new ParserDelegator().parse(new StringReader(source), new HTMLEditorKit.ParserCallback() {
      private int ignored;
      @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
        if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) ignored++;
      }
      @Override public void handleEndTag(HTML.Tag tag, int position) {
        if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && ignored > 0) ignored--;
        if (ignored == 0) text.append('\n');
      }
      @Override public void handleText(char[] chars, int position) { if (ignored == 0) text.append(chars).append(' '); }
    }, true);
    return text.toString();
  }
  private String decode(byte[] bytes) {
    org.mozilla.universalchardet.UniversalDetector detector = new org.mozilla.universalchardet.UniversalDetector(null);
    detector.handleData(bytes, 0, bytes.length); detector.dataEnd();
    String charset = detector.getDetectedCharset();
    return new String(bytes, charset == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(charset));
  }
  public static String extension(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
  public static final class UnsupportedFormat extends IOException { public UnsupportedFormat() { super("unsupported text extraction"); } }
}
