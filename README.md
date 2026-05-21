# Privacy Display

<p align="center">
  <img src="https://img.shields.io/badge/Android-26%2B-3DDC84?style=flat&logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/One%20UI-8.0-1428A0?style=flat&logo=samsung&logoColor=white"/>
  <img src="https://img.shields.io/badge/Language-Java-ED8B00?style=flat&logo=openjdk&logoColor=white"/>
  <img src="https://img.shields.io/badge/Material-3-6750A4?style=flat&logo=material-design&logoColor=white"/>
</p>

> Ứng dụng Android mô phỏng tính năng **Privacy Display** của Samsung Galaxy S26 — tự động làm tối màn hình khi người dùng nghiêng điện thoại, ngăn người xung quanh nhìn trộm nội dung.

---

## Tính năng

- **Tilt Detection** — Phát hiện nghiêng máy bằng accelerometer, kích hoạt ngay khi góc nghiêng vượt ngưỡng
- **Smooth Fade** — Hiệu ứng tối/sáng mượt mà (fade in 200ms / fade out 180ms)
- **Full-screen Coverage** — Phủ toàn màn hình kể cả status bar, navigation bar và notch
- **Hysteresis** — Chống dao động khi điện thoại ở ranh giới nghiêng/thẳng
- **Battery Optimized** — Sensor batching + low-pass filter, tiêu thụ pin thấp
- **Silent Notification** — Chạy nền không gây phiền với thông báo im lặng
- **Auto Start** — Tự khởi động khi bật máy
- **Bilingual** — Hỗ trợ Tiếng Việt và English
- **Theme** — Sáng / Tối / Theo hệ thống (Material 3)

---

## Yêu cầu

| Mục | Yêu cầu |
|-----|---------|
| Android | 8.0 (API 26) trở lên |
| Cảm biến | Accelerometer |
| Quyền | Hiển thị trên ứng dụng khác (SYSTEM_ALERT_WINDOW) |
| Thử nghiệm | Samsung One UI 8.0 / Android 16 |

---

## Cài đặt

### Tải APK trực tiếp
1. Vào tab **Actions** của repo → chọn build mới nhất
2. Tải file `privacy-display.apk` trong phần **Artifacts**
3. Chuyển APK vào điện thoại (Telegram / Google Drive / USB)
4. Mở file APK → cấp quyền **Cài từ nguồn không xác định** → Cài đặt

### Build từ source
```bash
# Clone repo
git clone https://github.com/<your-username>/privacy-display.git
cd privacy-display

# Build debug APK
./gradlew assembleDebug

# APK xuất ra tại:
# app/build/outputs/apk/debug/privacy-display.apk
```

---

## Hướng dẫn sử dụng

### Lần đầu
1. Mở app **Privacy Display**
2. Nhấn **Cấp quyền** → cho phép *Hiển thị trên ứng dụng khác*
3. Bật toggle **Màn hình riêng tư**

### Cài đặt
| Tuỳ chọn | Mô tả |
|----------|-------|
| **Độ nhạy** | Góc nghiêng tối thiểu để kích hoạt (15°–55°). Mặc định 25° |
| **Độ tối** | Mức độ tối của màn hình (20%–100%). Mặc định 88% |
| **Tự khởi động** | Tự bật khi khởi động điện thoại |

### Menu (⋮)
- **Ngôn ngữ** — Chuyển Tiếng Việt / English
- **Giao diện** — Sáng / Tối / Theo hệ thống

---

## Tối ưu pin (Samsung One UI)

Samsung có thể tự động tắt app chạy nền. Để app hoạt động ổn định:

1. Vào **Cài đặt** → **Ứng dụng** → **Privacy Display**
2. Chọn **Pin**
3. Chọn **Không hạn chế**

---

## Cấu trúc project

```
privacy-display/
├── .github/
│   └── workflows/
│       └── build.yml              # GitHub Actions — tự động build APK
├── app/
│   └── src/main/
│       ├── java/com/privacydisplay/
│       │   ├── MainActivity.java  # UI chính
│       │   ├── OverlayService.java# Service lõi (sensor + overlay)
│       │   ├── BootReceiver.java  # Auto-start khi boot
│       │   └── Prefs.java         # Hằng số SharedPreferences
│       ├── res/
│       │   ├── layout/            # XML layout
│       │   ├── drawable/          # Icons & shapes
│       │   ├── values/            # Strings (EN), Colors, Themes
│       │   ├── values-vi/         # Strings (VI)
│       │   ├── values-night/      # Dark theme
│       │   ├── mipmap-*/          # App icon (adaptive)
│       │   └── xml/               # Locale config
│       └── AndroidManifest.xml
├── gradle/wrapper/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradlew
```

---

## Kiến trúc kỹ thuật

### Overlay
- Dùng `TYPE_APPLICATION_OVERLAY` với `FLAG_LAYOUT_NO_LIMITS` để phủ toàn màn hình
- `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (Android 11+) để phủ qua notch
- `screenBrightness` trong `WindowManager.LayoutParams` để dim backlight — phủ status bar và nav bar
- **Một view duy nhất** được tạo khi service start và tồn tại suốt — chỉ animate alpha, không add/remove → không chớp giật

### Sensor
- `SENSOR_DELAY_GAME` (~20ms/sample) cho phản hồi nhanh
- **Low-pass filter** (α = 0.25) lọc nhiễu sensor
- **Warmup period** (5 sample đầu) để seed filter với dữ liệu thực trước khi đánh giá góc nghiêng
- **Hysteresis 7°** — ON tại N°, OFF tại (N-7)° — tránh dao động tại ranh giới
- **Time-gate 300ms** — phải ổn định ít nhất 300ms mới chuyển trạng thái

### Pin
| Cơ chế | Hiệu quả |
|--------|---------|
| Sensor GAME rate | Phản hồi nhanh, ~3% pin/giờ |
| Low-pass filter | Giảm số lần trigger không cần thiết |
| Overlay alpha-only | Không tốn chi phí add/remove view |
| IMPORTANCE_MIN notification | Không wake CPU thêm |

---

## Permissions

| Permission | Lý do |
|-----------|-------|
| `SYSTEM_ALERT_WINDOW` | Vẽ overlay lên trên tất cả ứng dụng |
| `FOREGROUND_SERVICE` | Chạy service nền ổn định |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ yêu cầu khai báo loại service |
| `POST_NOTIFICATIONS` | Hiển thị notification (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start khi bật máy |

---

## Build tự động (GitHub Actions)

Mỗi lần push lên `main`/`master`, GitHub Actions tự động build APK:

1. Vào tab **Actions** của repo GitHub
2. Chọn workflow **Build Privacy Display APK**
3. Nhấn **Run workflow** (nếu chưa tự chạy)
4. Chờ ~3–5 phút
5. Tải APK tại phần **Artifacts → privacy-display**

---

## License

```
MIT License — Tự do sử dụng, chỉnh sửa và phân phối
```
