# LabAccess — SmartCard

Ứng dụng Java Card quản lý lượt tự học trong phòng lab. Gồm Applet, ứng dụng JavaFX cho Staff/Kiosk và Backend NestJS/PostgreSQL.

## Yêu cầu

Windows, JDK 25, Oracle Java Card SDK 26.0, Maven, Node.js 24 và PostgreSQL. Cấu hình `JAVA_HOME`, `JC_HOME_TOOLS`, `JC_HOME_SIMULATOR`; tạo `backend/.env` từ `backend/.env.example` và không commit bí mật.

## Khởi động

Khởi động PostgreSQL, rồi cài đặt và triển khai Backend lần đầu:

```powershell
docker compose up -d --wait
cd backend
npm ci
npm run db:deploy
npm run build
cd ..
```

Chỉ chạy migration trên đúng database đã xác nhận; sao lưu trước nếu database có dữ liệu. Tạo tài khoản Staff bằng `create-staff.cmd` (không có tài khoản mặc định), rồi mở hệ thống:

```powershell
.\create-staff.cmd
.\run-labaccess.cmd
```

Launcher mở Virtual Reader, Backend, Staff Console và Kiosk. Chọn thẻ ở Reader trước khi kết nối ứng dụng. Hướng dẫn cài Applet lên thẻ trắng: `install-applet.cmd`.

## Build, kiểm tra và dừng

Khi các tiến trình LabAccess đã dừng:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/labaccess.ps1 -Action build
powershell -NoProfile -ExecutionPolicy Bypass -File tools/quality/run-lint.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-launcher.ps1
cd backend
npm test
npm run lint
```

Dừng hệ thống bằng `stop-labaccess.cmd`. Build CAP không tự cài Applet lên thẻ. Không cài đè thẻ đã phát hành, xóa journal hoặc retry APDU khi giao dịch chưa được đối soát.
