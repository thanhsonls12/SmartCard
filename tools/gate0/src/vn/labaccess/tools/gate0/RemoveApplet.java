/*
 * Cong cu BAO TRI Gate 0 — go mot applet da cai khoi Simulator.
 *
 * Chi dung khi can thay ban applet probe trong qua trinh phat trien.
 * KHONG duoc goi tu UI JavaFX va KHONG nam trong luong Connect.
 */

package vn.labaccess.tools.gate0;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;
import java.util.Properties;

import javax.smartcardio.Card;
import javax.smartcardio.CardChannel;
import javax.smartcardio.CardException;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.smartcardio.TerminalFactory;

import com.oracle.javacard.ams.AMService;
import com.oracle.javacard.ams.AMServiceFactory;
import com.oracle.javacard.ams.AMSession;
import com.oracle.javacard.ams.script.APDUScript;
import com.oracle.javacard.ams.script.ScriptFailedException;
import com.oracle.javacard.ams.script.Scriptable;

public class RemoveApplet {

    private static final String ISD_AID = "aid:A000000151000000";
    private static final String PROPS_DEFAULT =
            "D:\\JavaCard\\sdk-26\\simulator\\samples\\client.config.properties";
    private static final String HOST_DEFAULT = "socket:127.0.0.1:9025";

    public static void main(String[] args) {
        int exit = 0;
        Card card = null;
        try {
            String pkgHex = getArg(args, "pkg", null);
            String instHex = getArg(args, "inst", pkgHex + "01");
            String propsPath = getArg(args, "props", PROPS_DEFAULT);
            String host = getArg(args, "host", HOST_DEFAULT);

            System.out.println("Go applet:");
            System.out.println("  Package AID  : " + pkgHex);
            System.out.println("  Instance AID : " + instHex);

            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(propsPath)) {
                props.load(in);
            }

            AMService ams = AMServiceFactory.getInstance("GP2.2");
            ams.setProperties(props);

            /* uninstall applet instance truoc, roi unload package */
            AMSession remove = ams.openSession(ISD_AID)
                    .uninstall("aid:" + instHex)
                    .unload("aid:" + pkgHex)
                    .close();

            String[] hp = host.split(":");
            TerminalFactory tf = TerminalFactory.getInstance(
                    "SocketCardTerminalFactoryType",
                    List.of(new InetSocketAddress(hp[1], Integer.parseInt(hp[2]))),
                    "SocketCardTerminalProvider");
            CardTerminal terminal = tf.terminals().list().get(0);

            if (terminal.waitForCardPresent(10000)) {
                System.out.println("Connection to simulator established: " + terminal.getName());
                card = terminal.connect("*");
                List<ResponseAPDU> responses = new Script().append(remove).execute(card.getBasicChannel());
                System.out.println("Responses count: " + responses.size());
                System.out.println("Ket qua: DA GO");
            } else {
                System.out.println("Connection to simulator failed");
                exit = -1;
            }

        } catch (NoSuchAlgorithmException | NoSuchProviderException | CardException
                 | ScriptFailedException | IOException e) {
            System.out.println("LOI: " + e);
            exit = -1;
        } finally {
            if (card != null) {
                try {
                    card.disconnect(true);
                } catch (CardException ignored) {
                    /* dang don dep */
                }
            }
        }
        System.exit(exit);
    }

    private static String getArg(String[] args, String name, String def) {
        String value = def;
        for (String param : args) {
            if (param.startsWith("-" + name + "=")) {
                value = param.substring(param.indexOf('=') + 1);
            }
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Thieu tham so [" + name + "]");
        }
        return value;
    }

    private static class Script extends APDUScript {
        public Script append(Scriptable<CardChannel, CommandAPDU, ResponseAPDU> other) {
            super.append(other);
            return this;
        }

        List<ResponseAPDU> execute(CardChannel channel) throws ScriptFailedException {
            return super.run(channel, c -> c, r -> false);
        }
    }
}
