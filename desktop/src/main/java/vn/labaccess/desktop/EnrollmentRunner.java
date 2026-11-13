package vn.labaccess.desktop;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Runner có kiểu cho luồng nhiều bước: kết quả enrollment không đi qua {@code CardOperationResult} để khỏi phải parse ngược chuỗi log.
 *
 * <p>Vẫn chạy trên worker hiện có của {@code App}; interface này chỉ mô tả hợp đồng, không tạo executor riêng.
 */
interface EnrollmentRunner {

    <T> void run(String label, Callable<T> operation, Consumer<T> onSuccess, Consumer<Exception> onFailure);
}
