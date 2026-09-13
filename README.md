# OSM Location v2.0

Ứng dụng Android lưu & hiển thị vị trí trên **OpenStreetMap (OSM)** – miễn phí, không cần API key.

## Phiên bản 2.0 – Ổn định trên máy thật

### Cải tiến chính
- Icon app mới (map pin)
- Bản đồ OSM chính thức (`tile.openstreetmap.org`)
- Khởi tạo osmdroid trong `Application` class (một lần, ổn định)
- Cache tile dùng app-private storage (Android 10–14)
- User-Agent hợp lệ → không bị OSM block
- Network Security Config đầy đủ
- Lấy vị trí: FusedLocation + fallback LocationManager (máy OEM / thiếu GMS)
- Firebase offline persistence
- Xử lý lỗi bao quanh mọi thao tác quan trọng → hạn chế crash
- versionCode 2 / versionName 2.0

### Tính năng
- Đăng nhập / Đăng ký email (Firebase Auth)
- Bản đồ OpenStreetMap
- Vị trí hiện tại (GPS)
- Thêm vị trí bất kỳ (long-press hoặc nút +)
- Lưu vào node `locations` trên Firebase Realtime Database
- Danh sách + xóa vị trí
- Giao diện Material 3, đa thiết bị

### Cài đặt nhanh
1. Thay `app/google-services.json` bằng file thật từ Firebase Console  
   (package name: `com.example.locationmap`)
2. Bật Email/Password trong Firebase Authentication
3. Cập nhật Rules:
```json
{
  "rules": {
    "locations": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid"
      },
      "companies": {
        ".read": "auth != null",
        ".write": "auth != null"
      }
    }
  }
}
```
4. Build:
```bash
./gradlew clean assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

### Trên máy Oscal Tiger 12 / Android 12+
- Cấp quyền **Vị trí**
- Bật GPS + Internet
- Nếu map xám: Xóa dữ liệu app → mở lại

### GitHub Actions
Push lên repo → tab Actions → tải artifact `app-debug`.

## v2.1 – Bản đồ địa hình + Fix lưu vị trí

- **Bản đồ địa hình**: OpenTopoMap (dựa trên OSM + SRTM)
- **Lưu vị trí**: ghi bằng HashMap (tránh lỗi serialize), thông báo lỗi rõ (Permission denied / mạng)
- Đọc dữ liệu an toàn từng field từ Firebase

### Firebase Rules bắt buộc (nếu chưa cập nhật sẽ lỗi Permission denied khi lưu):

```json
{
  "rules": {
    "locations": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid"
      },
      "companies": {
        ".read": "auth != null",
        ".write": "auth != null"
      }
    }
  }
}
```

Vào Firebase Console → Realtime Database → Rules → dán rules trên → Publish.
