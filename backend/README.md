# LabAccess — Backend

NestJS + Prisma/PostgreSQL làm sổ đối chiếu mỏng cho Applet và JavaFX. Thẻ vẫn là nguồn sự thật về balance/counter (INV-1); backend chỉ lưu và xác minh.

## Yêu cầu

- Node.js 24 (NestJS 12 và Prisma 7 yêu cầu Node ≥ 20.19 / 22.12 / 24)
- Docker Desktop đang chạy
- Cổng `127.0.0.1:15434` còn trống cho PostgreSQL local

## Cấu hình môi trường

`backend/.env` là file local, đã bị `.gitignore` loại bỏ và **không được commit**. Tạo từ mẫu rồi thay mật khẩu bằng giá trị ngẫu nhiên của riêng máy:

```powershell
Copy-Item backend\.env.example backend\.env
# sửa POSTGRES_PASSWORD và mật khẩu trong DATABASE_URL cho khớp nhau
```

`backend/.env.example` chỉ chứa placeholder, không chứa secret thật.

| Biến | Ý nghĩa |
|---|---|
| `POSTGRES_DB` | Tên database, mặc định `labaccess` |
| `POSTGRES_USER` | User PostgreSQL, mặc định `labaccess` |
| `POSTGRES_PASSWORD` | Mật khẩu local, chỉ nằm trong `.env` |
| `DATABASE_URL` | Chuỗi kết nối Prisma, trỏ `127.0.0.1:15434` |

## Chạy PostgreSQL

Compose ở repository root chỉ có một service PostgreSQL (image cố định `postgres:17.6-alpine`), dùng named volume `labaccess-postgres-data` và chỉ bind cổng ra loopback:

```powershell
docker compose -f compose.yml up -d --wait
docker compose -f compose.yml ps
```

Container phải ở trạng thái `healthy` trước khi chạy backend.

## Chạy backend trên host

NestJS chạy trực tiếp trên máy, không nằm trong Compose:

```powershell
cd backend
npm install
npm run db:validate
npm run build
npm run start
```

Ứng dụng lắng nghe `127.0.0.1:3100` (đổi bằng biến `PORT`).

## Kiểm tra kết nối database

`GET /health/database` chạy một truy vấn thật qua Prisma rồi trả tên database hiện tại:

```powershell
curl.exe http://127.0.0.1:3100/health/database
```

Kết quả đạt:

```json
{"status":"ok","provider":"postgresql","database":"labaccess"}
```

## Kiểm tra chất lượng

```powershell
npm run lint
npm run test
npm run test:e2e
```

`test:e2e` dựng `AppModule` thật nên cần PostgreSQL đang chạy và `.env` đã cấu hình.

## Prisma

- Schema: `prisma/schema.prisma` (generator `prisma-client`, output `src/generated/prisma`)
- Cấu hình CLI: `prisma.config.ts` — tên chuẩn, Prisma 7 tự nạp; đọc `DATABASE_URL` qua `env()` nên lệnh `migrate` dùng đúng chuỗi kết nối trong `.env`
- Client sinh tự động bởi `npm run db:generate` và được gọi lại trong `build`/`start`

`src/generated/prisma` là mã sinh tự động, không commit.

### Bảng và ràng buộc (BE-02, BE-03)

| Bảng | Nội dung |
|---|---|
| `students` | Enrollment: `cardId` 8B duy nhất, `studentId`, `publicKey` 65B, `enrolledAt` |
| `card_ledgers` | State sổ đối chiếu theo thẻ, một dòng mỗi `cardId`: `lastCounter`, `lastBalance`, `activeVisitId`, `updatedAt` — dòng này là thứ BE-08 khóa để tuần tự hóa |
| `transactions` | Receipt: `cardId`, `counterAfter`, `operation`, `delta`, `balanceAfter`, `visitId`, `payload` canonical, `signature` DER nguyên byte, `recordedAt` |

Ràng buộc PostgreSQL (không chỉ kiểm ở code):

- `UNIQUE (cardId, counterAfter)` — chặn ghi trùng receipt.
- `FOREIGN KEY (cardId) → students(cardId)` với `ON DELETE RESTRICT` — receipt phải tham chiếu thẻ đã enroll.
- Kiểu lưu là `BIGINT` cho mọi trường số, vì `INTEGER` có dấu không chứa hết uint32; miền giá trị do `CHECK` quyết định.

| Trường | Miền áp dụng | Lý do |
|---|---|---|
| `lastCounter` | `0..4294967295` | uint32, 0 là baseline trước giao dịch đầu |
| `counterAfter` | `1..4294967295` | receipt luôn sau ít nhất một giao dịch; 0 chỉ là baseline |
| `lastBalance`, `balanceAfter` | `0..9999` | balance là uint16 giới hạn 0..9999 |
| `delta` (tổng) | `-1..9999` | int16, cận dưới chỉ là `-1` của START |
| `delta` theo operation | GRANT `1..9999`, START `-1`, END `0` | hai chiều operation↔delta |
| `visitId` | `NULL` khi GRANT; `START`/`END` phải đúng 16B **và khác toàn zero** | GRANT dùng sentinel toàn 0 trong payload đã ký, cột parse để `NULL` |
| `activeVisitId` | `NULL` = không có ca; có giá trị phải đúng 16B và khác toàn zero | tránh ca "mở" với ID rỗng |
| `cardId` | đúng 8B | cardId 8B theo hợp đồng APDU |

Ghi chú cho BE-07: khi insert GRANT, `payload` canonical vẫn giữ 16 byte visitId toàn zero đã ký, cột `visitId` để `NULL`, và **không** xóa `card_ledgers.activeVisitId` của ca đang mở.

