package com.glitch9.moonlight;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.view.SurfaceHolder;

import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Reusable, UI-free Moonlight engine used by the Glitch9 Flutter host. */
public final class Glitch9MoonlightEngine {
    public interface Listener {
        void onStreaming();
        void onFailure(String code);
        void onRumble(int controller, int low, int high);
    }

    private volatile NvConnection connection;
    private volatile MediaCodecDecoderRenderer decoder;

    public boolean isIdentityValid(String host, int port, String clientId, byte[] encoded) {
        try {
            Identity identity = Identity.decode(encoded);
            NvHTTP http = http(host, port, clientId, identity);
            return http.getPairState() == PairingManager.PairState.PAIRED;
        } catch (Exception ignored) {
            return false;
        }
    }

    public byte[] pair(String host, int port, String clientId, String pin, Runnable pairingRequestReady) throws Exception {
        Identity identity = Identity.create();
        NvHTTP http = http(host, port, clientId, identity);
        PairingManager manager = http.getPairingManager();
        String serverInfo = http.getServerInfo(true);
        AtomicReference<Throwable> relayFailure = new AtomicReference<>();
        Thread relay = new Thread(() -> {
            try {
                // PairingManager opens its long-lived getservercert request below. Give that
                // request time to reach the desktop before asking the backend to submit the PIN.
                Thread.sleep(500);
                pairingRequestReady.run();
            } catch (Throwable error) {
                relayFailure.set(error);
            }
        }, "Glitch9 pairing relay");
        relay.start();
        PairingManager.PairState result = manager.pair(serverInfo, pin);
        relay.join();
        if (relayFailure.get() != null) throw new IOException("pairing_rejected", relayFailure.get());
        if (result != PairingManager.PairState.PAIRED || manager.getPairedCert() == null) {
            throw new IOException(result == PairingManager.PairState.PIN_WRONG ? "pairing_rejected" : "pairing_required");
        }
        identity.serverCertificate = manager.getPairedCert();
        return identity.encode();
    }

    public void start(Activity activity, SurfaceHolder surface, String host, int port, String application,
                      String clientId, byte[] encodedIdentity, Listener listener) throws Exception {
        Identity identity = Identity.decode(encodedIdentity);
        NvHTTP http = http(host, port, clientId, identity);
        NvApp app = http.getAppByName(application);
        if (app == null) throw new IOException("desktop_unavailable");

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(activity);
        GlPreferences glPrefs = GlPreferences.readPreferences(activity);
        MediaCodecHelper.initialize(activity, glPrefs.glRenderer);
        ConnectivityManager connectivity = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        MediaCodecDecoderRenderer video = new MediaCodecDecoderRenderer(activity, prefs,
                error -> listener.onFailure("decoder_unavailable"), 0,
                connectivity != null && connectivity.isActiveNetworkMetered(), false,
                glPrefs.glRenderer, text -> { });
        if (!video.isAvcSupported()) throw new IOException("decoder_unavailable");
        video.setRenderTarget(surface);

        int formats = MoonBridge.VIDEO_FORMAT_H264;
        if (video.isHevcSupported()) formats |= MoonBridge.VIDEO_FORMAT_H265;
        if (video.isAv1Supported()) formats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefs.width, prefs.height)
                .setLaunchRefreshRate(prefs.fps).setRefreshRate(prefs.fps)
                .setApp(app).setBitrate(prefs.bitrate).setEnableSops(prefs.enableSops)
                .enableLocalAudioPlayback(prefs.playHostAudio).setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                .setSupportedVideoFormats(formats).setAttachedGamepadMask(1)
                .setAudioConfiguration(prefs.audioConfiguration)
                .setColorSpace(video.getPreferredColorSpace()).setColorRange(video.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(true).build();

        NvConnection nativeConnection = new NvConnection(activity.getApplicationContext(),
                new ComputerDetails.AddressTuple(host, port), 0, clientId, config, identity,
                identity.serverCertificate);
        connection = nativeConnection;
        decoder = video;
        nativeConnection.start(new AndroidAudioRenderer(activity, prefs.enableAudioFx), video,
                new NvConnectionListener() {
                    @Override public void stageStarting(String stage) { }
                    @Override public void stageComplete(String stage) { }
                    @Override public void stageFailed(String stage, int portFlags, int errorCode) { listener.onFailure("desktop_unavailable"); }
                    @Override public void connectionStarted() { listener.onStreaming(); }
                    @Override public void connectionTerminated(int errorCode) { listener.onFailure("stream_interrupted"); }
                    @Override public void connectionStatusUpdate(int connectionStatus) { }
                    @Override public void displayMessage(String message) { }
                    @Override public void displayTransientMessage(String message) { }
                    @Override public void rumble(short controller, short low, short high) { listener.onRumble(controller, low & 0xffff, high & 0xffff); }
                    @Override public void rumbleTriggers(short controller, short left, short right) { }
                    @Override public void setHdrMode(boolean enabled, byte[] metadata) { video.setHdrMode(enabled, metadata); }
                    @Override public void setMotionEventState(short controller, byte type, short rate) { }
                    @Override public void setControllerLED(short controller, byte r, byte g, byte b) { }
                });
    }

