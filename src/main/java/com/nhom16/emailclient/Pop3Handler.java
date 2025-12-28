package com.nhom16.emailclient;

import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pop3Handler {
    private SSLSocket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    public static class EmailInfo {
        public int id;
        public String from = "Unknown";
        public String subject = "No Subject";
        public String date = "";
        public String cleanContent = "";
        
        @Override
        public String toString() {
            // Hiển thị [Ngày giờ] Tiêu đề
            return String.format("[%s] %s", date, subject);
        }
    }

    public void connect(String host, int port, String user, String password) throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new SecureRandom());
        SSLSocketFactory factory = sc.getSocketFactory();
        
        socket = (SSLSocket) factory.createSocket(host, port);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        readOneLine();
        sendCommand("USER " + user);
        sendCommand("PASS " + password);
    }

    public List<EmailInfo> getRecentEmails() throws IOException {
        List<EmailInfo> list = new ArrayList<>();
        
        String statLine = sendCommand("STAT");
        String[] parts = statLine.split(" ");
        if (parts.length < 2) return list;

        int totalMsg = Integer.parseInt(parts[1]);
        // Lấy 10 mail mới nhất
        int start = Math.max(1, totalMsg - 9); 
        
        for (int i = totalMsg; i >= start; i--) {
            EmailInfo email = new EmailInfo();
            email.id = i;
            
            writer.write("RETR " + i + "\r\n");
            writer.flush();
            
            String response = reader.readLine();
            if (response != null && response.startsWith("+OK")) {
                StringBuilder rawBody = new StringBuilder();
                String line;
                boolean isHeader = true;
                
                String mainContentType = "";
                String mainEncoding = "";

                while ((line = reader.readLine()) != null) {
                    if (line.equals(".")) break; 

                    if (isHeader) {
                        if (line.isEmpty()) { isHeader = false; continue; }
                        
                        String lower = line.toLowerCase();
                        if (lower.startsWith("subject: ")) email.subject = decodeHeader(line.substring(9));
                        else if (lower.startsWith("from: ")) email.from = decodeHeader(line.substring(6));
                        else if (lower.startsWith("date: ")) {
                            email.date = line.substring(6).trim();
                            if (email.date.length() > 25) email.date = email.date.substring(0, 16);
                        }
                        else if (lower.startsWith("content-type: ")) mainContentType = line;
                        else if (lower.startsWith("content-transfer-encoding: ")) mainEncoding = line.substring(27).trim();
                    } else {
                        rawBody.append(line).append("\n");
                    }
                }
                
                email.cleanContent = processEmailBody(rawBody.toString(), mainContentType, mainEncoding);
                list.add(email);
            }
        }
        return list;
    }

    public void quit() throws IOException {
        sendCommand("QUIT");
        socket.close();
    }

    private String processEmailBody(String rawContent, String contentType, String encoding) {
        if (rawContent.contains("Content-Type:") || (contentType != null && contentType.toLowerCase().contains("multipart"))) {
            return extractTextFromMultipart(rawContent);
        }
        
        String decoded = rawContent;
        if ("base64".equalsIgnoreCase(encoding)) {
            decoded = decodeBase64(rawContent);
        } else if ("quoted-printable".equalsIgnoreCase(encoding)) {
            decoded = decodeQuotedPrintable(rawContent);
        }
        
        if (decoded.contains("<html") || decoded.contains("<div") || decoded.contains("<br")) {
            return stripHtmlTags(decoded);
        }
        
        return decoded;
    }

    private String extractTextFromMultipart(String raw) {
        try {
            String targetHeader = "Content-Type: text/plain";
            int index = raw.indexOf(targetHeader);
            
            boolean isHtml = false;
            if (index == -1) {
                targetHeader = "Content-Type: text/html";
                index = raw.indexOf(targetHeader);
                isHtml = true;
            }
            
            if (index != -1) {
                int headerEnd = findHeaderEnd(raw, index);
                
                if (headerEnd != -1) {
                    String partHeaders = raw.substring(index, headerEnd).toLowerCase();
                    boolean isBase64 = partHeaders.contains("base64");
                    boolean isQuoted = partHeaders.contains("quoted-printable");
                    
                    int startBody = headerEnd + 1;
                    int endBody = raw.indexOf("\n--", startBody);
                    if (endBody == -1) endBody = raw.length();
                    
                    String contentPart = raw.substring(startBody, endBody).trim();
                    
                    String result = contentPart;
                    if (isBase64) result = decodeBase64(contentPart);
                    else if (isQuoted) result = decodeQuotedPrintable(contentPart);
                    
                    if (isHtml) {
                        return stripHtmlTags(result);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
        }
        return "Không thể trích xuất nội dung văn bản từ email này.";
    }

    private String stripHtmlTags(String html) {
        String text = html.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p>", "\n\n");
        
        text = text.replaceAll("<[^>]+>", "");
        
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"");
                   
        return text.trim();
    }

    private int findHeaderEnd(String raw, int startIndex) {
        int idx = raw.indexOf("\n\n", startIndex);
        if (idx == -1) idx = raw.indexOf("\r\n\r\n", startIndex);
        return (idx != -1) ? idx + ((raw.charAt(idx) == '\r') ? 4 : 2) : -1;
    }

    private String decodeBase64(String encoded) {
        try {
            String clean = encoded.replaceAll("[^A-Za-z0-9+/=]", "");
            return new String(Base64.getDecoder().decode(clean), StandardCharsets.UTF_8);
        } catch (Exception e) { return encoded; }
    }

    private String decodeHeader(String text) {
        text = text.trim();
        try {
            Pattern p = Pattern.compile("=\\?UTF-8\\?(B|Q)\\?(.*?)\\?=", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            if (m.find()) {
                String type = m.group(1);
                String content = m.group(2);
                if (type.equalsIgnoreCase("B")) return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
                else if (type.equalsIgnoreCase("Q")) return decodeQuotedPrintable(content);
            }
        } catch (Exception e) {}
        return text;
    }

    private String decodeQuotedPrintable(String input) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c == '=') {
                    if (i + 2 < input.length()) {
                        String hex = input.substring(i + 1, i + 3);
                        try { buffer.write(Integer.parseInt(hex, 16)); i += 2; } 
                        catch (Exception e) { buffer.write(c); }
                    } else { buffer.write(c); }
                } else { buffer.write(c); }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return input; }
    }

    private String sendCommand(String cmd) throws IOException {
        writer.write(cmd + "\r\n");
        writer.flush();
        return reader.readLine();
    }

    private void readOneLine() throws IOException { reader.readLine(); }
}