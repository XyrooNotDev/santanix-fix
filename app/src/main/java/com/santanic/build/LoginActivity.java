package com.santanic.build;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.santanic.build.api.GitHubAuthManager;
import com.santanic.build.utils.SessionManager;

public class LoginActivity extends AppCompatActivity {
    private SessionManager sessionManager;
    private GitHubAuthManager auth;
    private TextView tvDeviceCode, tvStatus;
    private Button btnLogin;
    private boolean navigating = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        sessionManager = new SessionManager(this);
        if (sessionManager.isLoggedIn() && !sessionManager.getToken().isEmpty()) {
            openHome(); return;
        }
        tvDeviceCode = findViewById(R.id.tvDeviceCode);
        tvStatus = findViewById(R.id.tvStatus);
        btnLogin = findViewById(R.id.btnLoginGithub);
        btnLogin.setOnClickListener(v -> beginLogin());
    }

    private void beginLogin() {
        if (auth != null) auth.cancel();
        auth = new GitHubAuthManager();
        btnLogin.setEnabled(false);
        btnLogin.setText("Menghubungkan...");
        tvDeviceCode.setText("");
        tvStatus.setText("Meminta kode login aman ke GitHub...");
        auth.requestDeviceCode(new GitHubAuthManager.AuthCallback() {
            @Override public void onCodeReceived(String code, String uri) {
                tvDeviceCode.setText("BUKA " + uri + "\n\nKODE LOGIN\n" + code);
                tvStatus.setText("Masukkan kode di halaman GitHub. Jangan bagikan kode ini kepada orang lain.");
            }
            @Override public void onLoginSuccess(String token) {
                if (isFinishing() || isDestroyed()) return;
                tvStatus.setText("Login terverifikasi. Mengambil profil GitHub...");
                auth.getUsername(token, (username, error) -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null || username == null) {
                        btnLogin.setEnabled(true); btnLogin.setText("Coba Login Lagi");
                        tvStatus.setText(error == null ? "Gagal mengambil profil GitHub." : error);
                        return;
                    }
                    sessionManager.saveSession(token, username);
                    navigating = true;
                    Toast.makeText(LoginActivity.this, "Selamat datang, " + username, Toast.LENGTH_SHORT).show();
                    openHome();
                });
            }
            @Override public void onError(String error) {
                if (isFinishing() || isDestroyed()) return;
                btnLogin.setEnabled(true); btnLogin.setText("Login dengan GitHub");
                tvStatus.setText(error);
            }
        });
    }

    private void openHome() {
        navigating = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override protected void onDestroy() {
        if (!navigating && auth != null) auth.cancel();
        super.onDestroy();
    }
}
