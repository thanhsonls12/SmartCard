/*
 * Công cụ Gate 0 — cài HelloWorld và GIỮ Applet trên Simulator.
 * Bản sao rút gọn từ client Oracle (samples/HelloWorld/client), không sửa SDK gốc.
 */
module vn.labaccess.tools.gate {
    exports vn.labaccess.tools.gate0;

    requires com.oracle.javacard.amservice;
    requires java.smartcardio;
}
