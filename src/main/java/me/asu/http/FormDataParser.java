package me.asu.http;

import java.util.List;
import java.util.Locale;

import static me.asu.http.HeaderKey.CONTENT_TYPE;
import static me.asu.http.Request.parseParameters;
import static me.asu.http.Streams.readToken;

public class FormDataParser implements RequestParser {
    @Override
    public void parseRequest(Request request) {
        try {
            String ct = request.headers.get(CONTENT_TYPE); // body params
            if (ct != null && ct.toLowerCase(Locale.US).startsWith(CommonContentType.FORM.type())) {

            }
            final List<String[]> strings = parseParameters(readToken(request.body, -1, "UTF-8", 2097152));
            for (String[] string : strings) {
                String key = string[0];
                String val = string[1];
                request.bodyMap.setParameter(key, val);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
