SANTANIC BUILD - PERBAIKAN

Perubahan utama:
- Tampilan login, dashboard, form build, dan riwayat diperbarui dengan tema gelap-merah mengikuti referensi.
- Login GitHub menggunakan OAuth Device Flow.
- Semua callback autentikasi dikirim ke main/UI thread.
- Menambahkan timeout koneksi, pemeriksaan HTTP, penanganan authorization_pending, slow_down, access_denied, expired_token, serta batas waktu kode login.
- Polling dan request aktif dibatalkan ketika Activity ditutup agar tidak terus berjalan di background.
- Tombol login dinonaktifkan selama proses berlangsung untuk mencegah request login ganda.
- Profil GitHub divalidasi sebelum session disimpan.

PENTING:
1. Pastikan CLIENT_ID di GitHubAuthManager.java adalah Client ID OAuth App milik Anda.
2. Aktifkan opsi Device Flow pada pengaturan OAuth App GitHub tersebut.
3. OAuth App harus memiliki izin/scopes repo, workflow, dan read:user sesuai kebutuhan aplikasi.
4. Build lokal belum dapat diverifikasi di lingkungan ini karena Gradle/Android SDK tidak tersedia. Jalankan assembleDebug atau workflow GitHub Actions untuk validasi build final.
5. Perbaikan ini menangani penyebab crash yang ditemukan pada alur login, tetapi tetap uji pada perangkat Android dan periksa Logcat jika ada crash lain.
