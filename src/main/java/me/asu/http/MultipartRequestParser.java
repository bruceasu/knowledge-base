package me.asu.http;

public class MultipartRequestParser implements RequestParser {
    @Override
    public void parseRequest(Request request) {
        try {
            final MultipartIterator multipartIterator = new MultipartIterator(request);
            while (multipartIterator.hasNext()) {
                final Part next = multipartIterator.next();
                if (next.type == Part.FILE) {
                    next.writeToTempFile();
                    request.files.add(next);
                } else {
                    request.dataMap.put(next.name, next.getString());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}