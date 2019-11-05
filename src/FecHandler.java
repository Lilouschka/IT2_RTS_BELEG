import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/*
   Information according to RFC 5109
   http://apidocs.jitsi.org/libjitsi/
*/

public class FecHandler {
  RTPpacket rtp;
  FECpacket fec;

  // Receiver
  HashMap<Integer, RTPpacket> rtpStack = new HashMap<>();
  HashMap<Integer, FECpacket> fecStack = new HashMap<>();
  HashMap<Integer, Integer> fecNr = new HashMap<>();
  HashMap<Integer, List<Integer>> fecList = new HashMap<>();

  int playCounter = 0; // SNr of RTP-packet to play next, initialized with first received packet
  int rtpCounter  = 0; // depreciated

  // *** RTP-Header ************************
  static final int MJPEG = 26;
  int FEC_PT = 127; // Type for FEC
  int fecSeqNr; // Sender: increased by one, starting from 0
  int lastReceivedSeqNr;  // Receiver: last received media packet

  // *** FEC Parameters -> Sender ************
  final static int maxGroupSize = 48;
  int fecGroupSize; // FEC group size
  int fecGroupCounter;

  // Error Concealment
  byte[] lastPayload = {1};

  // *** Statistics for media packets ********
  int nrReceived;  // count only media at receiver
  int nrLost;      // media missing, play loop
  int nrCorrected; // play loop
  int nrNotCorrected; // play loop


  /**
   * Constructor for Sender
   */
  public FecHandler(int size) {
    fecGroupSize = size;
  }

  public FecHandler() {}


  // *************** Sender SET *******************************************************************

  /**
   * *** Sender ***
   * Saves the involved RTP packets to build the FEC packet
   *
   * @param rtp RTPpacket
   */
  public void setRtp(RTPpacket rtp) {
    // init new FEC packet if necessary
    if (fec == null) {
      fec = new FECpacket(FEC_PT, fecSeqNr, rtp.gettimestamp(), fecGroupSize, rtp.getsequencenumber());
      fec.setUlpLevelHeader(0  , 0, fecGroupSize);
    }

    fecGroupCounter++; // count the packets in the group
    fec.TimeStamp = rtp.gettimestamp(); // adjust the time stamp to the last packet in the group
    fec.addRtp(rtp);
  }


  /**
   * @return True,
   * if all RTP-packets of the group are handled
   */
  public boolean isReady() {
    return (fecGroupCounter == fecGroupSize);
  }

  /**
   * *** Sender ***
   * Builds the FEC-RTP-Packet and resets the FEC-group
   *
   * @return Bitstream of FEC-Packet including RTP-Header
   */
  public byte[] getPacket() {
    fec.printHeaders();
    // Adjust and reset all involved variables
    fecSeqNr++;
    fecGroupCounter = 0;
    byte[] buf = fec.getpacket();
    fec = null; // reset fec
    return buf;
  }


  /** Reset of fec group and variables */
  private void clearSendGroup() {
    // TODO
  }


  /**
   * *** Sender ***
   * Posibility to set the group at run time
   * @param size FEC Group
   */
  public void setFecGroupSize(int size) {
    fecGroupSize = size;
  }



  // *************** Receiver PUT *****************************************************************

  /**
   * Handles and store a received media packet
   * @param rtp the received RTP
   */
  public void rcvRtpPacket(RTPpacket rtp) {
    int seqNr = rtp.getsequencenumber();
    // if first packet set playcounter
    if (rtpStack.size() == 0 ) playCounter = seqNr;
    // separate Media an FEC
    if (rtp.getpayloadtype() == MJPEG) {
      nrReceived++; // count only media
      rtpStack.put(seqNr, rtp);
      lastReceivedSeqNr = seqNr;
      System.out.println("FEC: set media nr: " + seqNr);
    } else {
      rcvFecPacket(rtp);
    }
  }

