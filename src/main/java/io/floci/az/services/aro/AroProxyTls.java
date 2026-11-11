package io.floci.az.services.aro;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.StringReader;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The TLS pieces of an ARO cluster's API proxy: MicroShift's own PEM material read into Java keys, a server key
 * manager that picks MicroShift's localhost or external serving certificate by SNI, and client-certificate handling
 * like kube-apiserver's: certificates are requested, any is accepted during the handshake, and each request verifies
 * the chain against MicroShift's client CA bundle before using its subject as the user.
 */
final class AroProxyTls {

    static final String LOCALHOST_ALIAS = "localhost";
    static final String EXTERNAL_ALIAS = "external";

    private AroProxyTls() {
    }

    /** A certificate chain and its private key. */
    record KeyMaterial(X509Certificate[] chain, PrivateKey key) {

        static KeyMaterial read(String chainPem, String keyPem) {
            List<X509Certificate> chain = certificates(chainPem);
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("no certificate in the chain PEM");
            }
            return new KeyMaterial(chain.toArray(X509Certificate[]::new), privateKey(keyPem));
        }

        /** A key manager presenting this chain, for a TLS client. */
        X509KeyManager keyManager() {
            try {
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                store.setKeyEntry("key", key, new char[0], chain);
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, new char[0]);
                return (X509KeyManager) factory.getKeyManagers()[0];
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException("cannot build a key manager", e);
            }
        }
    }

    static List<X509Certificate> certificates(String pem) {
        List<X509Certificate> certificates = new ArrayList<>();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            for (Object parsed = parser.readObject(); parsed != null; parsed = parser.readObject()) {
                if (parsed instanceof X509CertificateHolder holder) {
                    certificates.add(converter.getCertificate(holder));
                }
            }
        } catch (IOException | CertificateException e) {
            throw new IllegalArgumentException("cannot read certificates: " + e.getMessage(), e);
        }
        return certificates;
    }

    static PrivateKey privateKey(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            return switch (parser.readObject()) {
                case PEMKeyPair pair -> converter.getKeyPair(pair).getPrivate();
                case PrivateKeyInfo info -> converter.getPrivateKey(info);
                case null, default -> throw new IllegalArgumentException("no private key in the PEM");
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the private key: " + e.getMessage(), e);
        }
    }

    /**
     * Serves MicroShift's localhost serving certificate to {@code localhost}, {@code 127.0.0.1} and clients that send
     * no SNI, and its external serving certificate (which carries {@code api.<domain>}, the container name and the
     * configured extra names) to every other name.
     */
    static final class SniKeyManager extends X509ExtendedKeyManager {

        private final KeyMaterial localhost;
        private final KeyMaterial external;

        SniKeyManager(KeyMaterial localhost, KeyMaterial external) {
            this.localhost = localhost;
            this.external = external;
        }

        static String aliasFor(String serverName) {
            String name = serverName == null ? "" : serverName.toLowerCase(Locale.ROOT);
            return name.isEmpty() || "localhost".equals(name) || "127.0.0.1".equals(name)
                    ? LOCALHOST_ALIAS : EXTERNAL_ALIAS;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            String serverName = "";
            if (engine != null && engine.getHandshakeSession() instanceof ExtendedSSLSession session) {
                for (SNIServerName name : session.getRequestedServerNames()) {
                    if (name instanceof SNIHostName host) {
                        serverName = host.getAsciiName();
                        break;
                    }
                }
            }
            String alias = aliasFor(serverName);
            return material(alias).key().getAlgorithm().equals(keyType) ? alias : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return localhost.key().getAlgorithm().equals(keyType) ? LOCALHOST_ALIAS : null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[]{LOCALHOST_ALIAS, EXTERNAL_ALIAS};
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            KeyMaterial material = material(alias);
            return material == null ? null : material.chain().clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            KeyMaterial material = material(alias);
            return material == null ? null : material.key();
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        private KeyMaterial material(String alias) {
            if (LOCALHOST_ALIAS.equals(alias)) {
                return localhost;
            }
            return EXTERNAL_ALIAS.equals(alias) ? external : null;
        }
    }

    /**
     * Accepts whatever client certificate is offered during the handshake, as kube-apiserver does with
     * {@code RequestClientCert}, and names the client CA bundle as acceptable issuers so clients pick their kubeconfig
     * certificate. {@link ClientCertificates} verifies the chain for each request.
     */
    static final class RequestedClientCertificates extends X509ExtendedTrustManager {

        private final X509Certificate[] acceptedIssuers;

        RequestedClientCertificates(List<X509Certificate> clientCa) {
            this.acceptedIssuers = clientCa.toArray(X509Certificate[]::new);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Verified per request by ClientCertificates; an untrusted certificate gets 401, not a failed handshake.
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // As above.
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // As above.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("server certificates are not checked here");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("server certificates are not checked here");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("server certificates are not checked here");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return acceptedIssuers.clone();
        }
    }

    /** The user a verified client certificate names: CN as the user, each O as a group, as kube-apiserver maps it. */
    record CertificateUser(String name, List<String> groups) {
    }

    /** Verifies client certificate chains against MicroShift's client CA bundle, with the TLS client-auth checks. */
    static final class ClientCertificates {

        private final X509TrustManager trust;

        ClientCertificates(List<X509Certificate> clientCa) {
            try {
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                int i = 0;
                for (X509Certificate ca : clientCa) {
                    store.setCertificateEntry("ca-" + i++, ca);
                }
                TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
                factory.init(store);
                X509TrustManager found = null;
                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509TrustManager x509) {
                        found = x509;
                    }
                }
                this.trust = found;
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException("cannot load the client CA bundle", e);
            }
        }

        /** The user the chain names when it chains to the client CA, else empty. */
        Optional<CertificateUser> verify(X509Certificate[] chain) {
            if (chain == null || chain.length == 0 || trust == null) {
                return Optional.empty();
            }
            try {
                trust.checkClientTrusted(chain, chain[0].getPublicKey().getAlgorithm());
            } catch (CertificateException e) {
                return Optional.empty();
            }
            String name = null;
            List<String> groups = new ArrayList<>();
            try {
                for (Rdn rdn : new LdapName(chain[0].getSubjectX500Principal().getName()).getRdns()) {
                    if ("CN".equalsIgnoreCase(rdn.getType()) && name == null) {
                        name = String.valueOf(rdn.getValue());
                    } else if ("O".equalsIgnoreCase(rdn.getType())) {
                        groups.add(String.valueOf(rdn.getValue()));
                    }
                }
            } catch (InvalidNameException e) {
                return Optional.empty();
            }
            // LdapName lists RDNs from the last to the first; the subject's O values are kept in certificate order.
            List<String> ordered = new ArrayList<>(groups.reversed());
            return name == null || name.isEmpty() ? Optional.empty() : Optional.of(new CertificateUser(name, ordered));
        }
    }
}
