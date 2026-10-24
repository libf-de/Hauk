package info.varden.hauk.http;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;

import info.varden.hauk.Constants;
import info.varden.hauk.R;
import info.varden.hauk.struct.Session;
import info.varden.hauk.struct.Version;
import info.varden.hauk.utils.Log;

/**
 * Packet that is sent to update the camera stream links shown on the map. It is sent independently
 * of location updates, so that the links reach the viewers even before the first location is
 * known. Only supported by the Go backend.
 */
public abstract class CameraStreamsPacket extends Packet {
    /**
     * Creates the packet.
     *
     * @param ctx     Android application context.
     * @param session The session for which the camera stream links are updated.
     * @param streams The camera stream links as a JSON array.
     */
    protected CameraStreamsPacket(Context ctx, Session session, String streams) {
        super(ctx, session.getServerURL(), session.getConnectionParameters(), Constants.URL_PATH_POST_CAMERA_STREAMS);
        setParameter(Constants.PACKET_PARAM_SESSION_ID, session.getID());

        if (session.getDerivableE2EKey() == null) {
            setParameter(Constants.PACKET_PARAM_CAMERA_STREAMS, streams);
        } else {
            // We're using end-to-end encryption - generate an IV and encrypt the links.
            try {
                Cipher cipher = Cipher.getInstance(Constants.E2E_TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, session.getDerivableE2EKey().deriveSpec(), new SecureRandom());
                setParameter(Constants.PACKET_PARAM_INIT_VECTOR, Base64.encodeToString(cipher.getIV(), Base64.DEFAULT));
                setParameter(Constants.PACKET_PARAM_CAMERA_STREAMS, Base64.encodeToString(cipher.doFinal(streams.getBytes(StandardCharsets.UTF_8)), Base64.DEFAULT));
            } catch (Exception e) {
                Log.e("Error was thrown when encrypting camera stream links", e); //NON-NLS
            }
        }
    }

    @Override
    protected final void onSuccess(String[] data, Version backendVersion) throws ServerException {
        // Somehow the data array can be empty? Check for this.
        if (data.length < 1) {
            throw new ServerException(getContext(), R.string.err_empty);
        }

        // If the first line of the response is not "OK", an error of some sort has occurred.
        if (!data[0].equals(Constants.PACKET_RESPONSE_OK)) {
            StringBuilder err = new StringBuilder();
            for (String line : data) {
                err.append(line);
                err.append(System.lineSeparator());
            }
            throw new ServerException(err.toString());
        }
    }
}