  /**
   * Handles and store a recieved FEC packet
   * @param rtp the received FEC-RTP
   */
  private void rcvFecPacket(RTPpacket rtp) {
    // build fec from rtp
    fec = new FECpacket(rtp.getpacket(), rtp.getpacket().length );
    // stores fec
    int seqNrFec = fec.getsequencenumber();
    fecSeqNr = seqNrFec; // for deletion of fec storage
    fecStack.put(seqNrFec, fec);

    // get RTP List
    ArrayList<Integer> list = fec.getRtpList();
    fec.printHeaders();
    System.out.println("FEC: set list: " + seqNrFec+  " "  + list.toString());

    // set list to get fec packet nr
    list.forEach((E) -> fecNr.put(E, seqNrFec));
    list.forEach((E) -> fecList.put(E, list));
  }


  // *************** Receiver GET *****************************************************************

  /**
   * Delivers next Frame, depreciated, see getNextRtpList
   *
   * @return JPEG
   */
  public byte[] getNextFrame() {
    playCounter++;
    if (playCounter >  lastReceivedSeqNr) {
      return null; // Jitter buffer is empty -> finish
    }
    RTPpacket rtp = getNextRtp();

    clearStack(playCounter);  // reduce the stack

    if (rtp == null) {
      return lastPayload;  // error concealment
    }
    else {
      lastPayload = rtp.getpayload();
      return rtp.getpayload();
    }
  }

  /**
   * Delivers next RTP packet
   *
   * @return RTPpacket
   */
  private RTPpacket getNextRtp() {
    return getRtp( playCounter % 0x10000 );
  }

  /**
   * Delivers a RTP packet
   *
   * @return RTPpacket
   */
  private RTPpacket getRtp(int snr) {
    RTPpacket rtp = rtpStack.get(snr);
    System.out.println("FEC: get nr: " + snr);

    // check if correction is possible
    if (rtp == null ) {
      System.out.println("FEC: Media lost: " + snr);
      nrLost++;
      if (checkCorrection(snr) ) {
        nrCorrected++;
        System.out.println("---> FEC: correctable: " + snr);
        return correctRtp(snr);
      } else {
        nrNotCorrected++;
        System.err.println("---> FEC: not correctable: " + snr);
        return null;
      }
    }
    return rtp;
  }



  /**
   * Delivers a set of RTPs with the same Time stamp
   * the set is in the correct order concerning the sequence number
   * @return List
   */
  public ArrayList<RTPpacket> getNextRtpList() {
    System.out.println("--------> Get RTPs  " );
    playCounter++;
    rtpCounter++;
    ArrayList<RTPpacket> list = new ArrayList<>();
    //TODO check for lost rtp
    RTPpacket rtp = getNextRtp();
    int ts = rtp.gettimestamp();

    do{
      list.add(rtp);
      playCounter++;
      rtp = getNextRtp();
    } while (ts == rtp.gettimestamp() );

    playCounter--;
    System.out.println("--------> Get RTPs with TS: " + ts);
    return list;
  }



  /**
   * Checks if the RTP packet is reparable
   * @param nr Sequence Nr.
   * @return true if possible
   */
  private boolean checkCorrection(int nr) {
    //TASK complete this method!
    return false;
  }

  /**
   * Build a RTP packet from FEC and group
   * @param nr Sequence Nr.
   * @return RTP packet
   */
  private RTPpacket correctRtp(int nr) {
    //TASK complete this method!
    return fec.getLostRtp(nr);
  }


  /**
   * It is necessary to clear all data structures
   * @param nr Media Sequence Nr.
   */
  private void clearStack(int nr) {
    //TASK complete this method!
  }

  // *************** Receiver Sta. ****************************************************************

  /**
   * Latest (highest) received sequence number
   * @return Nr.
   */
  public int getSeqNr() {
    return lastReceivedSeqNr;
  }


  /**
   * Received media packets for input buffer
   * @return Amount
   */
  public int getNrReceived() {
    return nrReceived;
  }

  public int getPlayCounter() {
    return playCounter;
  }

  public int getNrLost() { return nrLost; }

  public int getNrCorrected() {
    return nrCorrected;
  }

  public int getNrNotCorrected() {
    return nrNotCorrected;
  }
}