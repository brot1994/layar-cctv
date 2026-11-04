REMOTE TV  -  remote Google TV dari laptop (lewat ADB, jaringan WiFi/LAN)

SIAPKAN TV (sekali saja)
 1. Settings > System > About > klik "Android TV OS build" 7 kali (muncul "You are a developer").
 2. Settings > System > Developer options > aktifkan "USB debugging" dan
    "Network debugging" / "Wireless debugging" (nama beda-beda tiap TV).
 3. Catat IP TV (Settings > Network & Internet), misal 192.168.68.50.

PAKAI
 1. Ekstrak zip ini ke satu folder, jalankan RemoteTV.exe (folder "tools" harus ikut di sampingnya).
 2. Isi IP TV, klik Hubungkan.
 3. Pertama kali: di layar TV muncul "Allow debugging?" -> pilih "Always allow", lalu klik Hubungkan lagi.
 4. Klik "Simpan" untuk menyimpan TV (bisa 2 TV: CCTV dan web).

FITUR
 - Remote : tombol di layar atau keyboard laptop (panah, Enter = OK, Esc = Back, Home).
 - Ketik teks langsung ke kolom input di TV (huruf latin/angka/simbol biasa).
 - File / APK : seret file ke jendela. APK dipasang, file lain masuk folder Download TV.
 - Aplikasi : daftar aplikasi terpasang, buka / hentikan.
 - Layar : screenshot TV, atau tampilkan layar TV di laptop (scrcpy).

CATATAN
 - Laptop dan TV harus bisa saling terjangkau di jaringan.
 - Selama debugging jaringan aktif, perangkat lain di jaringan yang sama juga bisa mengontrol TV.
 - Sebagian TV mematikan Network debugging setelah restart; aktifkan lagi kalau gagal terhubung.
 - Netflix dan aplikasi ber-DRM tampil hitam di screenshot / scrcpy (kontrol tetap jalan).
 - adb dan scrcpy di folder "tools" adalah program pihak ketiga (Genymobile/scrcpy, Apache-2.0).
