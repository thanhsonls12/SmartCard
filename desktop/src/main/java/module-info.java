module vn.labaccess.desktop {
    requires javafx.controls;
    requires java.smartcardio;
    /* Chuyen anh sang grayscale dung ImageIO/BufferedImage cua java.desktop. */
    requires java.desktop;
    /* Provider socket cua Oracle: bat buoc phai requires de module duoc resolve,
       neu khong TerminalFactory se khong tim thay provider luc chay. */
    requires com.oracle.smartcardio.socketprovider;

    exports vn.labaccess.desktop;
}
