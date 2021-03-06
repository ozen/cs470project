//  ChatClient.java
//
//  Modified 1/30/2000 by Alan Frindell
//  Last modified 2/18/2003 by Ting Zhang 
//  Last modified : Priyank Patel <pkpatel@cs.stanford.edu>
//
//  Chat Client starter application.
package Chat;

import java.awt.*;
import java.awt.event.*;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SealedObject;
import javax.crypto.SecretKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.swing.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.Arrays;

public class ChatClient {

    public static final int SUCCESS = 0;
    public static final int CONNECTION_REFUSED = 1;
    public static final int BAD_HOST = 2;
    public static final int ERROR = 3;
    String _loginName;
    ChatServer _server;
    ChatClientThread _thread;
    ChatLoginPanel _loginPanel;
    ChatRoomPanel _chatPanel;
    ObjectOutputStream out = null;
    ObjectInputStream in = null;
    CardLayout _layout;
    JFrame _appFrame;

    Socket _socket = null;
    PublicKey CAPublicKey;
    Certificate clientCert;
    PrivateKey RSAPrivateKey;
    SecretKey roomKey;
    boolean exit = false;

    //  ChatClient Constructor
    //
    //  empty, as you can see.
    public ChatClient() {

        _loginName = null;
        _server = null;

        try {
            initComponents();
        } catch (Exception e) {
            System.out.println("ChatClient error: " + e.getMessage());
            e.printStackTrace();
        }

        _layout.show(_appFrame.getContentPane(), "Login");

    }

    public void run() {
        _appFrame.pack();
        _appFrame.setVisible(true);

    }

    //  main
    //
    //  Construct the app inside a frame, in the center of the screen
    public static void main(String[] args) {
        
        ChatClient app = new ChatClient();

        app.run();
    }

