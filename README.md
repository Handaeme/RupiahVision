# Rupiah Denomination Detection App

Aplikasi Android untuk deteksi denominasi Rupiah secara offline, dirancang untuk membantu tunanetra. Dibangun dengan Kotlin, CameraX, dan TFLite.

## Fitur Utama
- Deteksi Rupiah (Rp1,000–Rp100,000) secara real-time.
- Operasi offline dengan model TFLite kuantisasi (3MB).
- Kelas "no object" untuk filtrasi input tidak valid.
- Frame averaging (5 frame) untuk stabilitas.
- TTS offline untuk feedback aksesibel.

## Instalasi
1. Clone repositori: 'https://github.com/Handaeme/RupiahVision`
2. Buka di Android Studio.
3. Sinkronkan dependensi di `build.gradle`.
4. Jalankan di perangkat Android (min. Android 10, 4GB RAM).

## Struktur Proyek
- `/app/src/main/java/`: Kode Kotlin (CameraX, TFLite, TTS).
- `/app/src/main/assets/model.tflite`: Model TFLite.
- `/docs/dataset_description.md`: Deskripsi dataset.
- `/docs/screenshots/`: Screenshot aplikasi (UI, deteksi, "no object").

## Screenshot
![image](https://github.com/user-attachments/assets/17548b77-27c8-495f-9296-91f40d3c0125)
![image](https://github.com/user-attachments/assets/8ae23086-e996-41d7-8026-d32c4720c710)
![image](https://github.com/user-attachments/assets/b701f31b-9c67-4559-aa93-b3560a2d06dc)


## Lisensi
MIT License - lihat file [LICENSE](LICENSE).
