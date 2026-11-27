# LabAccess — SmartCard

Ứng dụng Java Card quản lý lượt sử dụng trong phòng lab. Gồm Applet, ứng dụng JavaFX cho Staff/Kiosk và Backend NestJS/PostgreSQL.

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

Chỉ chạy migration trên đúng database đã xác nhận; sao lưu trước nếu database có dữ liệu. Tạo tài khoản Staff bằng `cmd/create-staff.cmd` (không có tài khoản mặc định), rồi mở hệ thống:

```powershell
.\cmd\create-staff.cmd
.\cmd\run-labaccess.cmd
```

Launcher mở Virtual Reader, Backend, Staff Console và Kiosk. Chọn thẻ ở Reader trước khi kết nối ứng dụng. Hướng dẫn cài Applet lên thẻ trắng: `cmd/install-applet.cmd`.

## Hồ sơ trên thẻ và tương thích

Hồ sơ schema v2 lưu mã sinh viên, họ tên, lớp và ngành trong bản ghi AES-GCM trên thẻ; chữ ký hồ sơ bao phủ toàn bộ các trường này. Luồng phát hành và đổi thông tin hiện tại yêu cầu lớp hợp lệ; ngành được suy ra từ mã sinh viên. Hồ sơ v1 vẫn đọc được và được nâng lên v2 qua thao tác cập nhật có xác thực, bổ sung lớp rõ ràng. Đổi riêng họ tên giữ nguyên lớp/ngành đã lưu.

Ứng dụng cần Applet hỗ trợ schema v2 để ghi định dạng mới; build CAP không cập nhật Applet đang cài. Với thẻ đã phát hành, kiểm tra tương thích và thực hiện quy trình chuyển đổi đã xác nhận trước khi nâng cấp; giữ nguyên dữ liệu thẻ. Yêu cầu đổi thông tin `PENDING` cũ thiếu lớp phải được thay bằng yêu cầu mới có lớp và ký challenge mới rồi mới hoàn tất. Chi tiết giao thức, giới hạn tương thích và evidence Simulator tại [APDU §1.17](docs/design/apdu-commands.md#117-hồ-sơ-mã-hóa-v2--tương-thích-và-nghiệm-thu); chưa dùng kết quả Simulator để xác nhận thẻ vật lý.

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

Sau khi build CAP và các biến thể test bằng `applet/scripts/build.ps1`, chạy từ thư mục gốc repo:

```powershell
# Bộ tập trung schema v2 và xác minh chữ ký hồ sơ bằng Node.js
powershell -NoProfile -ExecutionPolicy Bypass -File applet/scripts/test-profile-v2.ps1
# Bộ PERSONALIZE/PIN mặc định và xác minh receipt bằng Node.js
powershell -NoProfile -ExecutionPolicy Bypass -File applet/scripts/test-profile-v2.ps1 -FullSuite
# Fault sau ghi ciphertext: đổi tên, đổi định danh và nâng v1 lên v2
powershell -NoProfile -ExecutionPolicy Bypass -File applet/scripts/test-profile-rollback.ps1
```

Mỗi lệnh mở Simulator riêng rồi dừng Simulator đó; hai lệnh đầu ghi `applet/build/profile-v2-*/manifest.json`, lệnh rollback ghi `applet/build/profile-rollback-*/manifest.json`. Chạy các bộ phù hợp để lưu riêng evidence schema v2, bộ mặc định và rollback; chúng không thay thế các suite APDU error, PH/ảnh hay GUI/Backend end-to-end. Các lệnh này cài/xóa **test AID** trên Simulator riêng, không cập nhật thẻ đã phát hành.

Dừng hệ thống bằng `cmd/stop-labaccess.cmd`. Build CAP không tự cài Applet lên thẻ. Không cài đè thẻ đã phát hành, xóa journal hoặc retry APDU khi giao dịch chưa được đối soát.
