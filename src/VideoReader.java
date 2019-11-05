import static java.util.logging.Level.WARNING;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Klasse zum Einlesen eines MJPEG-Videos.
 *
 * @author Elisa Zschorlich (s70342)
 */
public class VideoReader {

  private FileInputStream fileInputStream;
  private boolean isClosed = true;

  /**
   * Initialisiert den Video-Reader inkl. File Input Stream.
   *
   * @param videoFilePath Pfad für das Video
   * @throws FileNotFoundException falls das Video nicht gefunden werden kann
   */
  public VideoReader(final String videoFilePath) throws FileNotFoundException {
    // Öffnet den Input-Stream
    this.fileInputStream =
        new FileInputStream(videoFilePath) {

          /** Schließe den Input-Stream und setze das entsprechende Flag. */
          @Override
          public void close() throws IOException {
            isClosed = true;
            super.close();
          }

          /** Prüft vor dem Lesen eines weiteren Bytes, ob der Input-Stream noch geöffnet ist. */
          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            return isClosed ? -1 : super.read(b, off, len);
          }
        };
    this.isClosed = false;
  }

  /** Schließt den Input-Stream, wenn dieser initialisiert und nicht geschlossen ist. */
  public void close() {
    if (fileInputStream != null && !isClosed) {
      try {
        fileInputStream.close();
      } catch (IOException e) {
        Logger.getGlobal()
            .log(WARNING, "FileInputStream des VideoReader konnte nicht geschlossen werden.");
      }
    }
  }

  /**
   * Liest das nächste JPEG-Bild aus der MJPEG-Videodatei ein.
   *
   * @return das eingelesene JPEG-Bild als Byte Array, falls dieses vollständig eingelesen werden
   *     konnte, sonst NULL.
   * @throws IOException IOException
   */
  public byte[] readNextImage() throws IOException {
    // Prüft das der Input-Stream initialisiert und nicht geschlossen ist.
    if (fileInputStream != null && !isClosed) {
      // Puffer für die Bytes eines Bildes, inklusive SOI- und EOI-Marker.
      final List<Byte> imageBytes = new ArrayList<>();
      // Puffer für das nächste eingelesene Byte
      final byte[] nxtByte = new byte[1];
      // Zähler für enthaltene SOI-Marker, für die noch kein EOI gelesen wurde.
      int soiCount = 0;
      // Gibt an, ob ein Markeranfang (0xFF) gelesen wurde.
      Byte markerStartBytes = null;

      // Lese solange Bytes, bis das Dateiende erreicht ist.
      while (fileInputStream.read(nxtByte, 0, nxtByte.length) != -1) {
        final byte currentByte = nxtByte[0];

        if (currentByte == JpegFrame.MARKER_TAG_START) {
          // Marker startet (0xFF)
          markerStartBytes = currentByte;
        } else if (markerStartBytes != null) {
          if (currentByte == JpegFrame.SOI_MARKER[1]) {
            // SOI Marker (0xD8)
            imageBytes.add(markerStartBytes);
            soiCount++;
          } else if (currentByte == JpegFrame.EOI_MARKER[1]) {
            // EOI Marker (0xD9)
            soiCount--;

            if (soiCount == 0) {
              // Ende des Bildes erreicht.
              imageBytes.add(currentByte);
              break;
            }
          }
          markerStartBytes = null;
        }

        // Kein Marker aber innerhalb des SOI
        if (soiCount > 0) {
          // Nur Bytes innerhalb von SOI und EOI gehören zum Bild.
          imageBytes.add(currentByte);
        }
      }

      // Input-Stream wurde geschlossen oder es konnten keine Bytes gelesen werden.
      if (this.isClosed || imageBytes.isEmpty()) {
        return null;
      }

      // Transformiere Liste in ein Byte Array
      final byte[] result = new byte[imageBytes.size()];
      for (int i = 0; i < result.length; i++) {
        result[i] = imageBytes.get(i);
      }

      // JPEG muss mit SOI beginnen und mit EOI enden
      if (Arrays.equals(
          JpegFrame.EOI_MARKER, Arrays.copyOfRange(result, result.length - 2, result.length))
          && Arrays.equals(JpegFrame.SOI_MARKER, Arrays.copyOf(result, 2))) {
        return result;
      }
      return null;
    } else {
      return null;
    }
  }
}
