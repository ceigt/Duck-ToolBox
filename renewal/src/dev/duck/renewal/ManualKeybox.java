package dev.duck.renewal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Manual imports accept self-signed/AOSP roots; no claim of Google trust is made. */
final class ManualKeybox {
    static void validate(byte[] xml, long now) throws Exception {
        if (xml.length > 128 * 1024) throw new IOException("invalid_keybox");
        String text = new String(xml, StandardCharsets.UTF_8);
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY"))
            throw new IOException("xml_dtd");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((a, b) -> { throw new IOException("external_entity"); });
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        Document doc = builder.parse(new InputSource(new StringReader(text)));
        if (!"AndroidAttestation".equals(doc.getDocumentElement().getTagName())
                || doc.getElementsByTagName("Keybox").getLength() != 1) throw new IOException("invalid_keybox");
        NodeList keys = doc.getElementsByTagName("Key");
        if (keys.getLength() < 1 || keys.getLength() > 2) throw new IOException("invalid_keybox");
        Set<String> algorithms = new HashSet<>();
        for (int k = 0; k < keys.getLength(); k++) {
            Element key = (Element) keys.item(k);
            String algorithm = key.getAttribute("algorithm");
            if (!Arrays.asList("rsa", "ecdsa").contains(algorithm) || !algorithms.add(algorithm)
                    || key.getElementsByTagName("PrivateKey").getLength() != 1) throw new IOException("invalid_keybox");
            NodeList nodes = key.getElementsByTagName("Certificate");
            if (nodes.getLength() < 2 || nodes.getLength() > 8) throw new IOException("invalid_chain_length");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (int c = 0; c < nodes.getLength(); c++) {
                String pem = nodes.item(c).getTextContent().trim().replaceAll("(?m)^\\s+", "");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
                cert.checkValidity(new Date(now)); chain.add(cert);
            }
            X509Certificate root = chain.get(chain.size() - 1);
            root.verify(root.getPublicKey());
            PKIXParameters params = new PKIXParameters(Collections.singleton(new TrustAnchor(root, null)));
            params.setRevocationEnabled(false); params.setDate(new Date(now));
            CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(chain.subList(0, chain.size() - 1)), params);
            PublicKey publicKey = chain.get(0).getPublicKey();
            String kind = "rsa".equals(algorithm) ? "RSA" : "EC";
            if (!kind.equals(publicKey.getAlgorithm())) throw new IOException("private_key_mismatch");
            String pem = key.getElementsByTagName("PrivateKey").item(0).getTextContent().trim();
            String label = pem.startsWith("-----BEGIN RSA PRIVATE KEY-----") ? "RSA PRIVATE KEY"
                    : pem.startsWith("-----BEGIN EC PRIVATE KEY-----") ? "EC PRIVATE KEY" : "PRIVATE KEY";
            if (!pem.startsWith("-----BEGIN " + label + "-----") || !pem.endsWith("-----END " + label + "-----"))
                throw new IOException("invalid_key_encoding");
            byte[] raw = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN " + label + "-----", "").replace("-----END " + label + "-----", ""));
            byte[] wrapped = raw;
            try {
                if (!"PRIVATE KEY".equals(label)) {
                    byte[] spki = publicKey.getEncoded();
                    int start = Keybox.bounds(spki, 0)[0];
                    byte[] alg = Arrays.copyOfRange(spki, start, Keybox.bounds(spki, start)[1]);
                    wrapped = Keybox.der(0x30, Keybox.join(new byte[]{2, 1, 0}, alg, Keybox.der(4, raw)));
                }
                PrivateKey privateKey = KeyFactory.getInstance(kind).generatePrivate(new PKCS8EncodedKeySpec(wrapped));
                byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
                Signature proof = Signature.getInstance("RSA".equals(kind) ? "SHA256withRSA" : "SHA256withECDSA");
                proof.initSign(privateKey); proof.update(challenge); byte[] signed = proof.sign();
                proof.initVerify(publicKey); proof.update(challenge);
                if (!proof.verify(signed)) throw new IOException("private_key_mismatch");
            } finally { Arrays.fill(raw, (byte)0); Arrays.fill(wrapped, (byte)0); }
        }
    }
}
