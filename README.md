# Layar CCTV (Android TV)

Aplikasi Android TV untuk menampilkan beberapa kamera Hikvision (via RTSP) dalam
satu layar grid. Daftar kamera & layout diatur dari HP lewat halaman web, tanpa
menyentuh remote TV.

Default: **1 besar + 5 kecil** (kotak besar = PTZ 10.46.10.65).

## Cara build (tanpa Android Studio, lewat GitHub Actions)

1. Buat repository baru di GitHub (boleh private), misalnya `layar-cctv`.
2. Upload semua isi folder ini ke repo (jaga struktur foldernya).
3. Buka tab **Actions** di repo. Workflow "Build APK" jalan otomatis setiap push.
   Kalau tidak jalan, klik **Build APK → Run workflow**.
4. Setelah hijau (± 3-5 menit), buka run-nya → bagian **Artifacts** →
   download **LayarCCTV-debug** (berisi `app-debug.apk`).
5. Install APK itu di Google TV (lewat "Downloader", "Send Files to TV", atau `adb install`).

## Cara pakai

1. Buka aplikasi **Layar CCTV** di TV. Di pojok kanan atas muncul alamat setting,
   misalnya `http://192.168.68.50:8080`.
2. Buka alamat itu di browser HP (HP harus di WiFi yang sama dengan TV).
3. Isi:
   - **Password default (DVR)** = password admin DVR 10.46.10.50
   - Kamera #1 (PTZ .65): isi **Password** sendiri (beda dari DVR)
   - Kamera .100: isi Password-nya kalau sudah ketemu
4. Klik **SIMPAN & TAMPILKAN**. Layar TV langsung reload.

### Catatan
- Kotak besar pakai Main stream (tajam), kotak kecil pakai Sub stream (ringan),
  bisa diubah per kamera di halaman setting.
- Kalau gambar patah-patah, naikkan **Buffer (ms)** atau pindahkan kotak kecil ke Sub.
- Halaman setting tanpa password; aman dipakai di jaringan kantor saja.
- Channel = nomor kamera di DVR (1-4). Kamera IP tunggal = channel 1.
