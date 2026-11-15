# QA — đóng JavaFX trước khi gửi receipt HTTP

Harness chạy JavaFX thật, Oracle Simulator, Backend NestJS và PostgreSQL local để kiểm trường hợp START đã commit, receipt đã được xác minh, journal vẫn PENDING nhưng ứng dụng đóng trước `POST /receipts/verify` của START. Đây là công cụ live QA riêng, không thuộc test mặc định.

## Chạy

Cần Node.js, JDK 25, Maven trên PATH, Java Card Simulator 26, CAP sản phẩm đã build, dependency Backend đã cài và PostgreSQL trong cấu hình local `backend/.env` đang hoạt động. Harness tạo cardId/studentId mới, không xóa hoặc sửa dữ liệu của thẻ đã có.

````powershell
$env:JAVA_HOME = 'đường-dẫn-JDK-25'
$env:JC_HOME_SIMULATOR = 'đường-dẫn-Java-Card-Simulator-26'
node tools/qa-before-http/run.mjs
````

Có thể truyền `--java-home=...`, `--simulator=...` và `--evidence=logs/tên-thư-mục-mới` thay cho các giá trị mặc định. Mỗi lượt phải dùng thư mục evidence mới. Lệnh mặc định build desktop/Backend và compile hai class QA; không sửa source sản phẩm hoặc SDK.

## Cách tạo và kiểm mốc dừng

- `BeforeHttpScenario.java` được thêm vào module bằng `--patch-module` như một class QA mới; không thay class sản phẩm. Driver gọi các nút hiện có và xác nhận hộp thoại thật trên FX thread qua `Platform.runLater`, ngoài callback animation. Reflection chỉ lấy hai component UI để dùng API điền dữ liệu và bấm nút mà demo hiện có đang dùng.
- `BeforeHttpGate.java` kết nối JDWP loopback tới JVM thử. Breakpoint ở đầu `App.saveReceiptToLedger`; GRANT baseline đi qua, START được giữ tại ranh giới trước `BackendClient.verifyReceipt`.
- Gate kiểm stack `runStateTransaction`, trạng thái gateway đã xác minh chữ ký, verify ECDSA độc lập với public key đọc từ gateway, và kiểm âm bằng payload bị sửa. Gate so toàn bộ trường nghiệp vụ với journal PENDING, kiểm đúng đường journal, rồi ghi `QA_BEFORE_HTTP_GATED` qua chính logger của app. Luồng card-io giữ tại breakpoint tới khi JVM ngắt; không resume để gửi HTTP.
- Proxy HTTP ghi metadata request ngay khi request tới, chuyển tiếp bình thường, không sửa/cắt response và không ghi header/body chứa thông tin phiên. Checkpoint sau GRANT tách request baseline khỏi request của START. Relay APDU chạy passthrough và dùng cơ chế che dữ liệu nhạy cảm đã có.
- Driver phát close-request của cửa sổ. Vì card-io đang bị giữ, app thoát bằng watchdog 5 giây; đây là cưỡng chế thoát theo lifecycle sản phẩm, không phải bằng chứng disconnect sạch.
- JVM thứ hai dùng cùng journal, cùng Simulator/CAP, không bật gate. Driver ghi trạng thái khóa khi khởi động, kết nối/nhận diện và xác nhận hộp thoại đối soát. Không có giao dịch nghiệp vụ nào sau đối soát.

## Điều kiện PASS

`result.json` chỉ xuất hiện sau khi toàn bộ assertion đạt: trước và sau đóng app, Backend vẫn counter=1/balance=5 với một GRANT baseline, không có POST verify mới sau checkpoint; phục hồi có đúng một POST START trả `201 recorded`, một dòng START mới, journal bị xóa, card/ledger cùng counter=2/balance=4/visitId. Relay có tổng một GRANT, một START, không END và không APDU đổi state trong phần phục hồi. Chữ ký/payload lưu trong DB khớp receipt tại gate.

Evidence gồm log app của hai JVM, log gate, proxy JSONL, relay JSONL, SQL tại từng mốc, bản journal PENDING, receipt/public key đã xác minh, manifest hash và ảnh scene JavaFX. PIN thử sinh ngẫu nhiên, chỉ truyền qua stdin; harness quét ASCII/hex của PIN trước khi ghi PASS. Không lưu PIN, private key hoặc EEPROM image.

Harness dừng các tiến trình do nó tạo và xóa scratch sau thành công; PostgreSQL và các tiến trình có sẵn được giữ nguyên. Dữ liệu QA trong DB được giữ để đối chiếu. Nếu ca thất bại khi còn journal, scratch được giữ làm dấu vết, nhưng Simulator đã dừng nên journal đó không chứng minh khả năng phục hồi; không dùng ca thất bại để nghiệm thu hoặc ghép mốc với một thẻ mới.
