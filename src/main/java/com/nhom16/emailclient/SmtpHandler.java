package com.nhom16.emailclient;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

public class SmtpHandler {
    private SSLSocket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    // Kết nối và Đăng nhập
    public void connectAndLogin(String server, int port, String user, String appPassword) throws Exception {
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
        socket = (SSLSocket) factory.createSocket(server, port);
        
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        
        readResponse("INIT"); 
        sendCommand("EHLO localhost");
        sendCommand("AUTH LOGIN");
        sendCommand(Base64.getEncoder().encodeToString(user.getBytes()));
        sendCommand(Base64.getEncoder().encodeToString(appPassword.getBytes()));
        
        System.out.println(">>> Đăng nhập OK!");
    }

    //GỬI MAIL KÈM FILE
    public void sendEmailWithAttachment(String from, String to, String subject, String body, File attachment) throws IOException {
        sendCommand("MAIL FROM:<" + from + ">");
        sendCommand("RCPT TO:<" + to + ">");
        sendCommand("DATA");

        String boundary = "---Boundary_Nhom16_" + System.currentTimeMillis();

        writer.write("Subject: " + subject + "\r\n");
        writer.write("From: " + from + "\r\n");
        writer.write("To: " + to + "\r\n");
        writer.write("MIME-Version: 1.0\r\n");
        writer.write("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n");
        writer.write("\r\n");

        writer.write("--" + boundary + "\r\n");
        writer.write("Content-Type: text/plain; charset=UTF-8\r\n");
        writer.write("Content-Transfer-Encoding: 7bit\r\n\r\n");
        writer.write(body + "\r\n\r\n");

        //Phần File đính kèm
        if (attachment != null && attachment.exists()) {
            writer.write("--" + boundary + "\r\n");
            writer.write("Content-Type: application/octet-stream; name=\"" + attachment.getName() + "\"\r\n");
            writer.write("Content-Transfer-Encoding: base64\r\n");
            writer.write("Content-Disposition: attachment; filename=\"" + attachment.getName() + "\"\r\n\r\n");
            
            // Đọc file -> Mã hóa Base64 -> Ghi vào Socket
            byte[] fileContent = Files.readAllBytes(attachment.toPath());
            String encodedFile = Base64.getMimeEncoder().encodeToString(fileContent);
            writer.write(encodedFile + "\r\n\r\n");
        }

        writer.write("--" + boundary + "--\r\n");
        writer.write(".\r\n");
        writer.flush();
        
        System.out.println("Client: [Đã gửi dữ liệu MIME]");
        readResponse("DATA_END");
        
        sendCommand("QUIT");
        socket.close();
    }

    private void sendCommand(String command) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
        readResponse(command);
    }

    private void readResponse(String step) throws IOException {
        String line = reader.readLine();
        while (line != null) {
            if (line.length() >= 4 && line.charAt(3) == '-') {
                line = reader.readLine(); 
            } else {
                break; 
            }
        }
    }
}