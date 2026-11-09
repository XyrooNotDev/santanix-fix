package com.santanic.build.api;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** GitHub OAuth Device Flow. Semua callback dikirim ke main thread. */
public class GitHubAuthManager {
    private static final String CLIENT_ID = "Ov23liJRu7F3iYnIevNi";
    private static final String DEVICE_URL = "https://github.com/login/device/code";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String API_USER = "https://api.github.com/user";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private volatile boolean completed = false;
    private volatile Call activeCall;
    private Runnable pollTask;

    public interface AuthCallback {
        void onCodeReceived(String userCode, String verificationUri);
        void onLoginSuccess(String accessToken);
        void onError(String error);
    }

    public void requestDeviceCode(AuthCallback callback) {
        RequestBody body = new FormBody.Builder().add("client_id", CLIENT_ID)
                .add("scope", "repo workflow read:user").build();
        Request request = new Request.Builder().url(DEVICE_URL)
                .header("Accept", "application/json").post(body).build();
        activeCall = client.newCall(request);
        activeCall.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (!cancelled) postError(callback, "Tidak dapat menghubungi GitHub: " + safeMessage(e));
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String raw = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) { postError(callback, httpError(r.code(), raw)); return; }
                    JSONObject json = new JSONObject(raw);
                    String deviceCode = json.optString("device_code");
                    String userCode = json.optString("user_code");
                    String uri = json.optString("verification_uri", "https://github.com/login/device");
                    int interval = Math.max(5, json.optInt("interval", 5));
                    int expires = json.optInt("expires_in", 900);
                    if (deviceCode.isEmpty() || userCode.isEmpty()) {
                        postError(callback, "Respons GitHub tidak lengkap. Periksa konfigurasi OAuth App."); return;
                    }
                    if (!cancelled) {
                        main.post(() -> { if (!cancelled) callback.onCodeReceived(userCode, uri); });
                        startPolling(deviceCode, interval, expires, callback);
                    }
                } catch (Exception e) { postError(callback, "Gagal membaca respons GitHub: " + safeMessage(e)); }
            }
        });
    }

    private void startPolling(String deviceCode, int interval, int expires, AuthCallback callback) {
        final long deadline = System.currentTimeMillis() + expires * 1000L;
        final int[] delay = {interval};
        pollTask = new Runnable() {
            @Override public void run() {
                if (cancelled || completed) return;
                if (System.currentTimeMillis() >= deadline) {
                    postError(callback, "Kode login kedaluwarsa. Silakan mulai login lagi."); return;
                }
                RequestBody body = new FormBody.Builder().add("client_id", CLIENT_ID)
                        .add("device_code", deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build();
                Request req = new Request.Builder().url(TOKEN_URL)
                        .header("Accept", "application/json").post(body).build();
                activeCall = client.newCall(req);
                activeCall.enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        if (!cancelled && System.currentTimeMillis() < deadline) schedule(delay[0] * 1000L);
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try (Response r = response) {
                            String raw = r.body() != null ? r.body().string() : "";
                            JSONObject json = new JSONObject(raw);
                            String token = json.optString("access_token");
                            if (r.isSuccessful() && !token.isEmpty()) {
                                completed = true;
                                main.post(() -> { if (!cancelled) callback.onLoginSuccess(token); });
                                return;
                            }
                            String error = json.optString("error", "");
                            if ("authorization_pending".equals(error)) {
                                schedule(delay[0] * 1000L);
                            } else if ("slow_down".equals(error)) {
                                delay[0] += 5; schedule(delay[0] * 1000L);
                            } else if ("access_denied".equals(error)) {
                                postError(callback, "Login ditolak pada halaman GitHub.");
                            } else if ("expired_token".equals(error)) {
                                postError(callback, "Kode login sudah kedaluwarsa. Coba login kembali.");
                            } else if (!error.isEmpty()) {
                                postError(callback, "GitHub OAuth: " + error.replace('_', ' '));
                            } else if (r.code() >= 500 || r.code() == 429) {
                                schedule(delay[0] * 1000L);
                            } else {
                                postError(callback, httpError(r.code(), raw));
                            }
                        } catch (Exception e) {
                            if (!cancelled) schedule(delay[0] * 1000L);
                        }
                    }
                });
            }
            private void schedule(long millis) {
                if (!cancelled && !completed && System.currentTimeMillis() < deadline)
                    main.postDelayed(this, millis);
            }
        };
        main.postDelayed(pollTask, interval * 1000L);
    }

    public void getUsername(String token, OnUsernameFetched listener) {
        Request request = new Request.Builder().url(API_USER)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Santanic-Build-Android").build();
        activeCall = client.newCall(request);
        activeCall.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                main.post(() -> listener.onFetched(null, "Gagal mengambil profil: " + safeMessage(e)));
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String raw = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) { main.post(() -> listener.onFetched(null, httpError(r.code(), raw))); return; }
                    String username = new JSONObject(raw).optString("login", "");
                    if (username.isEmpty()) main.post(() -> listener.onFetched(null, "Username GitHub tidak ditemukan."));
                    else main.post(() -> listener.onFetched(username, null));
                } catch (Exception e) {
                    main.post(() -> listener.onFetched(null, "Gagal membaca profil GitHub: " + safeMessage(e)));
                }
            }
        });
    }

    public void cancel() {
        cancelled = true;
        if (pollTask != null) main.removeCallbacks(pollTask);
        Call call = activeCall;
        if (call != null) call.cancel();
    }

    private void postError(AuthCallback callback, String message) {
        if (cancelled || completed) return;
        completed = true;
        main.post(() -> { if (!cancelled) callback.onError(message); });
    }
    private static String safeMessage(Exception e) {
        String m = e.getMessage(); return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
    private static String httpError(int code, String body) {
        String detail = "";
        try { detail = new JSONObject(body).optString("message", ""); } catch (Exception ignored) { }
        return "GitHub merespons HTTP " + code + (detail.isEmpty() ? "." : ": " + detail);
    }
    public interface OnUsernameFetched { void onFetched(String username, String error); }
}