    public void pause() { MediaCodecDecoderRenderer value = decoder; if (value != null) value.notifyVideoBackground(); }
    public void resume() { MediaCodecDecoderRenderer value = decoder; if (value != null) value.notifyVideoForeground(); }
    public void stop() {
        NvConnection value = connection; connection = null; decoder = null;
        if (value != null) value.stop();
    }
    public void sendPointer(short x, short y) { NvConnection value = connection; if (value != null) value.sendMouseMove(x, y); }
    public void sendPointerButton(byte button, boolean pressed) {
        NvConnection value = connection; if (value == null) return;
        if (pressed) value.sendMouseButtonDown(button); else value.sendMouseButtonUp(button);
    }
    public void sendController(int buttons, int leftTrigger, int rightTrigger, short lx, short ly, short rx, short ry) {
        NvConnection value = connection;
        if (value != null) value.sendControllerInput((short) 0, (short) 1, buttons,
                (byte) leftTrigger, (byte) rightTrigger, lx, ly, rx, ry);
    }

    private static NvHTTP http(String host, int port, String clientId, Identity identity) throws IOException {
        return new NvHTTP(new ComputerDetails.AddressTuple(host, port), 0, clientId,
                identity.serverCertificate, identity);
    }

    private static final class Identity implements LimelightCryptoProvider {
        private static final int MAGIC = 0x47394944;
        private static final BouncyCastleProvider BC = new BouncyCastleProvider();
        private final X509Certificate clientCertificate;
        private final PrivateKey privateKey;
        private final byte[] pemCertificate;
        private X509Certificate serverCertificate;

        private Identity(X509Certificate client, PrivateKey key, X509Certificate server) throws IOException {
            clientCertificate = client; privateKey = key; serverCertificate = server;
            StringWriter writer = new StringWriter();
            try (JcaPEMWriter pem = new JcaPEMWriter(writer)) { pem.writeObject(client); }
            pemCertificate = writer.toString().replace("\r", "").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }

        static Identity create() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BC);
            generator.initialize(2048); KeyPair pair = generator.generateKeyPair();
            Date now = new Date(); Calendar expiry = Calendar.getInstance(); expiry.setTime(now); expiry.add(Calendar.YEAR, 20);
            byte[] serialBytes = new byte[8]; new SecureRandom().nextBytes(serialBytes);
            X500Name name = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "NVIDIA GameStream Client").build();
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(name, new BigInteger(serialBytes).abs(),
                    now, expiry.getTime(), Locale.ENGLISH, name, SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(pair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer));
            return new Identity(certificate, pair.getPrivate(), null);
        }

        byte[] encode() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); write(out, clientCertificate.getEncoded()); write(out, privateKey.getEncoded());
                write(out, serverCertificate.getEncoded());
            }
            return bytes.toByteArray();
        }
        static Identity decode(byte[] encoded) throws Exception {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
                if (in.readInt() != MAGIC) throw new IOException("Invalid identity");
                CertificateFactory certificates = CertificateFactory.getInstance("X.509", BC);
                X509Certificate client = (X509Certificate) certificates.generateCertificate(new ByteArrayInputStream(read(in)));
                PrivateKey key = KeyFactory.getInstance("RSA", BC).generatePrivate(new PKCS8EncodedKeySpec(read(in)));
                X509Certificate server = (X509Certificate) certificates.generateCertificate(new ByteArrayInputStream(read(in)));
                if (in.available() != 0) throw new IOException("Trailing identity data");
                return new Identity(client, key, server);
            }
        }
        private static void write(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
        private static byte[] read(DataInputStream in) throws IOException {
            int length = in.readInt(); if (length <= 0 || length > 64 * 1024) throw new IOException("Invalid identity field");
            byte[] value = new byte[length]; in.readFully(value); return value;
        }
        @Override public X509Certificate getClientCertificate() { return clientCertificate; }
        @Override public PrivateKey getClientPrivateKey() { return privateKey; }
        @Override public byte[] getPemEncodedClientCertificate() { return pemCertificate.clone(); }
        @Override public String encodeBase64String(byte[] data) { return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP); }
    }
}
