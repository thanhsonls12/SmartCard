module vn.labaccess.desktop {
    requires javafx.controls;
    requires java.net.http;
    requires java.smartcardio;
    /* Chuyen anh sang grayscale dung ImageIO/BufferedImage cua java.desktop. */
    requires java.desktop;
    /* Parse phan hoi JSON cua Backend. */
    requires com.fasterxml.jackson.databind;
    /* Theme va control phu cua AtlantaFX; stylesheet rieng cua LabAccess phu len. */
    requires atlantafx.base;
    /* Icon vector: Ikonli va pack Feather. */
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.feather;
    /* Provider socket cua Oracle: bat buoc phai requires de module duoc resolve,
       neu khong TerminalFactory se khong tim thay provider luc chay. */
    requires com.oracle.smartcardio.socketprovider;

    exports vn.labaccess.desktop;
}
