package me.asu.http;

public enum CommonContentType {

    FORM("application/x-www-form-urlencoded"),
    FORM_DATA("multipart/form-data"),
    JSON("application/json"),
    XML("application/xml"),
    OCTET_STREAM("application/octet-stream"),
    ;

    private String type;

    CommonContentType(String type) {
        this.type = type;
    }

    public String type() {
        return this.type;
    }
}
