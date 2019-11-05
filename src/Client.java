/* ------------------
Client
usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]
---------------------- */

import java.io.*;
import java.net.*;
import java.text.DecimalFormat;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;

public class Client {

  // GUI
  // ----
  JFrame f = new JFrame("Client");
  JButton setupButton = new JButton("Setup");
  JButton playButton = new JButton("Play");
  JButton pauseButton = new JButton("Pause");
  JButton tearButton = new JButton("Teardown");
  JButton optionsButton = new JButton("Options");
  JButton describeButton = new JButton("Describe");
  JPanel mainPanel = new JPanel(); // Container
  JPanel buttonPanel = new JPanel(); // Buttons
  JPanel statsPanel = new JPanel();
  JPanel inputPanel = new JPanel();
  JLabel iconLabel = new JLabel(); // Image
  JLabel statusLabel = new JLabel("Status: "); // Statistics
  JLabel pufferLabel = new JLabel("Puffer: "); // Statistics
  JLabel statsLabel = new JLabel("Statistics: "); // Statistics
  JLabel fecLabel = new JLabel("FEC: "); // Statistics
  ImageIcon icon;
  JTextField textField = new JTextField("mystream", 30);

  // RTP variables:
  // ----------------
  DatagramSocket RTPsocket; // socket to be used to send and receive UDP packets
  DatagramSocket FECsocket; // socket to be used to send and receive UDP packets for FEC
  FecHandler fec;
  static int RTP_RCV_PORT = 25000; // port where the client will receive the RTP packets
  // static int FEC_RCV_PORT = 25002; // port where the client will receive the RTP packets

  static final int MAX_FRAME_SIZE = 65536;
  static final int RCV_RATE = 2; // interval for receiving loop

  Timer timer; // timer used to receive data from the UDP socket
  Timer timerPlay; // timer used to display the frames at correct frame rate

  // RTSP variables
  // ----------------
  // rtsp states
  static final int INIT = 0;
  static final int READY = 1;
  static final int PLAYING = 2;
  static int state; // RTSP state == INIT or READY or PLAYING
  Socket RTSPsocket; // socket used to send/receive RTSP messages
  // input and output stream filters
  static BufferedReader RTSPBufferedReader;
  static BufferedWriter RTSPBufferedWriter;
  static String rtspServer;
  static int rtspPort;
  static String rtspUrl;
  static String VideoFileName; // video file to request to the server
  static int RTSPSeqNb = 0; // Sequence number of RTSP messages within the session
  String RTSPid = "0"; // ID of the RTSP session (given by the RTSP Server)

  static final String CRLF = "\r\n";

  // Video constants:
  // ------------------
  // static int MJPEG_TYPE = 26; // RTP payload type for MJPEG video
  static final int FRAME_RATE = 40;

