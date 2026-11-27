module vn.labaccess.desktop {
    requires javafx.controls;
    requires java.net.http;
    requires java.smartcardio;
    requires java.desktop;
    requires com.fasterxml.jackson.databind;
    requires atlantafx.base;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.feather;
    requires com.oracle.smartcardio.socketprovider;

    exports vn.labaccess.desktop;
}
