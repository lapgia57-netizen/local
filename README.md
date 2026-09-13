# Location Map App – Android

Ứng dụng Android lưu trữ & hiển thị vị trí trên bản đồ, đồng bộ Firebase Realtime Database.

## Tính năng

- **Đăng nhập / Đăng ký** bằng Email + Password (Firebase Authentication)
- **Bản đồ miễn phí** OpenStreetMap (osmdroid) – không cần Google Maps API key
- **Vị trí hiện tại**: hiển thị chấm xanh, nút “Vị trí của tôi”
- **Thêm vị trí bất kỳ**:
  - Nhấn giữ (long-press) trên bản đồ
  - Hoặc bấm nút + rồi chỉnh tọa độ
- **Lưu vào node mới** `locations` trên Firebase Realtime Database
- Danh sách vị trí đã lưu, click để di chuyển map, xóa được
- Giao diện Material Design 3, hỗ trợ đa thiết bị (phone/tablet)
- Có GitHub Actions để build APK tự động

## Cấu trúc Firebase

### Node mới được sử dụng
```
locations/
  {auto-generated-id}/
    id: string
    name: string
    latitude: number
    longitude: number
    userId: string
    userEmail: string
    timestamp: number
```

### Cập nhật Security Rules (quan trọng!)

Trong Firebase Console → Realtime Database → Rules, thêm rule cho node `locations`:

```json
{
  "rules": {
    "users": {
      "$user_id": {
        ".read": "$user_id === auth.uid",
        ".write": "$user_id === auth.uid"
      },
      "companies": {
        ".read": "auth != null",
        ".write": "auth != null"
      },
      "locations": {
        ".read": "auth != null",
        ".write": "auth != null"
      }
    }
  }
}
```

(Hoặc chặt hơn: chỉ cho user đọc/ghi location của chính mình)

## Cài đặt & chạy

### 1. Thêm Android app vào Firebase project

1. Vào [Firebase Console](https://console.firebase.google.com/) → project `ptptt-pcpc`
2. Project settings → Your apps → Add app → Android
3. Package name: `com.example.locationmap`
4. Tải file `google-services.json` và **thay thế** file trong thư mục `app/`

### 2. Bật Authentication

Firebase Console → Authentication → Sign-in method → Email/Password → Enable

### 3. Mở project bằng Android Studio

- File → Open → chọn thư mục project
- Sync Gradle
- Chạy trên máy thật hoặc emulator (có Google Play Services để lấy vị trí)

### 4. Quyền vị trí

App sẽ xin quyền `ACCESS_FINE_LOCATION` khi mở.

## Build APK trên GitHub

1. Push code lên GitHub repository
2. Vào tab **Actions** → workflow **Build APK** sẽ chạy
3. Tải artifact `app-debug` sau khi build xong

Hoặc build local:

```bash
./gradlew assembleDebug
# APK nằm tại: app/build/outputs/apk/debug/app-debug.apk
```

## Lưu ý

- Project sử dụng **osmdroid** (OpenStreetMap) → hoàn toàn miễn phí, không cần API key bản đồ.
- Firebase config đã được điền sẵn theo thông tin bạn cung cấp.
- Cần thay `google-services.json` bằng file thật từ Firebase Console (Android app).
- minSdk = 24, targetSdk = 34.

## Cấu trúc thư mục chính

```
app/src/main/java/com/example/locationmap/
├── data/FirebaseRepository.kt   # Auth + Realtime DB
├── model/LocationItem.kt
└── ui/
    ├── LoginActivity.kt
    ├── MainActivity.kt
    └── LocationAdapter.kt
```

Chúc bạn code vui vẻ!

## Sửa lỗi build thường gặp

### 1. Missing dependencies (đã fix)
Đã thêm:
- `androidx.coordinatorlayout:coordinatorlayout`
- `androidx.recyclerview:recyclerview`
- `androidx.preference:preference-ktx`

### 2. Deprecated PreferenceManager
Đã đổi sang `androidx.preference.PreferenceManager`

### 3. google-services.json
**Bắt buộc** thay file `app/google-services.json` bằng file thật tải từ Firebase Console (thêm Android app với package `com.example.locationmap`).

Nếu không thay, build có thể báo lỗi "No matching client found for package name".

### 4. Gradle Wrapper
Đã thêm đầy đủ `gradlew` + `gradle-wrapper.jar`. Chạy:

```bash
chmod +x gradlew
./gradlew assembleDebug
```

### 5. Lỗi permissions / location trên máy ảo
Dùng máy thật hoặc emulator có Google Play + bật Location.

### 6. Firebase Rules
Nhớ cập nhật rules cho node `locations` như hướng dẫn ở trên, nếu không sẽ không lưu được dữ liệu.
