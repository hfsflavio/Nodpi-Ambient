package com.nodpi.ambient;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Android 4.4 has TLS 1.2 turned off by default for client connections and an
 * outdated list of root certificates. This helper enables TLS 1.2 and adds a
 * bundled list of modern root certificates (res/raw/cacert.pem) on top of the
 * certificates already present in the system.
 */
final class TlsHelper {

    private static SSLSocketFactory cached;

    private TlsHelper() {}

    static synchronized SSLSocketFactory factory(Context ctx) throws Exception {
        if (cached != null) return cached;

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int n = 0;

        // 1) system certificates
        try {
            KeyStore sys = KeyStore.getInstance("AndroidCAStore");
            sys.load(null, null);
            Enumeration<String> aliases = sys.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                try {
                    Certificate c = sys.getCertificate(a);
                    if (c != null) ks.setCertificateEntry("sys" + (n++), c);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 2) bundled certificates
        for (Certificate c : readBundled(ctx)) {
            try {
                ks.setCertificateEntry("app" + (n++), c);
            } catch (Throwable ignored) {}
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sc;
        try {
            sc = SSLContext.getInstance("TLSv1.2");
        } catch (Throwable t) {
            sc = SSLContext.getInstance("TLS");
        }
        sc.init(null, tmf.getTrustManagers(), null);

        cached = new Tls12Factory(sc.getSocketFactory());
        return cached;
    }

    private static List<Certificate> readBundled(Context ctx) {
        List<Certificate> out = new ArrayList<Certificate>();
        BufferedReader br = null;
        try {
            InputStream raw = ctx.getResources().openRawResource(R.raw.cacert);
            br = new BufferedReader(new InputStreamReader(raw, "UTF-8"));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            StringBuilder sb = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("-----BEGIN CERTIFICATE-----")) {
                    sb = new StringBuilder();
                }
                if (sb != null) {
                    sb.append(line).append('\n');
                    if (line.startsWith("-----END CERTIFICATE-----")) {
                        try {
                            Certificate c = cf.generateCertificate(
                                    new ByteArrayInputStream(sb.toString().getBytes("UTF-8")));
                            out.add(c);
                        } catch (Throwable ignored) {}
                        sb = null;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (IOException ignored) {}
        }
        return out;
    }

    /** Wraps a socket factory so every socket has TLS 1.2 enabled. */
    private static final class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Tls12Factory(SSLSocketFactory delegate) { d = delegate; }

        private Socket enable(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> want = new ArrayList<String>();
                String[] supported = ss.getSupportedProtocols();
                String[] order = {"TLSv1.2", "TLSv1.1", "TLSv1"};
                for (String p : order) {
                    for (String q : supported) {
                        if (q.equals(p)) { want.add(p); break; }
                    }
                }
                if (!want.isEmpty()) {
                    ss.setEnabledProtocols(want.toArray(new String[want.size()]));
                }
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enable(d.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return enable(d.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress lh, int lp) throws IOException {
            return enable(d.createSocket(host, port, lh, lp));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return enable(d.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress lh, int lp) throws IOException {
            return enable(d.createSocket(a, port, lh, lp));
        }
    }
}