### Enrollment (BE-04, BE-06)

Hai bảng phục vụ enrollment: `admin_sessions` (phiên quản trị local do Backend tự phát, TTL 30 phút, có `revokedAt`) và `enrollment_challenges` (nonce 32B dùng một lần, TTL 5 phút, gắn `adminSessionId`, `cardId`, `studentId`, `publicKey`, `publicKeyHash`, `usedAt`).

| Endpoint | Vào | Ra |
|---|---|---|
| `POST /admin/sessions` | không body | `201 {sessionId, expiresAt}` |
| `POST /enrollments/challenges` | header `x-admin-session`, body `{cardId hex16, studentId, publicKey hex130}` | `201 {challengeId, nonce hex64, expiresAt}` |
| `POST /enrollments/complete` | header `x-admin-session`, body `{challengeId, signature hex}` | `201 {cardId, studentId, enrolledAt}` |

`studentId` gồm 8–10 chữ cái ASCII hoặc chữ số, nhận chữ thường/in hoa và lưu thành chữ hoa. Không nhận khoảng trắng hay ký tự đặc biệt; không tự sửa dữ liệu đã đăng ký trước đó.

Mã lỗi theo [quy ước Q-45](docs/dev/quy-uoc-code.md): `401` phiên thiếu/không phải uuid/không tồn tại/hết hạn/đã thu hồi, hoặc challenge thuộc phiên khác; `400` sai schema (hex sai độ dài, `studentId` sai quy tắc trên, điểm không hợp lệ); `404` không thấy `challengeId`; `409` challenge đã dùng hoặc hết hạn, `cardId` đã enroll; `422` chữ ký không verify.

Chữ ký và thông điệp: xem [bảng APDU §1.13](docs/design/apdu-commands.md). Backend dựng lại đúng 95 byte từ **dữ liệu đã lưu của challenge**, không bao giờ từ `cardId`/`publicKey`/`nonce` client gửi lên; public key được kiểm là điểm P-256 hợp lệ (nằm trên đường cong, đúng tiền tố `04`, đúng 65 byte) ngay khi tạo challenge.

`complete` chạy trong một `$transaction`: tiêu thụ challenge bằng `updateMany` có điều kiện (`usedAt IS NULL AND expiresAt > now()`, phải trả `count = 1`), rồi insert `students` và baseline `card_ledgers` (`lastCounter` 0, `lastBalance` 0, `activeVisitId` NULL), rồi commit. Insert lỗi thì việc tiêu thụ cũng rollback. Hai request đồng thời: một `201`, một `409`, đúng một dòng `students` và một dòng `card_ledgers`.

`x-admin-session` là bí mật phiên do Backend phát qua `POST /admin/sessions`; Backend kiểm lại trong DB mỗi lần dùng, không tin một id tuỳ ý trong request.

### Migration

```powershell
cd backend
npm run db:migrate     # tạo/áp dụng migration khi phát triển
npm run db:deploy      # áp dụng migration có sẵn (dùng cho DB test)
npm run db:status      # xem migration đã áp dụng
```

Migration hiện có, áp theo thứ tự:

1. `prisma/migrations/20261110144438_init_ledger_schema` — bảng, unique index, khóa ngoại, `CHECK` ban đầu.
2. `prisma/migrations/20261110145601_tighten_numeric_domains` — siết miền số về hợp đồng `0..9999` và siết visit ID.
3. `prisma/migrations/20261110151239_enrollment_challenges` — `admin_sessions` và `enrollment_challenges` kèm `CHECK` độ dài nonce/cardId/publicKey/publicKeyHash/studentId.

Không sửa DB bằng tay; mọi thay đổi schema đi qua migration mới.

### Database test riêng

Nghiệm thu schema trên database sạch, không đụng dữ liệu dev:

```powershell
docker exec smartcard-postgres-1 psql -U labaccess -d postgres -c "DROP DATABASE IF EXISTS labaccess_test" -c "CREATE DATABASE labaccess_test"
$env:DATABASE_URL = "postgresql://labaccess:<mật-khẩu>@127.0.0.1:15434/labaccess_test?schema=public"
npm run db:deploy
Remove-Item Env:\DATABASE_URL
```

`DATABASE_URL` đặt trong biến môi trường sẽ thắng giá trị trong `.env`, nên cùng một cấu hình `prisma.config.ts` phục vụ cả DB dev và DB test.

## Lịch sử giao dịch

`GET /cards/:cardId/transactions` trả `cardId` và mảng `transactions` theo `counterAfter` tăng dần. Mỗi dòng gồm `id`, `counterAfter`, `operation`, `delta`, `balanceAfter`, `verifyStatus` và `recordedAt` (chuỗi ISO 8601). `recordedAt` lấy từ thời điểm Backend lưu receipt, không nằm trong payload do thẻ ký và có thể muộn hơn giao dịch trên thẻ khi đối soát. Không có migration mới.

Desktop kiểm đối chiếu trên toàn bộ lịch sử trước khi hiển thị theo counter giảm dần và lọc ngày/loại giao dịch. Ngày lọc dùng múi giờ máy đang chạy; chi tiết giao dịch ghi rõ múi giờ. Desktop và Backend cần cùng bản hỗ trợ `recordedAt`; phản hồi thiếu/sai thời gian bị từ chối.

## Phạm vi

Backend chỉ phục vụ demo local có kiểm soát, không mở API ra Internet và không thêm JWT/RBAC, CRUD phòng hay dashboard (BE-11, BE-12). Đã có enrollment (BE-04–BE-06), ghi receipt (BE-07/BE-08) và `GET /cards/:cardId/transactions` (BE-09).
