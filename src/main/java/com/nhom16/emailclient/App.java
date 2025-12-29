package com.nhom16.emailclient;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.File;
import java.util.List;

public class App extends Application {

    private Stage primaryStage;
    private SmtpHandler smtpHandler;
    private String currentUserEmail;
    private String currentPassword; // Lưu mật khẩu để dùng cho POP3
    private File selectedFile = null;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        showLoginScreen();
    }

    // --- MÀN HÌNH ĐĂNG NHẬP ---
    private void showLoginScreen() {
        Label lblHeader = new Label("EMAIL CLIENT - NHÓM 16");
        lblHeader.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        TextField txtServer = new TextField("smtp.gmail.com");
        TextField txtUser = new TextField(); 
        txtUser.setPromptText("Nhập email Gmail của bạn...");
        PasswordField txtPass = new PasswordField();
        txtPass.setPromptText("Mật khẩu ứng dụng 16 ký tự");

        Button btnLogin = new Button("Đăng Nhập");

        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Server:"), 0, 0); grid.add(txtServer, 1, 0);
        grid.add(new Label("Email:"), 0, 1);   grid.add(txtUser, 1, 1);
        grid.add(new Label("Mật khẩu:"), 0, 2);   grid.add(txtPass, 1, 2);

        VBox root = new VBox(20);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);
        root.getChildren().addAll(lblHeader, grid, btnLogin);

        btnLogin.setOnAction(e -> {
            try {
                smtpHandler = new SmtpHandler();
                smtpHandler.connectAndLogin(txtServer.getText(), 465, txtUser.getText(), txtPass.getText());
                
                currentUserEmail = txtUser.getText();
                currentPassword = txtPass.getText();
                
                showAlert("Thành công", "Đăng nhập OK!");
                showInboxScreen();
            } catch (Exception ex) {
                ex.printStackTrace();
                showAlert("Lỗi", "Đăng nhập thất bại: " + ex.getMessage());
            }
        });

        Scene scene = new Scene(root, 400, 350);
        primaryStage.setTitle("Đăng nhập");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    // --- MÀN HÌNH HỘP THƯ ĐẾN (INBOX) ---
    private void showInboxScreen() {
        Label lblTitle = new Label("HỘP THƯ ĐẾN (" + currentUserEmail + ")");
        lblTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        ListView<Pop3Handler.EmailInfo> listView = new ListView<>();
        TextArea txtContent = new TextArea();
        txtContent.setEditable(false);
        txtContent.setWrapText(true);

        Button btnRefresh = new Button("Làm mới (Tải mail)");
        Button btnCompose = new Button("Soạn thư mới");
        Button btnLogout = new Button("Đăng xuất");

        btnRefresh.setOnAction(e -> {
    try {
        listView.getItems().clear(); 
        
        Pop3Handler pop3 = new Pop3Handler();
        
        String recentUser = "recent:" + currentUserEmail; 
        
        pop3.connect("pop.gmail.com", 995, recentUser, currentPassword);
        
        List<Pop3Handler.EmailInfo> emails = pop3.getRecentEmails();
        listView.getItems().setAll(emails);
        
        pop3.quit();
        
        showAlert("Thành công", "Đã tải " + emails.size() + " email mới nhất!");
        
    } catch (Exception ex) {
        ex.printStackTrace();
        showAlert("Lỗi POP3", ex.getMessage());
    }
});

        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                txtContent.setText(newVal.cleanContent);
            }
        });

        btnCompose.setOnAction(e -> showComposeScreen());
        btnLogout.setOnAction(e -> showLoginScreen());

        // Bố cục giao diện
        HBox topBar = new HBox(10, btnRefresh, btnCompose, btnLogout);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(listView, txtContent);
        splitPane.setDividerPositions(0.4); // Chia tỷ lệ 40% - 60%

        VBox root = new VBox(5, lblTitle, topBar, splitPane);
        root.setPadding(new Insets(10));
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 700, 500);
        primaryStage.setTitle("Inbox - " + currentUserEmail);
        primaryStage.setScene(scene);
        
        // Tự động tải mail ngay khi vào màn hình
        btnRefresh.fire(); 
    }

    // --- MÀN HÌNH SOẠN THƯ (COMPOSE) ---
    private void showComposeScreen() {        
        Label lblTitle = new Label("SOẠN THƯ MỚI");
        TextField txtTo = new TextField(); txtTo.setPromptText("Người nhận");
        TextField txtSubject = new TextField(); txtSubject.setPromptText("Tiêu đề");
        TextArea txtContent = new TextArea();
        
        Button btnAttach = new Button("Đính kèm tệp...");
        Label lblFileName = new Label("");
        Button btnSend = new Button("Gửi Email");
        Button btnBack = new Button("Quay lại Inbox");

        btnAttach.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            selectedFile = fileChooser.showOpenDialog(primaryStage);
            if(selectedFile != null) lblFileName.setText(selectedFile.getName());
        });

        btnSend.setOnAction(e -> {
            try {
                smtpHandler = new SmtpHandler(); // Tạo kết nối mới
                smtpHandler.connectAndLogin("smtp.gmail.com", 465, currentUserEmail, currentPassword);
                smtpHandler.sendEmailWithAttachment(currentUserEmail, txtTo.getText(), txtSubject.getText(), txtContent.getText(), selectedFile);
                showAlert("Thành công", "Đã gửi!");
            } catch (Exception ex) { showAlert("Lỗi", ex.getMessage()); }
        });

        btnBack.setOnAction(e -> showInboxScreen());

        VBox layout = new VBox(10, lblTitle, new Label("To:"), txtTo, new Label("Subject:"), txtSubject, txtContent, btnAttach, lblFileName, btnSend, btnBack);
        layout.setPadding(new Insets(20));
        
        Scene scene = new Scene(layout, 500, 600);
        primaryStage.setTitle("Soạn thư");
        primaryStage.setScene(scene);
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}