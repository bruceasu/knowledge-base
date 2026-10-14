package me.asu.http;

import java.util.Map;

public class XmlParser implements RequestParser {
    @Override
    public void parseRequest(Request request) {
        Map<String, Object> m = ParseXMLUtils.string2Map(request.getString());
        if (m != null) {
            request.getDataMap().putAll(m);
        }
    }
}
