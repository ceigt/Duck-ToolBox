package dev.duck.renewal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Local-only validation. Neither certificates nor keys are sent by this class. */
final class Keybox {
    final byte[] xml;
    final long expires;
    final String hash, runtimeId;
    final List<X509Certificate> chain;

    private Keybox(byte[] xml, List<X509Certificate> chain, long expires) throws Exception {
        this.xml = xml; this.chain = chain; this.expires = expires;
        hash = sha256(xml);
        runtimeId = runtimeId(xml);
    }

    static String runtimeId(byte[] xml) {
        long id = 0xcbf29ce484222325L;
        for (byte b : xml) id = (id ^ (b & 255)) * 0x100000001b3L;
        return String.format(Locale.ROOT, "%016x", id);
    }

    static Keybox read(Path path, Set<String> pins, long now, boolean requireCurrent) throws Exception {
        return parse(readFile(path, 128 * 1024), pins, now, requireCurrent);
    }

    static Keybox parse(byte[] xml, Set<String> pins, long now, boolean requireCurrent) throws Exception {
        if (xml.length > 128 * 1024 || pins.isEmpty()) throw new IOException("invalid_keybox");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(xml)).toString();
        // Android's XML implementation does not implement every Xerces feature. Reject
        // DTDs before parsing a UTF-8 String, and reject every external resolution too.
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("xml_dtd");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new IOException("external_entity"); });
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        Document document = builder.parse(new InputSource(new StringReader(text)));
        if (!"AndroidAttestation".equals(document.getDocumentElement().getTagName())
                || document.getElementsByTagName("Keybox").getLength() != 1
                || document.getElementsByTagName("Key").getLength() != 1)
            throw new IOException("single_ec_keybox_required");
        Element key = (Element) document.getElementsByTagName("Key").item(0);
        if (!"ecdsa".equals(key.getAttribute("algorithm"))
                || key.getElementsByTagName("PrivateKey").getLength() != 1)
            throw new IOException("single_ec_keybox_required");
        NodeList nodes = key.getElementsByTagName("Certificate");
        if (nodes.getLength() < 2 || nodes.getLength() > 8) throw new IOException("invalid_chain_length");
        List<X509Certificate> chain = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        long expires = Long.MAX_VALUE;
        for (int i = 0; i < nodes.getLength(); i++) {
            String pem = nodes.item(i).getTextContent().trim().replaceAll("(?m)^\\s+", "");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if (cert.getNotBefore().getTime() > now) throw new IOException("certificate_not_yet_valid");
            if (requireCurrent) cert.checkValidity(new Date(now));
            expires = Math.min(expires, cert.getNotAfter().getTime());
            chain.add(cert);
        }
        X509Certificate root = chain.get(chain.size() - 1);
        if (!pins.contains(sha256(root.getPublicKey().getEncoded()))) throw new IOException("untrusted_root");
        root.verify(root.getPublicKey());
        // PKIX checks names, signatures, path constraints and critical extensions. For an
        // expired existing box, validate the path at the last instant all certs coexisted.
        long validationTime = now;
        if (!requireCurrent && expires <= now) validationTime = expires - 1;
        PKIXParameters parameters = new PKIXParameters(Collections.singleton(new TrustAnchor(root, null)));
        parameters.setRevocationEnabled(false);
        parameters.setDate(new Date(validationTime));
        CertPathValidator.getInstance("PKIX").validate(
                cf.generateCertPath(chain.subList(0, chain.size() - 1)), parameters);
        PublicKey pub = chain.get(0).getPublicKey();
        if (!(pub instanceof java.security.interfaces.ECPublicKey)
                || ((java.security.interfaces.ECPublicKey) pub).getParams().getCurve().getField().getFieldSize() != 256)
            throw new IOException("p256_required");
        String pem = key.getElementsByTagName("PrivateKey").item(0).getTextContent().trim();
        boolean sec1 = pem.startsWith("-----BEGIN EC PRIVATE KEY-----");
        String label = sec1 ? "EC PRIVATE KEY" : "PRIVATE KEY";
        if (!pem.startsWith("-----BEGIN " + label + "-----")) throw new IOException("invalid_key_encoding");
        byte[] raw = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", ""));
        byte[] wrapped = raw;
        try {
            if (sec1) {
                byte[] spki = pub.getEncoded();
                int start = bounds(spki, 0)[0];
                byte[] algorithm = Arrays.copyOfRange(spki, start, bounds(spki, start)[1]);
                wrapped = der(0x30, join(new byte[]{2, 1, 0}, algorithm, der(4, raw)));
            }
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(wrapped));
            byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            Signature proof = Signature.getInstance("SHA256withECDSA");
            proof.initSign(privateKey); proof.update(challenge); byte[] signature = proof.sign();
            proof.initVerify(pub); proof.update(challenge);
            if (!proof.verify(signature)) throw new IOException("private_key_mismatch");
            Arrays.fill(signature, (byte) 0);
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (wrapped != raw) Arrays.fill(wrapped, (byte) 0);
        }
        return new Keybox(xml, chain, expires);
    }

    static Set<String> pins(Path pem) throws Exception {
        Set<String> pins = new HashSet<>();
        for (java.security.cert.Certificate cert : CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(readFile(pem, 64 * 1024))))
            pins.add(sha256(cert.getPublicKey().getEncoded()));
        if (pins.isEmpty()) throw new IOException("trust_roots_missing");
        return pins;
    }

    static byte[] readFile(Path path, int maximum) throws IOException {
        safePath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximum)
            throw new IOException("unsafe_or_oversized_file");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (n > maximum - output.size()) throw new IOException("oversized_file");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    static void safePath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.equals(path.toAbsolutePath()) || !path.toFile().getCanonicalFile().toPath().equals(absolute))
            throw new IOException("symlink_or_noncanonical_path");
        Path part = absolute.getRoot();
        for (Path segment : absolute) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) throw new IOException("symlink_path");
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    static int[] bounds(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 2 > bytes.length) throw new IOException("der_bounds");
        int p = offset + 1, n = bytes[p++] & 255;
        if (n >= 128) {
            int count = n & 127; n = 0;
            if (count == 0 || count > 3 || p + count > bytes.length) throw new IOException("der_length");
            for (int i = 0; i < count; i++) n = (n << 8) | (bytes[p++] & 255);
        }
        if (p + n > bytes.length) throw new IOException("der_bounds");
        return new int[]{p, p + n};
    }
    static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }
    static byte[] der(int tag, byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(tag);
        int n = bytes.length;
        if (n < 128) out.write(n);
        else if (n < 256) { out.write(0x81); out.write(n); }
        else { out.write(0x82); out.write(n >>> 8); out.write(n); }
        out.write(bytes); return out.toByteArray();
    }
}