  public Client() {
    // build GUI
    // Frame
    f.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            System.exit(0);
          }
        });

    // Buttons
    buttonPanel.setLayout(new GridLayout(1, 0));
    buttonPanel.add(setupButton);
    buttonPanel.add(playButton);
    buttonPanel.add(pauseButton);
    buttonPanel.add(tearButton);
    buttonPanel.add(optionsButton);
    buttonPanel.add(describeButton);
    setupButton.addActionListener(new setupButtonListener());
    playButton.addActionListener(new playButtonListener());
    pauseButton.addActionListener(new pauseButtonListener());
    tearButton.addActionListener(new tearButtonListener());
    optionsButton.addActionListener(new optionsButtonListener());
    describeButton.addActionListener(new describeButtonListener());

    // Image display label
    iconLabel.setIcon(null);

    // Text
    statsPanel.setLayout(new GridLayout(4, 0));
    statsPanel.add(statusLabel);
    statsPanel.add(pufferLabel);
    statsPanel.add(statsLabel);
    statsPanel.add(fecLabel);

    inputPanel.setLayout(new BorderLayout());
    inputPanel.add(textField, BorderLayout.SOUTH);

    // frame layout
    mainPanel.setLayout(null);
    mainPanel.add(iconLabel);
    mainPanel.add(buttonPanel);
    mainPanel.add(statsPanel);
    mainPanel.add(inputPanel);
    iconLabel.setBounds(0, 0, 640, 480);
    buttonPanel.setBounds(0, 480, 640, 50);
    statsPanel.setBounds(0, 550, 640, 150);
    inputPanel.setBounds(0, 700, 640, 100);

    f.getContentPane().add(mainPanel, BorderLayout.CENTER);
    f.setSize(new Dimension(640, 800));
    f.setVisible(true);

    // init timer
    // --------------------------
    timer = new Timer(RCV_RATE, new timerListener());
    timer.setInitialDelay(0);
    timer.setCoalesce(true); // combines events

    timerPlay = new Timer(FRAME_RATE, new timerPlayListener());
    timerPlay.setInitialDelay(2000);
    timerPlay.setCoalesce(true); // combines events
  }

  /**
   * Initialization of the GUI
   *
   * @param argv host port file
   * @throws Exception stacktrace at console
   */
  public static void main(String[] argv) throws Exception {
    // Create a Client object
    Client theClient = new Client();

    // get server RTSP port and IP address from the command line
    // ------------------
    int RTSP_server_port = Integer.parseInt(argv[1]);
    String ServerHost = argv[0];
    InetAddress ServerIPAddr = InetAddress.getByName(ServerHost);
    rtspPort = RTSP_server_port;
    rtspServer = ServerHost;
    rtspUrl = "rtsp://" + ServerHost + ":" + RTSP_server_port + "/";

    // get video filename to request:
    VideoFileName = argv[2];
    theClient.textField.setText(VideoFileName);

    // Establish a TCP connection with the server to exchange RTSP messages
    // ------------------
    theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);

    // Set input and output stream filters:
    RTSPBufferedReader =
        new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
    RTSPBufferedWriter =
        new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

    // init RTSP state:
    state = INIT;
    // init RTSP sequence number
    RTSPSeqNb = 1;

  }

  // TASK Complete all button handlers
  /** Handler for the Setup button */
  class setupButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {

      System.out.println("Setup Button pressed !");

      if (state == INIT) {
        // Init non-blocking RTPsocket that will be used to receive data
        try {
          //TASK construct a new DatagramSocket to receive server RTP packets on port RTP_RCV_PORT
          RTPsocket = new DatagramSocket();

          // for now FEC packets are received via RTP-Port, so keep comment below
          // FECsocket = new DatagramSocket(FEC_RCV_PORT);

          //TASK set Timeout value of the socket to 1 ms
          // ....

          // Init the FEC-handler
          fec = new FecHandler();
          timerPlay.setInitialDelay(2000);

        } catch (SocketException se) {
          System.out.println("Socket exception: " + se);
          System.exit(0);
        }

        VideoFileName = textField.getText();

        // Send SETUP message to the server
        send_RTSP_request("SETUP");

        // Wait for the response
        System.out.println("Wait for response...");
        if (parse_server_response() != 200) System.out.println("Invalid Server Response");
        else {
          //TASK change RTSP state and print new state to console and statusLabel
          // state = ....
          // statusLabel
          // System.out.println("New RTSP state: ");
        }
      } // else if state != INIT then do nothing
    }
  }

  /** Handler for Play button */
  class playButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {

      System.out.println("Play Button pressed !");
      if (state == READY) {
        //TASK increase RTSP sequence number
        // .....

        // Send PLAY message to the server
        send_RTSP_request("PLAY");

        // Wait for the response
        if (parse_server_response() != 200) System.out.println("Invalid Server Response");
        else {
          //TASK change RTSP state and print out new state to console an statusLabel
          // state = ....

          // start the timer
          timer.start();
          timerPlay.start();
        }
      } // else if state != READY then do nothing
    }
  }

  /** Handler for Pause button */
  class pauseButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {

      System.out.println("Pause Button pressed !");
      if (state == PLAYING) {
        //TASK increase RTSP sequence number
        // ....

        // Send PAUSE message to the server
        send_RTSP_request("PAUSE");

        // Wait for the response
        if (parse_server_response() != 200) System.out.println("Invalid Server Response");
        else {
          //TASK change RTSP state and print out new state to console and statusLabel
          // state = ....

          // stop the timer
          timer.stop();
          timerPlay.stop();
          timerPlay.setInitialDelay(0);
        }
      }
      // else if state != PLAYING then do nothing
    }
  }

  /** Handler for Teardown button */
  class tearButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {

      System.out.println("Teardown Button pressed !");
      //TASK increase RTSP sequence number

      // Send TEARDOWN message to the server
      send_RTSP_request("TEARDOWN");

      // Wait for the response
      if (parse_server_response() != 200) System.out.println("Invalid Server Response");
      else {
        //TASK change RTSP state and print out new state to console and statusLabel
        // state = ....

        // stop the timer
        timer.stop();
        timerPlay.stop();

        RTPsocket.close();
        // exit
        // System.exit(0);
      }
    }
  }

  /** Handler for Options button */
  class optionsButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {

      System.out.println("Options Button pressed !");
      RTSPSeqNb++;
      send_RTSP_request("OPTIONS");

      if (parse_server_response() != 200) System.out.println("Invalid Server Response");
    }
  }

  /** Handler for Describe button */
  class describeButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {

      System.out.println("Describe Button pressed !");
      RTSPSeqNb++;
      send_RTSP_request("DESCRIBE");

      if (parse_server_response() != 200) System.out.println("Invalid Server Response");
    }
  }

  /** Handler for the timer event fetches the RTP-packets and displays the images */
  class timerListener implements ActionListener {
    byte[] buf = new byte[MAX_FRAME_SIZE]; // allocate memory to receive UDP data from server

    public void actionPerformed(ActionEvent e) {
      DatagramPacket rcvDp = new DatagramPacket(buf, buf.length); // RTP needs UDP socket
      try {
        RTPsocket.receive(rcvDp); // receive the DP from the socket:
        RTPpacket rtp = new RTPpacket(rcvDp.getData(), rcvDp.getLength()); // for the rcvDp

        // print important header fields of the RTP packet received:
        System.out.println(
            "Got RTP packet with SeqNum # "
                + rtp.getsequencenumber()
                + " TimeStamp "
                + (0xFFFFFFFFL & rtp.gettimestamp())  // cast to long
                + " ms, of type "
                + rtp.getpayloadtype());

        rtp.printheader(); // print header bitstream:
        fec.rcvRtpPacket(rtp); // stores the received RTP packet in jitter buffer

      } catch (InterruptedIOException iioe) {
        // System.out.println("Nothing to read");
      } catch (IOException ioe) {
        System.out.println("Exception caught: " + ioe);
      }
    }
  }

  /** Displays one Frame if available */
  class timerPlayListener implements ActionListener {
    public void actionPerformed(ActionEvent e) {
      DecimalFormat df = new DecimalFormat("###.###");

      try {
        // get a list of rtps from jitter buffer
        byte[] payload = JpegFrame.combineToOneImage( fec.getNextRtpList() );

        // buffer empty -> finish
        if (payload == null) {
          statusLabel.setText("End of Stream");
          return;
        }

        // get an Image object from the payload bitstream
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        Image image = toolkit.createImage(payload, 0, payload.length);

        // display the image as an ImageIcon object
        icon = new ImageIcon(image);
        iconLabel.setIcon(icon);

      } catch (RuntimeException rex) {
        rex.printStackTrace();
      }

      //TASK complete the statistics
      pufferLabel.setText(
          "Puffer: "
              + ""  //
              + " aktuelle Nr. / Summe empf.: "
              + " / "
              + "");
      statsLabel.setText(
          "<html>Abspielzähler / verlorene Bilder: "
              + ""
              + " / "
              + ""
              + "<p/>"
              + "</html>");
      fecLabel.setText(
          "FEC: korrigiert / nicht korrigiert: "
              + ""
              + " / "
              + ""
              + "  Ratio: "
              + "");
    }
  }

  /**
   * Parse Server Response Handles Session, Public, Content-Base - attribute
   *
   * @return the reply code
   */
  private int parse_server_response() {
    int reply_code = 0;
    int cl = 0;

    System.out.println("Waiting for Server response...");
    try {
      // parse the whole reply
      ArrayList<String> respLines = new ArrayList<>();

      String line;
      do {
        line = RTSPBufferedReader.readLine();
        System.out.println(line);
        if (!line.equals("")) respLines.add(line);
      } while (!line.equals(""));
      ListIterator<String> respIter = respLines.listIterator(0);

      StringTokenizer tokens = new StringTokenizer( respIter.next() );
      tokens.nextToken(); // skip over the RTSP version
      reply_code = Integer.parseInt(tokens.nextToken());

      while ( respIter.hasNext() ) {
        line = respIter.next();
        StringTokenizer headerField = new StringTokenizer( line);

        switch (headerField.nextToken().toLowerCase()) {
          case "cseq:":
            System.out.println("SNr: " + headerField.nextToken());
            break;

          case "session:":
            if (state == INIT) {
              RTSPid = headerField.nextToken().split(";")[0];  // cat semicolon
            }
            break;

          case "content-length:":
            cl = Integer.parseInt(headerField.nextToken());
            break;

          case "public:":
            System.out.println("Options-Response: " + headerField.nextToken());
            break;

          case "content-type:":
            String ct = headerField.nextToken();
            System.out.println("Content-Type: " + ct);
            break;

          case "transport:":
            System.out.println();
            break;

          default:
            System.out.println("Unknown: " + line );
        }
      }
      System.out.println("*** End of Response Header ***\n----------------");

      // Describe will send content
      if (cl > 0) parse_server_data(cl);

    } catch (Exception ex) {
      ex.printStackTrace();
      System.out.println("Exception caught: " + ex);
      System.exit(0);
    }
    return (reply_code);
  }


  private void parse_server_data(int cl) throws Exception {
    char[] cbuf = new char[cl];
    System.out.println("*** Parsing Response Data...");
    int data = RTSPBufferedReader.read(cbuf, 0, cl);
    System.out.println("Data: " + data);
    System.out.print(new String(cbuf) );
    System.out.println("Finished Content Reading...");
  }



  /**
   * Send the RTSP Request
   *
   * @param request_type the RTSP-Request, e.g. SETUP or PLAY
   */
  private void send_RTSP_request(String request_type) {
    try {
      // defines the URL
      String rtsp = rtspUrl + VideoFileName;
      if (request_type.equals("SETUP")) rtsp = rtsp + "/trackID=0";

      String rtspReq = "";
      //TASK Complete the RTSP request method line
      // rtspReq = ....

      //TASK write the CSeq line:
      // rtspReq += ....

      // check if request_type is equal to "SETUP" and in this case write the Transport: line
      // advertising to the server the port used to receive the RTP packets RTP_RCV_PORT
      // otherwise, write the Session line from the RTSPid field
      if (request_type.equals("SETUP")) {
        //TASK Complete the Transport Attribute
        rtspReq += "Transport:";
      }

      // SessionIS if available
      if (!RTSPid.equals("0")) {
        rtspReq += "Session: " + RTSPid + CRLF;
      }

      System.out.println(rtspReq);  // console debug
      // Use the RTSPBufferedWriter to write to the RTSP socket
      RTSPBufferedWriter.write(rtspReq + CRLF);
      RTSPBufferedWriter.flush();
      System.out.println("*** RTSP-Request " + request_type + " send ***");

    } catch (Exception ex) {
      ex.printStackTrace();
      System.out.println("Exception caught: " + ex);
      System.exit(0);
    }
  }
}
