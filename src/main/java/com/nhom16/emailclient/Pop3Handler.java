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
        String respUser = sendCommand("USER " + user);
        if (!respUser.startsWith("+OK")) throw new Exception("Lỗi User: " + respUser);

        String respPass = sendCommand("PASS " + password);
        if (!respPass.startsWith("+OK")) throw new Exception("Lỗi Password/POP3: " + respPass);
    }

    public List<EmailInfo> getRecentEmails() throws IOException {
        List<EmailInfo> list = new ArrayList<>();
        
        String statLine = sendCommand("STAT");
        if (!statLine.startsWith("+OK")) return list;

        String[] parts = statLine.split(" ");
        if (parts.length < 2) return list;

        try {
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
                    String mainContentType = "", mainEncoding = "";

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
        } catch (Exception e) { System.err.println("Lỗi phân tích mail: " + e.getMessage()); }
        return list;
    }

    public void quit() throws IOException {
        sendCommand("QUIT");
        if (socket != null && !socket.isClosed()) socket.close();
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
                    int p1 = raw.lastIndexOf("\n\n", index);
                    int p2 = raw.lastIndexOf("\r\n\r\n", index);
                    int headerStart = Math.max(p1, p2);
                    if (headerStart == -1) headerStart = 0;
                    
                    String partHeaders = raw.substring(headerStart, headerEnd).toLowerCase();
                    
                    boolean isBase64 = partHeaders.contains("base64");
                    boolean isQuoted = partHeaders.contains("quoted-printable");
                    
                    int startBody = headerEnd + 1;
                    int endBody = raw.indexOf("\n--", startBody);
                    if (endBody == -1) endBody = raw.length();
                    
                    String contentPart = raw.substring(startBody, endBody).trim();
                    String result = contentPart;
                    
                    if (isBase64) result = decodeBase64(contentPart);
                    else if (isQuoted) result = decodeQuotedPrintable(contentPart);
                    
                    if (isHtml) return stripHtmlTags(result);
                    return result;
                }
            }
        } catch (Exception e) {}
        return "Không trích xuất được nội dung.";
    }

    private String stripHtmlTags(String html) {
        String text = html.replaceAll("(?i)<br\\s*/?>", "\n")
                          .replaceAll("(?i)</p>", "\n\n")
                          .replaceAll("<[^>]+>", "")
                          .replace("&nbsp;", " ").replace("&amp;", "&")
                          .replace("&lt;", "<").replace("&gt;", ">")
                          .replace("&quot;", "\"").replace("&apos;", "'");
        
        try {
            Matcher m = Pattern.compile("&#(\\d+);").matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
            m.appendTail(sb);
            text = sb.toString();
        } catch (Exception e) {}
        return text.trim();
    }

    private int findHeaderEnd(String raw, int startIndex) {
        int idx = raw.indexOf("\n\n", startIndex);
        if (idx == -1) idx = raw.indexOf("\r\n\r\n", startIndex);
        return (idx != -1) ? idx + ((raw.charAt(idx) == '\r') ? 4 : 2) : -1;
    }

    private String decodeBase64(String encoded) {
        try {
            // Xóa ký tự xuống dòng trước khi giải mã
            String clean = encoded.replaceAll("[^A-Za-z0-9+/=]", "");
            return new String(Base64.getDecoder().decode(clean), StandardCharsets.UTF_8);
        } catch (Exception e) { return encoded; }
    }

    private String decodeHeader(String text) {
        text = text.trim();
        try {
            Matcher m = Pattern.compile("=\\?UTF-8\\?(B|Q)\\?(.*?)\\?=", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) {
                String type = m.group(1), content = m.group(2);
                if (type.equalsIgnoreCase("B")) return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
                else return decodeQuotedPrintable(content);
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
                        try { buffer.write(Integer.parseInt(input.substring(i + 1, i + 3), 16)); i += 2; } 
                        catch (Exception e) { buffer.write(c); }
                    } else buffer.write(c); 
                } else buffer.write(c);
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