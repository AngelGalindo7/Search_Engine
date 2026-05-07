package com.example;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

public class SecurityGuards {

    public static final long MAX_TOTAL_OUTPUT_BYTES = 5L * 1024 * 1024 * 1024; // 5 GB hard cap
    public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;                 // 2 MB per article
    public static final int MAX_REDIRECTS = 5;
    public static final int FETCH_TIMEOUT_MS = 15_000;
    public static final int FEED_CONNECT_TIMEOUT_MS = 15_000;
    public static final int FEED_READ_TIMEOUT_MS = 30_000;
    public static final int MAX_POSTS_PER_BLOG_HARD = 500;                    // safety belt

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public static DocumentBuilder secureXmlBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    public static boolean isUrlSafe(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) return false;

            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;

            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (a.isLoopbackAddress()) return false;        // 127.0.0.0/8, ::1
                if (a.isSiteLocalAddress()) return false;       // 10/8, 172.16/12, 192.168/16
                if (a.isLinkLocalAddress()) return false;       // 169.254/16 (incl. AWS metadata)
                if (a.isMulticastAddress()) return false;
                if (a.isAnyLocalAddress()) return false;        // 0.0.0.0
                String addr = a.getHostAddress().toLowerCase();
                if (addr.startsWith("fc") || addr.startsWith("fd")) return false; // IPv6 ULA
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String fetchHtmlSafe(String url, String userAgent) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isUrlSafe(current)) return null;
            try {
                Connection.Response resp = Jsoup.connect(current)
                        .userAgent(userAgent)
                        .timeout(FETCH_TIMEOUT_MS)
                        .maxBodySize(MAX_BODY_BYTES)
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(false)
                        .execute();
                int code = resp.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = resp.header("Location");
                    if (loc == null || loc.isBlank()) return null;
                    if (!loc.startsWith("http://") && !loc.startsWith("https://")) {
                        loc = URI.create(current).resolve(loc).toString();
                    }
                    current = loc;
                } else if (code >= 200 && code < 300) {
                    String contentType = resp.contentType();
                    if (contentType != null && !contentType.toLowerCase().contains("html")
                            && !contentType.toLowerCase().contains("xml")
                            && !contentType.toLowerCase().contains("text")) {
                        return null; // non-text payload, drop it
                    }
                    return resp.parse().outerHtml();
                } else {
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
