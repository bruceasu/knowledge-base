package me.asu.http;

import me.asu.log.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParseXMLUtils {

    /**
     * 将Document对象转为Map（String→Document→Map）
     */
    @SuppressWarnings("rawtypes")
    public static Map<String, Object> string2Map(String str) {
        return dom2Map(parseXmlString(str));
    }

    /**
     * 将Document对象转为Map（String→Document→Map）
     */
    @SuppressWarnings("rawtypes")
    public static Map<String, Object> dom2Map(Document doc) {
        Map<String, Object> map = new HashMap<String, Object>();
        if (doc == null) {
            return map;
        }
        Element root = doc.getDocumentElement();
        return (Map) dom2Map(root);
    }

    /**
     * 将Element对象转为Map（String→Document→Element→Map）
     *
     * @param e Node
     * @return Map
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object dom2Map(Node e) {
        Map map = new HashMap();
        if (e.hasChildNodes()) {
            NodeList childNodes = e.getChildNodes();
            if (childNodes.getLength() == 1 && childNodes.item(0).getNodeType() == Node.TEXT_NODE) {
                return childNodes.item(0).getNodeValue();
            } else {
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node node = childNodes.item(i);
                    if (node.getNodeType() == Node.TEXT_NODE) {
                        //map.put(e.getNodeName(), node.getNodeValue());
                        // 通常这个节点是空的。
                    } else {
                        Object val = dom2Map(node);
                        if (map.containsKey(node.getNodeName())) {
                            // convert to list
                            Object o = map.get(node.getNodeName());
                            if (o instanceof List) {
                                ((List) o).add(val);
                            } else {
                                List list = Arrays.asList(o, val);
                                map.put(node.getNodeName(), list);
                            }
                        } else {
                            map.put(node.getNodeName(), val);
                        }
                    }
                }
            }

        } else {
            return e.getNodeValue();
        }
        return map;
    }

    public static Document parseXmlString(String xmlStr) {

        try {
            InputSource is = new InputSource(new StringReader(xmlStr));
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            return doc;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) {
        String str1 = "<HEADER>" + "       <POOL_ID>2</POOL_ID>" + "       <DB_ID>EUR</DB_ID>"
                + "       <CHANNEL_ID>11</CHANNEL_ID>" + "       <USERNAME>tom</USERNAME>"
                + "       <PASSWORD>sss</PASSWORD>" + "   </HEADER>";
        String str2 = "<ROOT>" + "  <HEADER>" + "      <POOL_ID>2</POOL_ID>"
                + "      <CHANNEL_ID>11</CHANNEL_ID>" + "      <USERNAME>tom</USERNAME>"
                + "      <PASSWORD>sss</PASSWORD>" + "  </HEADER>" + "  <BODY>" + "      <BUSLIST>"
                + "          <PHONE_NO>7107300212</PHONE_NO>"
                + "          <TRACE_ID>97D2C7D26224A2DAE9A1CB501E60F395</TRACE_ID>"
                + "          <TENANT_ID>EUR</TENANT_ID>" + "          <LANG>zh_CN</LANG>"
                + "      </BUSLIST>" + "      <BUSLIST>"
                + "          <PHONE_NO>2222300212</PHONE_NO>"
                + "          <TRACE_ID>444424A2DAE9A1CB501E60F395</TRACE_ID>"
                + "          <TENANT_ID>USA</TENANT_ID>" + "          <LANG>zh_CN</LANG>"
                + "      </BUSLIST>" + "  </BODY>" + "</ROOT>";
        Document document;
        try {
            document = parseXmlString(str1);
            Map map = dom2Map(document);
            Log.info("map 1 >>> " + map);
            document = parseXmlString(str2);
            map = dom2Map(document);
            Log.info("map 2 >>> " + map);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}