    //  initComponents
    //
    //  Component initialization
    private void initComponents() throws Exception {

        _appFrame = new JFrame("CS470 Chat");
        _layout = new CardLayout();
        _appFrame.getContentPane().setLayout(_layout);
        _loginPanel = new ChatLoginPanel(this);
        _chatPanel = new ChatRoomPanel(this);
        _appFrame.getContentPane().add(_loginPanel, "Login");
        _appFrame.getContentPane().add(_chatPanel, "ChatRoom");
        _appFrame.addWindowListener(new WindowAdapter() {

            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
    }

    //  quit
    //
    //  Called when the application is about to quit.
    public void quit() {

        try {
            _socket.shutdownOutput();
            _socket.close();
            exit = true;
            _thread.join();

        } catch (Exception err) {
            System.out.println("ChatClient error: " + err.getMessage());
            err.printStackTrace();
        }

        System.exit(0);
    }

    //
    //  connect
    //
    //  Called from the login panel when the user clicks the "connect"
    //  button.
    public int connect(String loginName, String room,
            String keyStoreName, char[] keyStorePassword,
            String caHost, int caPort,
            String serverHost, int serverPort) {

        try {

            _loginName = loginName;

            // load client keystore
            FileInputStream inputStream = new FileInputStream(new File(getClass().getResource(keyStoreName).getPath()));
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(inputStream, keyStorePassword);
            CAPublicKey = keyStore.getCertificate("ca").getPublicKey();

            if(!keyStore.isCertificateEntry(_loginName)) {
                RSAPrivateKey = (PrivateKey) keyStore.getKey("client", keyStorePassword);
                clientCert = keyStore.getCertificate("client");
                PublicKey RSAPublicKey = clientCert.getPublicKey();

                // establish connection to CA
                Socket ca_socket = new Socket(caHost, caPort);
                OutputStream outStream = ca_socket.getOutputStream();
                InputStream inStream = ca_socket.getInputStream();
                out = new ObjectOutputStream(outStream);
                in = new ObjectInputStream(inStream);

                out.writeObject(new PackageRegister(_loginName, RSAPublicKey));

                try {
                    clientCert = (X509Certificate) in.readObject();
                    clientCert.verify(CAPublicKey);
                } catch (ClassCastException caste) {
                    return CONNECTION_REFUSED;
                } catch (Exception e) {
                    return BAD_HOST;
                }

                out.close();
                in.close();

                // save the certificate
                keyStore.setCertificateEntry(_loginName, clientCert);
                FileOutputStream keyStoreStream = new FileOutputStream(new File(keyStoreName));
                keyStore.store(keyStoreStream, keyStorePassword);
            }
            else {
                RSAPrivateKey = (PrivateKey) keyStore.getKey("client", keyStorePassword);
                clientCert = keyStore.getCertificate(_loginName);
            }

            // establish connection to chat server
            _socket = new Socket(serverHost, serverPort);
            OutputStream outStream = _socket.getOutputStream();
            InputStream inStream = _socket.getInputStream();
            out = new ObjectOutputStream(outStream);
            in = new ObjectInputStream(inStream);

            System.out.println("Connection established to the chat server");

            // receive server key exchange
            PackageServerExchange serverExchange = (PackageServerExchange) in.readObject();
            DHPublicKeySpec DHServerPublicKeyPart = serverExchange.getDHServerPart();
            DHPublicKey DHServerPublicKey = serverExchange.getDHServerKey();
            Certificate serverCert = serverExchange.getServerCertificate();

            // verify
            serverCert.verify(CAPublicKey);
            if(!serverExchange.verify()) {
                return BAD_HOST;
            }

            System.out.println("Server key exchange verified");

            // generate DH key part
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("DiffieHellman");
            DHParameterSpec param = new DHParameterSpec(DHServerPublicKeyPart.getP(), DHServerPublicKeyPart.getG());
            kpg.initialize(param);
            KeyPair kp = kpg.generateKeyPair();
            KeyFactory kfactory = KeyFactory.getInstance("DiffieHellman");
            DHPublicKeySpec DHClientPublicKeySpec = (DHPublicKeySpec) kfactory.getKeySpec(kp.getPublic(), DHPublicKeySpec.class);
            PublicKey DHClientPublicKey = kp.getPublic();

            // create and send client key exchange
            PackageClientExchange clientExchange = new PackageClientExchange(clientCert, serverCert, kp.getPublic(), RSAPrivateKey, serverExchange);
            out.writeObject(clientExchange);

            System.out.println("Sent client key exchange message. Calculating shared secret.");

            // calculate shared secret
            KeyAgreement ka = KeyAgreement.getInstance("DH");
            ka.init(kp.getPrivate());
            ka.doPhase(DHServerPublicKey, true);
            SecretKey secretKey = ka.generateSecret("AES");

            System.out.println("Key exchange completed: " + Arrays.toString(secretKey.getEncoded()));

            // initialize symmetric ciphers
            Cipher enCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            Cipher deCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            enCipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec("yigitozenemredogru".getBytes(), 0, 16));
            deCipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec("yigitozenemredogru".getBytes(), 0, 16));

            // send join room request
            out.writeObject(new SealedObject(room, enCipher));

            System.out.println("Join room request sent.");

            // receive join room reply
            SealedObject joinRoomReply = (SealedObject) in.readObject();
            roomKey = (SecretKey) joinRoomReply.getObject(deCipher);

            if(roomKey == null) {
                System.out.println("Joining room failed.");
                return CONNECTION_REFUSED;
            }

            System.out.println("Joined room.");

            _layout.show(_appFrame.getContentPane(), "ChatRoom");

            _thread = new ChatClientThread(this);
            _thread.start();
            return SUCCESS;

        } catch (UnknownHostException e) {

            System.err.println("Don't know about the serverHost: " + serverHost);
            System.exit(1);

        } catch (IOException e) {

            System.err.println("Couldn't get I/O for "
                    + "the connection to the serverHost: " + serverHost);
            System.out.println("ChatClient error: " + e.getMessage());
            e.printStackTrace();

            System.exit(1);

        } catch (AccessControlException e) {

            return BAD_HOST;

        } catch (Exception e) {

            System.out.println("ChatClient err: " + e.getMessage());
            e.printStackTrace();
        }

        return ERROR;

    }

    //  sendMessage
    //
    //  Called from the ChatPanel when the user types a carrige return.
    public void sendMessage(String msg) {
        try {
            msg = _loginName + "> " + msg;
            PackageChatMessage pkg = new PackageChatMessage(msg, roomKey);
            out.writeObject(pkg);
        } catch (Exception e) {
            System.out.println("ChatClient err: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Socket getSocket() {
        return _socket;
    }

    public JTextArea getOutputArea() {
        return _chatPanel.getOutputArea();
    }
}
