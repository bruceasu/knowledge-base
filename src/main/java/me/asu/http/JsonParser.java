package me.asu.http;

import me.asu.json.OKJSON;


import java.util.Map;

public class JsonParser implements RequestParser {
    @Override
    public void parseRequest(Request request) {
        Map<String, Object> m = OKJSON.toJson(request.getString(), Map.class,
                OKJSON.OPTIONS_DIRECT_ACCESS_PROPERTY_ENABLE);
        request.getDataMap().putAll(m);
    }
}
